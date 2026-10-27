package com.hwcamtrack.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.hwcamtrack.net.SpsParser
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 解码方式：自动（优先硬解，失败退软解）/ 只用硬解 / 只用软解。 */
enum class DecoderPreference {
    AUTO,
    HARDWARE,
    SOFTWARE;

    val label: String
        get() = when (this) {
            AUTO -> "auto"
            HARDWARE -> "hardware"
            SOFTWARE -> "software"
        }
}

/**
 * 把 Annex-B 的 H.264 NAL 组装成访问单元并解码（硬解/软解可选）。
 *
 * 两条取流通道共用这一段：
 *  - [RawH264Player]：裸 H.264 over TCP
 *  - [RtspNativePlayer]：自研 RTSP / RTP 通道
 *
 * ## 为什么解码要独立一个线程
 *
 * 最初是"读一帧 → 立刻 dequeueInputBuffer → 立刻送解码"写在同一段网络循环里。
 * 那样网络读取会被解码器的节奏反过来卡住：解码器一忙，`dequeueInputBuffer`
 * 就得等到超时，这段时间网络根本没在读，TCP 缓冲堆起来、随后又一次性涌进来，
 * 画面就是一顿一顿的。
 *
 * 现在网络线程只负责把访问单元丢进**有界队列**，解码线程按自己的节奏取，
 * 两边互不拖累；队列满了优先丢掉最老的非关键帧，保证延迟不滚雪球。
 *
 * 解码器配置必须等到第一个 IDR：`csd-0` / `csd-1` 要真实的 SPS/PPS，
 * MediaCodec 本身也要求从关键帧开始。
 */
class H264AccessUnitDecoder(
    private val surface: Surface,
    private val preference: DecoderPreference,
    private val onLog: (String) -> Unit,
    private val onConfigured: (width: Int, height: Int) -> Unit
) {

    private class Frame(
        val annexB: ByteArray,
        val presentationTimeUs: Long,
        val key: Boolean
    )

    private val queue = LinkedBlockingDeque<Frame>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    private var codec: MediaCodec? = null

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** 裸 TCP 通道用的访问单元组装缓冲。 */
    private val unit = ArrayList<ByteArray>(4)
    private var unitHasVcl = false

    /** 上一次送进解码器的 PTS，用来保证单调不减。 */
    private var lastQueuedPtsUs = -1L
    private val bufferInfo = MediaCodec.BufferInfo()

    // 统计（每个上报周期清零）
    private val decodedSinceReport = AtomicLong()
    private val droppedQueueFull = AtomicLong()
    private val droppedNoBuffer = AtomicLong()
    private val droppedOversize = AtomicLong()
    private val lateFrames = AtomicLong()
    private var lastReportAt = 0L

    /**
     * 软解失败或者"配好了却一个画面都不出"时，退回系统默认（硬解）解码器。
     *
     * 真机上出现过"打开软解就是黑屏"：解码器 configure 抛异常，
     * 而原来的代码只是默默返回、下一帧再试一次，于是永远黑屏、日志也看不出所以然。
     */
    @Volatile
    private var fallbackToDefaultDecoder = false

    /** 连续配置失败的次数与冷却，避免每帧都去 configure 一次刷屏。 */
    private var configureFailures = 0
    private var nextConfigureAttemptAt = 0L

    /** 软件解码器候选与当前试到第几个。 */
    private var softwareDecoderCache: List<String>? = null
    private var softwareDecoderIndex = 0

    /** 解码器配置完成的时刻，用来判断"配好了但不出图"。 */
    private var configuredAt = 0L
    private var sawAnyOutput = false

    val isConfigured: Boolean get() = codec != null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastReportAt = System.currentTimeMillis()
        worker = Thread({ decodeLoop() }, "hwcam-h264-decode").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 预置参数集（来自 SDP 的 `sprop-parameter-sets`）。
     * 有它就不用等码流里的第一组 SPS/PPS，起播更快、也更稳。
     */
    fun seedParameterSets(spsNal: ByteArray?, ppsNal: ByteArray?) {
        if (codec != null) return
        if (spsNal != null && spsNal.isNotEmpty()) sps = spsNal
        if (ppsNal != null && ppsNal.isNotEmpty()) pps = ppsNal
    }

    /** 送一个 NAL（**不含**起始码）——裸 TCP 通道用。 */
    fun submit(nal: ByteArray) {
        if (nal.isEmpty()) return
        val type = nal[0].toInt() and 0x1F

        when (type) {
            NAL_SPS -> {
                if (codec == null) sps = nal
                return
            }

            NAL_PPS -> {
                if (codec == null) pps = nal
                return
            }

            NAL_AUD -> return
        }

        val isVcl = type == NAL_SLICE || type == NAL_IDR
        if (isVcl && unitHasVcl) {
            flushUnit()
            unit.clear()
            unitHasVcl = false
        }
        unit.add(nal)
        if (isVcl) unitHasVcl = true
    }

    /**
     * 直接送一个**完整的 Annex-B 访问单元**——自研 RTSP 通道用。
     *
     * `presentationTimeUs` 必须由**码流自己的时钟**推出来（RTSP 用 RTP 时间戳），
     * 不能用"数据到达的时刻"：MediaCodec 拿这个值排渲染时间，
     * 用到达时刻等于把网络抖动写进画面节奏。
     */
    fun submitAccessUnit(annexB: ByteArray, presentationTimeUs: Long) {
        if (annexB.isEmpty()) return
        enqueue(annexB, presentationTimeUs, key = containsIdr(annexB))
    }

    fun release() {
        running.set(false)
        worker?.interrupt()
        worker = null
        queue.clear()
        releaseCodec()
    }

    private fun releaseCodec() {
        val decoder = codec
        codec = null
        if (decoder != null) {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    // ------------------------------------------------------------------
    // 生产侧（网络线程）
    // ------------------------------------------------------------------

    private fun flushUnit() {
        if (unit.isEmpty()) return

        var total = 0
        var hasKeyFrame = false
        for (nal in unit) {
            total += nal.size
            if ((nal[0].toInt() and 0x1F) == NAL_IDR) hasKeyFrame = true
        }
        if (total == 0) return

        val annexB = ByteArray(total)
        var offset = 0
        for (nal in unit) {
            nal.copyInto(annexB, offset)
            offset += nal.size
        }
        // 裸流没有时间戳可用，只能按入队时刻排
        enqueue(annexB, System.nanoTime() / 1_000L, hasKeyFrame)
    }

    private fun enqueue(annexB: ByteArray, presentationTimeUs: Long, key: Boolean) {
        val frame = Frame(annexB, presentationTimeUs, key)
        if (queue.offerLast(frame)) return

        // 队列满：优先丢掉最老的非关键帧，别让延迟越堆越大
        var removed = false
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().key) {
                iterator.remove()
                removed = true
                break
            }
        }
        if (!removed) queue.pollFirst()
        droppedQueueFull.incrementAndGet()
        queue.offerLast(frame)
    }

    // ------------------------------------------------------------------
    // 消费侧（解码线程）
    // ------------------------------------------------------------------

    private fun decodeLoop() {
        while (running.get()) {
            val frame = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                break
            }

            if (frame != null) feed(frame)
            drain()
            reportStatsIfDue()
        }
    }

    private fun feed(frame: Frame) {
        // release() 之后可能还有一个在途的帧，别让它再新建解码器（那样没人释放）
        if (!running.get()) return

        if (codec == null) {
            // 配置刚失败过就先冷却，别每来一帧都去 configure 一次刷爆日志
            if (System.currentTimeMillis() < nextConfigureAttemptAt) return

            var foundSps: ByteArray? = null
            var foundPps: ByteArray? = null
            var hasIdr = false
            scanNalUnits(frame.annexB) { type, offset, length ->
                when (type) {
                    NAL_SPS -> if (foundSps == null) {
                        foundSps = frame.annexB.copyOfRange(offset, offset + length)
                    }

                    NAL_PPS -> if (foundPps == null) {
                        foundPps = frame.annexB.copyOfRange(offset, offset + length)
                    }

                    NAL_IDR -> hasIdr = true
                }
            }
            if (foundSps != null) sps = foundSps
            if (foundPps != null) pps = foundPps

            // 解码器只能从关键帧起步
            if (!hasIdr) return
            val parameterSps = sps
            val parameterPps = pps
            if (parameterSps == null || parameterPps == null) return
            if (!configure(parameterSps, parameterPps)) return
        }

        queueToCodec(frame)
    }

    private fun queueToCodec(frame: Frame) {
        val decoder = codec ?: return

        // MediaCodec 要求 PTS 单调不减
        val pts = if (frame.presentationTimeUs > lastQueuedPtsUs) {
            frame.presentationTimeUs
        } else {
            lastQueuedPtsUs + 1
        }
        lastQueuedPtsUs = pts

        if (pts < System.nanoTime() / 1_000L - LATE_THRESHOLD_US) {
            lateFrames.incrementAndGet()
        }

        try {
            val index = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) {
                droppedNoBuffer.incrementAndGet()
                return
            }

            val buffer = decoder.getInputBuffer(index)
            if (buffer == null) {
                droppedNoBuffer.incrementAndGet()
                return
            }
            buffer.clear()
            if (buffer.capacity() < frame.annexB.size) {
                droppedOversize.incrementAndGet()
                onLog("Access unit (${frame.annexB.size} B) exceeds input buffer (${buffer.capacity()} B); dropping one frame")
                return
            }
            buffer.put(frame.annexB)
            decoder.queueInputBuffer(
                index,
                0,
                frame.annexB.size,
                pts,
                if (frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            )
        } catch (t: Throwable) {
            onLog("Failed to queue input: ${t.message}")
        }
    }

    private fun drain() {
        val decoder = codec ?: return
        while (true) {
            val index = try {
                decoder.dequeueOutputBuffer(bufferInfo, 0)
            } catch (t: Throwable) {
                return
            }
            if (index < 0) return
            runCatching { decoder.releaseOutputBuffer(index, true) }
            sawAnyOutput = true
            decodedSinceReport.incrementAndGet()
        }
    }

    /**
     * "配好了却一个画面都不出"的看门狗。
     *
     * 软解最典型的故障就是这种：configure 和 start 都成功，日志也打了"已启动"，
     * 但解码器死活不吐 buffer，界面全黑。这里超时就自动换回系统默认（硬解）解码器，
     * 用户至少能看到画面，日志里也留下了明确原因。
     */
    private fun checkStalledDecoder() {
        if (preference != DecoderPreference.SOFTWARE) return
        if (sawAnyOutput || fallbackToDefaultDecoder) return

        val decoder = codec ?: return
        if (System.currentTimeMillis() - configuredAt < NO_OUTPUT_TIMEOUT_MS) return

        onLog(
            "Software decoder ${decoder.name} produced no output for ${NO_OUTPUT_TIMEOUT_MS / 1000}s; " +
                "falling back to the system default (hardware) decoder"
        )
        fallbackToDefaultDecoder = true
        releaseCodec()
        nextConfigureAttemptAt = 0L
    }

    /**
     * 定期把解码侧的指标报出来。
     *
     * "画面卡"到底是手机解不动、还是摄像头本身就只有十几帧，光看画面分不出来；
     * 把解码帧率和丢帧原因打出来就能一眼定位。
     */
    private fun reportStatsIfDue() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastReportAt
        if (elapsed < STATS_INTERVAL_MS) return
        lastReportAt = now

        checkStalledDecoder()

        val decoded = decodedSinceReport.getAndSet(0)
        val fps = decoded * 1000.0 / elapsed

        onLog(
            "decode %.1f fps · queue %d · late %d · drops(no input buffer %d / queue full %d / oversize %d)".format(
                fps,
                queue.size,
                lateFrames.getAndSet(0),
                droppedNoBuffer.getAndSet(0),
                droppedQueueFull.getAndSet(0),
                droppedOversize.getAndSet(0)
            )
        )
    }

    // ------------------------------------------------------------------
    // 解码器选择
    // ------------------------------------------------------------------

    private fun configure(spsNal: ByteArray, ppsNal: ByteArray): Boolean {
        val size = SpsParser.parse(spsNal)
        val width = size?.width ?: DEFAULT_WIDTH
        val height = size?.height ?: DEFAULT_HEIGHT

        return try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            )
            // 给足输入缓冲：否则大 IDR 帧会 dequeue 出一个装不下的 buffer，
            // 那个 buffer 就再也还不回解码器了（漏几次解码器就饿死）。
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsNal))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsNal))

            val decoder = createDecoder()
            decoder.configure(format, surface, null, 0)
            decoder.start()

            codec = decoder
            configuredAt = System.currentTimeMillis()
            sawAnyOutput = false
            configureFailures = 0
            val activeLabel = if (fallbackToDefaultDecoder) "system default" else preference.label
            onLog("H.264 decoder started ($activeLabel): ${decoder.name}, ${width}x$height")
            onConfigured(width, height)
            true
        } catch (t: Throwable) {
            configureFailures++
            val activePreference = if (fallbackToDefaultDecoder) "system default" else preference.label
            onLog("Cannot start H.264 decoder ($activePreference, attempt $configureFailures): ${t.message}")

            if (preference == DecoderPreference.SOFTWARE && !fallbackToDefaultDecoder) {
                // 一个软件解码器起不来不代表别的也不行（设备上可能同时有
                // c2.android.avc.decoder 和 OMX.google.h264.decoder），先换下一个试。
                softwareDecoderIndex++
                if (softwareDecoderIndex < softwareDecoderNames().size) {
                    onLog("Trying the next software decoder...")
                } else {
                    fallbackToDefaultDecoder = true
                    onLog("No software decoder worked; falling back to the system default (hardware) decoder")
                }
            } else {
                nextConfigureAttemptAt = System.currentTimeMillis() + CONFIGURE_RETRY_DELAY_MS
            }
            false
        }
    }

    private fun createDecoder(): MediaCodec {
        // 软解失败过 / 确认不出图之后，不再走软解
        if (!fallbackToDefaultDecoder && preference == DecoderPreference.SOFTWARE) {
            val name = softwareDecoderNames().getOrNull(softwareDecoderIndex)
            if (name != null) return MediaCodec.createByCodecName(name)
            onLog("No software decoder available; using the system default decoder")
            fallbackToDefaultDecoder = true
        }

        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    /** 设备上所有纯软件的 H.264 解码器名字，按系统给出的顺序。 */
    private fun softwareDecoderNames(): List<String> {
        softwareDecoderCache?.let { return it }
        val names = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info ->
                    !info.isEncoder && info.supportedTypes.any {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                    }
                }
                .filter { info -> isSoftwareCodec(info) }
                .map { info -> info.name }
        }.getOrDefault(emptyList())
        softwareDecoderCache = names
        return names
    }

    private fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
        // API 29 以下没有 isSoftwareOnly，只能按命名惯例判断
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") ||
            name.contains("software")
    }

    // ------------------------------------------------------------------
    // Annex-B 扫描
    // ------------------------------------------------------------------

    private fun containsIdr(annexB: ByteArray): Boolean {
        var found = false
        scanNalUnits(annexB) { type, _, _ -> if (type == NAL_IDR) found = true }
        return found
    }

    /** 遍历一段 Annex-B 数据里的 NAL，回调给出 NAL 类型和载荷范围（不含起始码）。 */
    private fun scanNalUnits(
        data: ByteArray,
        action: (type: Int, offset: Int, length: Int) -> Unit
    ) {
        var index = 0
        var nalStart = -1

        while (index + 3 <= data.size) {
            val startCodeLength = startCodeLengthAt(data, index)
            if (startCodeLength > 0) {
                if (nalStart in 0 until index) {
                    action(data[nalStart].toInt() and 0x1F, nalStart, index - nalStart)
                }
                index += startCodeLength
                nalStart = index
                continue
            }
            index++
        }

        if (nalStart in 0 until data.size) {
            action(data[nalStart].toInt() and 0x1F, nalStart, data.size - nalStart)
        }
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        if (data[index] != 0.toByte() || data[index + 1] != 0.toByte()) return 0
        if (data[index + 2] == 1.toByte()) return 3
        if (index + 4 <= data.size &&
            data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()
        ) {
            return 4
        }
        return 0
    }

    private companion object {
        const val NAL_SLICE = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val NAL_AUD = 9

        const val DEQUEUE_TIMEOUT_US = 50_000L
        const val POLL_TIMEOUT_MS = 200L
        const val STATS_INTERVAL_MS = 5_000L

        /** 队列最多 12 帧（30fps 下约 400ms），再多就说明延迟在堆积了。 */
        const val QUEUE_CAPACITY = 12

        /** 比这个还迟到的帧单独计数，用来判断是不是解不过来。 */
        const val LATE_THRESHOLD_US = 500_000L

        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720

        /** 1 MB，对 848x480/1080p 的 IDR 都绰绰有余。 */
        const val MAX_INPUT_SIZE = 1 shl 20

        /** configure 失败后的冷却时间。 */
        const val CONFIGURE_RETRY_DELAY_MS = 2_000L

        /** 解码器配置完成后多久还没有任何输出，就判定为"卡死不出图"。 */
        const val NO_OUTPUT_TIMEOUT_MS = 8_000L
    }
}

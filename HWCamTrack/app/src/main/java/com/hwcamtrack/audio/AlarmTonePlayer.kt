package com.hwcamtrack.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * "滴滴……"报警声发生器。
 *
 * ## 为什么自己合成 PCM，而不是 SoundPool / MediaPlayer + 音频资源
 *
 *  - 需求是"**持续 1~10 秒**"，也就是由时长决定播多久，而不是循环固定次数的音效。
 *    用 [AudioTrack] 自己按样点写，时长可以精确到毫秒；
 *  - 不用往 APK 里塞 wav/ogg，体积为零，音色和节奏随时可调；
 *  - `SoundPool`/`MediaPlayer` 在低端机上有几十到上百毫秒的启动延迟，
 *    而报警声"该响就得立刻响"。
 *
 * ## 声音形态
 *
 * 一个循环 730 ms，读起来就是"滴滴……滴滴……"：
 *
 * ```
 * 0        120      210    330                     730 ms
 * |===滴===|        |==滴==|                        |
 *   beep1   间隔      beep2          长间隔
 * ```
 *
 * 每个 beep 的两端各有 [rampSamples] 的淡入淡出，避免波形被硬切造成"咔哒"爆音。
 *
 * ## 线程模型
 *
 * [play] 之后由一条专用线程把 PCM 写进 AudioTrack（STREAM 模式下 `write` 是阻塞的）。
 * 写入按 [CHUNK_FRAMES] 分块（约 23 ms 音频一块），因此 [stop] 最多几十毫秒就能生效，
 * 不会出现"按了断开还在响"的拖尾。
 *
 * AudioTrack 由渲染线程自己创建、自己释放，[stop] 只置标志位，
 * 不去碰正在 `write` 的对象——避免两个线程同时操作一块 AudioTrack。
 */
class AlarmTonePlayer {

    private val sampleRate = 44_100

    /** C6：穿透力强，又不至于像高频啸叫那样刺耳。 */
    private val frequency = 1046.0

    /** 峰值幅度。留足余量，避免叠加系统音效时削顶。 */
    private val amplitude = 0.45

    private fun msToSamples(milliseconds: Int): Int = milliseconds * sampleRate / 1000

    /** 一个循环里的两段提示音（左闭右开的样点区间）。 */
    private val beeps: List<IntRange> = listOf(
        msToSamples(0) until msToSamples(120),
        msToSamples(210) until msToSamples(330)
    )

    private val rampSamples = msToSamples(5)
    private val periodSamples = msToSamples(730)

    private var thread: Thread? = null

    @Volatile
    private var running = false

    /**
     * 播放一次"滴滴……"，持续 [durationMs] 毫秒。
     *
     * 重复调用会**重新计时**：连续来告警就连续响，不会叠加成两条声音。
     */
    @Synchronized
    fun play(durationMs: Long) {
        stop()
        running = true
        thread = Thread({ render(durationMs.coerceAtLeast(1L)) }, "alarm-tone").apply {
            isDaemon = true
            start()
        }
    }

    /** 立刻停止（最多留几十毫秒的尾巴，那是已经写进音频队列的最后一块）。 */
    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    fun release() {
        stop()
    }

    // ------------------------------------------------------------------ 内部实现

    private fun render(durationMs: Long) {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) return      // 设备不支持该格式，静默放弃

        val chunkBytes = CHUNK_FRAMES * 2    // 16 bit 单声道
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM 走"闹钟"音量，静音模式/勿扰下依然能响——
                        // 这正是告警该有的行为（README 里有说明）。
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferBytes, chunkBytes * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Throwable) {
            return
        }

        try {
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return
            audioTrack.play()

            val buffer = ShortArray(CHUNK_FRAMES)
            val deadline = SystemClock.elapsedRealtime() + durationMs
            var framesWritten = 0L

            while (running && SystemClock.elapsedRealtime() < deadline) {
                fillBuffer(buffer, framesWritten)
                val written = audioTrack.write(buffer, 0, buffer.size)
                if (written <= 0) break
                framesWritten += written
            }

            // 让已经排进队列的声音自然放完再收尾：否则最后一声"滴"会被拦腰切断，
            // 听起来是"咔"的一下。用户主动 stop() 时不必等，直接退出。
            drainPlayback(audioTrack, framesWritten)

            runCatching { audioTrack.stop() }
        } catch (_: Throwable) {
            // 播放期间被抢占（来电、其它 App 抢走音频）不算故障
        } finally {
            runCatching { audioTrack.release() }
        }
    }

    /** 等播放头追上写入位置（最多 [DRAIN_TIMEOUT_MS]），保证最后一声是完整的。 */
    private fun drainPlayback(audioTrack: AudioTrack, framesWritten: Long) {
        val limit = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
        while (running && SystemClock.elapsedRealtime() < limit) {
            if (audioTrack.playbackHeadPosition.toLong() >= framesWritten) return
            Thread.sleep(DRAIN_POLL_MS)
        }
    }

    /** 按"当前已经写了多少帧"这个绝对位置，填出一整块带包络的波形。 */
    private fun fillBuffer(buffer: ShortArray, startFrame: Long) {
        for (i in buffer.indices) {
            val position = ((startFrame + i) % periodSamples).toInt()
            buffer[i] = sampleAt(position)
        }
    }

    private fun sampleAt(position: Int): Short {
        val beep = beeps.firstOrNull { position in it } ?: return 0

        val offset = position - beep.first
        val length = beep.last - beep.first + 1
        // 距离两端越近，包络越小（两端各 rampSamples 个样点完成淡入/淡出）
        val envelope = min(
            min(offset, length - 1 - offset).toDouble() / rampSamples,
            1.0
        ).coerceAtLeast(0.0)

        val value = sin(2.0 * PI * frequency * position / sampleRate) * amplitude * envelope
        return (value * Short.MAX_VALUE)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private companion object {
        /** 一块约 23 ms（44.1 kHz），决定 [stop] 的响应速度 */
        const val CHUNK_FRAMES = 1024

        /** 收尾时最多再等多久把队列里的声音放完 */
        const val DRAIN_TIMEOUT_MS = 200L

        /** 收尾时的播放头轮询间隔 */
        const val DRAIN_POLL_MS = 10L
    }
}

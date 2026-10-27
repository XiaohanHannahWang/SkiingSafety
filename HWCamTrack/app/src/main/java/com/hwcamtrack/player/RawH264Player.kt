package com.hwcamtrack.player

import android.view.Surface
import com.hwcamtrack.data.CamEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 裸 H.264 over TCP 播放器。
 *
 * 少数老方案（部分联咏/海思贴牌）只在某个端口上直接推一条 Annex-B 的 H.264 裸流，
 * 既没有 RTSP 信封，也没有 FLV/MP4 容器，ExoPlayer 的 extractor 全都无从下手。
 *
 * 这里只负责"从 socket 里切出 NAL"这一件事，组装访问单元和解码交给
 * [H264AccessUnitDecoder]（和 [RtspNativePlayer] 共用同一段）。
 */
class RawH264Player(
    private val preference: DecoderPreference,
    private val onState: (PlaybackState) -> Unit,
    private val onLog: (String) -> Unit
) {

    private var job: Job? = null
    private var decoder: H264AccessUnitDecoder? = null

    /**
     * `job.cancel()` 不会打断阻塞中的 `read()`，必须同时把 socket 关掉，
     * 否则"断开"以后这条协程会一直挂着，记录仪那边的连接也不会释放。
     */
    @Volatile
    private var activeSocket: Socket? = null

    /** 是否是用户主动断开，避免把关 socket 引发的异常报成播放故障。 */
    @Volatile
    private var stopping = false

    fun start(endpoint: CamEndpoint, surface: Surface, scope: CoroutineScope) {
        stop()
        stopping = false
        job = scope.launch(Dispatchers.IO) {
            try {
                run(endpoint, surface)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!stopping) {
                    onLog("Raw stream interrupted: ${t.message}")
                    onState(PlaybackState.Error(t.message ?: "Raw H.264 stream interrupted", endpoint))
                }
            }
        }
    }

    fun stop() {
        stopping = true
        job?.cancel()
        job = null
        activeSocket?.let { runCatching { it.close() } }
        activeSocket = null
        decoder?.release()
        decoder = null
    }

    private fun run(endpoint: CamEndpoint, surface: Surface) {
        onState(PlaybackState.Connecting(endpoint))

        val unitDecoder = H264AccessUnitDecoder(
            surface = surface,
            preference = preference,
            onLog = onLog,
            onConfigured = { width, height ->
                onState(
                    PlaybackState.Playing(
                        endpoint = endpoint,
                        width = width,
                        height = height,
                        note = "Raw H.264 / ${preference.label}"
                    )
                )
            }
        )
        unitDecoder.start()
        decoder = unitDecoder

        val socket = Socket()
        activeSocket = socket

        try {
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            onLog("Connected to ${endpoint.host}:${endpoint.port}; waiting for raw H.264")

            val input = BufferedInputStream(socket.getInputStream(), INPUT_BUFFER_SIZE)
            val chunk = ByteArray(CHUNK_SIZE)
            var pending = ByteArray(0)

            while (!Thread.currentThread().isInterrupted) {
                val read = input.read(chunk)
                if (read < 0) break

                val data = if (pending.isEmpty()) {
                    chunk.copyOf(read)
                } else {
                    pending + chunk.copyOf(read)
                }

                val (nals, rest) = splitAnnexB(data)
                // 防止找不到起始码时无限膨胀
                pending = if (rest.size > MAX_PENDING_BYTES) ByteArray(0) else rest

                for (nal in nals) unitDecoder.submit(nal)
                // 解码跑在独立线程上，这里只管喂，不再阻塞网络读取
            }
        } finally {
            runCatching { socket.close() }
            activeSocket = null
            unitDecoder.release()
            if (decoder === unitDecoder) decoder = null
        }
    }

    /**
     * 按 Annex-B 起始码切分 NAL。
     *
     * 只返回"确定已经结束"的 NAL（即后面还有下一个起始码的），
     * 最后一段留在 `rest` 里等下一次读到的数据补齐——否则会把半个 NAL 送进解码器。
     */
    private fun splitAnnexB(data: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val starts = ArrayList<Int>(16)
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add(i)
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }

        if (starts.size < 2) {
            return emptyList<ByteArray>() to
                if (starts.isEmpty()) data else data.copyOfRange(starts[0], data.size)
        }

        val nals = ArrayList<ByteArray>(starts.size - 1)
        for (index in 0 until starts.size - 1) {
            val startCodeLength = if (data[starts[index] + 2] == 1.toByte()) 3 else 4
            val from = starts[index] + startCodeLength
            val to = starts[index + 1]
            if (to > from) nals.add(data.copyOfRange(from, to))
        }

        return nals to data.copyOfRange(starts.last(), data.size)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        const val READ_TIMEOUT_MS = 5000

        const val CHUNK_SIZE = 64 * 1024
        const val INPUT_BUFFER_SIZE = 128 * 1024
        const val MAX_PENDING_BYTES = 1024 * 1024
    }
}

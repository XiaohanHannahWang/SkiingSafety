package com.hwcamtrack.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hwcamtrack.data.CamEndpoint
import com.hwcamtrack.data.ProfileCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * HTTP MJPEG（`multipart/x-mixed-replace`）播放器。
 *
 * ExoPlayer 不支持这种流，所以这里直接按 multipart 协议手撕：
 * 逐段读 `Content-Length` 个字节 → `BitmapFactory` 解码 → 交给 UI。
 *
 * 两个工程要点：
 * 1. **读超时必须关掉**（`readTimeout(0)`）——流是长连接，帧间可能静默数秒，
 *    默认 10s 超时会在低帧率时误杀连接。
 * 2. **要背压**。解码在主线程之外，但 `setImageBitmap` 在主线程；
 *    如果不等 UI 消费就继续解码，低端机很快 OOM。所以 `onFrame` 设计成
 *    suspend 函数，由调用方在主线程 `withContext` 里消费完再回来。
 */
class MjpegPlayer(
    private val okHttpClient: OkHttpClient,
    private val onFrame: suspend (Bitmap) -> Unit,
    private val onState: (PlaybackState) -> Unit,
    private val onLog: (String) -> Unit
) {

    private var job: Job? = null

    /**
     * 当前正在执行的请求。
     *
     * 光 `job.cancel()` 是没用的——协程正阻塞在 socket 读上，取消标记不会打断它。
     * 必须把 OkHttp 的 Call 也 cancel 掉，底层连接关闭后 read 才会抛异常退出。
     */
    @Volatile
    private var activeCall: Call? = null

    /**
     * 是否是"用户主动断开"。
     *
     * 主动断开时 socket 会被关掉，阻塞中的 read 抛 IOException——那不是故障，
     * 不能报成播放错误，否则界面上会先显示"已断开"再跳成"出错"。
     */
    @Volatile
    private var stopping = false

    /** 只在第一帧探测一次，之后复用，避免每帧都做 bounds 解码。 */
    private var sampleSize = 1

    /**
     * 最近一帧解码结果。
     *
     * 人体分析流水线（`BitmapFrameSource`）直接读这里，不必再"从视图抓帧 → 缩放"绕一圈。
     * 写在解码协程（IO 线程）上，读在分析线程上，所以用 @Volatile 保证可见性。
     */
    @Volatile
    private var currentFrame: Bitmap? = null

    /** 最近一帧 MJPEG 解码结果（还没出帧时为 null）。 */
    fun currentFrameBitmap(): Bitmap? = currentFrame

    fun start(endpoint: CamEndpoint, scope: CoroutineScope) {
        stop()
        stopping = false
        job = scope.launch(Dispatchers.IO) {
            try {
                run(endpoint)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!stopping) {
                    onLog("MJPEG stream interrupted: ${t.message}")
                    onState(PlaybackState.Error(t.message ?: "MJPEG stream interrupted", endpoint))
                }
            }
        }
    }

    fun stop() {
        stopping = true
        job?.cancel()
        job = null
        activeCall?.let { runCatching { it.cancel() } }
        activeCall = null
        sampleSize = 1
        currentFrame = null
    }

    private suspend fun run(endpoint: CamEndpoint) {
        onState(PlaybackState.Connecting(endpoint))
        onLog("Connecting MJPEG: ${endpoint.displayUri}")

        // 长连接：取消所有读超时，只保留连接超时
        val client = okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(endpoint.uri)
            .header("User-Agent", ProfileCatalog.USER_AGENT)
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} ${response.message}")
                }
                val body = response.body ?: throw IOException("Response has no body")
                val contentType = response.header("Content-Type").orEmpty()
                onLog("MJPEG Content-Type: ${contentType.ifEmpty { "unknown" }}")

                val input = BufferedInputStream(body.byteStream(), INPUT_BUFFER_SIZE)
                readFrames(input, endpoint)
            }
        } finally {
            activeCall = null
        }
    }

    private suspend fun readFrames(input: InputStream, endpoint: CamEndpoint) {
        var frameIndex = 0
        var announced = false

        while (true) {
            val headers = readPartHeaders(input) ?: break
            val declaredLength = headers["content-length"]?.trim()?.toIntOrNull()

            val jpeg = when {
                declaredLength != null && declaredLength in 1..MAX_FRAME_BYTES ->
                    readExact(input, declaredLength)

                else -> readUntilJpegEnd(input)
            }
            if (jpeg.isEmpty()) break

            val bitmap = decode(jpeg) ?: continue
            currentFrame = bitmap
            frameIndex++

            if (!announced) {
                announced = true
                onLog("First MJPEG frame: ${bitmap.width}x${bitmap.height}")
                onState(
                    PlaybackState.Playing(
                        endpoint = endpoint,
                        width = bitmap.width,
                        height = bitmap.height,
                        note = "MJPEG"
                    )
                )
            } else if (frameIndex % 60 == 0) {
                onLog("MJPEG decoded $frameIndex frames")
            }

            // 交给 UI 消费；这里会挂起，天然形成背压
            onFrame(bitmap)
        }
    }

    /**
     * 读一段 multipart 的头部，直到空行。
     * 返回 null 表示流结束（或对方关闭）。带了多重保护，不会死循环。
     *
     * 注意要区分"上一段数据后面的那个 CRLF"和"本段 header 之后的空行"——
     * 前者不算头部结束，否则帧边界就全乱了。判据是本次调用有没有见过 `--boundary` 行。
     */
    private fun readPartHeaders(input: InputStream): Map<String, String>? {
        val headers = LinkedHashMap<String, String>(4)
        var sawBoundaryHere = false
        var lineCount = 0

        while (true) {
            if (lineCount++ > MAX_HEADER_LINES) return null
            val line = readLine(input) ?: return null

            when {
                line.startsWith("--") -> {
                    // multipart 分隔行，后面跟着这一段的 header
                    sawBoundaryHere = true
                    headers.clear()
                }

                line.isEmpty() -> {
                    // 空行：要么是上一段数据尾巴上的 CRLF，要么是头部结束
                    if (sawBoundaryHere || headers.isNotEmpty()) return headers
                }

                else -> {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                            line.substring(colon + 1).trim()
                    } else if (!sawBoundaryHere) {
                        // 既不是 boundary 也不是 header：说明这个服务器根本不发 header，
                        // 这一行已经是 JPEG 数据了，直接把头部判定为空。
                        return headers
                    }
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                return if (buffer.size() == 0) null else ascii(buffer)
            }
            if (byte == '\n'.code) return ascii(buffer).trimEnd('\r')
            buffer.write(byte)
            if (buffer.size() > MAX_LINE_BYTES) return ascii(buffer)
        }
    }

    private fun ascii(buffer: ByteArrayOutputStream) =
        String(buffer.toByteArray(), Charsets.US_ASCII)

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) data else ByteArray(0)
    }

    /**
     * 没有 Content-Length 的服务器（少数）只能靠 JPEG 的 EOI 标记 FFD9 切帧。
     * 同时兼容它前面可能有前一段残留的 CRLF。
     */
    private fun readUntilJpegEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        var previous = -1
        while (out.size() < MAX_FRAME_BYTES) {
            val byte = input.read()
            if (byte < 0) break
            // 跳过 JPEG 之前的空白
            if (out.size() == 0 && (byte == '\r'.code || byte == '\n'.code)) continue
            out.write(byte)
            if (previous == 0xFF && byte == 0xD9) return out.toByteArray()
            previous = byte
        }
        return if (out.size() == 0) ByteArray(0) else out.toByteArray()
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        if (sampleSize == 1) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth > MAX_DECODE_WIDTH) {
                sampleSize = Integer.highestOneBit(bounds.outWidth / MAX_DECODE_WIDTH)
                    .coerceAtLeast(1)
            }
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // RGB_565：预览够用，内存占用只有 ARGB_8888 的一半
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }.getOrNull()
    }

    private companion object {
        const val INPUT_BUFFER_SIZE = 64 * 1024
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val MAX_LINE_BYTES = 4096
        const val MAX_HEADER_LINES = 64
        const val MAX_DECODE_WIDTH = 1920
    }
}

package com.hwcamtrack.net

import com.hwcamtrack.data.ProfileCatalog
import com.hwcamtrack.util.Base64Codec
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** 一条 RTSP 响应。 */
data class RtspResponse(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String>,
    val body: String
) {
    val server: String? get() = headers["server"]
    val contentBase: String? get() = headers["content-base"]
    val sessionId: String? get() = headers["session"]?.substringBefore(';')?.trim()
    val isSuccess: Boolean get() = statusCode in 200..299
    val isUnauthorized: Boolean get() = statusCode == 401
}

/** 一个 TCP 交错帧（channel 0 = RTP，1 = RTCP）。 */
class InterleavedFrame(val channel: Int, val data: ByteArray)

/**
 * 极简 RTSP 客户端，只做发现需要的三件事：OPTIONS / DESCRIBE / 认证重试。
 *
 * 它**不会**发 PLAY——真正的播放交给 ExoPlayer，这里只负责回答
 * "这个地址是不是 RTSP""用什么路径能 DESCRIBE 成功""视频到底是什么编码格式"。
 */
class RtspClient(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 2500
) : Closeable {

    private val socket = Socket()
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var cseq = 0

    /** 本端 RTCP 同步源标识（随机即可，仅用于让报告合法）。 */
    private val ourSsrc: Long = System.nanoTime() and 0xFFFFFFFFL

    fun connect() {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        val buffered = BufferedInputStream(socket.getInputStream(), STREAM_BUFFER_SIZE)
        input = buffered
        output = socket.getOutputStream()
        interleavedReader = InterleavedReader(buffered)
    }

    fun options(url: String): RtspResponse = request("OPTIONS", url)

    fun describe(url: String): RtspResponse =
        request("DESCRIBE", url, mapOf("Accept" to "application/sdp"))

    /**
     * SETUP：为一条媒体轨协商传输方式。
     *
     * `url` 必须是**媒体级** `a=control` 相对 `Content-Base` 解析出来的 track URL。
     * 这一点是很多记录仪能不能出流的分水岭：联咏 Nvt 那套 LIVE555 会给每条流分配
     * 一个 `rtsp://host/<会话ID>/` 的 Content-Base 命名空间，只有打到这个命名空间里的
     * SETUP/PLAY 才认；打原始请求 URI 会直接 `404 Stream Not Found`。
     */
    fun setup(url: String, transport: String, sessionId: String? = null): RtspResponse {
        val extra = LinkedHashMap<String, String>(2)
        extra["Transport"] = transport
        if (sessionId != null) extra["Session"] = sessionId
        return request("SETUP", url, extra)
    }

    /**
     * PLAY。
     *
     * `url` 用 Content-Base 解析出来的聚合 URL（或 track URL）——**不要**用原始请求 URI。
     */
    fun play(url: String, sessionId: String): RtspResponse = request(
        "PLAY",
        url,
        mapOf("Session" to sessionId, "Range" to "npt=0.000-")
    )

    fun teardown(url: String, sessionId: String): RtspResponse =
        request("TEARDOWN", url, mapOf("Session" to sessionId))

    /**
     * 发一个**不读回应**的保活请求。
     *
     * RTSP 会话有超时（LIVE555 默认 65 秒），长时间预览必须定期续命。
     * 这里刻意不读回应：会话建立后我们一直在按交错格式读数据流，
     * 再去同步读一个 RTSP 响应会打乱帧边界；回应交给 [readInterleavedFrame]
     * 当杂散字节丢掉就行（它会跳过所有非 `$` 开头的字节）。
     */
    @Synchronized
    fun sendKeepAlive(url: String, sessionId: String) {
        val out = output ?: return
        cseq += 1
        val head = buildString(128) {
            append("OPTIONS ").append(url).append(" RTSP/1.0\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            append("Session: ").append(sessionId).append("\r\n")
            append("User-Agent: ").append(ProfileCatalog.USER_AGENT).append("\r\n")
            append("\r\n")
        }
        runCatching {
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    /** 取流阶段把读超时放宽（长连接上包间隔可以很大）。 */
    fun setReadTimeout(millis: Int) {
        runCatching { socket.soTimeout = millis }
    }

    /**
     * 发一个最小的 RTCP Receiver Report（走交错通道 1）。
     *
     * 用来验证/规避一种服务端行为：**收不到任何 RTCP 反馈就把会话掐掉**。
     * 报告内容本身很朴素（丢包率 0、抖动 0），关键是让服务端看见客户端还活着。
     *
     * @param sourceSsrc 对端 RTP 的同步源（从收到的 RTP 包里取）
     * @param highestSequenceExtended 已经收到的最高序号（用 16 位回绕计数扩展成 32 位）
     */
    @Synchronized
    fun sendRtcpReceiverReport(sourceSsrc: Long, highestSequenceExtended: Long) {
        val out = output ?: return

        val report = ByteArray(32)
        report[0] = 0x81.toByte() // V=2, P=0, RC=1
        report[1] = 201.toByte()  // PT = Receiver Report
        putUInt16(report, 2, 7)   // 长度字段 = 8 个 32 位字 - 1
        putUInt32(report, 4, ourSsrc)
        putUInt32(report, 8, sourceSsrc)
        putUInt32(report, 12, 0)                      // fraction lost + cumulative lost
        putUInt32(report, 16, highestSequenceExtended) // extended highest sequence number
        putUInt32(report, 20, 0)                      // jitter
        putUInt32(report, 24, 0)                      // LSR
        putUInt32(report, 28, 0)                      // DLSR

        val framed = ByteArray(4 + report.size)
        framed[0] = 0x24                       // '$'
        framed[1] = 1                          // 交错通道 1 = RTCP
        framed[2] = ((report.size shr 8) and 0xFF).toByte()
        framed[3] = (report.size and 0xFF).toByte()
        report.copyInto(framed, 4)

        runCatching {
            out.write(framed)
            out.flush()
        }
    }

    private fun putUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    /** 取流阶段读交错帧的读取器（连接建立时创建）。 */
    private var interleavedReader: InterleavedReader? = null

    /**
     * 从进入取流阶段开始，被当作 RTSP 报文前缀丢掉的字节数。
     * 正常只来自保活回执，恒为 0 或几十字节。
     */
    val discardedBytes: Long get() = interleavedReader?.discardedBytes ?: 0

    /** 取流期间完整读掉并丢弃的 RTSP 报文数（正常只来自保活请求的回执）。 */
    val discardedRtspMessages: Long get() = interleavedReader?.discardedRtspMessages ?: 0

    /**
     * 读一个 TCP 交错帧（RFC 2326 §10.12）。
     *
     * 具体解析在 [InterleavedReader] 里（那样可以脱离 socket 做单元测试）；
     * 这里只负责把已建立的输入流交给它。
     */
    fun readInterleavedFrame(): InterleavedFrame? = interleavedReader?.read()

    /** 带认证重试的请求：先裸发，401 就按挑战头逐个凭据试。 */
    private fun request(
        method: String,
        url: String,
        extra: Map<String, String> = emptyMap()
    ): RtspResponse {
        var authHeader: String? = null
        var last: RtspResponse? = null
        var credentialIndex = 0

        while (true) {
            val response = send(method, url, extra, authHeader)
            if (!response.isUnauthorized) return response
            last = response

            val challenge = response.headers["www-authenticate"] ?: return response
            val credential = authCandidates.getOrNull(credentialIndex) ?: return response
            credentialIndex++

            authHeader = RtspAuth.build(challenge, method, url, credential.first, credential.second)
                ?: return response
        }
    }

    private fun send(
        method: String,
        url: String,
        extra: Map<String, String>,
        authHeader: String?
    ): RtspResponse {
        val out = output ?: throw IllegalStateException("RTSP is not connected")
        cseq += 1

        val head = StringBuilder(160)
        head.append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
        head.append("CSeq: ").append(cseq).append("\r\n")
        head.append("User-Agent: ").append(ProfileCatalog.USER_AGENT).append("\r\n")
        extra.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        if (authHeader != null) head.append("Authorization: ").append(authHeader).append("\r\n")
        head.append("\r\n")

        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
        return readResponse()
    }

    private fun readResponse(): RtspResponse {
        val inp = input ?: throw IllegalStateException("RTSP is not connected")

        val statusLine = readLine(inp) ?: throw EOFException("RTSP connection closed by peer")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1

        val headers = LinkedHashMap<String, String>(12)
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length in 1..MAX_BODY_BYTES) {
            String(readFully(inp, length), Charsets.UTF_8)
        } else {
            ""
        }

        return RtspResponse(statusCode, statusLine, headers, body)
    }

    private fun readLine(inp: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val byte = inp.read()
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

    private fun readFully(inp: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = inp.read(data, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) data else data.copyOf(offset)
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket.close() }
    }

    private companion object {
        const val MAX_LINE_BYTES = 8192
        const val MAX_BODY_BYTES = 256 * 1024
        const val STREAM_BUFFER_SIZE = 64 * 1024

        /** 认证时跳过"无凭据"那一项，避免白白浪费一次往返。 */
        val authCandidates: List<Pair<String, String>> =
            ProfileCatalog.CREDENTIALS.filter { it.first.isNotEmpty() }
    }
}

/**
 * RTSP 的 Basic / Digest 认证头构造。
 *
 * 记录仪绝大多数是"无认证"，但少数贴牌货会在 OPTIONS 上就要求 Digest，
 * 这里把两种方式都做掉，发现流程就不用为它们特判了。
 */
internal object RtspAuth {

    fun build(
        challenge: String,
        method: String,
        uri: String,
        user: String,
        password: String
    ): String? {
        val trimmed = challenge.trim()
        return when {
            trimmed.startsWith("Digest", ignoreCase = true) ->
                buildDigest(trimmed, method, uri, user, password)

            trimmed.startsWith("Basic", ignoreCase = true) ->
                buildBasic(user, password)

            else -> null
        }
    }

    private fun buildBasic(user: String, password: String): String {
        val token = Base64Codec.encode("$user:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }

    private fun buildDigest(
        challenge: String,
        method: String,
        uri: String,
        user: String,
        password: String
    ): String {
        val params = parseParams(challenge)
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val opaque = params["opaque"]
        val algorithm = params["algorithm"].orEmpty()
        val qop = params["qop"]?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val nc = "00000001"

        val response = if (qop == null) {
            md5("$ha1:$nonce:$ha2")
        } else {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        }

        return buildString(192) {
            append("Digest ")
            append("username=\"").append(user).append("\", ")
            append("realm=\"").append(realm).append("\", ")
            append("nonce=\"").append(nonce).append("\", ")
            append("uri=\"").append(uri).append("\", ")
            append("response=\"").append(response).append('"')
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append('"')
            }
            if (!opaque.isNullOrEmpty()) append(", opaque=\"").append(opaque).append('"')
            if (algorithm.isNotEmpty()) append(", algorithm=").append(algorithm)
        }
    }

    private val PARAM_RE = Regex("([A-Za-z0-9_\\-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))")

    private fun parseParams(challenge: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (match in PARAM_RE.findAll(challenge)) {
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            map[match.groupValues[1].lowercase(Locale.US)] = value
        }
        return map
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

package com.hwcamtrack.data

/**
 * 一个"可以播放的摄像头端点"：地址 + 端口 + 协议 + 路径 + 已经探明的编码格式。
 *
 * 自动发现的产物就是这个对象；"连接"就是把它交给播放器。
 */
data class CamEndpoint(
    val host: String,
    val port: Int,
    val protocol: CamProtocol,
    val path: String = "",
    /** 识别出的厂商/方案名，识别不出时为空串。 */
    val vendor: String = "",
    /** HTTP `Server:` 或 RTSP `Server:` 头原文，作为证据保留。 */
    val serverHeader: String = "",
    /** H264 / H265 / MJPEG / MPEG4 ... */
    val videoCodec: String = "",
    val audioCodec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    /** 0~100，越大越确信这就是可以播的主码流。 */
    val confidence: Int = 0,
    /** 判定依据，展示给用户，也方便排错。 */
    val evidence: List<String> = emptyList(),
    val user: String = "",
    val password: String = "",
    /** RTSP DESCRIBE 返回的 `Content-Base` 原文（如果有）。 */
    val rtspContentBase: String = ""
) {

    val normalizedPath: String
        get() = when {
            path.isEmpty() -> "/"
            path.startsWith("/") -> path
            else -> "/$path"
        }

    private val authority: String
        get() = buildString {
            if (user.isNotEmpty()) {
                append(user)
                if (password.isNotEmpty()) {
                    append(':')
                    append(password)
                }
                append('@')
            }
            append(host)
            append(':')
            append(port)
        }

    /** 交给播放器的真实 URL（含认证信息）。 */
    val uri: String
        get() = when (protocol) {
            CamProtocol.RTSP -> "rtsp://$authority$normalizedPath"
            CamProtocol.RTMP -> "rtmp://$authority$normalizedPath"
            CamProtocol.HTTP_MJPEG, CamProtocol.HTTP_FLV, CamProtocol.HTTP_MP4 ->
                "http://$authority$normalizedPath"
            CamProtocol.RAW_H264_TCP, CamProtocol.UNKNOWN -> "$host:$port"
        }

    /** 展示用 URL，把密码打码。 */
    val displayUri: String
        get() = when (protocol) {
            CamProtocol.RTSP -> "rtsp://${maskedAuthority}$normalizedPath"
            CamProtocol.RTMP -> "rtmp://${maskedAuthority}$normalizedPath"
            CamProtocol.HTTP_MJPEG, CamProtocol.HTTP_FLV, CamProtocol.HTTP_MP4 ->
                "http://${maskedAuthority}$normalizedPath"
            CamProtocol.RAW_H264_TCP, CamProtocol.UNKNOWN -> "$host:$port (TCP)"
        }

    private val maskedAuthority: String
        get() = buildString {
            if (user.isNotEmpty()) {
                append(user)
                append(":***@")
            }
            append(host)
            append(':')
            append(port)
        }

    /** 例如 "H264 1920x1080 30fps" */
    val videoSummary: String
        get() {
            val parts = ArrayList<String>(3)
            if (videoCodec.isNotEmpty()) parts += videoCodec
            if (width > 0 && height > 0) parts += "${width}x$height"
            if (fps > 0) parts += "${fps}fps"
            return if (parts.isEmpty()) "unknown format" else parts.joinToString(" ")
        }

    /** 用于去重的稳定键。 */
    val key: String
        get() = "${protocol.name}|$host:$port|$normalizedPath"

    /**
     * 服务器是否要求把 SETUP/PLAY 的 URL 按 `Content-Base` 解析。
     *
     * Media3 的 RTSP 客户端对 SETUP 处理正确，但 PLAY 一律发到**原始会话 URI**
     * （`RtspClient.startPlayback()`），遇到这类服务器会直接 `404 Stream Not Found`，
     * 最终表现成 `ERROR_CODE_IO_UNSPECIFIED Source error`。
     * 发现阶段已经拿到 Content-Base 了，所以这里可以直接判断，
     * 连的时候换用自研 RTSP 通道，不必先失败一次。
     */
    val rtspRequiresContentBase: Boolean
        get() {
            if (protocol != CamProtocol.RTSP) return false
            val base = rtspContentBase.trim()
            if (base.isEmpty()) return false
            return base.trimEnd('/') != uri.trimEnd('/')
        }

    /** 打分：证据越多越靠前；用于结果列表排序。 */
    fun withEvidence(text: String): CamEndpoint = copy(evidence = evidence + text)

    override fun toString(): String = displayUri
}

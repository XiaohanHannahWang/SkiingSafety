package com.hwcamtrack.net

/** SDP 里的一条媒体轨（video / audio）。 */
data class SdpTrack(
    val media: String,
    val payloadType: Int,
    val codec: String,
    val clockRate: Int,
    val control: String?,
    val fmtp: String?
)

/** DESCRIBE 返回的 SDP 解析结果。 */
data class SdpInfo(
    val tracks: List<SdpTrack>,
    val width: Int,
    val height: Int,
    val fps: Int,
    val sessionControl: String?
) {
    val videoTrack: SdpTrack? get() = tracks.firstOrNull { it.media == "video" }
    val audioTrack: SdpTrack? get() = tracks.firstOrNull { it.media == "audio" }

    val videoCodec: String get() = SdpParser.normalizeCodec(videoTrack?.codec)
    val audioCodec: String get() = SdpParser.normalizeCodec(audioTrack?.codec)

    /** 这确实是一条能播的视频流吗（有 video m= 行就算）。 */
    val hasVideo: Boolean get() = videoTrack != null

    val summary: String
        get() = buildString {
            append(videoCodec.ifEmpty { "video?" })
            if (width > 0 && height > 0) append(" ${width}x$height")
            if (fps > 0) append(" ${fps}fps")
            if (audioCodec.isNotEmpty()) append(" + $audioCodec")
        }
}

/**
 * SDP（RFC 4566 + RFC 6184）解析器。
 *
 * 这是"自动获取协议格式"的关键一步：DESCRIBE 成功后，编解码器、时钟频率、
 * 分辨率、帧率、控制 URL 全在这段文本里。
 */
object SdpParser {

    /** 静态负载类型里唯一和记录仪有关的：26 = JPEG（MJPEG over RTP）。 */
    private const val STATIC_PAYLOAD_JPEG = 26

    fun parse(sdp: String): SdpInfo {
        var sessionControl: String? = null
        var width = 0
        var height = 0
        var fps = 0

        val tracks = ArrayList<SdpTrack>(2)
        var current: TrackBuilder? = null

        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trim()
            if (line.length < 3 || line[1] != '=') continue
            val value = line.substring(2)

            when (line[0]) {
                'm' -> {
                    current?.let { tracks += it.build() }
                    current = TrackBuilder.fromMediaLine(value)
                }

                'a' -> {
                    val track = current
                    when {
                        value.startsWith("control:") -> {
                            val control = value.removePrefix("control:")
                            if (track == null) sessionControl = control else track.control = control
                        }

                        value.startsWith("rtpmap:") -> track?.applyRtpmap(value.removePrefix("rtpmap:"))

                        value.startsWith("fmtp:") -> track?.applyFmtp(value.removePrefix("fmtp:"))

                        value.startsWith("framerate:") -> {
                            val parsed = value.removePrefix("framerate:")
                                .substringBefore(',')
                                .trim()
                                .toDoubleOrNull()
                            if (parsed != null && parsed > 0) fps = parsed.toInt()
                        }

                        value.startsWith("x-dimensions:") -> {
                            val dims = value.removePrefix("x-dimensions:").split(',')
                            if (dims.size >= 2) {
                                width = dims[0].trim().toIntOrNull() ?: width
                                height = dims[1].trim().toIntOrNull() ?: height
                            }
                        }
                    }
                }
            }
        }
        current?.let { tracks += it.build() }

        // 分辨率兜底：从 H.264 的 sprop-parameter-sets 里把 SPS 解出来
        if (width <= 0 || height <= 0) {
            tracks.firstOrNull { it.media == "video" && it.codec.equals("H264", true) }
                ?.fmtp
                ?.let { fmtp -> extractSprop(fmtp) }
                ?.let { sprop ->
                    SpsParser.fromSpropParameterSets(sprop)?.let {
                        width = it.width
                        height = it.height
                    }
                }
        }

        return SdpInfo(
            tracks = tracks,
            width = width,
            height = height,
            fps = fps,
            sessionControl = sessionControl
        )
    }

    /** 从 `a=fmtp` 里抠出 `sprop-parameter-sets=` 的值。 */
    private fun extractSprop(fmtp: String): String? {
        val marker = "sprop-parameter-sets="
        val index = fmtp.indexOf(marker, ignoreCase = true)
        if (index < 0) return null
        return fmtp.substring(index + marker.length)
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /** 把 SDP 里的编码名归一化成人类可读的名字。 */
    fun normalizeCodec(codec: String?): String {
        val raw = codec?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return when (raw.uppercase()) {
            "H264" -> "H264"
            "H265", "HEVC" -> "H265"
            "H263-1998", "H263" -> "H263"
            "JPEG", "MJPEG" -> "MJPEG"
            "MP4V-ES", "MPEG4" -> "MPEG4"
            "MPEG4-GENERIC", "MPEG4-GENERIC/44100" -> "AAC"
            "MPA", "MPEGLAYER3" -> "MP3"
            "PCMU" -> "G711U"
            "PCMA" -> "G711A"
            "OPUS" -> "OPUS"
            else -> raw.uppercase()
        }
    }

    private class TrackBuilder(
        val media: String,
        var payloadType: Int,
        var codec: String = "",
        var clockRate: Int = 0,
        var control: String? = null,
        var fmtp: String? = null
    ) {
        fun applyRtpmap(value: String) {
            // 形如 "96 H264/90000" 或 "96 H264/90000/1"
            val parts = value.trim().split(' ')
            if (parts.size < 2) return
            val pt = parts[0].toIntOrNull() ?: return
            if (pt != payloadType && payloadType != 0) return
            payloadType = pt
            val encoding = parts[1].split('/')
            codec = encoding.getOrNull(0)?.trim().orEmpty()
            clockRate = encoding.getOrNull(1)?.toIntOrNull() ?: 0
        }

        fun applyFmtp(value: String) {
            val parts = value.trim().split(' ', limit = 2)
            if (parts.isEmpty()) return
            val pt = parts[0].toIntOrNull()
            if (pt != null && pt != payloadType && payloadType != 0) return
            if (parts.size >= 2) fmtp = parts[1].trim()
        }

        fun build(): SdpTrack = SdpTrack(
            media = media,
            payloadType = payloadType,
            codec = if (codec.isEmpty() && payloadType == STATIC_PAYLOAD_JPEG) "JPEG" else codec,
            clockRate = clockRate,
            control = control,
            fmtp = fmtp
        )

        companion object {
            /** `m=video 0 RTP/AVP 96` */
            fun fromMediaLine(value: String): TrackBuilder? {
                val parts = value.trim().split(' ')
                if (parts.size < 4) return null
                val pt = parts[3].toIntOrNull() ?: 0
                return TrackBuilder(media = parts[0], payloadType = pt)
            }
        }
    }
}

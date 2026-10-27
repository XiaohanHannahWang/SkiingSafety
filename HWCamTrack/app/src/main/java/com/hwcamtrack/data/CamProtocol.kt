package com.hwcamtrack.data

/**
 * 行车记录仪/网络摄像头对外暴露的传输协议族。
 *
 * 市面上能自动连接的记录仪 App（例如"录风者"这一类）之所以能"通用"，
 * 是因为它们并不认识每一个品牌，而是把这几种协议都试一遍：
 * 联咏/海思方案基本都给 RTSP，全志/晨星方案常见 HTTP MJPEG，
 * 少数老方案只给一条裸 H.264 的 TCP 长连接。
 */
enum class CamProtocol(
    val label: String,
    val defaultPort: Int
) {
    RTSP("RTSP / RTP", 554),
    HTTP_MJPEG("HTTP MJPEG", 80),
    HTTP_FLV("HTTP-FLV", 8080),
    HTTP_MP4("HTTP progressive", 80),
    RAW_H264_TCP("Raw H.264 / TCP", 8000),
    RTMP("RTMP", 1935),
    UNKNOWN("Unknown", 0);

    /** HTTP 系协议可以共用一个 OkHttp/DataSource 通道。 */
    val isHttp: Boolean
        get() = this == HTTP_MJPEG || this == HTTP_FLV || this == HTTP_MP4

    companion object {
        fun fromName(name: String?): CamProtocol =
            entries.firstOrNull { it.name == name } ?: UNKNOWN

        /** 手动输入对话框里给用户选的协议（过滤掉 UNKNOWN）。 */
        val manualChoices: List<CamProtocol>
            get() = listOf(RTSP, HTTP_MJPEG, HTTP_FLV, HTTP_MP4, RAW_H264_TCP, RTMP)
    }
}

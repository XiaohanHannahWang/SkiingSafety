package com.hwcamtrack.data

/**
 * 行车记录仪"画像库"。
 *
 * ## 为什么要这个库
 * 记录仪的 WiFi 热点后面其实是一台很精简的嵌入式 Linux。厂商（以及给厂商做方案的
 * 原厂）只会复用少数几套成熟方案：联咏 Novatek、全志 Allwinner、海思 HiSilicon、
 * 晨星/矽力 SigmaStar-MStar、国科 Goke、安霸 Ambarella、瑞昱 Realtek……
 * 同一套方案在不同贴牌产品上的端口、RTSP/MJPEG 路径、HTTP 指纹几乎是同一份。
 *
 * 所以"自动发现"不需要认识每一个品牌，只要：
 *   1. 先按方案库给出**先验候选**（端口 + 路径），
 *   2. 再用真实握手（RTSP DESCRIBE / HTTP GET）**验证**，
 *   3. 用 `Server` 头、页面标题等指纹**回填厂商名**。
 * 这样把"穷举"压缩成了"少量高概率假设 + 验证"，几秒内就能出结果。
 */
object ProfileCatalog {

    const val USER_AGENT = "HWCamTrack/1.0 (Android)"

    /** RTSP 端口。554 是标准口，8554/10554 常见于二次开发方案。 */
    val RTSP_PORTS = intArrayOf(554, 8554, 10554, 555)

    /** HTTP 端口。80/8080 最常见，8199 是很多 MJPEG 方案的自定义口。 */
    val HTTP_PORTS = intArrayOf(80, 8080, 8081, 8888, 8199, 81, 8000, 5000)

    /** 只吐裸码流的 TCP 端口（没有 RTSP/HTTP 信封）。 */
    val RAW_PORTS = intArrayOf(8000, 5000, 7000, 9000, 9001)

    val RTMP_PORTS = intArrayOf(1935)

    /** 全端口扫描顺序：先最可能的，后兜底。 */
    val ALL_PORTS: IntArray
        get() = (intArrayOf(554) + HTTP_PORTS + RAW_PORTS).distinct().toIntArray()

    /**
     * 记录仪 AP 的出厂网关候选。绝大多数记录仪把摄像头本身做成网关，
     * 所以网关几乎必然是取流地址——这是"自动发现"第一优先级的线索。
     */
    val GATEWAY_HINTS = listOf(
        "192.168.1.1", "192.168.0.1", "192.168.1.254", "192.168.0.254",
        "192.168.4.1", "192.168.8.1", "192.168.10.1", "192.168.100.1",
        "192.168.42.1", "192.168.43.1", "192.168.2.1", "192.168.3.1",
        "192.168.169.1", "192.168.234.1", "192.168.31.1", "192.168.123.1",
        "10.0.0.1", "10.10.10.1", "10.0.0.138",
        "172.20.10.1", "172.16.0.1"
    )

    /** 子网内优先尝试的主机位（很多记录仪不是 .1）。 */
    val HOST_HINTS = intArrayOf(1, 254, 2, 10, 100, 123, 168, 101, 20, 50)

    /**
     * RTSP 播放路径候选，按命中概率排序。
     * 前面几条覆盖了 90% 以上的国产记录仪。
     */
    val RTSP_PATHS = listOf(
        "/",
        "/live",
        "/11",
        "/12",
        "/stream0",
        "/live/ch0",
        "/live/ch1",
        "/av0_0",
        "/h264",
        "/stream1",
        "/13",
        "/onvif1",
        "/onvif2",
        "/video1",
        "/videoMain",
        "/user=admin&password=&channel=1&stream=0.sdp?",
        "/user=admin_password=_channel=1_stream=0.sdp?real_stream",
        "/Streaming/Channels/101",
        "/Streaming/Channels/102",
        "/cam/realmonitor?channel=1&subtype=0",
        "/h264/ch1/main/av_stream",
        "/media/video1",
        "/1"
    )

    /** HTTP MJPEG 路径候选。 */
    val MJPEG_PATHS = listOf(
        "/?action=stream",
        "/mjpeg",
        "/video",
        "/mjpg/video.mjpg",
        "/videostream.cgi",
        "/stream",
        "/mjpg",
        "/live.mjpg",
        "/video.mjpg",
        "/cgi-bin/mjpg/video.cgi",
        "/axis-cgi/mjpg/video.cgi",
        "/videofeed",
        "/1/mjpeg"
    )

    /** HTTP-FLV 路径候选。 */
    val FLV_PATHS = listOf(
        "/live.flv",
        "/livestream.flv",
        "/live/livestream.flv",
        "/stream.flv",
        "/1.flv",
        "/live/0.flv"
    )

    /**
     * RTSP 认证凭据候选。记录仪出厂基本是"无认证"或 admin/空密码，
     * 少数贴牌货用 admin/admin、admin/12345。逐个试一遍成本很低。
     */
    val CREDENTIALS = listOf(
        "" to "",
        "admin" to "",
        "admin" to "admin",
        "admin" to "12345",
        "admin" to "123456",
        "root" to "",
        "root" to "admin"
    )

    /** 一套方案画像。 */
    data class Profile(
        val vendor: String,
        /** HTTP/RTSP `Server:` 头里出现的特征串（小写比较）。 */
        val serverTokens: List<String> = emptyList(),
        /** HTTP 页面正文/标题里的特征串（小写比较）。 */
        val bodyTokens: List<String> = emptyList(),
        /** 该方案偏好的 RTSP 路径，会排在通用候选之前。 */
        val rtspPaths: List<String> = emptyList(),
        /** 该方案偏好的 HTTP 流路径。 */
        val httpPaths: List<String> = emptyList(),
        /** 该方案最可能使用的协议，用于给候选加权。 */
        val likelyProtocol: CamProtocol? = null,
        /** 该方案常见的端口。 */
        val ports: List<Int> = emptyList()
    )

    val PROFILES: List<Profile> = listOf(
        Profile(
            vendor = "Novatek (NT9665x/9666x/9667x)",
            serverTokens = listOf("nvhttpd", "novatek", "h264dvr", "dvrdvs", "ipcam"),
            bodyTokens = listOf("novatek", "nt966", "car dvr", "dvrcam", "行车记录仪"),
            rtspPaths = listOf("/", "/live", "/11", "/12", "/user=admin&password=&channel=1&stream=0.sdp?"),
            httpPaths = listOf("/?action=stream", "/mjpeg"),
            likelyProtocol = CamProtocol.RTSP,
            ports = listOf(554, 80, 8080)
        ),
        Profile(
            vendor = "Allwinner (V3/V3s/A33)",
            serverTokens = listOf("allwinner", "a-III", "tiwebserver", "boa", "goahead"),
            bodyTokens = listOf("allwinner", "v3s", "car_recorder", "dvr"),
            rtspPaths = listOf("/11", "/live/ch0", "/av0_0", "/live"),
            httpPaths = listOf("/?action=stream", "/mjpeg", "/video"),
            ports = listOf(554, 80, 8080)
        ),
        Profile(
            vendor = "HiSilicon (Hi3516/Hi3518)",
            serverTokens = listOf("hipcam", "h264dvr", "hisi", "hikon", "ipcam"),
            rtspPaths = listOf("/11", "/Streaming/Channels/101", "/live", "/h264/ch1/main/av_stream"),
            likelyProtocol = CamProtocol.RTSP,
            ports = listOf(554, 80, 8000)
        ),
        Profile(
            vendor = "SigmaStar / MStar (SSC/SAV)",
            serverTokens = listOf("sigmastar", "mstar", "httpd", "mini_httpd"),
            rtspPaths = listOf("/live", "/stream0", "/11", "/h264"),
            httpPaths = listOf("/mjpeg", "/?action=stream", "/mjpg/video.mjpg"),
            ports = listOf(554, 80, 8080)
        ),
        Profile(
            vendor = "Goke (GK7102/GK7205)",
            serverTokens = listOf("goke", "gk7102", "gk-", "hisilicon"),
            rtspPaths = listOf("/live/ch0", "/live", "/11"),
            ports = listOf(554, 80)
        ),
        Profile(
            vendor = "Ambarella (A7/A9/A12)",
            serverTokens = listOf("ambarella", "webserver", "lighttpd"),
            rtspPaths = listOf("/", "/live", "/stream1"),
            httpPaths = listOf("/mjpeg", "/?action=stream"),
            ports = listOf(554, 80, 8080)
        ),
        Profile(
            vendor = "Realtek (RTS3903/3915)",
            serverTokens = listOf("rts390", "realtek", "boa"),
            rtspPaths = listOf("/live", "/11", "/stream1"),
            httpPaths = listOf("/mjpeg", "/video.mjpg"),
            ports = listOf(554, 80)
        ),
        Profile(
            vendor = "Generic embedded web (GoAhead/Boa/thttpd)",
            serverTokens = listOf("goahead-webs", "goahead", "app-webs", "thttpd", "uc-httpd", "alphapd"),
            rtspPaths = listOf("/live", "/11", "/stream0", "/video1"),
            httpPaths = listOf("/mjpeg", "/?action=stream", "/video", "/stream"),
            ports = listOf(554, 80, 8080)
        )
    )

    /** 用 `Server:` 头反查厂商画像。 */
    fun matchByServer(server: String?): Profile? {
        val s = server?.lowercase() ?: return null
        if (s.isBlank()) return null
        return PROFILES.firstOrNull { profile -> profile.serverTokens.any { s.contains(it) } }
    }

    /** 用 HTTP 正文/标题反查厂商画像。 */
    fun matchByBody(body: String?): Profile? {
        val b = body?.lowercase() ?: return null
        if (b.isBlank()) return null
        return PROFILES.firstOrNull { profile -> profile.bodyTokens.any { b.contains(it) } }
    }

    /** 把画像偏好的路径排在通用候选前面，去重后返回。 */
    fun rtspPathsFor(profile: Profile?): List<String> {
        val preferred = profile?.rtspPaths.orEmpty()
        return (preferred + RTSP_PATHS).distinct()
    }

    fun mjpegPathsFor(profile: Profile?): List<String> {
        val preferred = profile?.httpPaths.orEmpty()
        return (preferred + MJPEG_PATHS).distinct()
    }

    fun flvPathsFor(profile: Profile?): List<String> {
        val preferred = profile?.httpPaths.orEmpty().filter { it.endsWith(".flv") }
        return (preferred + FLV_PATHS).distinct()
    }
}

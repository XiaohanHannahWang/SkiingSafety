package com.hwcamtrack.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * 当前手机所处的网络环境。
 *
 * 这些信息全部来自 `LinkProperties`，**不需要任何运行时权限**，
 * 所以即使拒绝定位权限，自动发现依然能工作（只是拿不到 SSID 名字）。
 */
data class NetworkContext(
    val ssid: String?,
    val localIp: String?,
    val prefixLength: Int,
    val gateway: String?,
    val dnsServers: List<String>,
    val isWifi: Boolean
) {
    /** 本机 IPv4 所在 /24 的字符串前缀，例如 "192.168.1."。 */
    val subnet24: String?
        get() = localIp
            ?.split('.')
            ?.takeIf { it.size == 4 }
            ?.let { "${it[0]}.${it[1]}.${it[2]}." }

    /** 缓存键：优先用 SSID（同一台记录仪），退化到网关，再退化到网段。 */
    val cacheKey: String
        get() = ssid?.takeIf { it.isNotBlank() }
            ?: gateway
            ?: subnet24
            ?: "default"

    val isUsable: Boolean
        get() = isWifi && localIp != null

    val summary: String
        get() = buildString {
            append("WiFi: ").append(ssid ?: "unknown")
            append(" · IP ").append(localIp ?: "—")
            if (gateway != null) append(" · GW ").append(gateway)
        }
}

/** 读取当前网络环境。 */
class NetworkInfoProvider(context: Context) {

    private val appContext = context.applicationContext

    // allNetworks 在 API 31 起被标记为废弃（官方建议用 registerNetworkCallback 持续监听），
    // 但这里需要的只是"当前这一刻有哪些网络"的快照，allNetworks 在所有版本上都可用且语义正好。
    @Suppress("DEPRECATION")
    fun current(): NetworkContext {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return empty()

        // 关键：**不能只看 activeNetwork**。
        //
        // 行车记录仪的热点是没有外网的，手机同时开着移动数据时，系统会把蜂窝网络
        // 当成 activeNetwork（因为只有它能上网），于是"手机明明连着摄像头 WiFi"
        // 会被误判成"没连 WiFi"，自动发现直接被拦掉。
        //
        // 正确做法是在所有已连接的网络里显式找 WiFi。
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        val network = wifiNetwork ?: cm.activeNetwork ?: return empty()
        val caps = cm.getNetworkCapabilities(network)
        val link = cm.getLinkProperties(network)

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val ipv4 = link?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }
        val localIp = ipv4?.address?.hostAddress
        val prefix = ipv4?.prefixLength ?: 24

        // 摄像头热点通常没有默认路由（或默认路由指向蜂窝），所以网关要按优先级找：
        // 先看默认路由，再退而求其次看第一条路由的网关。
        val routes = link?.routes.orEmpty()
        val gateway = routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
            ?: routes.firstOrNull { it.gateway != null }?.gateway?.hostAddress

        val dns = link?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.filter { it.contains('.') && it != localIp }
            .orEmpty()

        return NetworkContext(
            ssid = readSsid(cm, caps),
            localIp = localIp,
            prefixLength = prefix,
            gateway = gateway,
            dnsServers = dns,
            isWifi = isWifi
        )
    }

    /**
     * 读 SSID。API 33+ 需要 NEARBY_WIFI_DEVICES，API 27~32 需要定位权限，
     * 所以整段都包在 try/catch 里，任何失败都只是"没有 SSID"而已。
     */
    private fun readSsid(cm: ConnectivityManager, caps: NetworkCapabilities?): String? = runCatching {
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps?.transportInfo as? WifiInfo
        } else {
            null
        }
        val info = fromCaps ?: run {
            @Suppress("DEPRECATION")
            (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        }
        val raw = info?.ssid ?: return@runCatching null
        if (raw.isBlank() || raw == "<unknown ssid>") return@runCatching null
        raw.trim('"').takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun empty() = NetworkContext(
        ssid = null,
        localIp = null,
        prefixLength = 24,
        gateway = null,
        dnsServers = emptyList(),
        isWifi = false
    )
}

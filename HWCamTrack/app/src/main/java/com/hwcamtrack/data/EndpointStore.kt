package com.hwcamtrack.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "学到的"端点缓存。
 *
 * 借鉴同类 App 的做法：第一次连某台记录仪要扫三五秒，第二次应该秒连。
 * 我们用 SSID（没有 SSID 权限时退化用网关地址）做键，把上次成功播放的端点存下来，
 * 下次扫描时**先验证缓存**，命中就直接返回。
 */
class EndpointStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 按键读取上次成功的端点（可能多个，按置信度排序）。 */
    fun load(key: String): List<CamEndpoint> {
        val raw = prefs.getString(cacheKey(key), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                runCatching { fromJson(array.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /** 把一次成功的发现结果写回缓存（最多保留 6 条）。 */
    fun save(key: String, endpoints: List<CamEndpoint>) {
        if (endpoints.isEmpty()) return
        val array = JSONArray()
        endpoints.sortedByDescending { it.confidence }.take(MAX_ENTRIES).forEach { ep ->
            runCatching { array.put(toJson(ep)) }
        }
        prefs.edit().putString(cacheKey(key), array.toString()).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(cacheKey(key)).apply()
    }

    private fun cacheKey(key: String) = "endpoints_${key.lowercase()}"

    private fun toJson(ep: CamEndpoint): JSONObject = JSONObject().apply {
        put("host", ep.host)
        put("port", ep.port)
        put("protocol", ep.protocol.name)
        put("path", ep.path)
        put("vendor", ep.vendor)
        put("server", ep.serverHeader)
        put("vcodec", ep.videoCodec)
        put("acodec", ep.audioCodec)
        put("w", ep.width)
        put("h", ep.height)
        put("fps", ep.fps)
        put("confidence", ep.confidence)
        put("user", ep.user)
        put("password", ep.password)
        put("contentbase", ep.rtspContentBase)
        put("evidence", JSONArray(ep.evidence))
    }

    private fun fromJson(o: JSONObject): CamEndpoint {
        val evidence = o.optJSONArray("evidence")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }.orEmpty()
        return CamEndpoint(
            host = o.getString("host"),
            port = o.getInt("port"),
            protocol = CamProtocol.fromName(o.optString("protocol")),
            path = o.optString("path"),
            vendor = o.optString("vendor"),
            serverHeader = o.optString("server"),
            videoCodec = o.optString("vcodec"),
            audioCodec = o.optString("acodec"),
            width = o.optInt("w"),
            height = o.optInt("h"),
            fps = o.optInt("fps"),
            confidence = o.optInt("confidence"),
            evidence = evidence,
            user = o.optString("user"),
            password = o.optString("password"),
            rtspContentBase = o.optString("contentbase")
        )
    }

    private companion object {
        const val PREFS_NAME = "hwcamtrack_endpoints"
        const val MAX_ENTRIES = 6
    }
}

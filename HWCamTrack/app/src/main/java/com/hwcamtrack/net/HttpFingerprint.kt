package com.hwcamtrack.net

import com.hwcamtrack.data.ProfileCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * HTTP 侧指纹采集。
 *
 * 记录仪的 Web 服务是最"健谈"的：`Server:` 头、`WWW-Authenticate` 的 realm、
 * 首页标题，往往直接暴露了它用的是哪套方案。抓完指纹再去试探 MJPEG / FLV 路径，
 * 命中率比盲猜高得多。
 */
class HttpFingerprint(private val client: OkHttpClient) {

    data class Fingerprint(
        val statusCode: Int,
        val server: String?,
        val contentType: String?,
        val authScheme: String?,
        val authRealm: String?,
        val title: String?,
        val bodySnippet: String?
    )

    /** 拉一次首页，采集指纹。失败返回 null。 */
    suspend fun probeRoot(baseUrl: String): Fingerprint? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/")
                .header("User-Agent", ProfileCatalog.USER_AGENT)
                .header("Accept", "*/*")
                .build()

            client.newCall(request).execute().use { response ->
                // 只看前 4KB，记录仪首页都很小，而且我们绝不读完整个 body
                val snippet = runCatching { response.peekBody(MAX_BODY_SNIFF).string() }.getOrNull()
                val challenge = response.header("WWW-Authenticate")
                Fingerprint(
                    statusCode = response.code,
                    server = response.header("Server"),
                    contentType = response.header("Content-Type"),
                    authScheme = challenge?.substringBefore(' ')?.trim(),
                    authRealm = challenge?.let { extractRealm(it) },
                    title = snippet?.let { extractTitle(it) },
                    bodySnippet = snippet
                )
            }
        }.getOrNull()
    }

    /**
     * 确认某个路径是不是 MJPEG 长连接。
     *
     * 关键点：**只读响应头就关掉**。MJPEG 的 body 是永不结束的，
     * 一旦去读 body 就会把发现流程挂死。
     */
    suspend fun confirmMultipart(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ProfileCatalog.USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
                if (contentType.contains("multipart/x-mixed-replace") ||
                    contentType.contains("multipart/mixed")
                ) {
                    response.header("Content-Type")
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /** 确认某个路径是不是 HTTP-FLV：Content-Type 或者开头的 "FLV" 魔数。 */
    suspend fun confirmFlv(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ProfileCatalog.USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
                if (contentType.contains("flv") || contentType.contains("x-flv")) return@use true

                val head = runCatching { response.peekBody(3).bytes() }.getOrNull()
                head != null && head.size == 3 &&
                    head[0] == 'F'.code.toByte() &&
                    head[1] == 'L'.code.toByte() &&
                    head[2] == 'V'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /** 用指纹反查厂商画像。 */
    fun matchVendor(fingerprint: Fingerprint): ProfileCatalog.Profile? =
        ProfileCatalog.matchByServer(fingerprint.server)
            ?: ProfileCatalog.matchByBody(fingerprint.title)
            ?: ProfileCatalog.matchByBody(fingerprint.bodySnippet)

    private fun extractTitle(html: String): String? =
        TITLE_RE.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun extractRealm(challenge: String): String? =
        REALM_RE.find(challenge)?.groupValues?.getOrNull(1)?.trim()

    private companion object {
        const val MAX_BODY_SNIFF = 4096L
        val TITLE_RE = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
        val REALM_RE = Regex("realm\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    }
}

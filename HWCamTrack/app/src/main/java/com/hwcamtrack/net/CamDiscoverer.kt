package com.hwcamtrack.net

import android.content.Context
import com.hwcamtrack.data.CamEndpoint
import com.hwcamtrack.data.CamProtocol
import com.hwcamtrack.data.EndpointStore
import com.hwcamtrack.data.ProfileCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 发现过程中的事件流，UI 边收边显示。 */
sealed interface DiscoveryEvent {
    /** 过程日志，用来向用户解释"为什么是这台"。 */
    data class Log(val message: String) : DiscoveryEvent

    /** 发现了一个可用端点（可能陆续多个）。 */
    data class Found(val endpoint: CamEndpoint) : DiscoveryEvent

    /** 扫描结束。 */
    data class Finished(val endpoints: List<CamEndpoint>) : DiscoveryEvent

    /** 前提条件不满足（例如没连 WiFi）。 */
    data class Failed(val message: String) : DiscoveryEvent
}

/**
 * 自动发现引擎。
 *
 * 整体策略是"先验假设 + 快速证伪"，一共五步：
 *
 * 1. **历史记录优先**：同一个 SSID 上次连通过谁，直接验证那一个（毫秒级）。
 * 2. **SSDP 线索**：支持 UPnP 的记录仪会自己报地址。
 * 3. **端口快扫**：对少量高概率地址（网关 / DNS / 常见网关位）并行做 TCP 连接，
 *    把 N×16 次连接压缩成"哪几个 (host, port) 真的有服务"。
 * 4. **协议握手**：只对开放端口做 RTSP DESCRIBE / HTTP 指纹 + MJPEG/FLV 探测，
 *    这一步才能拿到真正的地址、端口、协议和编码格式。
 * 5. **兜底全网段**：前四步颗粒无收时，才把整个 /24 扫一遍（只扫 554/80/8080）。
 *
 * 一旦拿到高置信度结果就提前收敛，不让用户干等。
 */
class CamDiscoverer(context: Context) {

    private val appContext = context.applicationContext
    private val store = EndpointStore(appContext)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(4000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val http = HttpFingerprint(httpClient)

    fun discover(net: NetworkContext): Flow<DiscoveryEvent> = channelFlow {
        if (!net.isUsable) {
            send(DiscoveryEvent.Failed("The phone is not connected to Wi-Fi. Join the camera hotspot first."))
            send(DiscoveryEvent.Finished(emptyList()))
            return@channelFlow
        }

        val emit: suspend (DiscoveryEvent) -> Unit = { send(it) }
        val log: suspend (String) -> Unit = { emit(DiscoveryEvent.Log(it)) }

        val results = Collections.synchronizedList(ArrayList<CamEndpoint>())
        val seenKeys = Collections.synchronizedSet(HashSet<String>())
        val bestConfidence = AtomicInteger(0)

        val publish: suspend (List<CamEndpoint>) -> Unit = { endpoints ->
            for (endpoint in endpoints) {
                if (seenKeys.add(endpoint.key)) {
                    results.add(endpoint)
                    if (endpoint.confidence > bestConfidence.get()) {
                        bestConfidence.set(endpoint.confidence)
                    }
                    emit(DiscoveryEvent.Found(endpoint))
                }
            }
        }

        val converged: () -> Boolean = { bestConfidence.get() >= HIGH_CONFIDENCE }

        log("Network: ${net.summary}")

        // ---- 第 1 步：历史记录 ----
        val learned = store.load(net.cacheKey)
        if (learned.isNotEmpty()) {
            log("Found ${learned.size} cached endpoint(s) for ${net.cacheKey}; verifying first")
            val verified = learned
                .map { endpoint -> async(Dispatchers.IO) { verify(endpoint) } }
                .awaitAll()
                .filterNotNull()
            publish(verified)
            if (converged()) {
                log("Cached endpoint still valid; skipping the subnet sweep")
                store.save(net.cacheKey, results.sortedByDescending { it.confidence })
                emit(DiscoveryEvent.Finished(results.sortedByDescending { it.confidence }))
                return@channelFlow
            }
        }

        // ---- 第 2 步：SSDP ----
        val ssdpDevices = runCatching { SsdpProbe.search(SSDP_TIMEOUT_MS) }.getOrDefault(emptyList())
        val ssdpHosts = if (ssdpDevices.isEmpty()) {
            emptyList()
        } else {
            log("SSDP: ${ssdpDevices.size} response(s): ${ssdpDevices.joinToString { "${it.address}(${it.server ?: "?"})" }}")
            ssdpDevices.map { it.address }
        }

        // ---- 第 3 步：候选地址端口快扫 ----
        val candidates = buildCandidateHosts(net, ssdpHosts)
        log("Probing ${candidates.size} candidate host(s)")
        val openPorts = scanPorts(candidates, ProfileCatalog.ALL_PORTS, FAST_TIMEOUT_MS, PORT_SCAN_CONCURRENCY)
        log("Open ports: ${formatOpen(openPorts)}")

        // ---- 第 4 步：协议握手 ----
        handshakeAll(openPorts, log, publish, converged)

        // ---- 第 5 步：兜底 /24 全网段 ----
        if (results.isEmpty() && !converged()) {
            val sweepHosts = subnetHosts(net)
            if (sweepHosts.isNotEmpty()) {
                log("No luck on the common gateways; sweeping the whole subnet (${sweepHosts.size} hosts)...")
                val swept = scanPorts(sweepHosts, SWEEP_PORTS, SWEEP_TIMEOUT_MS, SWEEP_CONCURRENCY)
                log("Subnet open ports: ${formatOpen(swept)}")
                handshakeAll(swept, log, publish, converged)
            }
        }

        if (results.isEmpty()) {
            log("No playable stream found. Check: hotspot connected, camera powered on, phone not blocked by AP isolation.")
        }

        val sorted = results.sortedByDescending { it.confidence }
        if (sorted.isNotEmpty()) {
            store.save(net.cacheKey, sorted)
            log("Scan finished: ${sorted.size} result(s); the best one is cached for next time")
        }
        emit(DiscoveryEvent.Finished(sorted))
    }

    // ------------------------------------------------------------------
    // 各阶段实现
    // ------------------------------------------------------------------

    /** 对"有开放端口"的地址做协议握手；地址之间并行，地址内部串行。 */
    private suspend fun handshakeAll(
        openPorts: Map<String, List<Int>>,
        log: suspend (String) -> Unit,
        publish: suspend (List<CamEndpoint>) -> Unit,
        converged: () -> Boolean
    ) = coroutineScope {
        openPorts.keys.map { host ->
            launch(Dispatchers.IO) {
                if (converged()) return@launch
                val ports = openPorts[host].orEmpty()
                val found = runCatching { probeHost(host, ports, log) }.getOrDefault(emptyList())
                if (found.isNotEmpty()) publish(found)
            }
        }.joinAll()
    }

    /** 对一个地址，按 RTSP → HTTP → 裸码流 的优先级做握手。 */
    private suspend fun probeHost(
        host: String,
        ports: List<Int>,
        log: suspend (String) -> Unit
    ): List<CamEndpoint> {
        // RTSP 优先：记录仪主码流基本都是 RTSP，画质和延迟都最好
        for (port in ProfileCatalog.RTSP_PORTS) {
            if (port !in ports) continue
            log("$host:$port is open; trying RTSP")
            probeRtsp(host, port, log)?.let { return listOf(it) }
            log("$host:$port is not a usable RTSP endpoint")
        }

        // HTTP：指纹 + MJPEG / FLV 路径探测
        for (port in ProfileCatalog.HTTP_PORTS) {
            if (port !in ports) continue
            val endpoints = probeHttp(host, port, log)
            if (endpoints.isNotEmpty()) return endpoints
        }

        // 裸 H.264 over TCP：连上就推二进制，没有协议信封
        for (port in ProfileCatalog.RAW_PORTS) {
            if (port !in ports) continue
            val sniff = SocketProbe.sniff(host, port, FAST_TIMEOUT_MS, SNIFF_READ_TIMEOUT_MS)
            if (!sniff.open || sniff.bytes.isEmpty()) continue
            if (sniff.looksLikeHttp() || sniff.looksLikeRtsp()) continue
            if (sniff.looksLikeAnnexB()) {
                log("$host:$port pushes Annex-B directly; detected raw H.264")
                return listOf(
                    CamEndpoint(
                        host = host,
                        port = port,
                        protocol = CamProtocol.RAW_H264_TCP,
                        videoCodec = "H264",
                        confidence = 58,
                        evidence = listOf("pushes Annex-B start codes on connect", "no RTSP/HTTP envelope")
                    )
                )
            }
        }

        return emptyList()
    }

    /** RTSP：OPTIONS 探活 + 逐路径 DESCRIBE，拿到 SDP 就拿到了"协议格式"。 */
    private suspend fun probeRtsp(
        host: String,
        port: Int,
        log: suspend (String) -> Unit
    ): CamEndpoint? = withContext(Dispatchers.IO) {
        val client = RtspClient(host, port, RTSP_TIMEOUT_MS)
        try {
            client.connect()

            val options = runCatching { client.options("rtsp://$host:$port/") }.getOrNull()
            val server = options?.server
            val profile = ProfileCatalog.matchByServer(server)
            options?.let {
                log(
                    "RTSP OPTIONS ${it.statusLine}" +
                        (server?.let { value -> " · Server=$value" } ?: "") +
                        (profile?.let { value -> " - looks like ${value.vendor}" } ?: "")
                )
            }

            val paths = ProfileCatalog.rtspPathsFor(profile).take(MAX_RTSP_PATH_TRIES)
            for (path in paths) {
                val url = "rtsp://$host:$port$path"
                val response = runCatching { client.describe(url) }.getOrNull() ?: continue
                if (!response.isSuccess) continue

                val sdp = SdpParser.parse(response.body)
                if (!sdp.hasVideo) continue

                log("RTSP DESCRIBE ok: $url -> ${sdp.summary}")
                val contentBase = response.contentBase
                if (!contentBase.isNullOrBlank()) {
                    log("Content-Base = $contentBase (SETUP/PLAY must resolve against it)")
                }
                return@withContext CamEndpoint(
                    host = host,
                    port = port,
                    protocol = CamProtocol.RTSP,
                    path = path,
                    vendor = profile?.vendor.orEmpty(),
                    serverHeader = server.orEmpty(),
                    videoCodec = sdp.videoCodec,
                    audioCodec = sdp.audioCodec,
                    width = sdp.width,
                    height = sdp.height,
                    fps = sdp.fps,
                    confidence = 95,
                    evidence = buildList {
                        add("RTSP DESCRIBE ok (path $path)")
                        add("SDP: ${sdp.summary}")
                        server?.let { add("Server: $it") }
                        profile?.let { add("profile match: ${it.vendor}") }
                        contentBase?.let { add("Content-Base: $it") }
                    },
                    rtspContentBase = contentBase.orEmpty()
                )
            }
            null
        } catch (t: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    /** HTTP：先抓指纹，再按厂商偏好顺序试 MJPEG / FLV 路径。 */
    private suspend fun probeHttp(
        host: String,
        port: Int,
        log: suspend (String) -> Unit
    ): List<CamEndpoint> {
        val base = "http://$host:$port"
        val fingerprint = http.probeRoot(base) ?: return emptyList()
        val profile = http.matchVendor(fingerprint)
        val vendor = profile?.vendor.orEmpty()

        log(
            "HTTP $base → ${fingerprint.statusCode}" +
                (fingerprint.server?.let { " · Server=$it" } ?: "") +
                (fingerprint.title?.let { " - title=$it" } ?: "") +
                (profile?.let { " - looks like ${it.vendor}" } ?: "")
        )

        val evidenceBase = buildList {
            add("HTTP ${fingerprint.statusCode}")
            fingerprint.server?.let { add("Server: $it") }
            fingerprint.title?.let { add("title: $it") }
            fingerprint.authScheme?.let { add("auth: $it") }
        }

        for (path in ProfileCatalog.mjpegPathsFor(profile).take(MAX_MJPEG_PATH_TRIES)) {
            val contentType = http.confirmMultipart("$base$path") ?: continue
            log("MJPEG stream confirmed: $base$path ($contentType)")
            return listOf(
                CamEndpoint(
                    host = host,
                    port = port,
                    protocol = CamProtocol.HTTP_MJPEG,
                    path = path,
                    vendor = vendor,
                    serverHeader = fingerprint.server.orEmpty(),
                    videoCodec = "MJPEG",
                    confidence = 88,
                    evidence = evidenceBase + "Content-Type: $contentType"
                )
            )
        }

        for (path in ProfileCatalog.flvPathsFor(profile).take(MAX_FLV_PATH_TRIES)) {
            if (!http.confirmFlv("$base$path")) continue
            log("HTTP-FLV stream confirmed: $base$path")
            return listOf(
                CamEndpoint(
                    host = host,
                    port = port,
                    protocol = CamProtocol.HTTP_FLV,
                    path = path,
                    vendor = vendor,
                    serverHeader = fingerprint.server.orEmpty(),
                    videoCodec = "H264",
                    confidence = 85,
                    evidence = evidenceBase + "FLV magic confirmed"
                )
            )
        }

        // 有记录仪指纹、但没找到流路径：给一个低分候选，让用户能手动补路径
        if (profile != null) {
            return listOf(
                CamEndpoint(
                    host = host,
                    port = port,
                    protocol = CamProtocol.HTTP_MP4,
                    path = "/",
                    vendor = vendor,
                    serverHeader = fingerprint.server.orEmpty(),
                    confidence = 30,
                    evidence = evidenceBase + "profile fingerprint matched but no stream path found"
                )
            )
        }

        return emptyList()
    }

    /** 验证缓存里的端点是否仍然可用。 */
    private suspend fun verify(endpoint: CamEndpoint): CamEndpoint? = when (endpoint.protocol) {
        CamProtocol.RTSP -> verifyRtsp(endpoint)

        CamProtocol.HTTP_MJPEG ->
            if (http.confirmMultipart(endpoint.uri) != null) {
                endpoint.copy(confidence = maxOf(endpoint.confidence, 92))
            } else {
                null
            }

        CamProtocol.HTTP_FLV ->
            if (http.confirmFlv(endpoint.uri)) {
                endpoint.copy(confidence = maxOf(endpoint.confidence, 88))
            } else {
                null
            }

        CamProtocol.RAW_H264_TCP ->
            if (SocketProbe.isOpen(endpoint.host, endpoint.port, FAST_TIMEOUT_MS)) endpoint else null

        else -> null
    }

    private suspend fun verifyRtsp(endpoint: CamEndpoint): CamEndpoint? = withContext(Dispatchers.IO) {
        val client = RtspClient(endpoint.host, endpoint.port, RTSP_TIMEOUT_MS)
        try {
            client.connect()
            val response = client.describe(endpoint.uri)
            if (!response.isSuccess) return@withContext null
            val sdp = SdpParser.parse(response.body)
            if (!sdp.hasVideo) return@withContext null
            endpoint.copy(
                videoCodec = sdp.videoCodec.ifEmpty { endpoint.videoCodec },
                audioCodec = sdp.audioCodec.ifEmpty { endpoint.audioCodec },
                width = if (sdp.width > 0) sdp.width else endpoint.width,
                height = if (sdp.height > 0) sdp.height else endpoint.height,
                fps = if (sdp.fps > 0) sdp.fps else endpoint.fps,
                confidence = 95,
                rtspContentBase = response.contentBase ?: endpoint.rtspContentBase
            )
        } catch (t: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------
    // 地址/端口计算
    // ------------------------------------------------------------------

    /** 并行扫端口，返回 host -> 开放端口列表。 */
    private suspend fun scanPorts(
        hosts: List<String>,
        ports: IntArray,
        timeoutMs: Int,
        concurrency: Int
    ): Map<String, List<Int>> = coroutineScope {
        val open = Collections.synchronizedList(ArrayList<Pair<String, Int>>())
        val semaphore = Semaphore(concurrency)

        val targets = ArrayList<Pair<String, Int>>(hosts.size * ports.size)
        for (host in hosts) for (port in ports) targets.add(host to port)

        targets.map { (host, port) ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    if (SocketProbe.isOpen(host, port, timeoutMs)) {
                        open.add(host to port)
                    }
                }
            }
        }.joinAll()

        open.groupBy({ it.first }, { it.second })
    }

    /**
     * 候选地址排序：SSDP 报的 → 网关 → DNS → 同网段的常见网关位 → 常见主机位。
     * 网关几乎必然是记录仪本身，所以它一定排在最前面。
     */
    private fun buildCandidateHosts(net: NetworkContext, ssdpHosts: List<String>): List<String> {
        val ordered = LinkedHashSet<String>()
        ssdpHosts.forEach { ordered.add(it) }
        net.gateway?.let { ordered.add(it) }
        net.dnsServers.forEach { ordered.add(it) }

        val prefix = net.subnet24
        if (prefix != null) {
            ProfileCatalog.GATEWAY_HINTS.filter { it.startsWith(prefix) }.forEach { ordered.add(it) }
            ProfileCatalog.HOST_HINTS.forEach { ordered.add("$prefix$it") }
        } else {
            ordered.addAll(ProfileCatalog.GATEWAY_HINTS)
        }

        net.localIp?.let { ordered.remove(it) }
        return ordered.filter { it.isNotBlank() }
    }

    /** /24 里除本机外的全部地址，用于兜底全网段扫描。 */
    private fun subnetHosts(net: NetworkContext): List<String> {
        val prefix = net.subnet24 ?: return emptyList()
        return (1..254).map { "$prefix$it" }.filter { it != net.localIp }
    }

    private fun formatOpen(openPorts: Map<String, List<Int>>): String =
        if (openPorts.isEmpty()) {
            "none"
        } else {
            openPorts.entries.joinToString("，") { (host, ports) ->
                "$host:${ports.sorted().joinToString("/")}"
            }
        }

    private companion object {
        /** 置信度达到这个值就不必再扫了。 */
        const val HIGH_CONFIDENCE = 92

        const val FAST_TIMEOUT_MS = 320
        const val SWEEP_TIMEOUT_MS = 260
        const val RTSP_TIMEOUT_MS = 1500
        const val SNIFF_READ_TIMEOUT_MS = 600
        const val SSDP_TIMEOUT_MS = 1200

        const val PORT_SCAN_CONCURRENCY = 32
        const val SWEEP_CONCURRENCY = 64

        const val MAX_RTSP_PATH_TRIES = 6
        const val MAX_MJPEG_PATH_TRIES = 6
        const val MAX_FLV_PATH_TRIES = 3

        /** 兜底全网段只试这三个口：覆盖了绝大多数记录仪。 */
        val SWEEP_PORTS = intArrayOf(554, 80, 8080)
    }
}

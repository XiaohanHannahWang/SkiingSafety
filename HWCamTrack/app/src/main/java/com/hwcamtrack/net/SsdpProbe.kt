package com.hwcamtrack.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.Locale

/** 一条 SSDP 应答。 */
data class SsdpDevice(
    val address: String,
    val server: String?,
    val location: String?,
    val usn: String?
) {
    val looksLikeCamera: Boolean
        get() {
            val text = "$server ${usn.orEmpty()} ${location.orEmpty()}".lowercase(Locale.US)
            return text.contains("camera") || text.contains("ipcam") ||
                text.contains("onvif") || text.contains("rtsp") ||
                text.contains("dvr") || text.contains("nvr")
        }
}

/**
 * SSDP（UPnP 设备发现）探测。
 *
 * 记录仪里只有一部分支持 UPnP，但代价极低（一次组播 + 一秒收包），
 * 命中就能直接把"可疑网段扫描"降级成"直接去问这一个地址"。
 * 全部失败也只是返回空列表，不影响主流程。
 */
object SsdpProbe {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val RECEIVE_TIMEOUT_MS = 400

    suspend fun search(timeoutMs: Int = 1500): List<SsdpDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, SsdpDevice>()

        runCatching {
            MulticastSocket().use { socket ->
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                runCatching { socket.timeToLive = 2 }

                val request = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: ").append(SSDP_ADDRESS).append(':').append(SSDP_PORT).append("\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 1\r\n")
                    append("ST: ssdp:all\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)

                val group = InetAddress.getByName(SSDP_ADDRESS)
                socket.send(DatagramPacket(request, request.size, group, SSDP_PORT))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)

                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    }
                    val address = packet.address?.hostAddress ?: continue
                    val headers = parseHeaders(
                        String(packet.data, 0, packet.length, Charsets.UTF_8)
                    )
                    found[address] = SsdpDevice(
                        address = address,
                        server = headers["server"],
                        location = headers["location"],
                        usn = headers["usn"]
                    )
                }
            }
        }

        found.values.toList()
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("HTTP/")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                line.substring(colon + 1).trim()
        }
        return headers
    }
}

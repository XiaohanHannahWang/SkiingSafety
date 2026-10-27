package com.hwcamtrack.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 最原始的 TCP 探针：只判断"端口开不开"和"对方先说什么"。
 *
 * 这一步是整个发现流程的过滤器——把 250 个地址 × 8 个端口压缩成少数几个
 * 真正有服务在听的 (host, port)，后面才值得做昂贵的协议握手。
 */
object SocketProbe {

    /** 裸连接结果 + 服务器主动吐出来的前几个字节。 */
    class Sniff(val open: Boolean, val bytes: ByteArray) {
        /** 用 ASCII 展示前若干字节，便于判断是不是文本协议。 */
        fun ascii(): String = buildString {
            bytes.take(24).forEach { b ->
                val v = b.toInt() and 0xFF
                append(if (v in 32..126) v.toChar() else '.')
            }
        }

        /** 是不是 Annex-B 起始码（00 00 01 / 00 00 00 01）开头——裸 H.264 的典型特征。 */
        fun looksLikeAnnexB(): Boolean {
            if (bytes.size < 4) return false
            val b = bytes
            return (b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte()) ||
                (b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && b[3] == 1.toByte())
        }

        fun looksLikeHttp(): Boolean = ascii().startsWith("HTTP/")
        fun looksLikeRtsp(): Boolean = ascii().startsWith("RTSP/")
    }

    suspend fun isOpen(host: String, port: Int, timeoutMs: Int = 350): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (t: Throwable) {
                false
            }
        }

    /**
     * 连接后被动等待一小会儿，看服务器会不会主动发东西。
     * 记录仪的裸码流端口通常是"连上就推"，而 HTTP 端口会等你先发请求。
     */
    suspend fun sniff(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 350,
        readTimeoutMs: Int = 600
    ): Sniff = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                val buffer = ByteArray(24)
                val read = try {
                    socket.getInputStream().read(buffer)
                } catch (t: Throwable) {
                    -1
                }
                Sniff(true, if (read > 0) buffer.copyOf(read) else ByteArray(0))
            }
        } catch (t: Throwable) {
            Sniff(false, ByteArray(0))
        }
    }
}

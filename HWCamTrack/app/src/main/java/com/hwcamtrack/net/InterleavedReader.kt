package com.hwcamtrack.net

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 从 RTSP over TCP 的字节流里读出交错帧（RFC 2326 §10.12）：
 * `'$' | channel(1B) | length(2B) | payload`。
 *
 * ## 为什么要单独一个类
 *
 * 取流阶段服务器还会往同一条连接上写**保活请求的 RTSP 回执**。处理不好就会
 * 把回执正文里的某个 0x24（`$`）当成帧头，再把它后面两个字节当成长度，
 * 结果吞掉一段真实数据、整条流从此错位。
 *
 * 所以这里不是"找不到 `$` 就逐字节丢弃"，而是：**只要不是 `$` 开头，
 * 就认定这是一段 RTSP 报文，把它完整读掉**。服务器不会把报文插进交错帧的载荷中间，
 * 所以流一定是"帧 / 报文"交替出现，这样处理既不丢同步也不吞数据。
 *
 * 逻辑与 socket 解耦之后可以纯内存单测（见 `InterleavedReaderTest`）。
 */
class InterleavedReader(private val input: InputStream) {

    /** 被当作 RTSP 报文前缀丢掉的字节数。 */
    var discardedBytes: Long = 0
        private set

    /** 完整读掉并丢弃的 RTSP 报文数（正常只来自保活回执）。 */
    var discardedRtspMessages: Long = 0
        private set

    /** 读一个交错帧；返回 null 表示连接结束。 */
    fun read(): InterleavedFrame? {
        while (true) {
            val first = input.read()
            if (first < 0) return null

            if (first != INTERLEAVE_MAGIC) {
                discardedBytes++
                consumeRtspMessage(first)
                discardedRtspMessages++
                continue
            }

            val channel = input.read()
            val high = input.read()
            val low = input.read()
            if (channel < 0 || high < 0 || low < 0) return null

            val length = (high shl 8) or low
            if (length <= 0 || length > MAX_INTERLEAVED_BYTES) {
                // 长度不合理，说明这个 '$' 不是帧头（多半在回执正文里）。
                // 把已经读掉的通道/长度字节算作丢弃，回头继续扫。
                discardedBytes += 3
                continue
            }

            val data = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(data, offset, length - offset)
                if (read < 0) return null
                offset += read
            }
            return InterleavedFrame(channel, data)
        }
    }

    /** 读掉一段完整的 RTSP 报文：状态行 + 头 + Content-Length 字节的正文。 */
    private fun consumeRtspMessage(firstByte: Int) {
        // firstByte 是状态行的首字符，剩下的部分连同后续头行逐行消费掉
        readLine() ?: return

        var contentLength = 0
        var headerLines = 0
        while (headerLines++ < MAX_RTSP_HEADER_LINES) {
            val line = readLine() ?: return
            if (line.isEmpty()) break
            val parsed = extractContentLength(line)
            if (parsed >= 0) contentLength = parsed
        }

        var remaining = contentLength.coerceIn(0, MAX_BODY_BYTES)
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(remaining, scratch.size))
            if (read < 0) return
            remaining -= read
        }
    }

    /** 从一行头里取 Content-Length；不是这一行就返回 -1。 */
    private fun extractContentLength(line: String): Int {
        val colon = line.indexOf(':')
        if (colon <= 0) return -1
        if (!line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) return -1
        return line.substring(colon + 1).trim().toIntOrNull() ?: -1
    }

    /** 按 CRLF / LF 读一行（返回内容，不含行尾）。流结束返回 null。 */
    private fun readLine(): String? {
        val buffer = ByteArrayOutputStream(64)
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                return if (buffer.size() == 0) null else ascii(buffer)
            }
            if (byte == '\n'.code) return ascii(buffer).trimEnd('\r')
            buffer.write(byte)
            if (buffer.size() > MAX_LINE_BYTES) return ascii(buffer)
        }
    }

    private fun ascii(buffer: ByteArrayOutputStream) =
        String(buffer.toByteArray(), Charsets.US_ASCII)

    private companion object {
        const val INTERLEAVE_MAGIC = 0x24 // '$'
        const val MAX_INTERLEAVED_BYTES = 256 * 1024
        const val MAX_BODY_BYTES = 256 * 1024
        const val MAX_LINE_BYTES = 8192
        const val MAX_RTSP_HEADER_LINES = 64
    }
}

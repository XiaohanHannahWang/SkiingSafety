package com.hwcamtrack.util

import java.io.ByteArrayOutputStream

/**
 * 最小可用的 Base64 编解码。
 *
 * 为什么不用 `android.util.Base64`：`net` 包里的 RTSP 客户端、SDP / SPS 解析全是纯逻辑，
 * 换掉这最后一个 Android 依赖之后，整个 `net` 包就能在桌面上用 JVM 直接编译运行，
 * 可以对着真实摄像头验证握手和解包是否正确（本工程的 tools/ 里就是这么做的）。
 */
object Base64Codec {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0

        while (i + 2 < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }

        when (bytes.size - i) {
            1 -> {
                val b0 = bytes[i].toInt() and 0xFF
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[(b0 and 0x03) shl 4])
                out.append("==")
            }

            2 -> {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                out.append(ALPHABET[(b1 and 0x0F) shl 2])
                out.append('=')
            }
        }

        return out.toString()
    }

    /**
     * 宽松解码：忽略空白和非法字符，也容忍缺少的 `=` 填充。
     * SDP 里的 `sprop-parameter-sets` 经常是裸 base64，这样最省事。
     */
    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0

        for (char in text) {
            if (char == '=') break
            val value = if (char.code in DECODE_TABLE.indices) DECODE_TABLE[char.code] else -1
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }

        return out.toByteArray()
    }
}

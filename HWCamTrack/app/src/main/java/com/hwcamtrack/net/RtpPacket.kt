package com.hwcamtrack.net

/**
 * 一个 RTP 包（RFC 3550）的头部解析结果。
 *
 * 只做"定位载荷"这一件事：算出载荷从第几个字节开始、有多长，
 * 剩下交给 [H264RtpDepacketizer]。
 */
class RtpPacket(
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    /** 同步源标识，构造 RTCP 接收报告时要带上。 */
    val ssrc: Long,
    val payloadOffset: Int,
    val payloadLength: Int
) {
    companion object {
        private const val FIXED_HEADER_SIZE = 12

        fun parse(data: ByteArray, length: Int = data.size): RtpPacket? {
            if (length < FIXED_HEADER_SIZE) return null

            val b0 = data[0].toInt() and 0xFF
            val version = b0 ushr 6
            if (version != 2) return null

            val padding = (b0 and 0x20) != 0
            val extension = (b0 and 0x10) != 0
            val csrcCount = b0 and 0x0F

            val b1 = data[1].toInt() and 0xFF
            val marker = (b1 and 0x80) != 0
            val payloadType = b1 and 0x7F

            val sequence = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val timestamp = ((data[4].toLong() and 0xFF) shl 24) or
                ((data[5].toLong() and 0xFF) shl 16) or
                ((data[6].toLong() and 0xFF) shl 8) or
                (data[7].toLong() and 0xFF)

            val ssrc = ((data[8].toLong() and 0xFF) shl 24) or
                ((data[9].toLong() and 0xFF) shl 16) or
                ((data[10].toLong() and 0xFF) shl 8) or
                (data[11].toLong() and 0xFF)

            var offset = FIXED_HEADER_SIZE + csrcCount * 4
            if (offset > length) return null

            if (extension) {
                if (offset + 4 > length) return null
                val extensionWords = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
                offset += 4 + extensionWords * 4
                if (offset > length) return null
            }

            var end = length
            if (padding && end > offset) {
                // 最后一个字节是 padding 长度
                val paddingLength = data[end - 1].toInt() and 0xFF
                if (paddingLength in 1..(end - offset)) end -= paddingLength
            }

            if (end < offset) return null

            return RtpPacket(
                marker = marker,
                payloadType = payloadType,
                sequenceNumber = sequence,
                timestamp = timestamp,
                ssrc = ssrc,
                payloadOffset = offset,
                payloadLength = end - offset
            )
        }
    }
}

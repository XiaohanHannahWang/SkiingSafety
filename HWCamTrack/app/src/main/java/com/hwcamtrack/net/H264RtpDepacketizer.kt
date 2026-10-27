package com.hwcamtrack.net

import java.io.ByteArrayOutputStream

/**
 * RFC 6184：把 H.264 的 RTP 载荷还原成 Annex-B 访问单元。
 *
 * 记录仪只会用到三种打包模式：
 *
 * | RTP 载荷类型 | 含义 | 处理 |
 * |---|---|---|
 * | 1 ~ 23 | 单个 NAL 单元 | 直接前面补 `00 00 00 01` |
 * | 24 | STAP-A，把 SPS/PPS/SEI 等小 NAL 打包在一起 | 按 16 位长度前缀逐个拆开 |
 * | 28 | FU-A，一个大 NAL 拆成多个 RTP 包 | 按 S/E 标志重组，还原 NAL 头 |
 *
 * 访问单元的边界用 RTP 头的 **marker 位**判断（marker = 本包是 AU 的最后一包），
 * 这也正是 MediaCodec 想要的一帧。
 */
class H264RtpDepacketizer {

    private val accessUnit = ByteArrayOutputStream(ACCESS_UNIT_CAPACITY)
    private val fragment = ByteArrayOutputStream(FRAGMENT_CAPACITY)

    var packetCount: Long = 0
        private set

    var nalCount: Long = 0
        private set

    /** 丢掉当前攒了一半的数据（例如重连之后）。 */
    fun reset() {
        accessUnit.reset()
        fragment.reset()
    }

    /**
     * 喂一个 RTP 载荷。
     *
     * @return marker 置位且攒到了完整访问单元时返回 Annex-B 字节；否则返回 null。
     */
    fun onRtpPayload(payload: ByteArray, offset: Int, length: Int, marker: Boolean): ByteArray? {
        if (length <= 0) return null
        packetCount++

        when (val nalType = payload[offset].toInt() and 0x1F) {
            in 1..23 -> appendNal(payload, offset, length)
            STAP_A -> appendStapA(payload, offset, length)
            FU_A -> appendFuA(payload, offset, length)
            else -> {
                // 25(STAP-B)/26(MTAP16)/27(MTAP24)/29-31 在记录仪上基本不出现；
                // 0 是未定义类型，一并忽略。
                if (nalType != 0) lastIgnoredType = nalType
            }
        }

        if (!marker || accessUnit.size() == 0) return null

        val unit = accessUnit.toByteArray()
        accessUnit.reset()
        return unit
    }

    /** 最近一个被忽略的载荷类型，用于日志/排错。 */
    var lastIgnoredType: Int = -1
        private set

    private fun appendNal(source: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        accessUnit.write(START_CODE, 0, START_CODE.size)
        accessUnit.write(source, offset, length)
        nalCount++
    }

    private fun appendStapA(payload: ByteArray, offset: Int, length: Int) {
        var index = offset + 1
        val end = offset + length
        while (index + 2 <= end) {
            val nalSize = ((payload[index].toInt() and 0xFF) shl 8) or
                (payload[index + 1].toInt() and 0xFF)
            index += 2
            if (nalSize <= 0 || index + nalSize > end) break
            appendNal(payload, index, nalSize)
            index += nalSize
        }
    }

    private fun appendFuA(payload: ByteArray, offset: Int, length: Int) {
        if (length < 3) return

        val indicator = payload[offset].toInt() and 0xFF
        val fuHeader = payload[offset + 1].toInt() and 0xFF

        val isStart = (fuHeader and 0x80) != 0
        val isEnd = (fuHeader and 0x40) != 0
        val originalType = fuHeader and 0x1F

        if (isStart) {
            fragment.reset()
            // 还原原始 NAL 头：F 和 NRI 来自 FU indicator，type 来自 FU header
            fragment.write((indicator and 0xE0) or originalType)
        }

        if (fragment.size() > 0) {
            if (fragment.size() + (length - 2) > MAX_FRAGMENT_BYTES) {
                // 异常大的分片（丢包后错位重组），放弃这一帧
                fragment.reset()
                return
            }
            fragment.write(payload, offset + 2, length - 2)
        }

        if (isEnd && fragment.size() > 0) {
            val nal = fragment.toByteArray()
            fragment.reset()
            appendNal(nal, 0, nal.size)
        }
    }

    companion object {
        private const val STAP_A = 24
        private const val FU_A = 28

        private const val ACCESS_UNIT_CAPACITY = 128 * 1024
        private const val FRAGMENT_CAPACITY = 32 * 1024
        private const val MAX_FRAGMENT_BYTES = 2 * 1024 * 1024

        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

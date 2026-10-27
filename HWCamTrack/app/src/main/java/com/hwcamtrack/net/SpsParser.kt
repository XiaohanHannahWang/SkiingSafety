package com.hwcamtrack.net

import com.hwcamtrack.util.Base64Codec
import java.io.ByteArrayOutputStream

/**
 * 极简 H.264 SPS 解析器——只为从码流参数里取出真实分辨率。
 *
 * 为什么需要它：大量记录仪的 SDP 里**没有** `a=x-dimensions`，也不给 framerate，
 * 只给一行 `a=fmtp:96 ...;sprop-parameter-sets=Z00AKeKQB4A8YNwAQGAGH4s=,aO48gA==`。
 * 那两个 base64 就是 SPS/PPS，分辨率信息完整地编码在 SPS 里。
 * 把 SPS 解开，"自动获取协议格式"才真正拿得到 1920x1080 这种可用信息。
 */
object SpsParser {

    data class Size(val width: Int, val height: Int)

    /** 从 SDP 的 `sprop-parameter-sets` 值（"SPS,PPS" 的 base64）解析分辨率。 */
    fun fromSpropParameterSets(sprop: String): Size? {
        val first = sprop.substringBefore(',').trim()
        if (first.isEmpty()) return null
        val raw = runCatching { Base64Codec.decode(first) }.getOrNull() ?: return null
        return parse(raw)
    }

    /**
     * 解析一段 SPS NAL（可以带 3/4 字节起始码，也可以是裸 NAL）。
     * 任何异常都返回 null——这是"尽力而为"的信息增强，不能因为它让发现流程失败。
     */
    fun parse(nal: ByteArray): Size? = runCatching {
        val withoutStartCode = stripStartCode(nal)
        val data = stripEmulationPrevention(withoutStartCode)
        if (data.size < 4) return@runCatching null

        val reader = BitReader(data)

        val nalHeader = reader.u(8)
        val nalType = nalHeader and 0x1F
        if (nalType != NAL_TYPE_SPS) return@runCatching null

        val profileIdc = reader.u(8)
        reader.u(8) // constraint_set flags + reserved
        reader.u(8) // level_idc
        reader.ue() // seq_parameter_set_id

        var chromaFormatIdc = 1
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = reader.ue()
            if (chromaFormatIdc == 3) reader.u(1) // separate_colour_plane_flag
            reader.ue() // bit_depth_luma_minus8
            reader.ue() // bit_depth_chroma_minus8
            reader.u(1) // qpprime_y_zero_transform_bypass_flag
            if (reader.u(1) == 1) { // seq_scaling_matrix_present_flag
                val count = if (chromaFormatIdc != 3) 8 else 12
                for (i in 0 until count) {
                    if (reader.u(1) == 1) skipScalingList(reader, if (i < 6) 16 else 64)
                }
            }
        }

        reader.ue() // log2_max_frame_num_minus4
        when (reader.ue()) { // pic_order_cnt_type
            0 -> reader.ue() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                reader.u(1) // delta_pic_order_always_zero_flag
                reader.se() // offset_for_non_ref_pic
                reader.se() // offset_for_top_to_bottom_field
                val cycle = reader.ue().coerceAtMost(MAX_POC_CYCLE)
                repeat(cycle) { reader.se() }
            }
        }

        reader.ue() // max_num_ref_frames
        reader.u(1) // gaps_in_frame_num_value_allowed_flag

        val picWidthInMbs = reader.ue() + 1
        val picHeightInMapUnits = reader.ue() + 1
        val frameMbsOnlyFlag = reader.u(1)
        if (frameMbsOnlyFlag == 0) reader.u(1) // mb_adaptive_frame_field_flag
        reader.u(1) // direct_8x8_inference_flag

        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (reader.u(1) == 1) { // frame_cropping_flag
            cropLeft = reader.ue()
            cropRight = reader.ue()
            cropTop = reader.ue()
            cropBottom = reader.ue()
        }

        // 裁剪单位取决于色度采样格式（H.264 规范 7.4.2.1.1）
        val subWidthC = if (chromaFormatIdc == 3) 1 else 2
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        val cropUnitX = if (chromaFormatIdc == 0) 1 else subWidthC
        val cropUnitY = if (chromaFormatIdc == 0) {
            2 - frameMbsOnlyFlag
        } else {
            subHeightC * (2 - frameMbsOnlyFlag)
        }

        val width = picWidthInMbs * 16 - cropUnitX * (cropLeft + cropRight)
        val height = (2 - frameMbsOnlyFlag) * picHeightInMapUnits * 16 -
            cropUnitY * (cropTop + cropBottom)

        if (width <= 0 || height <= 0) return@runCatching null
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return@runCatching null
        Size(width, height)
    }.getOrNull()

    private fun stripStartCode(nal: ByteArray): ByteArray {
        if (nal.size > 4 &&
            nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
            nal[2] == 0.toByte() && nal[3] == 1.toByte()
        ) {
            return nal.copyOfRange(4, nal.size)
        }
        if (nal.size > 3 &&
            nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()
        ) {
            return nal.copyOfRange(3, nal.size)
        }
        return nal
    }

    /** 去掉防竞争字节：00 00 03 -> 00 00。 */
    private fun stripEmulationPrevention(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var zeros = 0
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value == 0x03) {
                zeros = 0
                continue
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val delta = reader.se()
                nextScale = (lastScale + delta + 256) % 256
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    /** 按位读取 + 指数哥伦布解码。 */
    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun u(bits: Int): Int {
            var result = 0
            repeat(bits) { result = (result shl 1) or bit() }
            return result
        }

        fun bit(): Int {
            val byteIndex = bitPos ushr 3
            if (byteIndex >= data.size) throw IndexOutOfBoundsException("SPS data out of bounds")
            val value = data[byteIndex].toInt() and 0xFF
            val shift = 7 - (bitPos and 7)
            bitPos++
            return (value ushr shift) and 1
        }

        /** 无符号指数哥伦布。 */
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                if (zeros > 32) throw IllegalStateException("SPS exp-golomb overflow")
            }
            var value = 1
            repeat(zeros) { value = (value shl 1) or bit() }
            return value - 1
        }

        /** 有符号指数哥伦布。 */
        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }
    }

    private const val NAL_TYPE_SPS = 7
    private const val MAX_POC_CYCLE = 64
    private const val MAX_DIMENSION = 8192

    private val HIGH_PROFILES = setOf(
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135
    )
}

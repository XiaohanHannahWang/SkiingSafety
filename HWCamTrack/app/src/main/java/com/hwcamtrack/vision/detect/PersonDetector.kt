package com.hwcamtrack.vision.detect

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * 人体检测结果。
 *
 * @param rect  人体框，坐标系为“输入 Bitmap 的像素坐标”
 * @param score 置信度
 * @param label 类别标签（两种检测器统一为 person）
 */
data class Detection(
    val rect: RectF,
    val score: Float,
    val label: String = "person"
)

/**
 * 人体检测器抽象。
 *
 * 实现约定：
 *  - [detect] 为同步阻塞调用，调用方保证始终在同一个后台线程串行调用（本工程为单线程 Executor）
 *  - Bitmap 由调用方持有并复用，实现不得 recycle 它
 */
interface PersonDetector : AutoCloseable {

    /** 显示在界面上的名字 */
    val displayName: String

    fun detect(bitmap: Bitmap): List<Detection>

    override fun close() {}
}

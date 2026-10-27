package com.hwcamtrack.vision.detect

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * 方案 B：ML Kit Pose Detection（骨骼关键点）。
 *
 * 特点：模型随依赖打包，无需额外资源；单张图只返回“最显著的一人”，
 *       这里把可见关键点的外接矩形当作人体框。适合单人场景 / 兜底方案。
 */
class MlKitPosePersonDetector(
    private val minVisibleLandmarks: Int = MIN_VISIBLE_LANDMARKS,
    private val boxMarginRatio: Float = 0.08f,
    private val timeoutMs: Long = 5000L
) : PersonDetector {

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    override val displayName: String = "ML Kit Pose (single person)"

    override fun detect(bitmap: Bitmap): List<Detection> {
        val image = InputImage.fromBitmap(bitmap, 0)
        // 在后台线程上阻塞等待（本工程的 detect 始终在单线程 Executor 中调用）
        val pose: Pose = Tasks.await(detector.process(image), timeoutMs, TimeUnit.MILLISECONDS)
        val rect = boundingBoxOf(pose, bitmap.width, bitmap.height) ?: return emptyList()
        return listOf(Detection(rect, LANDMARK_CONFIDENCE, "person"))
    }

    private fun boundingBoxOf(pose: Pose, imageWidth: Int, imageHeight: Int): RectF? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var visible = 0

        for (landmark in pose.allPoseLandmarks) {
            if (landmark.inFrameLikelihood < MIN_LANDMARK_LIKELIHOOD) continue
            // PoseLandmark.getPosition() 标注为 @NonNull，直接使用
            val p = landmark.position
            visible++
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }

        if (visible < minVisibleLandmarks) return null
        if (maxX <= minX || maxY <= minY) return null

        // 关键点位于关节处，向四周外扩一点更接近人体轮廓
        val marginX = (maxX - minX) * boxMarginRatio
        val marginY = (maxY - minY) * boxMarginRatio

        val left = (minX - marginX).coerceIn(0f, imageWidth.toFloat())
        val top = (minY - marginY).coerceIn(0f, imageHeight.toFloat())
        val right = (maxX + marginX).coerceIn(0f, imageWidth.toFloat())
        val bottom = (maxY + marginY).coerceIn(0f, imageHeight.toFloat())
        if (right - left < 2f || bottom - top < 2f) return null
        return RectF(left, top, right, bottom)
    }

    override fun close() {
        try {
            detector.close()
        } catch (_: Throwable) {
            // 忽略关闭异常
        }
    }

    companion object {
        private const val MIN_VISIBLE_LANDMARKS = 6
        private const val MIN_LANDMARK_LIKELIHOOD = 0.5f
        private const val LANDMARK_CONFIDENCE = 0.9f
    }
}

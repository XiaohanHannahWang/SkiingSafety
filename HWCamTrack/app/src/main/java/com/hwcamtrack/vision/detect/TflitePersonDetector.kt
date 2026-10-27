package com.hwcamtrack.vision.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * 方案 A：TensorFlow Lite Task Library + EfficientDet-Lite0（COCO 数据集）。
 *
 * 特点：一次可输出多个人体框（label = person），CPU 上单帧约 40~120ms，适合多人跟踪。
 * 模型文件：app/src/main/assets/efficientdet_lite0.tflite（随 APK 打包，离线运行）。
 */
class TflitePersonDetector(
    context: Context,
    modelAssetName: String = DEFAULT_MODEL_ASSET,
    scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    maxResults: Int = 10,
    numThreads: Int = 4
) : PersonDetector {

    private val detector: ObjectDetector = ObjectDetector.createFromFileAndOptions(
        context,
        modelAssetName,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(numThreads).build())
            .setScoreThreshold(scoreThreshold)
            .setMaxResults(maxResults)
            .build()
    )

    override val displayName: String = "EfficientDet-Lite0 (multi-person)"

    override fun detect(bitmap: Bitmap): List<Detection> {
        val results = detector.detect(TensorImage.fromBitmap(bitmap))
        if (results.isEmpty()) return emptyList()

        val out = ArrayList<Detection>(results.size)
        for (d in results) {
            // 只要 COCO 的 person 类别
            var bestScore = 0f
            var isPerson = false
            for (category in d.categories) {
                if (category.label.equals("person", ignoreCase = true)) {
                    isPerson = true
                    if (category.score > bestScore) bestScore = category.score
                }
            }
            if (!isPerson) continue

            val box = d.boundingBox ?: continue
            if (box.width() < MIN_BOX_SIDE || box.height() < MIN_BOX_SIDE) continue
            out.add(Detection(RectF(box), bestScore, "person"))
        }

        normalizeIfRatioBased(out, bitmap.width, bitmap.height)
        return out
    }

    /**
     * 兼容性兜底：Task Library 的 `Detection.getBoundingBox()` 在文档里没有明确说明返回的是
     * “输入图像像素坐标”还是“0~1 归一化比例”。这里做一个安全判断：如果所有框都落在 0~1 之间，
     * 就认为是比例坐标并按图像尺寸还原（真实像素坐标不可能出现这种情况，除非图像只有 1 像素宽）。
     */
    private fun normalizeIfRatioBased(results: MutableList<Detection>, width: Int, height: Int) {
        if (results.isEmpty() || width <= 2 || height <= 2) return
        for (result in results) {
            val rect = result.rect
            if (rect.right > RATIO_UPPER_BOUND || rect.bottom > RATIO_UPPER_BOUND) return
        }
        val scaleX = width.toFloat()
        val scaleY = height.toFloat()
        for (i in results.indices) {
            val rect = results[i].rect
            results[i] = results[i].copy(
                rect = RectF(
                    rect.left * scaleX,
                    rect.top * scaleY,
                    rect.right * scaleX,
                    rect.bottom * scaleY
                )
            )
        }
    }

    override fun close() {
        try {
            detector.close()
        } catch (_: Throwable) {
            // 忽略关闭异常
        }
    }

    companion object {
        const val DEFAULT_MODEL_ASSET = "efficientdet_lite0.tflite"
        const val DEFAULT_SCORE_THRESHOLD = 0.35f
        private const val MIN_BOX_SIDE = 4f

        /** 判断“坐标像是 0~1 比例”的上界（像素坐标几乎不可能全部小于它） */
        private const val RATIO_UPPER_BOUND = 1.5f
    }
}

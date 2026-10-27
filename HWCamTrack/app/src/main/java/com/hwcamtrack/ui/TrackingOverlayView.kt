package com.hwcamtrack.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.hwcamtrack.R
import java.util.Locale

/**
 * 人体跟踪框绘制层，覆盖在视频画面之上。
 *
 * 颜色规则（题目要求）：
 *  - 正常跟踪的人体：黄色框
 *  - 面积比超过门限：红色框 + 告警文字
 *
 * 坐标映射：box.rect 用的是“分析图像像素坐标”，这里按最新的视频显示区域等比换算到 View 坐标。
 */
class TrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Box(
        val id: Int,
        /** 分析图像素坐标 */
        val rect: RectF,
        val ratio: Float,
        val ratioValid: Boolean,
        val alarmed: Boolean,
        /** 本帧没有检测到（框为上一次位置） */
        val missed: Boolean
    )

    private var boxes: List<Box> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0
    private val videoRect = RectF()

    private val density = resources.displayMetrics.density

    /** 标签文字大小（11sp 换算成 px，避免使用已废弃的 scaledDensity） */
    private val labelTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        11f,
        resources.displayMetrics
    )

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = labelTextSize
    }

    /** 正常跟踪 = 黄色框；面积比超门限 = 红色框（题目要求） */
    private val normalColor = ContextCompat.getColor(context, R.color.track_normal)
    private val alarmColor = ContextCompat.getColor(context, R.color.track_alarm)

    fun submit(videoRect: RectF, imageWidth: Int, imageHeight: Int, boxes: List<Box>) {
        this.videoRect.set(videoRect)
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.boxes = boxes
        invalidate()
    }

    fun clear() {
        if (boxes.isEmpty()) return
        boxes = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return
        if (videoRect.width() <= 1f || videoRect.height() <= 1f) return

        // 视频区域与分析图比例一致（都是同一个 TextureView），这里取小值保证不越界
        val scale = minOf(
            videoRect.width() / imageWidth,
            videoRect.height() / imageHeight
        )
        val offsetX = videoRect.left + (videoRect.width() - imageWidth * scale) / 2f
        val offsetY = videoRect.top + (videoRect.height() - imageHeight * scale) / 2f

        for (box in boxes) {
            val color = if (box.alarmed) alarmColor else normalColor
            val left = offsetX + box.rect.left * scale
            val top = offsetY + box.rect.top * scale
            val right = offsetX + box.rect.right * scale
            val bottom = offsetY + box.rect.bottom * scale
            if (right <= left || bottom <= top) continue

            // 主框
            framePaint.color = color
            framePaint.strokeWidth = (if (box.alarmed) 3.5f else 2.5f) * density
            canvas.drawRect(left, top, right, bottom, framePaint)

            // 四角加重，便于观察
            cornerPaint.color = color
            val corner = minOf(18f * density, (right - left) / 3f, (bottom - top) / 3f)
                .coerceAtLeast(4f * density)
            canvas.drawLine(left, top, left + corner, top, cornerPaint)
            canvas.drawLine(left, top, left, top + corner, cornerPaint)
            canvas.drawLine(right, top, right - corner, top, cornerPaint)
            canvas.drawLine(right, top, right, top + corner, cornerPaint)
            canvas.drawLine(left, bottom, left + corner, bottom, cornerPaint)
            canvas.drawLine(left, bottom, left, bottom - corner, cornerPaint)
            canvas.drawLine(right, bottom, right - corner, bottom, cornerPaint)
            canvas.drawLine(right, bottom, right, bottom - corner, cornerPaint)

            // 标签
            val label = buildLabel(box)
            labelPaint.color = color
            val textWidth = labelPaint.measureText(label)
            val labelHeight = labelPaint.textSize + 8f * density
            var labelTop = top - labelHeight - 2f * density
            if (labelTop < videoRect.top) {
                labelTop = top + 2f * density // 顶部空间不足时把标签画在框内
            }
            val labelRight = minOf(left + textWidth + 10f * density, width.toFloat())
            canvas.drawRect(left, labelTop, labelRight, labelTop + labelHeight, labelBackgroundPaint)
            canvas.drawText(
                label,
                left + 5f * density,
                labelTop + labelHeight - 5f * density,
                labelPaint
            )
        }
    }

    /**
     * 框上的标签，例如 `ALARM ID3 · ratio 2.13` / `【告警】ID3 · 面积比 2.13（未检出）`。
     *
     * 文案全部走字符串资源，所以会跟着界面语言切换；
     * 只有 `ID` 与数字本身与语言无关。
     */
    private fun buildLabel(box: Box): String {
        val ratioText = if (box.ratioValid) String.format(Locale.US, "%.2f", box.ratio) else "--"
        val prefix = if (box.alarmed) context.getString(R.string.overlay_label_alarm) else ""
        val missed = if (box.missed) context.getString(R.string.overlay_label_missed) else ""
        return context.getString(R.string.overlay_label, prefix, box.id, ratioText, missed)
    }
}

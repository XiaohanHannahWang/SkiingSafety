package com.hwcamtrack.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * 让子 View 始终以视频宽高比铺满自身的容器。
 *
 *  - aspectRatio <= 0（还不知道视频尺寸）时退化为普通 FrameLayout，直接铺满
 *  - 因为父布局用 layout_gravity="center" 摆放它，所以视频画面自动居中且不变形
 *  - 覆盖层（TrackingOverlayView）用它的 left/top/width/height 做坐标映射
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var aspectRatio: Float = 0f
        set(value) {
            val safe = if (value.isNaN() || value <= 0f) 0f else value
            if (field != safe) {
                field = safe
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        var targetWidth = parentWidth
        var targetHeight = parentHeight

        val ratio = aspectRatio
        if (ratio > 0f && parentWidth > 0 && parentHeight > 0) {
            val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
            if (parentRatio > ratio) {
                targetWidth = (parentHeight * ratio).toInt().coerceIn(1, parentWidth)
            } else {
                targetHeight = (parentWidth / ratio).toInt().coerceIn(1, parentHeight)
            }
        }

        val childWidthSpec = MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec)
        }
        // 用 resolveSize 兼容 wrap_content / UNSPECIFIED 的父布局（父布局给 EXACTLY 时结果不变）
        setMeasuredDimension(
            resolveSize(targetWidth, widthMeasureSpec),
            resolveSize(targetHeight, heightMeasureSpec)
        )
    }

    /**
     * 把子 View 居中摆放。
     *
     * 当父布局给的是 EXACTLY（本工程的画面区就是这样，它用 layout_weight 抢高度）时，
     * [resolveSize] 会把自己的尺寸撑到父布局大小，而子 View 仍然按视频比例算出来的
     * 小尺寸测量——如果照搬 FrameLayout 默认的左上角对齐，画面就会缩在左上角。
     * 这里统一居中，于是：
     *
     *  - 视频按真实宽高比居中显示，两边留黑边（不变形）
     *  - 跟踪框覆盖层与视频**完全重合**，坐标映射不需要再考虑黑边偏移
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = right - left
        val availableHeight = bottom - top
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val childLeft = (availableWidth - childWidth) / 2
            val childTop = (availableHeight - childHeight) / 2
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        }
    }
}

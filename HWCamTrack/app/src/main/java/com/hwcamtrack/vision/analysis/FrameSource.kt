package com.hwcamtrack.vision.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.TextureView

/**
 * 分析用的“视频帧来源”。
 *
 * 分析引擎只关心：当前帧有多大、能不能把它按比例画进我复用的那块 Bitmap。
 * 这样 ExoPlayer / MediaPlayer 路径（从 TextureView 抓帧）与内置 MJPEG 路径（直接给 Bitmap）
 * 可以共用同一套检测/跟踪流水线。
 */
interface FrameSource {

    /** 当前视频帧宽度（0 表示当前没有可用帧） */
    val sourceWidth: Int

    /** 当前视频帧高度（0 表示当前没有可用帧） */
    val sourceHeight: Int

    /** 把当前帧按比例画进 [dst]；返回 false 表示没有可用帧 */
    fun fillFrame(dst: Bitmap): Boolean
}

/** 从 TextureView 抓帧（ExoPlayer / 系统 MediaPlayer 路径） */
class TextureViewFrameSource(
    private val textureProvider: () -> TextureView?
) : FrameSource {

    private var buffer: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val sourceWidth: Int
        get() = textureProvider()?.let { if (it.isAvailable) it.width else 0 } ?: 0

    override val sourceHeight: Int
        get() = textureProvider()?.let { if (it.isAvailable) it.height else 0 } ?: 0

    override fun fillFrame(dst: Bitmap): Boolean {
        val view = textureProvider() ?: return false
        if (!view.isAvailable) return false
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false

        // TextureView.getBitmap(Bitmap) 要求传入的 Bitmap 与 View 尺寸一致，所以缓冲用 View 尺寸，
        // 再缩放到分析用的 dst（dst 由引擎复用，不产生额外分配）
        var local = buffer
        if (local == null || local.isRecycled || local.width != width || local.height != height) {
            local = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer = local
        }
        // TextureView.getBitmap(Bitmap) 声明为非空返回；View 不可用时内容不会更新，
        // 但上一帧的内容仍然可用（重复帧只会让面积比为 1，不会误告警）
        view.getBitmap(local)
        return try {
            Canvas(dst).drawBitmap(local, null, Rect(0, 0, dst.width, dst.height), paint)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 丢弃抓帧缓冲。这里**故意不 recycle**：后台分析线程可能正持有同一块 Bitmap，
     * 直接回收会导致崩溃，交给 GC 处理即可（切换播放引擎时才会调用）。
     */
    fun release() {
        buffer = null
    }
}

/** 直接使用某个 Bitmap（内置 MJPEG 播放器路径） */
class BitmapFrameSource(
    private val bitmapProvider: () -> Bitmap?
) : FrameSource {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val sourceWidth: Int get() = bitmapProvider()?.width ?: 0

    override val sourceHeight: Int get() = bitmapProvider()?.height ?: 0

    override fun fillFrame(dst: Bitmap): Boolean {
        val source = bitmapProvider() ?: return false
        if (source.isRecycled) return false
        return try {
            Canvas(dst).drawBitmap(source, null, Rect(0, 0, dst.width, dst.height), paint)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

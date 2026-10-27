package com.hwcamtrack.vision.analysis

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hwcamtrack.vision.detect.MlKitPosePersonDetector
import com.hwcamtrack.vision.detect.PersonDetector
import com.hwcamtrack.vision.detect.TflitePersonDetector
import com.hwcamtrack.settings.AnalysisSettings
import com.hwcamtrack.settings.DetectorKind
import com.hwcamtrack.settings.SettingsStore
import com.hwcamtrack.vision.track.IouTracker
import com.hwcamtrack.vision.track.TrackerOutput
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.min

/**
 * 分析流水线：定时从 TextureView 抓帧 -> 人体检测 -> IoU 跟踪 -> 计算面积比 -> 回调给 UI。
 *
 * 线程模型：
 *  - 抓帧（TextureView.getBitmap）在主线程完成，因为 SurfaceTexture 的读取必须与渲染同步
 *  - 检测与跟踪在单线程 Executor 中完成（模型推理是 CPU 重活，不能占用主线程）
 *  - 回调统一 post 到主线程
 *
 * 任何一帧同一时刻只处理一次（inFlight 标志），所以分析帧率上限受推理耗时限制，
 * 播放本身不受影响。
 */
class AnalysisEngine(
    private val appContext: Context,
    private val frameSourceProvider: () -> FrameSource?,
    private val settingsProvider: () -> AnalysisSettings,
    /** 返回一个时间戳（uptimeMillis）；在此之前不分析（例如正在拖动进度条） */
    private val suppressUntilProvider: () -> Long,
    private val listener: Listener
) {

    interface Listener {
        fun onAnalyzed(
            imageWidth: Int,
            imageHeight: Int,
            output: TrackerOutput,
            inferenceMs: Long,
            frameIndex: Long,
            measuredFps: Float
        )

        fun onDetectorChanged(displayName: String)

        fun onAnalysisError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "vr-analysis") }
    private val tracker = IouTracker()

    private var detector: PersonDetector? = null
    private var detectorKind: DetectorKind? = null
    private var reusableBitmap: Bitmap? = null
    private var lastErrorMessage: String? = null

    @Volatile
    private var running = false

    @Volatile
    private var inFlight = false

    @Volatile
    private var released = false

    /** TFLite 模型缺失/加载失败后不再重复尝试 */
    @Volatile
    private var tfliteUnavailable = false

    private var frameIndex = 0L
    private var lastFrameAtMs = 0L
    private var measuredFps = 0f

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val settings = settingsProvider()
            try {
                tick(settings)
            } catch (t: Throwable) {
                reportError(t.message ?: t.javaClass.simpleName)
            }
            val interval = 1000L / settings.analysisFps.coerceIn(SettingsStore.MIN_FPS, SettingsStore.MAX_FPS)
            if (running) handler.postDelayed(this, interval)
        }
    }

    fun start() {
        if (released || running) return
        running = true
        // 注意：这里不重置 inFlight。若上一次运行还有一帧正在推理，等它自己结束（process 的
        // 回调一定会把 inFlight 置回 false），否则主线程会在推理期间复用同一块 Bitmap。
        handler.post(loop)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(loop)
    }

    /** 播放位置跳变（拖动进度条 / 重新打开文件）后必须重置跟踪状态 */
    fun resetTracking() {
        tracker.reset()
    }

    fun release() {
        if (released) return
        released = true
        stop()
        val toClose = detector
        detector = null
        detectorKind = null
        reusableBitmap = null
        try {
            worker.execute { closeQuietly(toClose) }
        } catch (_: RejectedExecutionException) {
            closeQuietly(toClose)
        }
        worker.shutdown()
    }

    // ------------------------------------------------------------------ 内部实现

    private fun tick(settings: AnalysisSettings) {
        if (inFlight) return
        if (SystemClock.uptimeMillis() < suppressUntilProvider()) return

        val source = frameSourceProvider() ?: return
        val sourceWidth = source.sourceWidth
        val sourceHeight = source.sourceHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        val targetWidth = min(MAX_ANALYSIS_WIDTH, sourceWidth)
        val targetHeight = (targetWidth.toLong() * sourceHeight / sourceWidth).toInt().coerceAtLeast(1)
        val bitmap = obtainBuffer(targetWidth, targetHeight) ?: return
        if (!source.fillFrame(bitmap)) return

        inFlight = true
        try {
            worker.execute { process(bitmap, settings) }
        } catch (_: RejectedExecutionException) {
            inFlight = false
        }
    }

    /**
     * 取一块可复用的分析缓冲（尺寸由视频分辨率决定，最大 [MAX_ANALYSIS_WIDTH] 宽）。
     * 尺寸变化时分配新的一块，不回收旧的——避免后台线程正在使用时被回收导致崩溃，交给 GC 处理。
     */
    private fun obtainBuffer(width: Int, height: Int): Bitmap? {
        val cached = reusableBitmap
        if (cached != null && !cached.isRecycled && cached.width == width && cached.height == height) {
            return cached
        }
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusableBitmap = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun process(bitmap: Bitmap, settings: AnalysisSettings) {
        val startedAt = SystemClock.uptimeMillis()
        try {
            val personDetector = obtainDetector(settings.detector)
            val detections = personDetector?.detect(bitmap) ?: emptyList()
            val finishedAt = SystemClock.uptimeMillis()

            val output = tracker.update(
                detections = detections,
                nowMs = finishedAt,
                threshold = settings.thresholdRatio,
                symmetric = settings.symmetric,
                minArea = settings.minTrackAreaPx,
                holdMs = settings.alarmHoldMs,
                debounceMs = settings.alarmDebounceMs
            )

            val imageWidth = bitmap.width
            val imageHeight = bitmap.height
            val inferenceMs = finishedAt - startedAt

            handler.post {
                inFlight = false
                if (!running) return@post
                frameIndex++
                measuredFps = if (lastFrameAtMs <= 0L) {
                    0f
                } else {
                    val delta = finishedAt - lastFrameAtMs
                    if (delta <= 0L) measuredFps else 1000f / delta
                }
                lastFrameAtMs = finishedAt
                listener.onAnalyzed(imageWidth, imageHeight, output, inferenceMs, frameIndex, measuredFps)
            }
        } catch (t: Throwable) {
            handler.post {
                inFlight = false
                if (running) reportError(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun obtainDetector(kind: DetectorKind): PersonDetector? {
        val effectiveKind = if (kind == DetectorKind.TFLITE && tfliteUnavailable) DetectorKind.POSE else kind

        val current = detector
        if (current != null && detectorKind == effectiveKind) return current
        if (current != null) {
            closeQuietly(current)
            detector = null
            detectorKind = null
        }

        var created: PersonDetector? = null
        try {
            created = when (effectiveKind) {
                DetectorKind.TFLITE -> TflitePersonDetector(appContext)
                DetectorKind.POSE -> MlKitPosePersonDetector()
            }
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            if (effectiveKind == DetectorKind.TFLITE) {
                tfliteUnavailable = true
                reportError("TFLite detector init failed ($message); fell back to ML Kit Pose")
                created = try {
                    MlKitPosePersonDetector()
                } catch (_: Throwable) {
                    null
                }
            } else {
                reportError("Detector init failed: $message")
            }
        }

        val result = created ?: return null
        detector = result
        detectorKind = if (result is TflitePersonDetector) DetectorKind.TFLITE else DetectorKind.POSE
        handler.post { listener.onDetectorChanged(result.displayName) }
        return result
    }

    private fun reportError(message: String) {
        if (message == lastErrorMessage) return
        lastErrorMessage = message
        handler.post { listener.onAnalysisError(message) }
    }

    private fun closeQuietly(personDetector: PersonDetector?) {
        try {
            personDetector?.close()
        } catch (_: Throwable) {
            // 忽略
        }
    }

    companion object {
        /** 送进检测器的图像最大宽度（越大越准，越小越快） */
        const val MAX_ANALYSIS_WIDTH = 640
    }
}

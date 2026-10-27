package com.hwcamtrack.settings

import android.content.Context
import androidx.annotation.StringRes
import com.hwcamtrack.R

/**
 * 可选的人体检测器。
 *
 * 名字走字符串资源而不是硬编码，这样它会跟着界面语言切换。
 */
enum class DetectorKind(@StringRes val labelRes: Int) {
    /** TensorFlow Lite + EfficientDet-Lite0：COCO person 类，支持画面中多人 */
    TFLITE(R.string.detector_tflite),

    /** ML Kit Pose Detection：骨骼关键点，单帧只返回最显著的一人，无需额外模型文件 */
    POSE(R.string.detector_pose);

    companion object {
        fun fromName(value: String?): DetectorKind = entries.firstOrNull { it.name == value } ?: TFLITE
    }
}

/**
 * 检测 / 跟踪 / 告警参数快照。
 *
 * 后台分析线程每一帧都读一次，所以这里必须是**不可变值对象**——
 * 用户在界面上拖动门限滑杆时，正在推理的那一帧仍然按旧参数算完，不会读到写了一半的状态。
 */
data class AnalysisSettings(
    /** 面积比门限：同一目标本帧框面积 / 上一帧框面积 达到该值即告警 */
    val thresholdRatio: Float,
    /** 对称检测：开启后面积骤缩同样告警 */
    val symmetric: Boolean,
    /** 每秒分析帧数上限（真实帧率还受推理耗时限制） */
    val analysisFps: Int,
    val detector: DetectorKind,
    /** 小于该面积的框不参与告警判定（抑制远处小目标的噪声） */
    val minTrackAreaPx: Float,
    /** 告警状态（红框 / 横幅）保持时长 */
    val alarmHoldMs: Long,
    /** 同一目标两次告警之间的最小间隔 */
    val alarmDebounceMs: Long
)

/**
 * 本 App 全部可调参数的持久化（SharedPreferences）。
 *
 * 分成三组：
 *  - **跟踪告警**：门限、对称检测、最小面积、分析帧率、检测器
 *  - **取流**：RTP 传输方式、解码方式
 *  - **界面**：是否绘制跟踪框
 *
 * 集中在一个类里，是为了让"设置对话框"和"控制条"读写同一份状态，
 * 不会出现两处开关互相打架的情况。
 */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 跟踪告警

    /** 面积比门限，超出即告警（红色框 + 界面告警信息）。 */
    var thresholdRatio: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        set(value) = prefs.edit()
            .putFloat(KEY_THRESHOLD, value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD))
            .apply()

    /**
     * 对称检测。
     *
     * 默认关闭：题目要求的是"面积比超过门限"，即人靠近摄像头、框变大。
     * 打开后面积骤缩（人走远 / 检测框突然塌掉）也会告警，便于发现异常。
     */
    var symmetric: Boolean
        get() = prefs.getBoolean(KEY_SYMMETRIC, false)
        set(value) = prefs.edit().putBoolean(KEY_SYMMETRIC, value).apply()

    var analysisFps: Int
        get() = prefs.getInt(KEY_FPS, DEFAULT_FPS).coerceIn(MIN_FPS, MAX_FPS)
        set(value) = prefs.edit().putInt(KEY_FPS, value.coerceIn(MIN_FPS, MAX_FPS)).apply()

    var minTrackAreaPx: Float
        get() = prefs.getFloat(KEY_MIN_AREA, DEFAULT_MIN_AREA).coerceIn(MIN_MIN_AREA, MAX_MIN_AREA)
        set(value) = prefs.edit()
            .putFloat(KEY_MIN_AREA, value.coerceIn(MIN_MIN_AREA, MAX_MIN_AREA))
            .apply()

    var detector: DetectorKind
        get() = DetectorKind.fromName(prefs.getString(KEY_DETECTOR, null))
        set(value) = prefs.edit().putString(KEY_DETECTOR, value.name).apply()

    // ------------------------------------------------------------------ 报警声

    /** 出现红色告警时是否发出"滴滴……"报警声。 */
    var alarmSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALARM_SOUND, DEFAULT_ALARM_SOUND_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_ALARM_SOUND, value).apply()

    /**
     * 报警声持续时长（毫秒）。
     *
     * 1~10 秒可调：太短容易错过，太长会在连续告警时一直响。
     * 连续来告警时是**重新计时**而不是叠加，所以这里就是单次告警的最长响铃时间。
     */
    var alarmSoundMillis: Long
        get() = prefs.getLong(KEY_ALARM_SOUND_MS, DEFAULT_ALARM_SOUND_MS)
            .coerceIn(MIN_ALARM_SOUND_MS, MAX_ALARM_SOUND_MS)
        set(value) = prefs.edit()
            .putLong(KEY_ALARM_SOUND_MS, value.coerceIn(MIN_ALARM_SOUND_MS, MAX_ALARM_SOUND_MS))
            .apply()

    // ------------------------------------------------------------------ 取流

    /**
     * RTSP 是否强制 RTP over TCP。
     *
     * 默认 true：摄像头的 AP 往往有客户端隔离或者极小的 UDP 缓冲，
     * 走 UDP 经常花屏/丢包，走 TCP 稳定得多（代价是延迟略高）。
     */
    var forceRtpTcp: Boolean
        get() = prefs.getBoolean(KEY_RTP_TCP, true)
        set(value) = prefs.edit().putBoolean(KEY_RTP_TCP, value).apply()

    /**
     * 强制软解。给"硬解有 bug 导致花屏/卡顿"的机型准备的逃生通道，
     * 只在自研 RTSP / 裸 H.264 通道上生效（ExoPlayer 自己管理解码器）。
     */
    var softwareDecode: Boolean
        get() = prefs.getBoolean(KEY_SOFTWARE_DECODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SOFTWARE_DECODE, value).apply()

    // ------------------------------------------------------------------ 界面

    /** 是否在画面上绘制跟踪框（关掉只看画面，省一点 GPU）。 */
    var showTrackingOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_OVERLAY, value).apply()

    /** 供后台分析线程使用的不可变快照。 */
    fun snapshot(): AnalysisSettings = AnalysisSettings(
        thresholdRatio = thresholdRatio,
        symmetric = symmetric,
        analysisFps = analysisFps,
        detector = detector,
        minTrackAreaPx = minTrackAreaPx,
        alarmHoldMs = ALARM_HOLD_MS,
        alarmDebounceMs = ALARM_DEBOUNCE_MS
    )

    companion object {
        // 面积比门限：1.00 表示"完全不变"，5.00 表示面积翻两番半
        const val MIN_THRESHOLD = 1.0f
        const val MAX_THRESHOLD = 5.0f
        const val DEFAULT_THRESHOLD = 1.5f

        const val MIN_FPS = 1
        const val MAX_FPS = 15
        const val DEFAULT_FPS = 5

        // 最小告警框面积（分析图像素²，分析图最宽 640px）
        const val MIN_MIN_AREA = 100f
        const val MAX_MIN_AREA = 5000f
        const val DEFAULT_MIN_AREA = 400f

        /** 告警状态（红框 / 横幅）保持时长 */
        const val ALARM_HOLD_MS = 1200L

        /** 同一目标两次告警之间的最小间隔，避免日志刷屏 */
        const val ALARM_DEBOUNCE_MS = 1500L

        // 报警声时长：1~10 秒，滑杆按 1 秒一档
        const val MIN_ALARM_SOUND_MS = 1000L
        const val MAX_ALARM_SOUND_MS = 10000L
        const val DEFAULT_ALARM_SOUND_MS = 3000L
        const val ALARM_SOUND_STEP_MS = 1000L
        const val DEFAULT_ALARM_SOUND_ENABLED = true

        private const val PREFS_NAME = "hwcamtrack_settings"
        private const val KEY_THRESHOLD = "threshold_ratio"
        private const val KEY_SYMMETRIC = "symmetric"
        private const val KEY_FPS = "analysis_fps"
        private const val KEY_MIN_AREA = "min_track_area"
        private const val KEY_DETECTOR = "detector_kind"
        private const val KEY_ALARM_SOUND = "alarm_sound_enabled"
        private const val KEY_ALARM_SOUND_MS = "alarm_sound_ms"
        private const val KEY_RTP_TCP = "force_rtp_tcp"
        private const val KEY_SOFTWARE_DECODE = "software_decode"
        private const val KEY_SHOW_OVERLAY = "show_tracking_overlay"
    }
}

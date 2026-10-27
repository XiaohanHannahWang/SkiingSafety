package com.hwcamtrack.vision.track

import android.graphics.RectF

/**
 * 某个跟踪目标在当前分析帧上的状态。
 *
 * @param id          跟踪 ID（同一目标在连续帧中保持不变）
 * @param rect        当前框（分析图像素坐标）
 * @param area        框面积 = 框宽 × 框高
 * @param ratio       与上一分析帧的面积比（ratioValid=false 时无意义，表示该目标是新出现的）
 * @param alarmed     是否处于告警状态（界面应画红色框）
 * @param missedFrames 连续未匹配到的帧数（>0 表示该帧没有检测结果，框为上一次的位置）
 */
data class TrackResult(
    val id: Int,
    val rect: RectF,
    val area: Float,
    val ratio: Float,
    val ratioValid: Boolean,
    val alarmed: Boolean,
    val missedFrames: Int,
    val hits: Int
)

/** 新产生的告警事件（用于界面横幅与日志） */
data class AlarmEvent(
    val trackId: Int,
    val ratio: Float,
    val area: Float,
    val prevArea: Float,
    val threshold: Float,
    /** true=面积增大触发，false=面积骤缩触发（对称检测模式） */
    val grow: Boolean
)

/** 一帧跟踪结果 */
data class TrackerOutput(
    val results: List<TrackResult>,
    val newAlarms: List<AlarmEvent>
)

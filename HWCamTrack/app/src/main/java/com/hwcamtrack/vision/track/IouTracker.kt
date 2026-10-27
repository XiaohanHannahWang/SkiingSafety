package com.hwcamtrack.vision.track

import android.graphics.RectF
import com.hwcamtrack.vision.detect.Detection

/**
 * 简易 IoU 多目标跟踪器（SORT 的贪心简化版）。
 *
 * 流程：
 *  1. 计算“已有目标上一帧框”与“本帧检测框”的 IoU，从大到小贪心匹配（同一检测/目标只匹配一次）
 *  2. 匹配成功 -> 复用原 ID，更新框；记录上一帧面积用于算面积比
 *  3. 未匹配的检测 -> 新建目标（分配新 ID）
 *  4. 未匹配的目标 -> 丢失计数 +1，连续丢失超过 maxMissedFrames 帧后删除
 *  5. 面积比 = 本帧框面积 / 上一帧同一目标框面积；超过门限 -> 标记告警
 *
 * 线程安全：update/reset 用 @Synchronized 保护（UI 线程可能随时 reset）。
 */
class IouTracker(
    private val iouThreshold: Float = 0.25f,
    private val maxMissedFrames: Int = 8,
    private val duplicateIou: Float = 0.6f
) {

    private class Track(
        val id: Int,
        var rect: RectF,
        var area: Float,
        var prevArea: Float,
        var hasPrevArea: Boolean,
        var hits: Int,
        var missed: Int,
        var lastRatio: Float,
        var ratioValid: Boolean,
        var alarmedUntil: Long,
        var lastAlarmAt: Long
    )

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    @Synchronized
    fun reset() {
        tracks.clear()
        nextId = 1
    }

    @Synchronized
    fun update(
        detections: List<Detection>,
        nowMs: Long,
        threshold: Float,
        symmetric: Boolean,
        minArea: Float,
        holdMs: Long,
        debounceMs: Long
    ): TrackerOutput {
        // 注意：第 2 步会往 tracks 里追加新目标，所以这里必须先记下“已有目标数量”，
        // 第 3 步只能在这个范围内遍历（新目标自带 missed=0，不需要再加）。
        val existingTrackCount = tracks.size
        val trackAssigned = BooleanArray(existingTrackCount)
        val detectionAssigned = BooleanArray(detections.size)

        // ---------- 1) 贪心匹配 ----------
        // 元素为 [trackIndex, detectionIndex, iou]
        val pairs = ArrayList<FloatArray>()
        for (ti in tracks.indices) {
            for (di in detections.indices) {
                val value = iou(tracks[ti].rect, detections[di].rect)
                if (value >= iouThreshold) pairs.add(floatArrayOf(ti.toFloat(), di.toFloat(), value))
            }
        }
        pairs.sortByDescending { it[2] }
        for (pair in pairs) {
            val ti = pair[0].toInt()
            val di = pair[1].toInt()
            if (trackAssigned[ti] || detectionAssigned[di]) continue
            trackAssigned[ti] = true
            detectionAssigned[di] = true
            applyMatch(tracks[ti], detections[di])
        }

        // ---------- 2) 未匹配检测 -> 新目标 ----------
        for (di in detections.indices) {
            if (detectionAssigned[di]) continue
            val rect = RectF(detections[di].rect)
            tracks.add(
                Track(
                    id = nextId++,
                    rect = rect,
                    area = areaOf(rect),
                    prevArea = 0f,
                    hasPrevArea = false,
                    hits = 1,
                    missed = 0,
                    lastRatio = 1f,
                    ratioValid = false,
                    alarmedUntil = 0L,
                    lastAlarmAt = 0L
                )
            )
        }

        // ---------- 3) 未匹配目标 -> 丢失计数 ----------
        for (ti in 0 until existingTrackCount) {
            if (!trackAssigned[ti]) tracks[ti].missed++
        }

        // ---------- 4) 清理与去重 ----------
        tracks.removeAll { it.missed > maxMissedFrames }
        suppressDuplicates()

        // ---------- 5) 输出 + 面积比告警判定 ----------
        val results = ArrayList<TrackResult>(tracks.size)
        val alarms = ArrayList<AlarmEvent>()

        for (track in tracks) {
            if (track.ratioValid && track.prevArea > 0f) {
                val ratio = if (symmetric) {
                    maxOf(track.area, track.prevArea) / minOf(track.area, track.prevArea)
                } else {
                    track.area / track.prevArea
                }
                track.lastRatio = ratio

                val bigEnough = minOf(track.area, track.prevArea) >= minArea
                if (bigEnough && ratio >= threshold) {
                    if (nowMs - track.lastAlarmAt >= debounceMs) {
                        track.lastAlarmAt = nowMs
                        alarms.add(
                            AlarmEvent(
                                trackId = track.id,
                                ratio = ratio,
                                area = track.area,
                                prevArea = track.prevArea,
                                threshold = threshold,
                                grow = track.area >= track.prevArea
                            )
                        )
                    }
                    track.alarmedUntil = nowMs + holdMs
                }
            }

            results.add(
                TrackResult(
                    id = track.id,
                    rect = RectF(track.rect),
                    area = track.area,
                    ratio = track.lastRatio,
                    ratioValid = track.ratioValid,
                    alarmed = nowMs < track.alarmedUntil,
                    missedFrames = track.missed,
                    hits = track.hits
                )
            )
        }
        results.sortBy { it.id }
        return TrackerOutput(results, alarms)
    }

    private fun applyMatch(track: Track, detection: Detection) {
        track.prevArea = track.area
        track.hasPrevArea = track.prevArea > 0f
        track.rect = RectF(detection.rect)
        track.area = areaOf(track.rect)
        track.ratioValid = track.hasPrevArea && track.area > 0f
        track.hits++
        track.missed = 0
    }

    /** 同一个人被检测器输出多个高度重叠的框时，只保留 ID 较小的那个 */
    private fun suppressDuplicates() {
        if (tracks.size < 2) return
        val removed = HashSet<Int>()
        for (i in tracks.indices) {
            if (removed.contains(i)) continue
            for (j in i + 1 until tracks.size) {
                if (removed.contains(j)) continue
                if (iou(tracks[i].rect, tracks[j].rect) >= duplicateIou) removed.add(j)
            }
        }
        if (removed.isEmpty()) return
        val kept = ArrayList<Track>(tracks.size - removed.size)
        for (i in tracks.indices) {
            if (!removed.contains(i)) kept.add(tracks[i])
        }
        tracks.clear()
        tracks.addAll(kept)
    }

    private fun areaOf(rect: RectF): Float =
        maxOf(0f, rect.width()) * maxOf(0f, rect.height())

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return 0f
        val intersection = w * h
        val union = areaOf(a) + areaOf(b) - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

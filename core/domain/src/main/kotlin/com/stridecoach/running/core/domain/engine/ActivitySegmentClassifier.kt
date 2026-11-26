package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.DetectedSegment
import com.stridecoach.running.core.domain.model.TrackPoint
import kotlin.math.roundToInt

object ActivitySegmentClassifier {
    private const val RUN_MPS = 2.08f
    private const val WALK_MPS = 0.83f
    private const val REST_MPS = 0.56f
    private const val RUN_SPM = 145
    private const val WALK_SPM = 65

    private data class RawSpan(
        val type: ActivitySegmentType,
        val startIdx: Int,
        val endIdx: Int
    )

    fun classifyInstant(speedMps: Float?, cadenceSpm: Int, paused: Boolean): ActivitySegmentType {
        if (paused) return ActivitySegmentType.STRETCH_REST
        val v = speedMps ?: 0f
        if (v >= RUN_MPS || cadenceSpm >= RUN_SPM) return ActivitySegmentType.RUNNING
        if (v < REST_MPS && cadenceSpm < WALK_SPM) return ActivitySegmentType.STRETCH_REST
        if (v < RUN_MPS && (v >= WALK_MPS || cadenceSpm >= WALK_SPM)) return ActivitySegmentType.WALKING
        return ActivitySegmentType.STRETCH_REST
    }

    fun computeElevationGain(pts: List<TrackPoint>): Int {
        var gain = 0.0
        var prevAlt: Double? = null
        for (pt in pts) {
            val alt = pt.altitudeMeters ?: continue
            val base = prevAlt
            if (base != null && alt - base > 1.5) gain += (alt - base)
            prevAlt = alt
        }
        return gain.roundToInt()
    }

    fun classify(pts: List<TrackPoint>): List<DetectedSegment> {
        if (pts.size < 2) return emptyList()
        val tags = pts.map { p -> classifyInstant(p.speedMps, p.cadenceSpm ?: 0, p.isPaused) }
        val spans = absorbGlitches(groupSpans(tags), pts)
        return spans.map { s -> toSegment(pts, s) }
    }

    private fun groupSpans(tags: List<ActivitySegmentType>): MutableList<RawSpan> {
        val out = mutableListOf<RawSpan>()
        var s = 0
        var cur = tags.first()
        for (i in 1 until tags.size) {
            if (tags[i] != cur) {
                out.add(RawSpan(cur, s, i - 1))
                s = i
                cur = tags[i]
            }
        }
        out.add(RawSpan(cur, s, tags.lastIndex))
        return out
    }

    private fun absorbGlitches(raw: MutableList<RawSpan>, pts: List<TrackPoint>): List<RawSpan> {
        if (raw.size <= 1) return raw
        val kept = raw.filterIndexed { i, sp -> !isNoise(sp, pts) || raw.size == 1 || (i == 0 && raw.size == 2 && isNoise(raw[1], pts)) }
        val base = if (kept.isEmpty()) raw else kept
        val merged = mutableListOf<RawSpan>()
        for (sp in base) {
            val last = merged.lastOrNull()
            if (last == null) {
                merged.add(sp)
            } else if (last.type == sp.type) {
                merged[merged.lastIndex] = last.copy(endIdx = sp.endIdx)
            } else {
                merged.add(RawSpan(sp.type, last.endIdx, sp.endIdx))
            }
        }
        return merged
    }

    private fun isNoise(sp: RawSpan, pts: List<TrackPoint>): Boolean {
        val dur = pts[sp.endIdx].timestampMillis - pts[sp.startIdx].timestampMillis
        val minMs = if (sp.type == ActivitySegmentType.STRETCH_REST) 20_000L else 12_000L
        return dur in 0 until minMs
    }

    private fun toSegment(pts: List<TrackPoint>, sp: RawSpan): DetectedSegment {
        val p0 = pts[sp.startIdx]
        val p1 = pts[sp.endIdx]
        val el0 = p0.elapsedMillis
        val el1 = p1.elapsedMillis.coerceAtLeast(el0)
        val wallMs = (p1.timestampMillis - p0.timestampMillis).coerceAtLeast(1_000L)
        val durMs = (el1 - el0).takeIf { it > 0L } ?: wallMs
        val dist = (p1.cumulativeDistanceMeters - p0.cumulativeDistanceMeters).roundToInt().coerceAtLeast(0)
        val steps = estimateSteps(pts, sp.startIdx, sp.endIdx, wallMs)
        val pace = if (dist >= 15) ((durMs / 1000.0) / (dist / 1000.0)).roundToInt() else 0
        return DetectedSegment(sp.type, el0, el1, dist, steps, pace)
    }

    private fun estimateSteps(pts: List<TrackPoint>, s: Int, e: Int, wallMs: Long): Int {
        var sum = 0
        var cnt = 0
        for (i in s..e) {
            val cad = pts[i].cadenceSpm ?: continue
            if (cad > 0) {
                sum += cad
                cnt++
            }
        }
        if (cnt == 0) return 0
        val avgSpm = sum.toDouble() / cnt
        return ((avgSpm * wallMs) / 60_000.0).roundToInt()
    }
}

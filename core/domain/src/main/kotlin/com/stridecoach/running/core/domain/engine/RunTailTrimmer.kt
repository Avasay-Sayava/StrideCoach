package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.TrackPoint
import kotlin.math.roundToInt

data class AutoTrimSuggestion(
    val startMs: Long,
    val endMs: Long,
    val trimmedStartMs: Long,
    val trimmedEndMs: Long,
    val reason: String
)

class RunTailTrimmer {
    fun trimToElapsedMillis(run: RunRecord, keepMs: Long): RunRecord =
        trimRange(run, 0L, keepMs)

    fun trimRange(run: RunRecord, startMs: Long, endMs: Long): RunRecord {
        val lo = startMs.coerceIn(0L, run.durationMillis)
        val hi = endMs.coerceIn(lo + 1_000L, run.durationMillis.coerceAtLeast(lo + 1_000L))
        val pts = run.trackPoints.ifEmpty {
            EncodedPolylineCodec.synthesizeReplayTrack(
                run.encodedPolyline,
                run.startTimestampMillis,
                run.durationMillis,
                run.splits
            )
        }
        val slice = pts.filter { it.elapsedMillis in lo..hi }
        if (slice.size < 2) return scaleOnly(run, lo, hi)

        val baseTime = slice.first().elapsedMillis
        val rebased = rebuildRebasedPoints(slice, baseTime)
        val dist = rebased.last().cumulativeDistanceMeters.roundToInt().coerceAtLeast(1)
        val dur = rebased.last().elapsedMillis.coerceAtLeast(1_000L)
        val pace = ((dur / 1000.0) / (dist / 1000.0)).roundToInt()
        val ratio = (dist.toDouble() / run.distanceMeters.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val cals = (run.caloriesBurned * ratio).roundToInt()
        val poly = EncodedPolylineCodec.encode(rebased.map { LatLon(it.latitude, it.longitude) })

        return run.copy(
            startTimestampMillis = run.startTimestampMillis + lo,
            distanceMeters = dist,
            durationMillis = dur,
            paceSecondsPerKm = pace,
            caloriesBurned = cals,
            encodedPolyline = poly,
            rawEncodedPolyline = poly,
            snappedEncodedPolyline = "",
            isSnapped = false,
            snapStreetPct = 0,
            snapGreeneryPct = 0,
            snapLoopsKept = 0,
            splits = rebuildSplits(rebased),
            trackPoints = rebased,
            segs = ActivitySegmentClassifier.classify(rebased),
            elevGainM = ActivitySegmentClassifier.computeElevationGain(rebased)
        )
    }

    fun suggestAutoTrim(
        pts: List<TrackPoint>,
        kind: ActivityKind = ActivityKind.RUNNING
    ): AutoTrimSuggestion? {
        if (pts.size < 8) return null
        val totalMs = pts.last().elapsedMillis
        if (totalMs < 45_000L) return null

        val minMoveMps = if (kind == ActivityKind.WALKING) 0.55f else 1.15f
        val startIdx = findActiveStartIdx(pts, minMoveMps)
        val endIdx = findActiveEndIdx(pts, kind, minMoveMps)

        if (endIdx <= startIdx + 2) return null
        val trimStart = pts[startIdx].elapsedMillis
        val trimEnd = pts[endIdx].elapsedMillis
        val cutStart = trimStart
        val cutEnd = (totalMs - trimEnd).coerceAtLeast(0L)
        val keptDur = trimEnd - trimStart
        if (cutStart + cutEnd < 15_000L) return null
        if (keptDur < 30_000L || keptDur < (totalMs * 0.30).toLong()) return null

        val reasons = buildList {
            if (cutStart >= 12_000L) add("${cutStart / 1000}s setup delay")
            if (cutEnd >= 12_000L) add("${cutEnd / 1000}s idle/post-run tail")
        }
        if (reasons.isEmpty()) return null
        return AutoTrimSuggestion(
            startMs = trimStart,
            endMs = trimEnd,
            trimmedStartMs = cutStart,
            trimmedEndMs = cutEnd,
            reason = "Auto-trim found ${reasons.joinToString(" & ")}"
        )
    }

    private fun findActiveStartIdx(pts: List<TrackPoint>, minMps: Float): Int {
        for (i in 0 until pts.lastIndex - 2) {
            val moving = (i..(i + 2).coerceAtMost(pts.lastIndex)).all { k ->
                val v = pts[k].speedMps ?: 0f
                v >= minMps
            }
            if (moving) return if (pts[i].elapsedMillis >= 15_000L) i else 0
        }
        return 0
    }

    private fun findActiveEndIdx(pts: List<TrackPoint>, kind: ActivityKind, minMps: Float): Int {
        val maxRunMps = if (kind == ActivityKind.RUNNING) 8.5f else 22.0f
        for (i in pts.lastIndex downTo 2) {
            val valid = ((i - 2).coerceAtLeast(0)..i).all { k ->
                val v = pts[k].speedMps ?: 0f
                v >= minMps && v <= maxRunMps
            }
            if (valid) {
                val tailCut = pts.last().elapsedMillis - pts[i].elapsedMillis
                return if (tailCut >= 15_000L) i else pts.lastIndex
            }
        }
        return pts.lastIndex
    }

    private fun rebuildRebasedPoints(slice: List<TrackPoint>, baseMs: Long): List<TrackPoint> {
        var accDist = 0.0
        return slice.mapIndexed { idx, pt ->
            if (idx > 0) {
                val prev = slice[idx - 1]
                accDist += GeoMath.distanceMeters(prev.latitude, prev.longitude, pt.latitude, pt.longitude)
            }
            val brg = if (idx > 0) {
                GeoMath.bearingDegrees(slice[idx - 1].latitude, slice[idx - 1].longitude, pt.latitude, pt.longitude)
            } else {
                0f
            }
            pt.copy(
                seqIndex = idx,
                elapsedMillis = (pt.elapsedMillis - baseMs).coerceAtLeast(0L),
                cumulativeDistanceMeters = accDist,
                bearingDegrees = brg
            )
        }
    }

    private fun scaleOnly(run: RunRecord, lo: Long, hi: Long): RunRecord {
        val keptMs = (hi - lo).coerceAtLeast(1_000L)
        val ratio = (keptMs.toDouble() / run.durationMillis.coerceAtLeast(1L)).coerceIn(0.05, 1.0)
        val dist = (run.distanceMeters * ratio).roundToInt().coerceAtLeast(1)
        val pace = ((keptMs / 1000.0) / (dist / 1000.0)).roundToInt()
        return run.copy(
            startTimestampMillis = run.startTimestampMillis + lo,
            distanceMeters = dist,
            durationMillis = keptMs,
            paceSecondsPerKm = pace,
            caloriesBurned = (run.caloriesBurned * ratio).roundToInt()
        )
    }

    private fun rebuildSplits(pts: List<TrackPoint>): List<RunSplit> = buildKmSplits(pts)

    companion object {
        fun buildKmSplits(pts: List<TrackPoint>): List<RunSplit> {
            if (pts.size < 2) return emptyList()
            val out = mutableListOf<RunSplit>()
            var num = 1
            var sDist = 0.0
            var sTime = pts.first().elapsedMillis

            for (pt in pts) {
                val d = pt.cumulativeDistanceMeters - sDist
                if (d >= 1_000.0) {
                    val dur = (pt.elapsedMillis - sTime).coerceAtLeast(1L)
                    out.add(RunSplit(num++, d.roundToInt(), dur))
                    sDist = pt.cumulativeDistanceMeters
                    sTime = pt.elapsedMillis
                }
            }
            val rem = (pts.last().cumulativeDistanceMeters - sDist).roundToInt()
            if (rem >= 50) {
                val dur = (pts.last().elapsedMillis - sTime).coerceAtLeast(1L)
                out.add(RunSplit(num, rem, dur))
            }
            return out
        }
    }
}

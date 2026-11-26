package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.TrackPoint
import kotlin.math.roundToInt

class RunTailTrimmer {
    fun trimToElapsedMillis(run: RunRecord, keepMs: Long): RunRecord {
        require(keepMs > 0L) { "keepMs must be positive" }
        val limit = keepMs.coerceAtMost(run.durationMillis)
        val pts = run.trackPoints.ifEmpty {
            EncodedPolylineCodec.synthesizeReplayTrack(
                run.encodedPolyline,
                run.startTimestampMillis,
                run.durationMillis,
                run.splits
            )
        }
        val kept = pts.filter { it.elapsedMillis <= limit }
        if (kept.size < 2) return scaleOnly(run, limit)

        val dist = kept.last().cumulativeDistanceMeters.roundToInt().coerceAtLeast(1)
        val dur = kept.last().elapsedMillis.coerceAtLeast(1_000L)
        val pace = ((dur / 1000.0) / (dist / 1000.0)).roundToInt()
        val ratio = (dist.toDouble() / run.distanceMeters.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val cals = (run.caloriesBurned * ratio).roundToInt()
        val poly = EncodedPolylineCodec.encode(kept.map { LatLon(it.latitude, it.longitude) })
        val splits = rebuildSplits(kept)

        return run.copy(
            distanceMeters = dist,
            durationMillis = dur,
            paceSecondsPerKm = pace,
            caloriesBurned = cals,
            encodedPolyline = poly,
            splits = splits,
            trackPoints = kept
        )
    }

    private fun scaleOnly(run: RunRecord, limit: Long): RunRecord {
        val ratio = (limit.toDouble() / run.durationMillis.coerceAtLeast(1L)).coerceIn(0.05, 1.0)
        val dist = (run.distanceMeters * ratio).roundToInt().coerceAtLeast(1)
        val pace = ((limit / 1000.0) / (dist / 1000.0)).roundToInt()
        return run.copy(
            distanceMeters = dist,
            durationMillis = limit,
            paceSecondsPerKm = pace,
            caloriesBurned = (run.caloriesBurned * ratio).roundToInt()
        )
    }

    private fun rebuildSplits(pts: List<TrackPoint>): List<RunSplit> {
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

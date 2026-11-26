package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.TrackPoint
import kotlin.math.roundToInt

data class MatchedPointSample(
    val matched: Boolean,
    val lat: Double,
    val lon: Double,
    val distM: Double
)

data class SnapMergeResult(
    val points: List<TrackPoint>,
    val snappedPct: Int,
    val greeneryPct: Int,
    val circlesKept: Int
)

object ParkSafeSnapMerger {
    fun detectHoldingLoops(raw: List<TrackPoint>): Pair<BooleanArray, Int> {
        val n = raw.size
        val marks = BooleanArray(n)
        if (n < 5) return marks to 0
        for (i in 0 until n - 4) markLoopWindow(raw, i, marks)
        return marks to countClusters(marks)
    }

    private fun markLoopWindow(raw: List<TrackPoint>, s: Int, marks: BooleanArray) {
        var pathM = 0.0
        var minLat = raw[s].latitude
        var maxLat = minLat
        var minLon = raw[s].longitude
        var maxLon = minLon
        for (j in s + 1 until raw.size) {
            if (raw[j].elapsedMillis - raw[s].elapsedMillis > 90_000L) break
            pathM += GeoMath.distanceMeters(raw[j - 1].latitude, raw[j - 1].longitude, raw[j].latitude, raw[j].longitude)
            minLat = minOf(minLat, raw[j].latitude)
            maxLat = maxOf(maxLat, raw[j].latitude)
            minLon = minOf(minLon, raw[j].longitude)
            maxLon = maxOf(maxLon, raw[j].longitude)
            if (j - s < 4 || pathM < 38.0) continue
            val diam = GeoMath.distanceMeters(minLat, minLon, maxLat, maxLon)
            val net = GeoMath.distanceMeters(raw[s].latitude, raw[s].longitude, raw[j].latitude, raw[j].longitude).coerceAtLeast(1.0)
            if (diam <= 34.0 && pathM / net >= 2.5) {
                for (k in s..j) marks[k] = true
            }
        }
    }

    fun mergePointMatches(
        raw: List<TrackPoint>,
        samples: List<MatchedPointSample>,
        maxSnapM: Double = 18.0
    ): SnapMergeResult {
        if (raw.size < 2 || samples.size != raw.size) return SnapMergeResult(raw, 0, 100, 0)
        val (loops, loopCount) = detectHoldingLoops(raw)
        var snapCount = 0

        val out = raw.mapIndexed { i, p ->
            val sp = samples[i]
            val useSnap = !loops[i] && sp.matched && sp.distM <= maxSnapM
            if (useSnap) {
                snapCount++
                p.copy(latitude = sp.lat, longitude = sp.lon)
            } else {
                smoothSinglePoint(raw, i, p)
            }
        }
        val rebuilt = rebuildDistAndBearing(out)
        val pct = ((snapCount * 100.0) / raw.size).roundToInt().coerceIn(0, 100)
        return SnapMergeResult(rebuilt, pct, (100 - pct).coerceIn(0, 100), loopCount)
    }

    private fun countClusters(marks: BooleanArray): Int {
        var cnt = 0
        var open = false
        for (m in marks) {
            if (m && !open) {
                cnt++
                open = true
            } else if (!m) {
                open = false
            }
        }
        return cnt
    }

    private fun smoothSinglePoint(raw: List<TrackPoint>, i: Int, p: TrackPoint): TrackPoint {
        if (i == 0 || i == raw.lastIndex) return p
        val a = raw[i - 1]
        val b = raw[i + 1]
        val lat = (a.latitude + p.latitude * 2.0 + b.latitude) / 4.0
        val lon = (a.longitude + p.longitude * 2.0 + b.longitude) / 4.0
        return p.copy(latitude = lat, longitude = lon)
    }

    private fun rebuildDistAndBearing(pts: List<TrackPoint>): List<TrackPoint> {
        if (pts.isEmpty()) return pts
        var acc = 0.0
        return pts.mapIndexed { i, p ->
            if (i == 0) {
                p.copy(seqIndex = 0, cumulativeDistanceMeters = 0.0)
            } else {
                val prev = pts[i - 1]
                acc += GeoMath.distanceMeters(prev.latitude, prev.longitude, p.latitude, p.longitude)
                val brg = GeoMath.bearingDegrees(prev.latitude, prev.longitude, p.latitude, p.longitude)
                p.copy(seqIndex = i, cumulativeDistanceMeters = acc, bearingDegrees = brg)
            }
        }
    }
}

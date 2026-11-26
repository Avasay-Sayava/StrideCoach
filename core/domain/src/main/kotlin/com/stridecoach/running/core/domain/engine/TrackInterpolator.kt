package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.TrackPoint

data class ReplayHead(
    val baseIdx: Int,
    val lat: Double,
    val lon: Double,
    val elapsedMs: Long,
    val distM: Double,
    val paceSec: Int
)

object TrackInterpolator {
    fun sampleAt(pts: List<TrackPoint>, targetMs: Long): ReplayHead? {
        if (pts.isEmpty()) return null
        if (pts.size == 1 || targetMs <= pts.first().elapsedMillis) {
            val p = pts.first()
            return ReplayHead(0, p.latitude, p.longitude, p.elapsedMillis, p.cumulativeDistanceMeters, toPace(p.speedMps))
        }
        if (targetMs >= pts.last().elapsedMillis) {
            val p = pts.last()
            return ReplayHead(pts.lastIndex, p.latitude, p.longitude, p.elapsedMillis, p.cumulativeDistanceMeters, toPace(p.speedMps))
        }

        val i0 = findIdx(pts, targetMs)
        val p0 = pts[i0]
        val p1 = pts[(i0 + 1).coerceAtMost(pts.lastIndex)]
        val span = (p1.elapsedMillis - p0.elapsedMillis).coerceAtLeast(1L)
        val t = ((targetMs - p0.elapsedMillis).toDouble() / span).coerceIn(0.0, 1.0)

        val lat = p0.latitude + (p1.latitude - p0.latitude) * t
        val lon = p0.longitude + (p1.longitude - p0.longitude) * t
        val dist = p0.cumulativeDistanceMeters + (p1.cumulativeDistanceMeters - p0.cumulativeDistanceMeters) * t
        val spd = interpSpeed(p0.speedMps, p1.speedMps, t)
        return ReplayHead(i0, lat, lon, targetMs, dist, toPace(spd))
    }

    private fun findIdx(pts: List<TrackPoint>, targetMs: Long): Int {
        var lo = 0
        var hi = pts.lastIndex - 1
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].elapsedMillis <= targetMs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    private fun interpSpeed(s0: Float?, s1: Float?, t: Double): Float? {
        if (s0 == null && s1 == null) return null
        val a = s0 ?: s1 ?: 0f
        val b = s1 ?: s0 ?: 0f
        return (a + (b - a) * t).toFloat()
    }

    private fun toPace(spd: Float?): Int {
        val v = spd ?: return 0
        return if (v > 0.4f) (1000.0 / v).toInt() else 0
    }
}

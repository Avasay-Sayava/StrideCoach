package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.TrackPoint

data class ReplayHead(
    val baseIdx: Int,
    val lat: Double,
    val lon: Double,
    val elapsedMs: Long,
    val distM: Double,
    val paceSec: Int,
    val speedKmh: Float = 0f,
    val avgPaceSoFarSec: Int = 0,
    val altitudeM: Double? = null,
    val gradePct: Float = 0f,
    val cadenceSpm: Int = 0,
    val currentKm: Int = 1,
    val bearingDeg: Float = 0f
)

object TrackInterpolator {
    fun sampleAt(pts: List<TrackPoint>, targetMs: Long): ReplayHead? {
        if (pts.isEmpty()) return null
        if (pts.size == 1 || targetMs <= pts.first().elapsedMillis) {
            val p = pts.first()
            val pace = toPace(p.speedMps)
            return ReplayHead(
                baseIdx = 0,
                lat = p.latitude,
                lon = p.longitude,
                elapsedMs = p.elapsedMillis,
                distM = p.cumulativeDistanceMeters,
                paceSec = pace,
                speedKmh = ((p.speedMps ?: 0f) * 3.6f).coerceAtLeast(0f),
                avgPaceSoFarSec = pace,
                altitudeM = p.altitudeMeters,
                gradePct = 0f,
                cadenceSpm = p.cadenceSpm ?: 0,
                currentKm = 1,
                bearingDeg = p.bearingDegrees ?: 0f
            )
        }
        if (targetMs >= pts.last().elapsedMillis) {
            val p = pts.last()
            val pace = toPace(p.speedMps)
            val avgPace = if (p.cumulativeDistanceMeters > 15.0 && p.elapsedMillis > 0L) {
                ((p.elapsedMillis / 1000.0) / (p.cumulativeDistanceMeters / 1000.0)).toInt()
            } else pace
            return ReplayHead(
                baseIdx = pts.lastIndex,
                lat = p.latitude,
                lon = p.longitude,
                elapsedMs = p.elapsedMillis,
                distM = p.cumulativeDistanceMeters,
                paceSec = pace,
                speedKmh = ((p.speedMps ?: 0f) * 3.6f).coerceAtLeast(0f),
                avgPaceSoFarSec = avgPace,
                altitudeM = p.altitudeMeters,
                gradePct = localGrade(pts, pts.lastIndex),
                cadenceSpm = p.cadenceSpm ?: 0,
                currentKm = (p.cumulativeDistanceMeters / 1000.0).toInt() + 1,
                bearingDeg = p.bearingDegrees ?: 0f
            )
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
        val pace = toPace(spd)
        val speedKmh = ((spd ?: 0f) * 3.6f).coerceAtLeast(0f)
        val avgPace = if (dist > 15.0 && targetMs > 0L) {
            ((targetMs / 1000.0) / (dist / 1000.0)).toInt()
        } else pace
        val alt = interpAlt(p0.altitudeMeters, p1.altitudeMeters, t)
        val grade = localGrade(pts, i0)
        val cad = p0.cadenceSpm ?: p1.cadenceSpm ?: 0
        val km = (dist / 1000.0).toInt() + 1
        val brg = lookAheadBearing(pts, i0, lat, lon)
        return ReplayHead(i0, lat, lon, targetMs, dist, pace, speedKmh, avgPace, alt, grade, cad, km, brg)
    }

    private fun lookAheadBearing(pts: List<TrackPoint>, startIdx: Int, lat: Double, lon: Double): Float {
        val baseD = pts[startIdx].cumulativeDistanceMeters
        var target = pts.last()
        for (i in (startIdx + 1) until pts.size) {
            if (pts[i].cumulativeDistanceMeters - baseD >= 35.0) {
                target = pts[i]
                break
            }
        }
        return GeoMath.bearingDegrees(lat, lon, target.latitude, target.longitude)
    }

    private fun interpAlt(a0: Double?, a1: Double?, t: Double): Double? {
        if (a0 == null && a1 == null) return null
        val x0 = a0 ?: a1 ?: return null
        val x1 = a1 ?: a0 ?: return null
        return x0 + (x1 - x0) * t
    }

    private fun localGrade(pts: List<TrackPoint>, idx: Int): Float {
        val endPt = pts[idx]
        val endAlt = endPt.altitudeMeters ?: return 0f
        for (k in (idx - 1) downTo (idx - 12).coerceAtLeast(0)) {
            val d = endPt.cumulativeDistanceMeters - pts[k].cumulativeDistanceMeters
            val startAlt = pts[k].altitudeMeters ?: continue
            if (d >= 30.0 || k == 0) {
                return TopographyPaceEngine.computeGradePct(endAlt - startAlt, d).toFloat()
            }
        }
        return 0f
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

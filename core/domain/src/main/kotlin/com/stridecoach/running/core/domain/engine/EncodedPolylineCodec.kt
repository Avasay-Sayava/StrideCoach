package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.TrackPoint
import kotlin.math.roundToInt

data class LatLon(val latitude: Double, val longitude: Double)

object EncodedPolylineCodec {
    private const val SCALE = 1e5

    fun encode(points: List<LatLon>): String {
        if (points.isEmpty()) return ""
        val out = StringBuilder()
        var pLat = 0
        var pLon = 0
        for (pt in points) {
            val lat = (pt.latitude * SCALE).roundToInt()
            val lon = (pt.longitude * SCALE).roundToInt()
            encodeDelta(lat - pLat, out)
            encodeDelta(lon - pLon, out)
            pLat = lat
            pLon = lon
        }
        return out.toString()
    }

    fun decode(encoded: String): List<LatLon> {
        if (encoded.isEmpty()) return emptyList()
        val pts = mutableListOf<LatLon>()
        var idx = 0
        var lat = 0
        var lon = 0
        while (idx < encoded.length) {
            val la = decodeDelta(encoded, idx)
            lat += la.first
            idx = la.second
            if (idx >= encoded.length) break
            val lo = decodeDelta(encoded, idx)
            lon += lo.first
            idx = lo.second
            val dLat = lat / SCALE
            val dLon = lon / SCALE
            if (dLat in -89.9..89.9 && dLon in -179.9..179.9) {
                pts.add(LatLon(dLat, dLon))
            }
        }
        return pts
    }

    fun synthesizeReplayTrack(
        encodedPolyline: String,
        startTimestampMillis: Long,
        totalDurationMillis: Long,
        splits: List<RunSplit>
    ): List<TrackPoint> {
        val coords = decode(encodedPolyline)
        if (coords.isEmpty()) return emptyList()
        val cumDist = cumDists(coords)
        val totD = cumDist.last().coerceAtLeast(1.0)
        val totT = totalDurationMillis.coerceAtLeast(1_000L)

        return coords.mapIndexed { i, c ->
            val d = cumDist[i]
            val el = interpTime(d, totD, totT, splits)
            val prev = if (i > 0) coords[i - 1] else c
            val brg = if (i > 0) GeoMath.bearingDegrees(prev.latitude, prev.longitude, c.latitude, c.longitude) else 0f
            val spd = splitSpeed(d, totD, totT, splits)
            TrackPoint(i, startTimestampMillis + el, el, c.latitude, c.longitude, speedMps = spd, bearingDegrees = brg, cumulativeDistanceMeters = d)
        }
    }

    private fun cumDists(coords: List<LatLon>): DoubleArray {
        val arr = DoubleArray(coords.size)
        var acc = 0.0
        for (i in 1 until coords.size) {
            acc += GeoMath.distanceMeters(coords[i - 1].latitude, coords[i - 1].longitude, coords[i].latitude, coords[i].longitude)
            arr[i] = acc
        }
        return arr
    }

    private fun interpTime(d: Double, totD: Double, totT: Long, splits: List<RunSplit>): Long {
        if (splits.isEmpty()) return ((d / totD) * totT).toLong()
        var dAcc = 0.0
        var tAcc = 0L
        for (sp in splits) {
            val sD = sp.distanceMeters.toDouble().coerceAtLeast(1.0)
            if (d <= dAcc + sD) {
                val frac = ((d - dAcc) / sD).coerceIn(0.0, 1.0)
                return tAcc + (frac * sp.durationMillis).toLong()
            }
            dAcc += sD
            tAcc += sp.durationMillis
        }
        return totT
    }

    private fun splitSpeed(d: Double, totD: Double, totT: Long, splits: List<RunSplit>): Float {
        if (splits.isEmpty()) return (totD / (totT / 1000.0)).toFloat()
        var dAcc = 0.0
        for (sp in splits) {
            val sD = sp.distanceMeters.toDouble().coerceAtLeast(1.0)
            if (d <= dAcc + sD) {
                val sec = (sp.durationMillis / 1000.0).coerceAtLeast(1.0)
                return (sD / sec).toFloat()
            }
            dAcc += sD
        }
        val tail = splits.last()
        return (tail.distanceMeters / (tail.durationMillis / 1000.0).coerceAtLeast(1.0)).toFloat()
    }

    private fun encodeDelta(vIn: Int, out: StringBuilder) {
        var v = if (vIn < 0) (vIn shl 1).inv() else (vIn shl 1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v ushr 5
        }
        out.append((v + 63).toChar())
    }

    private fun decodeDelta(enc: String, start: Int): Pair<Int, Int> {
        var i = start
        var sh = 0
        var res = 0
        while (i < enc.length) {
            val b = enc[i++].code - 63
            res = res or ((b and 0x1f) shl sh)
            sh += 5
            if (b < 0x20) break
        }
        val d = if ((res and 1) != 0) (res shr 1).inv() else (res shr 1)
        return Pair(d, i)
    }
}

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
        var prevLat = 0
        var prevLon = 0
        for (pt in points) {
            val lat = (pt.latitude * SCALE).roundToInt()
            val lon = (pt.longitude * SCALE).roundToInt()
            encodeDelta(lat - prevLat, out)
            encodeDelta(lon - prevLon, out)
            prevLat = lat
            prevLon = lon
        }
        return out.toString()
    }

    fun decode(encoded: String): List<LatLon> {
        if (encoded.isEmpty()) return emptyList()
        val points = mutableListOf<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            val latRes = decodeDelta(encoded, index)
            lat += latRes.first
            index = latRes.second
            if (index >= encoded.length) break
            val lonRes = decodeDelta(encoded, index)
            lon += lonRes.first
            index = lonRes.second
            points.add(LatLon(lat / SCALE, lon / SCALE))
        }
        return points
    }

    fun synthesizeReplayTrack(
        encodedPolyline: String,
        startTimestampMillis: Long,
        totalDurationMillis: Long,
        splits: List<RunSplit>
    ): List<TrackPoint> {
        val coords = decode(encodedPolyline)
        if (coords.isEmpty()) return emptyList()
        val cumDist = computeCumulativeDistances(coords)
        val totalDist = cumDist.last().coerceAtLeast(1.0)
        val totalTime = totalDurationMillis.coerceAtLeast(1_000L)

        return coords.mapIndexed { i, coord ->
            val dist = cumDist[i]
            val elapsed = interpolateElapsedMillis(dist, totalDist, totalTime, splits)
            val prev = if (i > 0) coords[i - 1] else coord
            val bearing = if (i > 0) {
                GeoMath.bearingDegrees(prev.latitude, prev.longitude, coord.latitude, coord.longitude)
            } else {
                0f
            }
            val speed = estimateSpeedMps(i, cumDist, totalDist, totalTime)
            TrackPoint(
                seqIndex = i,
                timestampMillis = startTimestampMillis + elapsed,
                elapsedMillis = elapsed,
                latitude = coord.latitude,
                longitude = coord.longitude,
                speedMps = speed,
                bearingDegrees = bearing,
                cumulativeDistanceMeters = dist
            )
        }
    }

    private fun computeCumulativeDistances(coords: List<LatLon>): DoubleArray {
        val dists = DoubleArray(coords.size)
        var acc = 0.0
        for (i in 1 until coords.size) {
            val prev = coords[i - 1]
            val curr = coords[i]
            acc += GeoMath.distanceMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            dists[i] = acc
        }
        return dists
    }

    private fun interpolateElapsedMillis(
        dist: Double,
        totalDist: Double,
        totalTime: Long,
        splits: List<RunSplit>
    ): Long {
        if (splits.isEmpty()) {
            return ((dist / totalDist) * totalTime).toLong()
        }
        var distAcc = 0.0
        var timeAcc = 0L
        for (split in splits) {
            val splitDist = split.distanceMeters.toDouble().coerceAtLeast(1.0)
            if (dist <= distAcc + splitDist) {
                val frac = ((dist - distAcc) / splitDist).coerceIn(0.0, 1.0)
                return timeAcc + (frac * split.durationMillis).toLong()
            }
            distAcc += splitDist
            timeAcc += split.durationMillis
        }
        return totalTime
    }

    private fun estimateSpeedMps(i: Int, cumDist: DoubleArray, totalDist: Double, totalTime: Long): Float {
        if (i == 0 || totalTime <= 0L) return (totalDist / (totalTime / 1000.0)).toFloat()
        val avgSpeed = totalDist / (totalTime / 1000.0)
        return avgSpeed.toFloat()
    }

    private fun encodeDelta(value: Int, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v ushr 5
        }
        out.append((v + 63).toChar())
    }

    private fun decodeDelta(encoded: String, startIndex: Int): Pair<Int, Int> {
        var index = startIndex
        var shift = 0
        var result = 0
        while (index < encoded.length) {
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val delta = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        return Pair(delta, index)
    }
}

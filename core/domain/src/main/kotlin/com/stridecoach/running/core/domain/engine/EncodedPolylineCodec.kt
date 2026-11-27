package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.TrackPoint
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

data class LatLon(val latitude: Double, val longitude: Double)

object EncodedPolylineCodec {
    private const val SCALE = 1e5
    private val decodeCache = ConcurrentHashMap<String, List<LatLon>>()
    private val synthCache = ConcurrentHashMap<String, List<TrackPoint>>()

    fun encode(points: List<LatLon>): String {
        if (points.isEmpty()) return ""
        val out = StringBuilder(points.size * 6)
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
        decodeCache[encoded]?.let { return it }
        val pts = ArrayList<LatLon>(encoded.length / 5 + 4)
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
        if (decodeCache.size > 128) decodeCache.clear()
        decodeCache[encoded] = pts
        return pts
    }

    /**
     * O(N) single-pass replay track synthesizer with precomputed split tables, cosine-blended
     * speed transitions, corner-slowdown modulation, and bounded memoization.
     */
    fun synthesizeReplayTrack(
        encodedPolyline: String,
        startTimestampMillis: Long,
        totalDurationMillis: Long,
        splits: List<RunSplit>
    ): List<TrackPoint> {
        if (encodedPolyline.isEmpty()) return emptyList()
        val cacheKey = "${encodedPolyline.hashCode()}_${encodedPolyline.length}_${startTimestampMillis}_${totalDurationMillis}_${splits.size}"
        synthCache[cacheKey]?.let { return it }

        val coords = decode(encodedPolyline)
        if (coords.isEmpty()) return emptyList()
        val n = coords.size
        val cumDist = cumDists(coords)
        val totD = cumDist.last().coerceAtLeast(1.0)
        val totT = totalDurationMillis.coerceAtLeast(1_000L)
        val brgs = FloatArray(n)
        for (i in 1 until n) {
            brgs[i] = GeoMath.bearingDegrees(coords[i - 1].latitude, coords[i - 1].longitude, coords[i].latitude, coords[i].longitude)
        }

        val spCnt = splits.size
        val splitEnds = DoubleArray(spCnt)
        val splitDurs = LongArray(spCnt)
        val splitStartsT = LongArray(spCnt)
        val midDists = DoubleArray(spCnt)
        val splitSpeeds = DoubleArray(spCnt)
        var dAcc = 0.0
        var tAcc = 0L
        for (k in 0 until spCnt) {
            val sp = splits[k]
            val sD = sp.distanceMeters.toDouble().coerceAtLeast(1.0)
            splitStartsT[k] = tAcc
            splitDurs[k] = sp.durationMillis
            midDists[k] = dAcc + sD * 0.5
            splitSpeeds[k] = sD / (sp.durationMillis / 1000.0).coerceAtLeast(1.0)
            dAcc += sD
            splitEnds[k] = dAcc
            tAcc += sp.durationMillis
        }

        val fallbackSpd = (totD / (totT / 1000.0)).toFloat()
        val out = ArrayList<TrackPoint>(n)
        var tIdx = 0
        var sIdx = 0

        for (i in 0 until n) {
            val c = coords[i]
            val d = cumDist[i]

            val el: Long = if (spCnt == 0) {
                ((d / totD) * totT).toLong()
            } else {
                while (tIdx < spCnt - 1 && d > splitEnds[tIdx]) tIdx++
                val sPrevD = if (tIdx == 0) 0.0 else splitEnds[tIdx - 1]
                val sSpan = (splitEnds[tIdx] - sPrevD).coerceAtLeast(1.0)
                val frac = ((d - sPrevD) / sSpan).coerceIn(0.0, 1.0)
                (splitStartsT[tIdx] + (frac * splitDurs[tIdx]).toLong()).coerceAtMost(totT)
            }

            val baseSpd: Float = when {
                spCnt == 0 -> fallbackSpd
                spCnt == 1 -> splitSpeeds[0].toFloat()
                d <= midDists[0] -> splitSpeeds[0].toFloat()
                d >= midDists[spCnt - 1] -> splitSpeeds[spCnt - 1].toFloat()
                else -> {
                    while (sIdx < spCnt - 2 && d > midDists[sIdx + 1]) sIdx++
                    val m0 = midDists[sIdx]
                    val m1 = midDists[sIdx + 1]
                    val t = ((d - m0) / (m1 - m0).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                    val mu = (1.0 - cos(t * PI)) * 0.5
                    (splitSpeeds[sIdx] + (splitSpeeds[sIdx + 1] - splitSpeeds[sIdx]) * mu).toFloat()
                }
            }

            val turnFactor = turnSlowdownFactor(brgs, i, n)
            val spd = (baseSpd * turnFactor).coerceIn(0.6f, 12.0f)
            out.add(
                TrackPoint(
                    seqIndex = i,
                    timestampMillis = startTimestampMillis + el,
                    elapsedMillis = el,
                    latitude = c.latitude,
                    longitude = c.longitude,
                    speedMps = spd,
                    bearingDegrees = brgs[i],
                    cumulativeDistanceMeters = d
                )
            )
        }

        if (synthCache.size > 32) synthCache.clear()
        synthCache[cacheKey] = out
        return out
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

    private fun turnSlowdownFactor(brgs: FloatArray, i: Int, n: Int): Float {
        if (i <= 0 || i >= n - 1) return 1.0f
        val diff = abs(((brgs[i + 1] - brgs[i] + 540f) % 360f) - 180f)
        return when {
            diff > 75f -> 0.88f
            diff > 40f -> 0.94f
            else -> 1.0f
        }
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

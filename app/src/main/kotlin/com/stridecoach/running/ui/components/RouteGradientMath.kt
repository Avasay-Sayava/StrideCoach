package com.stridecoach.running.ui.components

import android.graphics.Color
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.PaceZoneClassifier
import com.stridecoach.running.core.domain.engine.ReplayHead
import com.stridecoach.running.core.domain.model.TrackPoint
import org.maplibre.geojson.Point
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

private const val MAX_STOPS = 112
private const val MIN_STEP = 0.001

internal data class PaceStop(val frac: Double, val hex: String)

internal data class PreparedRoute(
    val pts: List<TrackPoint>,
    val mapPoints: List<Point>,
    val geoHash: Int,
    val cumFrac: DoubleArray,
    val totalMerc: Double,
    val bounds: Pair<LatLon, LatLon>?,
    val stops: List<PaceStop>
)

internal fun isValidCoord(lat: Double, lon: Double): Boolean =
    lat in -85.0..85.0 && lon in -179.9..179.9

internal fun prepareRoute(raw: List<TrackPoint>, refPace: Int, smooth: Boolean): PreparedRoute {
    val vp = raw.filter { isValidCoord(it.latitude, it.longitude) }
    val mp = vp.map { Point.fromLngLat(it.longitude, it.latitude) }
    val gh = computePtsGeoHash(vp)
    if (vp.size < 2) {
        val b = vp.firstOrNull()?.let { p ->
            val ll = LatLon(p.latitude, p.longitude)
            ll to ll
        }
        return PreparedRoute(vp, mp, gh, DoubleArray(vp.size), 0.0, b, emptyList())
    }
    val (cum, tot) = buildMercCum(vp)
    val b = computeBounds(vp)
    val stops = buildPaceStops(vp, cum, refPace, smooth)
    return PreparedRoute(vp, mp, gh, cum, tot, b, stops)
}

internal fun headProgressFrac(route: PreparedRoute, head: ReplayHead?): Double {
    if (head == null) return 1.0
    val pts = route.pts
    if (pts.size < 2 || route.totalMerc <= 1e-12) return 1.0
    val i = head.baseIdx.coerceIn(0, pts.lastIndex)
    val p = pts[i]
    val extra = mercDist(p.latitude, p.longitude, head.lat, head.lon)
    return (route.cumFrac[i] + extra / route.totalMerc).coerceIn(0.0, 1.0)
}

private fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

private fun mercY(lat: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1.0 - ln(tan(Math.PI / 4.0 + r / 2.0)) / Math.PI) * 0.5
}

private fun mercDist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dx = mercX(lon2) - mercX(lon1)
    val dy = mercY(lat2) - mercY(lat1)
    return sqrt(dx * dx + dy * dy)
}

private fun buildMercCum(vp: List<TrackPoint>): Pair<DoubleArray, Double> {
    val d = DoubleArray(vp.size)
    var px = mercX(vp[0].longitude)
    var py = mercY(vp[0].latitude)
    var acc = 0.0
    for (i in 1 until vp.size) {
        val x = mercX(vp[i].longitude)
        val y = mercY(vp[i].latitude)
        val seg = sqrt((x - px) * (x - px) + (y - py) * (y - py))
        acc += seg
        d[i] = acc
        px = x
        py = y
    }
    val tot = acc.coerceAtLeast(1e-12)
    for (i in 1 until vp.size) d[i] = (d[i] / tot).coerceIn(0.0, 1.0)
    d[vp.lastIndex] = 1.0
    return d to tot
}

private fun computeBounds(vp: List<TrackPoint>): Pair<LatLon, LatLon> {
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    for (p in vp) {
        if (p.latitude < minLat) minLat = p.latitude
        if (p.latitude > maxLat) maxLat = p.latitude
        if (p.longitude < minLon) minLon = p.longitude
        if (p.longitude > maxLon) maxLon = p.longitude
    }
    return LatLon(minLat, minLon) to LatLon(maxLat, maxLon)
}

private fun smoothedSpeeds(vp: List<TrackPoint>): FloatArray = FloatArray(vp.size) { i ->
    val lo = (i - 2).coerceAtLeast(0)
    val hi = (i + 2).coerceAtMost(vp.lastIndex)
    var sum = 0f
    var cnt = 0
    for (k in lo..hi) {
        val v = vp[k].speedMps ?: continue
        sum += v
        cnt++
    }
    if (cnt > 0) sum / cnt else (vp[i].speedMps ?: 0f)
}

private fun paceHex(spd: Float, refPace: Int, smooth: Boolean): String {
    if (!smooth) return PaceZoneClassifier.classifySpeed(spd, refPace)?.hex ?: "#9BA3AC"
    val hex = PaceZoneClassifier.smoothPaceHex(spd, refPace)
    val c = runCatching { Color.parseColor(hex) }.getOrNull() ?: return hex
    return "#%02X%02X%02X".format(Color.red(c) and 0xF0, Color.green(c) and 0xF0, Color.blue(c) and 0xF0)
}

private fun buildPaceStops(vp: List<TrackPoint>, cum: DoubleArray, refPace: Int, smooth: Boolean): List<PaceStop> {
    val spds = smoothedSpeeds(vp)
    val raw = ArrayList<PaceStop>(vp.size)
    raw.add(PaceStop(0.0, paceHex(spds[0], refPace, smooth)))
    var lastT = 0.0
    var lastHex = raw[0].hex
    for (i in 1 until vp.size) {
        val h = paceHex(spds[i], refPace, smooth)
        val t = cum[i].coerceIn(0.0, 1.0)
        val isEnd = i == vp.lastIndex
        if (!isEnd && h == lastHex) continue
        if (isEnd || t - lastT >= MIN_STEP) {
            if (raw.last().frac < t - 1e-6) raw.add(PaceStop(t, h)) else raw[raw.lastIndex] = PaceStop(t, h)
            lastT = t
            lastHex = h
        }
    }
    if (raw.last().frac < 1.0) raw.add(PaceStop(1.0, lastHex))
    return downsampleStops(raw)
}

private fun downsampleStops(src: List<PaceStop>): List<PaceStop> {
    if (src.size <= MAX_STOPS) return src
    val out = ArrayList<PaceStop>(MAX_STOPS)
    val step = (src.size - 1).toDouble() / (MAX_STOPS - 1)
    var idx = 0.0
    for (k in 0 until MAX_STOPS - 1) {
        val s = src[idx.toInt().coerceIn(0, src.lastIndex)]
        if (out.isEmpty() || s.frac - out.last().frac >= MIN_STEP) out.add(s)
        idx += step
    }
    val tail = src.last()
    if (out.last().frac < 1.0 - 1e-6) out.add(tail) else out[out.lastIndex] = PaceStop(1.0, tail.hex)
    return out
}

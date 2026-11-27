package com.stridecoach.running.core.data.routing

import com.stridecoach.running.core.domain.engine.TopographyPaceEngine
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Queries Open-Meteo Elevation DEM tiles for tracks lacking altitude samples and populates
 * per-point elevations, gain/loss, min/max altitude, split elevation deltas, and GAP.
 */
object ElevationDemService {
    private const val ENDPOINT = "https://api.open-meteo.com/v1/elevation"
    private const val MAX_SAMPLES = 75

    suspend fun enrichRunElevation(run: RunRecord, pts: List<TrackPoint>): RunRecord? = withContext(Dispatchers.IO) {
        if (pts.size < 2) return@withContext null
        val step = (pts.size.toDouble() / MAX_SAMPLES).coerceAtLeast(1.0)
        val sampledIdx = LinkedHashSet<Int>().apply {
            var pos = 0.0
            while (pos < pts.size) {
                add(pos.toInt().coerceIn(0, pts.lastIndex))
                pos += step
            }
            add(pts.lastIndex)
        }.toList().sorted()

        val lats = sampledIdx.joinToString(",") { "%.5f".format(pts[it].latitude) }
        val lons = sampledIdx.joinToString(",") { "%.5f".format(pts[it].longitude) }
        val raw = httpGet("$ENDPOINT?latitude=$lats&longitude=$lons") ?: return@withContext null
        val elevations = parseElevations(raw) ?: return@withContext null
        if (elevations.size != sampledIdx.size) return@withContext null

        val enrichedPts = interpolatePointAltitudes(pts, sampledIdx, elevations)
        val topo = TopographyPaceEngine.analyzeTrack(enrichedPts, run.paceSecondsPerKm.takeIf { it > 0 } ?: 330)
            ?: return@withContext null

        val enrichedSplits = updateSplitElevations(run, enrichedPts)
        run.copy(
            trackPoints = enrichedPts,
            splits = enrichedSplits,
            elevGainM = topo.gainMeters,
            elevLossM = topo.lossMeters,
            minAltM = topo.minAltMeters,
            maxAltM = topo.maxAltMeters,
            gradeAdjustedPaceSec = topo.gradeAdjustedPaceSec
        )
    }

    private fun interpolatePointAltitudes(
        pts: List<TrackPoint>,
        sampledIdx: List<Int>,
        alts: List<Double>
    ): List<TrackPoint> {
        val out = pts.toMutableList()
        for (k in 0 until sampledIdx.lastIndex) {
            val i0 = sampledIdx[k]
            val i1 = sampledIdx[k + 1]
            val a0 = alts[k]
            val a1 = alts[k + 1]
            val span = (i1 - i0).coerceAtLeast(1)
            for (i in i0..i1) {
                val t = (i - i0).toDouble() / span
                out[i] = out[i].copy(altitudeMeters = a0 + (a1 - a0) * t)
            }
        }
        return out
    }

    private fun updateSplitElevations(run: RunRecord, pts: List<TrackPoint>): List<com.stridecoach.running.core.domain.model.RunSplit> {
        if (run.splits.isEmpty()) return emptyList()
        var cursorDist = 0.0
        return run.splits.map { sp ->
            val dEnd = cursorDist + sp.distanceMeters
            val p0 = pts.firstOrNull { it.cumulativeDistanceMeters >= cursorDist } ?: pts.first()
            val p1 = pts.lastOrNull { it.cumulativeDistanceMeters <= dEnd } ?: pts.last()
            cursorDist = dEnd
            val a0 = p0.altitudeMeters
            val a1 = p1.altitudeMeters
            val delta = if (a0 != null && a1 != null) (a1 - a0).roundToInt() else sp.elevDeltaM
            sp.copy(elevDeltaM = delta)
        }
    }

    private fun parseElevations(rawJson: String): List<Double>? = runCatching {
        val arr = JSONObject(rawJson).optJSONArray("elevation") ?: return@runCatching null
        List(arr.length()) { i -> arr.getDouble(i) }
    }.getOrNull()

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "StrideCoach/1.0")
            connectTimeout = 6_000
            readTimeout = 8_000
        }
        if (conn.responseCode != 200) {
            conn.disconnect()
            return@runCatching null
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}

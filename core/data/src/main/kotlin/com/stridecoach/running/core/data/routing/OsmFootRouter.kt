package com.stridecoach.running.core.data.routing

import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.MatchedPointSample
import com.stridecoach.running.core.domain.engine.ParkSafeSnapMerger
import com.stridecoach.running.core.domain.engine.SnapMergeResult
import com.stridecoach.running.core.domain.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object OsmFootRouter {
    private const val OSRM_BASE = "https://routing.openstreetmap.de/routed-foot"
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/trace_attributes"
    private const val UA = "StrideCoach/1.0 (+https://github.com/Avasay-Sayava/StrideCoach)"
    private const val CHUNK_MAX = 750

    suspend fun snapRunParkSafe(raw: List<TrackPoint>): SnapMergeResult = withContext(Dispatchers.IO) {
        if (raw.size < 3) return@withContext SnapMergeResult(raw, 0, 100, 0)
        val all = ArrayList<MatchedPointSample>(raw.size)
        var start = 0
        while (start < raw.size) {
            val end = (start + CHUNK_MAX).coerceAtMost(raw.size)
            val sub = raw.subList(start, end)
            val chunkRes = matchValhallaChunk(sub)
            if (chunkRes == null || chunkRes.size != sub.size) {
                for (p in sub) all.add(MatchedPointSample(false, p.latitude, p.longitude, 999.0))
            } else {
                all.addAll(chunkRes)
            }
            start = end
        }
        ParkSafeSnapMerger.mergePointMatches(raw, all, maxSnapM = 18.0)
    }

    suspend fun routeFootLeg(from: LatLon, to: LatLon): List<LatLon> = withContext(Dispatchers.IO) {
        val url = "$OSRM_BASE/route/v1/foot/${from.longitude},${from.latitude};${to.longitude},${to.latitude}?overview=full&geometries=geojson"
        val txt = httpGet(url) ?: return@withContext listOf(from, to)
        val coords = parseFirstRouteCoords(txt)
        if (coords.size >= 2) coords else listOf(from, to)
    }

    private fun matchValhallaChunk(sub: List<TrackPoint>): List<MatchedPointSample>? {
        val shape = JSONArray()
        for (p in sub) {
            shape.put(JSONObject().put("lat", p.latitude).put("lon", p.longitude))
        }
        val opts = JSONObject().put("search_radius", 25).put("gps_accuracy", 10).put("breakage_distance", 120)
        val attrs = JSONArray().put("matched.point").put("matched.type").put("matched.distance_from_trace_point")
        val flt = JSONObject().put("attributes", attrs).put("action", "include")
        val body = JSONObject()
            .put("shape", shape)
            .put("costing", "pedestrian")
            .put("shape_match", "map_snap")
            .put("trace_options", opts)
            .put("filters", flt)

        val rawJson = httpPostJson(VALHALLA_URL, body.toString()) ?: return null
        return parseValhallaMatches(rawJson, sub)
    }

    private fun parseValhallaMatches(rawJson: String, sub: List<TrackPoint>): List<MatchedPointSample>? = runCatching {
        val root = JSONObject(rawJson)
        val mp = root.optJSONArray("matched_points") ?: return@runCatching null
        if (mp.length() != sub.size) return@runCatching null
        List(mp.length()) { i ->
            val obj = mp.getJSONObject(i)
            val typ = obj.optString("type", "unmatched")
            val ok = typ != "unmatched" && obj.has("lat") && obj.has("lon")
            if (ok) {
                MatchedPointSample(true, obj.getDouble("lat"), obj.getDouble("lon"), obj.optDouble("distance_from_trace_point", 0.0))
            } else {
                MatchedPointSample(false, sub[i].latitude, sub[i].longitude, 999.0)
            }
        }
    }.getOrNull()

    private fun parseFirstRouteCoords(txt: String): List<LatLon> = runCatching {
        val root = JSONObject(txt)
        if (root.optString("code") != "Ok") return@runCatching emptyList()
        val routes = root.optJSONArray("routes") ?: return@runCatching emptyList()
        if (routes.length() == 0) return@runCatching emptyList()
        val arr = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        List(arr.length()) { i ->
            val pair = arr.getJSONArray(i)
            LatLon(pair.getDouble(1), pair.getDouble(0))
        }
    }.getOrDefault(emptyList())

    private fun httpPostJson(urlStr: String, jsonBody: String): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 8_000
            readTimeout = 12_000
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) {
            conn.disconnect()
            return@runCatching null
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = 6_000
            readTimeout = 6_000
        }
        if (conn.responseCode != 200) {
            conn.disconnect()
            return@runCatching null
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}

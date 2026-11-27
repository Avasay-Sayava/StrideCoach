package com.stridecoach.running.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.ReplayHead
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.StrideThemeState
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.LineString
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

private const val ASSET_PREFIX = "https://stridecoach.local/assets/maplibre/"

private val LOCAL_MIME = mapOf(
    "maplibre-gl.js" to "application/javascript",
    "maplibre-gl.css" to "text/css",
    "mapbox-gl-rtl-text.js" to "application/javascript"
)

internal object MapLibreAssetCache {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun warmUp(ctx: Context) {
        getBytes(ctx, "maplibre/terrain-map.html")
        LOCAL_MIME.keys.forEach { getBytes(ctx, "maplibre/$it") }
    }

    fun getBytes(ctx: Context, path: String): ByteArray? =
        cache[path] ?: runCatching { ctx.assets.open(path).use { it.readBytes() } }
            .getOrNull()?.also { cache.putIfAbsent(path, it) }
}

private class TerrainMapHolder {
    var ready = false
    var queued: String? = null
    var lastJson: String? = null
    var baseKey = ""
    var guideKey = ""
    var ptsRef: List<TrackPoint> = emptyList()
    var geoHash = 0
    var refPace = -1
    var smoothGrad = true
    var chunks: List<ColorChunk> = emptyList()
}

internal class TerrainJsBridge(
    private val onReady: () -> Unit,
    private val onPan: () -> Unit,
    private val onTap: (Double, Double) -> Unit,
    private val onUnsup: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun onMapReady() = main.post(onReady)
    @JavascriptInterface fun onUserPan() = main.post(onPan)
    @JavascriptInterface fun onMapTap(lat: Double, lon: Double) = main.post { onTap(lat, lon) }
    @JavascriptInterface fun onWebGlUnsupported() = main.post(onUnsup)
}

@Composable
internal fun Terrain3DWebMap(
    pts: List<TrackPoint>,
    modifier: Modifier = Modifier,
    replayHead: ReplayHead? = null,
    guidePts: List<LatLon> = emptyList(),
    plannerWaypoints: List<LatLon> = emptyList(),
    userLocation: LatLon? = null,
    interactive: Boolean = true,
    showHead: Boolean = false,
    showUserDot: Boolean = false,
    colorByPace: Boolean = true,
    smoothGradient: Boolean = true,
    refPaceSec: Int = 330,
    highlightRange: IntRange? = null,
    highlightTrigger: Int = 0,
    animateHighlight: Boolean = false,
    trimStartIdx: Int? = null,
    trimEndIdx: Int? = null,
    cameraMode: MapCameraMode = MapCameraMode.FIT_ROUTE,
    show3dBuildings: Boolean = true,
    showTerrainHillshade: Boolean = true,
    terrainExaggeration: Float = 2.2f,
    lockAutoFit: Boolean = false,
    mapStyleMode: String = "MATCH_THEME",
    liveHeadingDeg: Float? = null,
    fallbackCenter: LatLon = LatLon(32.1133, 34.8044),
    effectiveTopPx: Int = 0,
    effectiveBottomPx: Int = 0,
    fitTopClearPx: Int = 0,
    fitBottomClearPx: Int = 0,
    fitSidePx: Int = 0,
    hiBottomPx: Int = 0,
    recenterToken: Int = 0,
    onUserManualPan: () -> Unit = {},
    onMapTap: ((LatLon) -> Unit)? = null,
    onWebGlUnsupported: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val d = LocalDensity.current.density.coerceAtLeast(1f)
    val style = resolveMapStyleUri(mapStyleMode, StrideThemeState.isLight)
    val bgHex = "#%06X".format(KdeBg.toArgb() and 0xFFFFFF)

    val panCb = rememberUpdatedState(onUserManualPan)
    val tapCb = rememberUpdatedState(onMapTap)
    val unsupCb = rememberUpdatedState(onWebGlUnsupported)
    val h = remember { TerrainMapHolder() }
    val wv = remember(ctx) {
        makeWebView(ctx, bgHex, h, { panCb.value() }, { lat, lon -> tapCb.value?.invoke(LatLon(lat, lon)) }, { unsupCb.value() })
    }

    BindWebLifecycle(owner, wv)

    AndroidView(
        factory = { wv },
        update = { view ->
            val raw = buildPayload(
                h = h, pts = pts, head = replayHead, guide = guidePts, wps = plannerWaypoints,
                loc = userLocation, interactive = interactive, showHead = showHead,
                showDot = showUserDot, paceCol = colorByPace, smooth = smoothGradient,
                refPace = refPaceSec, hi = highlightRange, hiTrig = highlightTrigger,
                animHi = animateHighlight, trimS = trimStartIdx, trimE = trimEndIdx,
                cam = cameraMode, bld = show3dBuildings, hill = showTerrainHillshade,
                exag = terrainExaggeration, lockFit = lockAutoFit, style = style,
                hdg = liveHeadingDeg, fb = fallbackCenter,
                topCss = effectiveTopPx / d, botCss = effectiveBottomPx / d,
                fitTopCss = fitTopClearPx / d, fitBotCss = fitBottomClearPx / d,
                fitSideCss = fitSidePx / d, hiBotCss = hiBottomPx / d, recenter = recenterToken
            )
            syncPayload(h, view, raw)
        },
        modifier = modifier
    )
}

@Composable
private fun BindWebLifecycle(owner: LifecycleOwner, wv: WebView) {
    DisposableEffect(owner, wv) {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) wv.onResume()
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) wv.onResume()
            else if (ev == Lifecycle.Event.ON_PAUSE) wv.onPause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            runCatching { wv.onPause(); wv.removeJavascriptInterface("StrideBridge"); wv.destroy() }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun makeWebView(
    ctx: Context,
    bgHex: String,
    h: TerrainMapHolder,
    onPan: () -> Unit,
    onTap: (Double, Double) -> Unit,
    onUnsup: () -> Unit
): WebView {
    val wv = WebView(ctx)
    wv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    wv.setBackgroundColor(Color.parseColor(bgHex))
    wv.isVerticalScrollBarEnabled = false
    wv.isHorizontalScrollBarEnabled = false
    applyWebSettings(wv.settings)
    wv.addJavascriptInterface(TerrainJsBridge({ flushQueued(h, wv) }, onPan, onTap, onUnsup), "StrideBridge")
    wv.webViewClient = assetWebClient(ctx)
    wv.loadDataWithBaseURL("https://stridecoach.local/", loadTerrainHtml(ctx, bgHex), "text/html", "UTF-8", null)
    return wv
}

@SuppressLint("SetJavaScriptEnabled")
private fun applyWebSettings(s: WebSettings) {
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.cacheMode = WebSettings.LOAD_DEFAULT
    s.allowFileAccess = false
    s.allowContentAccess = false
    s.useWideViewPort = false
    s.loadWithOverviewMode = false
}

private fun assetWebClient(ctx: Context): WebViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(v: WebView?, req: WebResourceRequest?): WebResourceResponse? {
        val url = req?.url?.toString() ?: return null
        if (!url.startsWith(ASSET_PREFIX)) return null
        return serveAsset(ctx, url.substringAfterLast('/'))
    }
}

private fun serveAsset(ctx: Context, name: String): WebResourceResponse? {
    val mime = LOCAL_MIME[name] ?: return null
    val bytes = MapLibreAssetCache.getBytes(ctx, "maplibre/$name") ?: return null
    return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
}

private fun flushQueued(h: TerrainMapHolder, wv: WebView) {
    h.ready = true
    val q = h.queued ?: return
    h.queued = null
    pushPayload(wv, q)
}

private fun syncPayload(h: TerrainMapHolder, wv: WebView, raw: String) {
    if (raw == h.lastJson) return
    h.lastJson = raw
    if (h.ready) pushPayload(wv, raw) else h.queued = raw
}

private fun pushPayload(wv: WebView, json: String) {
    wv.evaluateJavascript("window.__scUpdateScene && window.__scUpdateScene(${JSONObject.quote(json)});", null)
}

private fun validCoord(lat: Double, lon: Double) = lat in -85.0..85.0 && lon in -179.9..179.9

private fun buildPayload(
    h: TerrainMapHolder,
    pts: List<TrackPoint>,
    head: ReplayHead?,
    guide: List<LatLon>,
    wps: List<LatLon>,
    loc: LatLon?,
    interactive: Boolean,
    showHead: Boolean,
    showDot: Boolean,
    paceCol: Boolean,
    smooth: Boolean,
    refPace: Int,
    hi: IntRange?,
    hiTrig: Int,
    animHi: Boolean,
    trimS: Int?,
    trimE: Int?,
    cam: MapCameraMode,
    bld: Boolean,
    hill: Boolean,
    exag: Float,
    lockFit: Boolean,
    style: String,
    hdg: Float?,
    fb: LatLon,
    topCss: Float,
    botCss: Float,
    fitTopCss: Float,
    fitBotCss: Float,
    fitSideCss: Float,
    hiBotCss: Float,
    recenter: Int
): String {
    val vp = pts.filter { validCoord(it.latitude, it.longitude) }
    val vg = guide.filter { validCoord(it.latitude, it.longitude) }
    val vw = wps.filter { validCoord(it.latitude, it.longitude) }
    val gh = computePtsGeoHash(vp)

    val root = JSONObject()
        .put("styleUri", style)
        .put("interactive", interactive)
        .put("showHead", showHead && vp.isNotEmpty())
        .put("showUserDot", showDot && loc != null)
        .put("colorByPace", paceCol)
        .put("showBuildings", bld)
        .put("showHillshade", hill)
        .put("exag", exag.coerceIn(1.0f, 4.0f).toDouble())
        .put("camMode", cam.name)
        .put("lockAutoFit", lockFit)
        .put("recenter", recenter)
        .put("geoHash", gh)
        .put("ptsCount", vp.size)
        .put("topCss", topCss.toDouble())
        .put("botCss", botCss.toDouble())
        .put("fitTopClearCss", fitTopCss.toDouble())
        .put("fitBotClearCss", fitBotCss.toDouble())
        .put("fitSideCss", fitSideCss.toDouble())
        .put("hiBotCss", hiBotCss.toDouble())
        .put("hiTrigger", hiTrig)
        .put("animateHi", animHi)
        .put("liveHeading", (head?.bearingDeg ?: hdg ?: 0f).toDouble())
        .put("fallback", lonLat(fb.longitude, fb.latitude))

    if (loc != null) root.put("userLoc", lonLat(loc.longitude, loc.latitude))
    val hp = head?.let { LatLon(it.lat, it.lon) } ?: vp.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
    if (hp != null) root.put("head", lonLat(hp.longitude, hp.latitude))

    putBounds(root, if (vp.isNotEmpty()) vp.map { LatLon(it.latitude, it.longitude) } else vg)
    putBaseAndGuide(root, h, vp, vg, gh)
    putWaypoints(root, vw)
    putSeries(root, h, vp, pts, head, paceCol, smooth, refPace, gh)
    putTrim(root, vp, trimS, trimE)
    putHighlight(root, vp, hi, hiTrig)
    return root.toString()
}

private fun lonLat(lon: Double, lat: Double) = JSONArray().put(lon).put(lat)

private fun boundsPair(pts: List<LatLon>): JSONArray = JSONArray()
    .put(lonLat(pts.minOf { it.longitude }, pts.minOf { it.latitude }))
    .put(lonLat(pts.maxOf { it.longitude }, pts.maxOf { it.latitude }))

private fun putBounds(root: JSONObject, pts: List<LatLon>) {
    if (pts.isEmpty()) return
    root.put("bounds", boundsPair(pts))
    root.put("routeStart", lonLat(pts.first().longitude, pts.first().latitude))
    root.put("routeEnd", lonLat(pts.last().longitude, pts.last().latitude))
}

private fun putBaseAndGuide(root: JSONObject, h: TerrainMapHolder, vp: List<TrackPoint>, vg: List<LatLon>, gh: Int) {
    val bk = "${vp.size}_$gh"
    if (!h.ready || bk != h.baseKey) {
        val arr = JSONArray()
        if (vp.size >= 2) vp.forEach { arr.put(lonLat(it.longitude, it.latitude)) }
        root.put("base", arr)
        if (h.ready) h.baseKey = bk
    }
    val gk = "${vg.size}_${vg.firstOrNull()?.latitude}_${vg.lastOrNull()?.longitude}"
    if (!h.ready || gk != h.guideKey) {
        val arr = JSONArray()
        if (vg.size >= 2) vg.forEach { arr.put(lonLat(it.longitude, it.latitude)) }
        root.put("guide", arr)
        if (h.ready) h.guideKey = gk
    }
}

private fun putWaypoints(root: JSONObject, wps: List<LatLon>) {
    val arr = JSONArray()
    wps.forEachIndexed { idx, p ->
        val kind = if (idx == 0) "start" else if (idx == wps.lastIndex && wps.size > 1) "end" else "mid"
        arr.put(JSONObject().put("lon", p.longitude).put("lat", p.latitude).put("kind", kind))
    }
    root.put("waypoints", arr)
}

private fun putSeries(
    root: JSONObject,
    h: TerrainMapHolder,
    vp: List<TrackPoint>,
    raw: List<TrackPoint>,
    head: ReplayHead?,
    paceCol: Boolean,
    smooth: Boolean,
    refPace: Int,
    gh: Int
) {
    if (vp.size < 2) { root.put("pace", JSONArray()).put("active", JSONArray()); return }
    if (!paceCol) {
        val act = JSONArray()
        buildActiveCoords(vp, head).forEach { act.put(lonLat(it.longitude(), it.latitude())) }
        root.put("active", act).put("pace", JSONArray())
        return
    }
    ensureChunks(h, vp, raw, refPace, smooth, gh)
    val pace = JSONArray()
    sliceChunksToHead(h.chunks, vp, head).forEach { f ->
        val ls = f.geometry() as? LineString ?: return@forEach
        val c = JSONArray()
        ls.coordinates().forEach { c.put(lonLat(it.longitude(), it.latitude())) }
        pace.put(JSONObject().put("col", f.getStringProperty("col") ?: "#926EE4").put("coords", c))
    }
    root.put("pace", pace).put("active", JSONArray())
}

private fun ensureChunks(h: TerrainMapHolder, vp: List<TrackPoint>, raw: List<TrackPoint>, ref: Int, smooth: Boolean, gh: Int) {
    if (h.geoHash == gh && h.ptsRef === raw && h.refPace == ref && h.smoothGrad == smooth) return
    h.geoHash = gh; h.ptsRef = raw; h.refPace = ref; h.smoothGrad = smooth
    h.chunks = buildColorChunks(vp, ref, smooth)
}

private fun trackSliceArr(pts: List<TrackPoint>, range: IntRange): JSONArray {
    val seg = JSONArray()
    for (i in range) seg.put(lonLat(pts[i].longitude, pts[i].latitude))
    return seg
}

private fun putTrim(root: JSONObject, vp: List<TrackPoint>, s: Int?, e: Int?) {
    val arr = JSONArray()
    if (vp.size >= 2) {
        if (s != null && s >= 1) arr.put(trackSliceArr(vp, 0..s.coerceAtMost(vp.lastIndex)))
        if (e != null && e < vp.lastIndex) arr.put(trackSliceArr(vp, e.coerceAtLeast(0)..vp.lastIndex))
    }
    root.put("trim", arr)
}

private fun putHighlight(root: JSONObject, vp: List<TrackPoint>, hi: IntRange?, trig: Int) {
    val key = "${hi?.first}_${hi?.last}_$trig"
    if (hi == null || vp.size < 2 || hi.last - hi.first < 1) {
        root.put("hi", JSONArray()).put("hiKey", key)
        return
    }
    val s = hi.first.coerceIn(0, vp.lastIndex)
    val e = hi.last.coerceIn(s, vp.lastIndex)
    val slice = vp.subList(s, e + 1)
    root.put("hi", trackSliceArr(vp, s..e))
    root.put("hiBounds", boundsPair(slice.map { LatLon(it.latitude, it.longitude) }))
    root.put("hiKey", key)
}

private fun loadTerrainHtml(ctx: Context, bgHex: String): String {
    val bytes = MapLibreAssetCache.getBytes(ctx, "maplibre/terrain-map.html") ?: return ""
    return String(bytes, Charsets.UTF_8).replace("__BG_HEX__", bgHex)
}

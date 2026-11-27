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
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

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
    var inFlight = false
    var queuedStatic: String? = null
    var queuedFrame: String? = null
    var lastStatic = ""
    var lastFrame = ""
    var sentBaseKey = ""
    var sentGuideKey = ""
}

internal class TerrainJsBridge(
    private val onReady: () -> Unit,
    private val onPan: () -> Unit,
    private val onTap: (Double, Double) -> Unit,
    private val onUnsup: () -> Unit,
    private val onBearing: (Float) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun onMapReady() = main.post(onReady)
    @JavascriptInterface fun onUserPan() = main.post(onPan)
    @JavascriptInterface fun onMapTap(lat: Double, lon: Double) = main.post { onTap(lat, lon) }
    @JavascriptInterface fun onWebGlUnsupported() = main.post(onUnsup)
    @JavascriptInterface fun onBearingChanged(deg: Double) = main.post { onBearing(deg.toFloat()) }
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
    playing: Boolean = false,
    northLockedInFollow: Boolean = false,
    resetNorthToken: Int = 0,
    onUserManualPan: () -> Unit = {},
    onMapTap: ((LatLon) -> Unit)? = null,
    onWebGlUnsupported: () -> Unit = {},
    onBearingChanged: ((Float) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val d = LocalDensity.current.density.coerceAtLeast(1f)
    val style = resolveMapStyleUri(mapStyleMode, StrideThemeState.isLight)
    val bgHex = "#%06X".format(KdeBg.toArgb() and 0xFFFFFF)

    val prep = remember(pts, refPaceSec, smoothGradient) { prepareRoute(pts, refPaceSec, smoothGradient) }
    val validGuide = remember(guidePts) { guidePts.filter { isValidCoord(it.latitude, it.longitude) } }
    val validWps = remember(plannerWaypoints) { plannerWaypoints.filter { isValidCoord(it.latitude, it.longitude) } }

    val panCb = rememberUpdatedState(onUserManualPan)
    val tapCb = rememberUpdatedState(onMapTap)
    val unsupCb = rememberUpdatedState(onWebGlUnsupported)
    val brgCb = rememberUpdatedState(onBearingChanged)
    val h = remember { TerrainMapHolder() }
    val wv = remember(ctx) {
        makeWebView(ctx, bgHex, h, { panCb.value() }, { lat, lon -> tapCb.value?.invoke(LatLon(lat, lon)) }, { unsupCb.value() }, { deg -> brgCb.value?.invoke(deg) })
    }

    BindWebLifecycle(owner, wv)

    val topCss = qCss(effectiveTopPx, d)
    val botCss = qCss(effectiveBottomPx, d)
    val fitTopCss = qCss(fitTopClearPx, d)
    val fitBotCss = qCss(fitBottomClearPx, d)
    val fitSideCss = qCss(fitSidePx, d)
    val hiBotCss = qCss(hiBottomPx, d)

    val staticJson = remember(
        prep, validGuide, validWps, interactive, show3dBuildings, showTerrainHillshade,
        terrainExaggeration, style, highlightRange, highlightTrigger, animateHighlight,
        trimStartIdx, trimEndIdx, fallbackCenter, fitTopCss, fitBotCss, fitSideCss, hiBotCss
    ) {
        buildStaticJson(
            h, prep, validGuide, validWps, interactive, show3dBuildings, showTerrainHillshade,
            terrainExaggeration, style, highlightRange, highlightTrigger, animateHighlight,
            trimStartIdx, trimEndIdx, fallbackCenter, fitTopCss, fitBotCss, fitSideCss, hiBotCss
        )
    }

    AndroidView(
        factory = { wv },
        update = { view ->
            val frameJson = buildFrameJson(
                prep, replayHead, userLocation, showHead, showUserDot, colorByPace,
                cameraMode, liveHeadingDeg, topCss, botCss, fitTopCss, fitBotCss, fitSideCss,
                recenterToken, playing, northLockedInFollow, resetNorthToken
            )
            enqueueScene(h, view, staticJson, frameJson)
        },
        modifier = modifier
    )
}

private fun qCss(px: Int, density: Float): Int = (((px / density) / 4f).roundToInt() * 4)

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
    onUnsup: () -> Unit,
    onBearing: (Float) -> Unit
): WebView {
    val wv = WebView(ctx)
    wv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    wv.setBackgroundColor(Color.parseColor(bgHex))
    wv.isVerticalScrollBarEnabled = false
    wv.isHorizontalScrollBarEnabled = false
    applyWebSettings(wv.settings)
    wv.addJavascriptInterface(TerrainJsBridge({ onBridgeReady(h, wv) }, onPan, onTap, onUnsup, onBearing), "StrideBridge")
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

private fun onBridgeReady(h: TerrainMapHolder, wv: WebView) {
    h.ready = true
    dispatchPending(h, wv)
}

private fun enqueueScene(h: TerrainMapHolder, wv: WebView, sJson: String, fJson: String) {
    if (sJson != h.lastStatic) {
        h.lastStatic = sJson
        h.queuedStatic = sJson
    }
    if (fJson != h.lastFrame) {
        h.lastFrame = fJson
        h.queuedFrame = fJson
    }
    if (h.ready && !h.inFlight) dispatchPending(h, wv)
}

private fun dispatchPending(h: TerrainMapHolder, wv: WebView) {
    val qs = h.queuedStatic
    val qf = h.queuedFrame
    if (qs == null && qf == null) return
    val js = StringBuilder()
    if (qs != null) {
        h.queuedStatic = null
        js.append("window.__scSyncStatic&&window.__scSyncStatic(").append(JSONObject.quote(qs)).append(");")
    }
    if (qf != null) {
        h.queuedFrame = null
        js.append("window.__scSyncFrame&&window.__scSyncFrame(").append(JSONObject.quote(qf)).append(");")
    }
    h.inFlight = true
    wv.evaluateJavascript(js.toString()) {
        h.inFlight = false
        dispatchPending(h, wv)
    }
}

private fun buildStaticJson(
    h: TerrainMapHolder,
    prep: PreparedRoute,
    guide: List<LatLon>,
    wps: List<LatLon>,
    interactive: Boolean,
    bld: Boolean,
    hill: Boolean,
    exag: Float,
    style: String,
    hi: IntRange?,
    hiTrig: Int,
    animHi: Boolean,
    trimS: Int?,
    trimE: Int?,
    fb: LatLon,
    fitTopCss: Int,
    fitBotCss: Int,
    fitSideCss: Int,
    hiBotCss: Int
): String {
    val vp = prep.pts
    val bk = "${vp.size}_${prep.geoHash}"
    val gk = "${guide.size}_${guide.firstOrNull()?.latitude}_${guide.lastOrNull()?.longitude}"
    val root = JSONObject()
        .put("styleUri", style)
        .put("interactive", interactive)
        .put("showBuildings", bld)
        .put("showHillshade", hill)
        .put("exag", exag.coerceIn(1.0f, 4.0f).toDouble())
        .put("geoHash", prep.geoHash)
        .put("ptsCount", vp.size)
        .put("baseKey", bk)
        .put("guideKey", gk)
        .put("wpsKey", "${wps.size}_${wps.firstOrNull()?.latitude}_${wps.lastOrNull()?.longitude}")
        .put("trimKey", "${vp.size}_${trimS}_${trimE}")
        .put("hiDataKey", "${vp.size}_${hi?.first}_${hi?.last}")
        .put("stopsKey", "${prep.geoHash}_${prep.stops.size}_${prep.stops.firstOrNull()?.hex}_${prep.stops.lastOrNull()?.hex}")
        .put("hiTrigger", hiTrig)
        .put("animateHi", animHi)
        .put("fitTopClearCss", fitTopCss)
        .put("fitBotClearCss", fitBotCss)
        .put("fitSideCss", fitSideCss)
        .put("hiBotCss", hiBotCss)
        .put("fallback", lonLat(fb.longitude, fb.latitude))

    putBounds(root, prep, guide)
    if (!h.ready || bk != h.sentBaseKey) {
        val arr = JSONArray()
        if (vp.size >= 2) vp.forEach { arr.put(lonLat(it.longitude, it.latitude)) }
        root.put("base", arr)
        if (h.ready) h.sentBaseKey = bk
    }
    if (!h.ready || gk != h.sentGuideKey) {
        val arr = JSONArray()
        if (guide.size >= 2) guide.forEach { arr.put(lonLat(it.longitude, it.latitude)) }
        root.put("guide", arr)
        if (h.ready) h.sentGuideKey = gk
    }
    putStops(root, prep.stops)
    putWaypoints(root, wps)
    putTrim(root, vp, trimS, trimE)
    putHighlight(root, vp, hi)
    return root.toString()
}

private fun buildFrameJson(
    prep: PreparedRoute,
    head: ReplayHead?,
    loc: LatLon?,
    showHead: Boolean,
    showDot: Boolean,
    paceCol: Boolean,
    cam: MapCameraMode,
    hdg: Float?,
    topCss: Int,
    botCss: Int,
    fitTopCss: Int,
    fitBotCss: Int,
    fitSideCss: Int,
    recenter: Int,
    playing: Boolean,
    northLocked: Boolean,
    resetNorth: Int
): String {
    val vp = prep.pts
    val obj = JSONObject()
        .put("showHead", showHead && vp.isNotEmpty())
        .put("showUserDot", showDot && loc != null)
        .put("colorByPace", paceCol)
        .put("frac", headProgressFrac(prep, head))
        .put("camMode", cam.name)
        .put("recenter", recenter)
        .put("playing", playing)
        .put("northLocked", northLocked)
        .put("resetNorth", resetNorth)
        .put("topCss", topCss)
        .put("botCss", botCss)
        .put("fitTopClearCss", fitTopCss)
        .put("fitBotClearCss", fitBotCss)
        .put("fitSideCss", fitSideCss)
        .put("liveHeading", (head?.bearingDeg ?: hdg ?: 0f).toDouble())
    if (loc != null) obj.put("userLoc", lonLat(loc.longitude, loc.latitude))
    val hp = head?.let { LatLon(it.lat, it.lon) } ?: vp.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
    if (hp != null) obj.put("head", lonLat(hp.longitude, hp.latitude))
    return obj.toString()
}

private fun lonLat(lon: Double, lat: Double) = JSONArray().put(lon).put(lat)

private fun boundsPair(lo: LatLon, hi: LatLon): JSONArray = JSONArray()
    .put(lonLat(lo.longitude, lo.latitude))
    .put(lonLat(hi.longitude, hi.latitude))

private fun putBounds(root: JSONObject, prep: PreparedRoute, guide: List<LatLon>) {
    val vp = prep.pts
    val b = prep.bounds ?: if (guide.isNotEmpty()) {
        LatLon(guide.minOf { it.latitude }, guide.minOf { it.longitude }) to
            LatLon(guide.maxOf { it.latitude }, guide.maxOf { it.longitude })
    } else null
    if (b != null) root.put("bounds", boundsPair(b.first, b.second))
    val pts = if (vp.isNotEmpty()) vp.first().let { LatLon(it.latitude, it.longitude) } to vp.last().let { LatLon(it.latitude, it.longitude) }
    else if (guide.isNotEmpty()) guide.first() to guide.last() else null
    if (pts == null) return
    root.put("routeStart", lonLat(pts.first.longitude, pts.first.latitude))
    root.put("routeEnd", lonLat(pts.second.longitude, pts.second.latitude))
}

private fun putStops(root: JSONObject, stops: List<PaceStop>) {
    val arr = JSONArray()
    stops.forEach { s -> arr.put(JSONObject().put("t", s.frac).put("c", s.hex)) }
    root.put("paceStops", arr)
}

private fun putWaypoints(root: JSONObject, wps: List<LatLon>) {
    val arr = JSONArray()
    wps.forEachIndexed { idx, p ->
        val kind = if (idx == 0) "start" else if (idx == wps.lastIndex && wps.size > 1) "end" else "mid"
        arr.put(JSONObject().put("lon", p.longitude).put("lat", p.latitude).put("kind", kind))
    }
    root.put("waypoints", arr)
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

private fun putHighlight(root: JSONObject, vp: List<TrackPoint>, hi: IntRange?) {
    if (hi == null || vp.size < 2 || hi.last - hi.first < 1) {
        root.put("hi", JSONArray())
        return
    }
    val s = hi.first.coerceIn(0, vp.lastIndex)
    val e = hi.last.coerceIn(s, vp.lastIndex)
    root.put("hi", trackSliceArr(vp, s..e))
    var loLat = Double.MAX_VALUE; var hiLat = -Double.MAX_VALUE
    var loLon = Double.MAX_VALUE; var hiLon = -Double.MAX_VALUE
    for (i in s..e) {
        val p = vp[i]
        if (p.latitude < loLat) loLat = p.latitude
        if (p.latitude > hiLat) hiLat = p.latitude
        if (p.longitude < loLon) loLon = p.longitude
        if (p.longitude > hiLon) hiLon = p.longitude
    }
    root.put("hiBounds", boundsPair(LatLon(loLat, loLon), LatLon(hiLat, hiLon)))
}

private fun loadTerrainHtml(ctx: Context, bgHex: String): String {
    val bytes = MapLibreAssetCache.getBytes(ctx, "maplibre/terrain-map.html") ?: return ""
    return String(bytes, Charsets.UTF_8).replace("__BG_HEX__", bgHex)
}

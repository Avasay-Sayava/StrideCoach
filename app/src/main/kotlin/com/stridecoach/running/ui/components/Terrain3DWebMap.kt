package com.stridecoach.running.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
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

internal object MapLibreAssetCache {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun warmUp(ctx: Context) {
        getBytes(ctx, "maplibre/terrain-map.html")
        getBytes(ctx, "maplibre/maplibre-gl.js")
        getBytes(ctx, "maplibre/maplibre-gl.css")
        getBytes(ctx, "maplibre/mapbox-gl-rtl-text.js")
    }

    fun getBytes(ctx: Context, assetPath: String): ByteArray? =
        cache[assetPath] ?: runCatching {
            ctx.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull()?.also { cache.putIfAbsent(assetPath, it) }
}

private class TerrainMapHolder {
    var webView: WebView? = null
    var pageReady: Boolean = false
    var pendingJson: String? = null
    var lastSentJson: String? = null
    var sentBaseKey: String = ""
    var sentGuideKey: String = ""
    var cachedPtsRef: List<TrackPoint> = emptyList()
    var cachedGeoHash: Int = 0
    var cachedRefPace: Int = -1
    var cachedSmoothGrad: Boolean = true
    var cachedChunks: List<ColorChunk> = emptyList()
}

internal class TerrainJsBridge(
    private val onReady: () -> Unit,
    private val onPan: () -> Unit,
    private val onTap: (Double, Double) -> Unit,
    private val onUnsupported: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMapReady() {
        main.post { onReady() }
    }

    @JavascriptInterface
    fun onUserPan() {
        main.post { onPan() }
    }

    @JavascriptInterface
    fun onMapTap(lat: Double, lon: Double) {
        main.post { onTap(lat, lon) }
    }

    @JavascriptInterface
    fun onWebGlUnsupported() {
        main.post { onUnsupported() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
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
    showUserDot: Boolean = true,
    colorByPace: Boolean = false,
    smoothGradient: Boolean = true,
    refPaceSec: Int = 330,
    highlightRange: IntRange? = null,
    highlightTrigger: Int = 0,
    animateHighlight: Boolean = true,
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
    val density = LocalDensity.current.density.coerceAtLeast(1f)
    val styleUri = resolveMapStyleUri(mapStyleMode, StrideThemeState.isLight)
    val bgHex = "#%06X".format(KdeBg.toArgb() and 0xFFFFFF)

    val panCb = rememberUpdatedState(onUserManualPan)
    val tapCb = rememberUpdatedState(onMapTap)
    val unsupCb = rememberUpdatedState(onWebGlUnsupported)
    val holder = remember { TerrainMapHolder() }

    val webView = remember(ctx) {
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor(bgHex))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = false
                loadWithOverviewMode = false
            }
            val bridge = TerrainJsBridge(
                onReady = {
                    holder.pageReady = true
                    val q = holder.pendingJson
                    holder.pendingJson = null
                    if (q != null) {
                        pushTerrainPayload(this, q)
                    }
                },
                onPan = { panCb.value() },
                onTap = { lat, lon -> tapCb.value?.invoke(LatLon(lat, lon)) },
                onUnsupported = { unsupCb.value() }
            )
            addJavascriptInterface(bridge, "StrideBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith("https://stridecoach.local/assets/maplibre/")) {
                        val name = url.substringAfterLast('/')
                        return serveLocalAsset(ctx, name)
                    }
                    return null
                }
            }
            holder.webView = this
            loadDataWithBaseURL(
                "https://stridecoach.local/",
                loadTerrainHtml(ctx, bgHex),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    DisposableEffect(owner, webView) {
        val stNow = owner.lifecycle.currentState
        if (stNow.isAtLeast(Lifecycle.State.RESUMED)) webView.onResume()
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            runCatching {
                webView.onPause()
                webView.removeJavascriptInterface("StrideBridge")
                webView.destroy()
            }
        }
    }

    AndroidView(
        factory = { webView },
        update = { wv ->
            val payload = buildTerrainPayload(
                h = holder,
                pts = pts,
                replayHead = replayHead,
                guidePts = guidePts,
                plannerWaypoints = plannerWaypoints,
                userLoc = userLocation,
                interactive = interactive,
                showHead = showHead,
                showUserDot = showUserDot,
                colorByPace = colorByPace,
                smoothGradient = smoothGradient,
                refPaceSec = refPaceSec,
                hiRange = highlightRange,
                hiTrigger = highlightTrigger,
                animateHighlight = animateHighlight,
                trimStartIdx = trimStartIdx,
                trimEndIdx = trimEndIdx,
                cameraMode = cameraMode,
                show3dBuildings = show3dBuildings,
                showTerrainHillshade = showTerrainHillshade,
                exaggeration = terrainExaggeration,
                lockAutoFit = lockAutoFit,
                styleUri = styleUri,
                liveHeadingDeg = liveHeadingDeg,
                fallbackCenter = fallbackCenter,
                topCss = effectiveTopPx / density,
                botCss = effectiveBottomPx / density,
                fitTopClearCss = fitTopClearPx / density,
                fitBotClearCss = fitBottomClearPx / density,
                fitSideCss = fitSidePx / density,
                hiBotCss = hiBottomPx / density,
                recenterToken = recenterToken
            )
            if (payload != holder.lastSentJson) {
                holder.lastSentJson = payload
                if (holder.pageReady) {
                    pushTerrainPayload(wv, payload)
                } else {
                    holder.pendingJson = payload
                }
            }
        },
        modifier = modifier
    )
}

private fun serveLocalAsset(ctx: Context, name: String): WebResourceResponse? {
    val clean = when (name) {
        "maplibre-gl.js" -> "maplibre/maplibre-gl.js" to "application/javascript"
        "maplibre-gl.css" -> "maplibre/maplibre-gl.css" to "text/css"
        "mapbox-gl-rtl-text.js" -> "maplibre/mapbox-gl-rtl-text.js" to "application/javascript"
        else -> return null
    }
    return runCatching {
        val bytes = MapLibreAssetCache.getBytes(ctx, clean.first) ?: return null
        WebResourceResponse(
            clean.second,
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(bytes)
        )
    }.getOrNull()
}

private fun pushTerrainPayload(wv: WebView, json: String) {
    val escaped = JSONObject.quote(json)
    wv.evaluateJavascript("window.__scUpdateScene && window.__scUpdateScene($escaped);", null)
}

private fun buildTerrainPayload(
    h: TerrainMapHolder,
    pts: List<TrackPoint>,
    replayHead: ReplayHead?,
    guidePts: List<LatLon>,
    plannerWaypoints: List<LatLon>,
    userLoc: LatLon?,
    interactive: Boolean,
    showHead: Boolean,
    showUserDot: Boolean,
    colorByPace: Boolean,
    smoothGradient: Boolean,
    refPaceSec: Int,
    hiRange: IntRange?,
    hiTrigger: Int,
    animateHighlight: Boolean,
    trimStartIdx: Int?,
    trimEndIdx: Int?,
    cameraMode: MapCameraMode,
    show3dBuildings: Boolean,
    showTerrainHillshade: Boolean,
    exaggeration: Float,
    lockAutoFit: Boolean,
    styleUri: String,
    liveHeadingDeg: Float?,
    fallbackCenter: LatLon,
    topCss: Float,
    botCss: Float,
    fitTopClearCss: Float,
    fitBotClearCss: Float,
    fitSideCss: Float,
    hiBotCss: Float,
    recenterToken: Int
): String {
    val validPts = pts.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    val validGuide = guidePts.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    val validWaypoints = plannerWaypoints.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    val geoHash = computePtsGeoHash(validPts)

    val root = JSONObject()
        .put("styleUri", styleUri)
        .put("interactive", interactive)
        .put("showHead", showHead && validPts.isNotEmpty())
        .put("showUserDot", showUserDot && userLoc != null)
        .put("colorByPace", colorByPace)
        .put("showBuildings", show3dBuildings)
        .put("showHillshade", showTerrainHillshade)
        .put("exag", exaggeration.coerceIn(1.0f, 4.0f).toDouble())
        .put("camMode", cameraMode.name)
        .put("lockAutoFit", lockAutoFit)
        .put("recenter", recenterToken)
        .put("geoHash", geoHash)
        .put("ptsCount", validPts.size)
        .put("topCss", topCss.toDouble())
        .put("botCss", botCss.toDouble())
        .put("fitTopClearCss", fitTopClearCss.toDouble())
        .put("fitBotClearCss", fitBotClearCss.toDouble())
        .put("fitSideCss", fitSideCss.toDouble())
        .put("hiBotCss", hiBotCss.toDouble())
        .put("hiTrigger", hiTrigger)
        .put("animateHi", animateHighlight)
        .put("liveHeading", (replayHead?.bearingDeg ?: liveHeadingDeg ?: 0f).toDouble())
        .put("fallback", JSONArray().put(fallbackCenter.longitude).put(fallbackCenter.latitude))

    if (userLoc != null) root.put("userLoc", JSONArray().put(userLoc.longitude).put(userLoc.latitude))
    val headPt = replayHead?.let { LatLon(it.lat, it.lon) } ?: validPts.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
    if (headPt != null) root.put("head", JSONArray().put(headPt.longitude).put(headPt.latitude))

    appendRouteBounds(root, if (validPts.isNotEmpty()) validPts.map { LatLon(it.latitude, it.longitude) } else validGuide)
    appendBaseAndGuide(root, h, validPts, validGuide, geoHash)
    appendWaypoints(root, validWaypoints)
    appendRouteSeries(root, h, validPts, pts, replayHead, colorByPace, smoothGradient, refPaceSec, geoHash)
    appendTrimSegments(root, validPts, trimStartIdx, trimEndIdx)
    appendHighlightData(root, validPts, hiRange, hiTrigger)
    return root.toString()
}

private fun appendRouteBounds(root: JSONObject, routePts: List<LatLon>) {
    if (routePts.isEmpty()) return
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    for (p in routePts) {
        if (p.latitude < minLat) minLat = p.latitude
        if (p.latitude > maxLat) maxLat = p.latitude
        if (p.longitude < minLon) minLon = p.longitude
        if (p.longitude > maxLon) maxLon = p.longitude
    }
    root.put("bounds", JSONArray().put(JSONArray().put(minLon).put(minLat)).put(JSONArray().put(maxLon).put(maxLat)))
    root.put("routeStart", JSONArray().put(routePts.first().longitude).put(routePts.first().latitude))
    root.put("routeEnd", JSONArray().put(routePts.last().longitude).put(routePts.last().latitude))
}

private fun appendBaseAndGuide(
    root: JSONObject,
    h: TerrainMapHolder,
    validPts: List<TrackPoint>,
    validGuide: List<LatLon>,
    geoHash: Int
) {
    val baseKey = "${validPts.size}_$geoHash"
    if (!h.pageReady || baseKey != h.sentBaseKey) {
        val arr = JSONArray()
        if (validPts.size >= 2) {
            for (p in validPts) arr.put(JSONArray().put(p.longitude).put(p.latitude))
        }
        root.put("base", arr)
        if (h.pageReady) h.sentBaseKey = baseKey
    }
    val guideKey = "${validGuide.size}_${validGuide.firstOrNull()?.latitude}_${validGuide.lastOrNull()?.longitude}"
    if (!h.pageReady || guideKey != h.sentGuideKey) {
        val arr = JSONArray()
        if (validGuide.size >= 2) {
            for (p in validGuide) arr.put(JSONArray().put(p.longitude).put(p.latitude))
        }
        root.put("guide", arr)
        if (h.pageReady) h.sentGuideKey = guideKey
    }
}

private fun appendWaypoints(root: JSONObject, wps: List<LatLon>) {
    val arr = JSONArray()
    wps.forEachIndexed { idx, p ->
        val kind = when {
            idx == 0 -> "start"
            idx == wps.lastIndex && wps.size > 1 -> "end"
            else -> "mid"
        }
        arr.put(JSONObject().put("lon", p.longitude).put("lat", p.latitude).put("kind", kind))
    }
    root.put("waypoints", arr)
}

private fun appendRouteSeries(
    root: JSONObject,
    h: TerrainMapHolder,
    validPts: List<TrackPoint>,
    rawPts: List<TrackPoint>,
    replayHead: ReplayHead?,
    colorByPace: Boolean,
    smoothGradient: Boolean,
    refPaceSec: Int,
    geoHash: Int
) {
    if (validPts.size < 2) {
        root.put("pace", JSONArray()).put("active", JSONArray())
        return
    }
    if (!colorByPace) {
        val actArr = JSONArray()
        for (pt in buildActiveCoords(validPts, replayHead)) actArr.put(JSONArray().put(pt.longitude()).put(pt.latitude()))
        root.put("active", actArr).put("pace", JSONArray())
        return
    }
    val dirty = h.cachedGeoHash != geoHash || h.cachedPtsRef !== rawPts || h.cachedRefPace != refPaceSec || h.cachedSmoothGrad != smoothGradient
    if (dirty) {
        h.cachedGeoHash = geoHash
        h.cachedPtsRef = rawPts
        h.cachedRefPace = refPaceSec
        h.cachedSmoothGrad = smoothGradient
        h.cachedChunks = buildColorChunks(validPts, refPaceSec, smoothGradient)
    }
    val paceArr = JSONArray()
    for (f in sliceChunksToHead(h.cachedChunks, validPts, replayHead)) {
        val ls = f.geometry() as? LineString ?: continue
        val coords = JSONArray()
        for (pt in ls.coordinates()) coords.put(JSONArray().put(pt.longitude()).put(pt.latitude()))
        paceArr.put(JSONObject().put("col", f.getStringProperty("col") ?: "#926EE4").put("coords", coords))
    }
    root.put("pace", paceArr).put("active", JSONArray())
}

private fun appendTrimSegments(root: JSONObject, validPts: List<TrackPoint>, startIdx: Int?, endIdx: Int?) {
    val arr = JSONArray()
    if (validPts.size >= 2) {
        if (startIdx != null && startIdx >= 1) {
            val seg = JSONArray()
            for (i in 0..startIdx.coerceAtMost(validPts.lastIndex)) {
                seg.put(JSONArray().put(validPts[i].longitude).put(validPts[i].latitude))
            }
            arr.put(seg)
        }
        if (endIdx != null && endIdx < validPts.lastIndex) {
            val seg = JSONArray()
            for (i in endIdx.coerceAtLeast(0)..validPts.lastIndex) {
                seg.put(JSONArray().put(validPts[i].longitude).put(validPts[i].latitude))
            }
            arr.put(seg)
        }
    }
    root.put("trim", arr)
}

private fun appendHighlightData(root: JSONObject, validPts: List<TrackPoint>, hiRange: IntRange?, hiTrigger: Int) {
    val hiArr = JSONArray()
    if (hiRange != null && validPts.size >= 2 && hiRange.last - hiRange.first >= 1) {
        val s = hiRange.first.coerceIn(0, validPts.lastIndex)
        val e = hiRange.last.coerceIn(s, validPts.lastIndex)
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (i in s..e) {
            val p = validPts[i]
            hiArr.put(JSONArray().put(p.longitude).put(p.latitude))
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        root.put("hiBounds", JSONArray().put(JSONArray().put(minLon).put(minLat)).put(JSONArray().put(maxLon).put(maxLat)))
    }
    root.put("hi", hiArr).put("hiKey", "${hiRange?.first}_${hiRange?.last}_$hiTrigger")
}

private fun loadTerrainHtml(ctx: Context, bgHex: String): String {
    val bytes = MapLibreAssetCache.getBytes(ctx, "maplibre/terrain-map.html") ?: return ""
    return String(bytes, Charsets.UTF_8).replace("__BG_HEX__", bgHex)
}

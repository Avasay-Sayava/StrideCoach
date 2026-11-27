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

private class TerrainMapHolder {
    var webView: WebView? = null
    var pageReady: Boolean = false
    var pendingJson: String? = null
    var lastSentJson: String? = null
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
                buildTerrainHtml(bgHex),
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
        else -> return null
    }
    return runCatching {
        val stream = ctx.assets.open(clean.first)
        WebResourceResponse(
            clean.second,
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            stream
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
    root.put("styleUri", styleUri)
    root.put("interactive", interactive)
    root.put("showHead", showHead && validPts.isNotEmpty())
    root.put("showUserDot", showUserDot && userLoc != null)
    root.put("colorByPace", colorByPace)
    root.put("showBuildings", show3dBuildings)
    root.put("showHillshade", showTerrainHillshade)
    root.put("exag", exaggeration.coerceIn(1.0f, 4.0f).toDouble())
    root.put("camMode", cameraMode.name)
    root.put("lockAutoFit", lockAutoFit)
    root.put("recenter", recenterToken)
    root.put("geoHash", geoHash)
    root.put("ptsCount", validPts.size)
    root.put("topCss", topCss.toDouble())
    root.put("botCss", botCss.toDouble())
    root.put("fitTopClearCss", fitTopClearCss.toDouble())
    root.put("fitBotClearCss", fitBotClearCss.toDouble())
    root.put("fitSideCss", fitSideCss.toDouble())
    root.put("hiBotCss", hiBotCss.toDouble())
    root.put("hiTrigger", hiTrigger)
    root.put("animateHi", animateHighlight)
    root.put("liveHeading", (replayHead?.bearingDeg ?: liveHeadingDeg ?: 0f).toDouble())
    root.put("fallback", JSONArray().put(fallbackCenter.longitude).put(fallbackCenter.latitude))

    if (userLoc != null) {
        root.put("userLoc", JSONArray().put(userLoc.longitude).put(userLoc.latitude))
    }

    val headPt = replayHead?.let { LatLon(it.lat, it.lon) }
        ?: validPts.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
    if (headPt != null) {
        root.put("head", JSONArray().put(headPt.longitude).put(headPt.latitude))
    }

    val routePts = if (validPts.isNotEmpty()) validPts.map { LatLon(it.latitude, it.longitude) } else validGuide
    if (routePts.isNotEmpty()) {
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
        root.put(
            "bounds",
            JSONArray()
                .put(JSONArray().put(minLon).put(minLat))
                .put(JSONArray().put(maxLon).put(maxLat))
        )
        val lastPt = routePts.last()
        root.put("routeEnd", JSONArray().put(lastPt.longitude).put(lastPt.latitude))
        root.put("routeStart", JSONArray().put(routePts.first().longitude).put(routePts.first().latitude))
    }

    val baseArr = JSONArray()
    if (validPts.size >= 2) {
        for (p in validPts) baseArr.put(JSONArray().put(p.longitude).put(p.latitude))
    }
    root.put("base", baseArr)

    val guideArr = JSONArray()
    if (validGuide.size >= 2) {
        for (p in validGuide) guideArr.put(JSONArray().put(p.longitude).put(p.latitude))
    }
    root.put("guide", guideArr)

    val wpArr = JSONArray()
    validWaypoints.forEachIndexed { idx, p ->
        val kind = when {
            idx == 0 -> "start"
            idx == validWaypoints.lastIndex && validWaypoints.size > 1 -> "end"
            else -> "mid"
        }
        wpArr.put(JSONObject().put("lon", p.longitude).put("lat", p.latitude).put("kind", kind))
    }
    root.put("waypoints", wpArr)

    if (validPts.size >= 2) {
        if (colorByPace) {
            val dirty = h.cachedGeoHash != geoHash || h.cachedPtsRef !== pts || h.cachedRefPace != refPaceSec || h.cachedSmoothGrad != smoothGradient
            if (dirty) {
                h.cachedGeoHash = geoHash
                h.cachedPtsRef = pts
                h.cachedRefPace = refPaceSec
                h.cachedSmoothGrad = smoothGradient
                h.cachedChunks = buildColorChunks(validPts, refPaceSec, smoothGradient)
            }
            val feats = sliceChunksToHead(h.cachedChunks, validPts, replayHead)
            val paceArr = JSONArray()
            for (f in feats) {
                val ls = f.geometry() as? LineString ?: continue
                val coords = JSONArray()
                for (pt in ls.coordinates()) {
                    coords.put(JSONArray().put(pt.longitude()).put(pt.latitude()))
                }
                paceArr.put(JSONObject().put("col", f.getStringProperty("col") ?: "#926EE4").put("coords", coords))
            }
            root.put("pace", paceArr)
            root.put("active", JSONArray())
        } else {
            val activeCoords = buildActiveCoords(validPts, replayHead)
            val actArr = JSONArray()
            for (pt in activeCoords) actArr.put(JSONArray().put(pt.longitude()).put(pt.latitude()))
            root.put("active", actArr)
            root.put("pace", JSONArray())
        }
    } else {
        root.put("pace", JSONArray())
        root.put("active", JSONArray())
    }

    val trimArr = JSONArray()
    if (validPts.size >= 2) {
        if (trimStartIdx != null && trimStartIdx >= 1) {
            val e = trimStartIdx.coerceAtMost(validPts.lastIndex)
            val seg = JSONArray()
            for (i in 0..e) seg.put(JSONArray().put(validPts[i].longitude).put(validPts[i].latitude))
            trimArr.put(seg)
        }
        if (trimEndIdx != null && trimEndIdx < validPts.lastIndex) {
            val s = trimEndIdx.coerceAtLeast(0)
            val seg = JSONArray()
            for (i in s..validPts.lastIndex) seg.put(JSONArray().put(validPts[i].longitude).put(validPts[i].latitude))
            trimArr.put(seg)
        }
    }
    root.put("trim", trimArr)

    val hiArr = JSONArray()
    if (hiRange != null && validPts.size >= 2 && hiRange.last - hiRange.first >= 1) {
        val s = hiRange.first.coerceIn(0, validPts.lastIndex)
        val e = hiRange.last.coerceIn(s, validPts.lastIndex)
        var hiMinLat = Double.MAX_VALUE
        var hiMaxLat = -Double.MAX_VALUE
        var hiMinLon = Double.MAX_VALUE
        var hiMaxLon = -Double.MAX_VALUE
        for (i in s..e) {
            val p = validPts[i]
            hiArr.put(JSONArray().put(p.longitude).put(p.latitude))
            if (p.latitude < hiMinLat) hiMinLat = p.latitude
            if (p.latitude > hiMaxLat) hiMaxLat = p.latitude
            if (p.longitude < hiMinLon) hiMinLon = p.longitude
            if (p.longitude > hiMaxLon) hiMaxLon = p.longitude
        }
        root.put(
            "hiBounds",
            JSONArray()
                .put(JSONArray().put(hiMinLon).put(hiMinLat))
                .put(JSONArray().put(hiMaxLon).put(hiMaxLat))
        )
    }
    root.put("hi", hiArr)
    root.put("hiKey", "${hiRange?.first}_${hiRange?.last}_$hiTrigger")

    return root.toString()
}

private fun buildTerrainHtml(bgHex: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="https://stridecoach.local/assets/maplibre/maplibre-gl.css">
<style>
  html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; background: $bgHex; touch-action: none; }
  .maplibregl-ctrl-attrib, .maplibregl-ctrl-logo { display: none !important; }
</style>
</head>
<body>
<div id="map"></div>
<script src="https://stridecoach.local/assets/maplibre/maplibre-gl.js"></script>
<script>
(function() {
  function hasWebGL() {
    try {
      var c = document.createElement('canvas');
      return !!(window.WebGL2RenderingContext && c.getContext('webgl2')) || !!(c.getContext('webgl') || c.getContext('experimental-webgl'));
    } catch (e) { return false; }
  }
  if (typeof maplibregl === 'undefined' || !hasWebGL()) {
    StrideBridge.onWebGlUnsupported();
    return;
  }

  var state = {
    map: null,
    styleReady: false,
    activeStyle: '',
    lastPayload: null,
    lastCamMode: '',
    lastFitKey: '',
    lastFollowKey: '',
    lastFreeInit: false,
    lastHiTrig: -1,
    hiAnimTimer: null,
    lastExag: -1,
    lastInteractive: true
  };

  function emptyFc() { return { type: 'FeatureCollection', features: [] }; }
  function lineFc(coords) {
    if (!coords || coords.length < 2) return emptyFc();
    return { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'LineString', coordinates: coords }, properties: {} }] };
  }
  function pointFc(pt) {
    if (!pt) return emptyFc();
    return { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: pt }, properties: {} }] };
  }

  function ensureTerrainAndLayers(map, p) {
    if (!map.getSource('terrain-dem')) {
      map.addSource('terrain-dem', {
        type: 'raster-dem',
        tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'],
        encoding: 'terrarium',
        tileSize: 256,
        maxzoom: 14
      });
    }
    try {
      map.setTerrain({ source: 'terrain-dem', exaggeration: p.exag || 2.2 });
      state.lastExag = p.exag || 2.2;
    } catch (e) {
      StrideBridge.onWebGlUnsupported();
      return;
    }

    var beforeId = map.getLayer('building') ? 'building' : (map.getLayer('water_name') ? 'water_name' : undefined);
    if (!map.getLayer('terrain-hillshade-layer')) {
      var hsDef = {
        id: 'terrain-hillshade-layer',
        type: 'hillshade',
        source: 'terrain-dem',
        paint: { 'hillshade-exaggeration': 0.34 }
      };
      if (beforeId) map.addLayer(hsDef, beforeId); else map.addLayer(hsDef);
    }

    if (map.getSource('openmaptiles') && !map.getLayer('3d-buildings-layer')) {
      var bldDef = {
        id: '3d-buildings-layer',
        type: 'fill-extrusion',
        source: 'openmaptiles',
        'source-layer': 'building',
        minzoom: 12.5,
        paint: {
          'fill-extrusion-height': ['coalesce', ['get', 'render_height'], ['get', 'height'], 12],
          'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], ['get', 'min_height'], 0],
          'fill-extrusion-color': '#3A4354',
          'fill-extrusion-opacity': 0.86
        }
      };
      var bBefore = map.getLayer('highway_name_other') ? 'highway_name_other' : (map.getLayer('place_city') ? 'place_city' : undefined);
      if (bBefore) map.addLayer(bldDef, bBefore); else map.addLayer(bldDef);
    }

    var srcs = ['guide-src','base-src','pace-src','active-src','trim-src','hi-src','planner-pts-src','user-loc-src','head-src'];
    for (var i = 0; i < srcs.length; i++) {
      if (!map.getSource(srcs[i])) {
        map.addSource(srcs[i], { type: 'geojson', data: emptyFc() });
      }
    }

    if (!map.getLayer('guide-layer')) {
      map.addLayer({
        id: 'guide-layer', type: 'line', source: 'guide-src',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#3DAEE9', 'line-width': 3.6, 'line-dasharray': [2.2, 1.8] }
      });
    }
    if (!map.getLayer('base-layer')) {
      map.addLayer({
        id: 'base-layer', type: 'line', source: 'base-src',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#473C69', 'line-width': 4.0 }
      });
    }
    if (!map.getLayer('pace-layer')) {
      map.addLayer({
        id: 'pace-layer', type: 'line', source: 'pace-src',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'col'], 'line-width': 5.4 }
      });
    }
    if (!map.getLayer('active-layer')) {
      map.addLayer({
        id: 'active-layer', type: 'line', source: 'active-src',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#926EE4', 'line-width': 5.5 }
      });
    }
    if (!map.getLayer('trim-layer')) {
      map.addLayer({
        id: 'trim-layer', type: 'line', source: 'trim-src',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#F87171', 'line-width': 5.4 }
      });
    }
    if (!map.getLayer('hi-layer')) {
      map.addLayer({
        id: 'hi-layer', type: 'line', source: 'hi-src',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#3DAEE9', 'line-width': 7.5 }
      });
    }
    if (!map.getLayer('planner-pts-layer')) {
      map.addLayer({
        id: 'planner-pts-layer', type: 'circle', source: 'planner-pts-src',
        paint: {
          'circle-radius': ['match', ['get', 'kind'], 'start', 8.5, 'end', 8.5, 6.0],
          'circle-color': ['match', ['get', 'kind'], 'start', '#4ADE80', 'end', '#F87171', '#926EE4'],
          'circle-stroke-color': '#FFFFFF',
          'circle-stroke-width': 2.2
        }
      });
    }
    if (!map.getLayer('user-loc-halo')) {
      map.addLayer({ id: 'user-loc-halo', type: 'circle', source: 'user-loc-src', paint: { 'circle-radius': 14, 'circle-color': '#3DAEE9', 'circle-opacity': 0.28 } });
      map.addLayer({ id: 'user-loc-ring', type: 'circle', source: 'user-loc-src', paint: { 'circle-radius': 7.8, 'circle-color': '#FFFFFF' } });
      map.addLayer({ id: 'user-loc-core', type: 'circle', source: 'user-loc-src', paint: { 'circle-radius': 5.2, 'circle-color': '#3DAEE9' } });
    }
    if (!map.getLayer('head-halo')) {
      map.addLayer({ id: 'head-halo', type: 'circle', source: 'head-src', paint: { 'circle-radius': 13, 'circle-color': '#926EE4', 'circle-opacity': 0.32 } });
      map.addLayer({ id: 'head-ring', type: 'circle', source: 'head-src', paint: { 'circle-radius': 7.5, 'circle-color': '#FFFFFF' } });
      map.addLayer({ id: 'head-core', type: 'circle', source: 'head-src', paint: { 'circle-radius': 4.8, 'circle-color': '#926EE4' } });
    }
  }

  function pulseHighlight(map, p) {
    if (!p.animateHi || !map.getLayer('hi-layer')) return;
    if (state.hiAnimTimer) clearInterval(state.hiAnimTimer);
    var startMs = Date.now();
    state.hiAnimTimer = setInterval(function() {
      if (!map.getLayer('hi-layer')) { clearInterval(state.hiAnimTimer); return; }
      var dt = Date.now() - startMs;
      if (dt < 340) {
        var t = dt / 340.0;
        var v = Math.round(61 + (255 - 61) * t);
        var g = Math.round(174 + (255 - 174) * t);
        var b = Math.round(233 + (255 - 233) * t);
        map.setPaintProperty('hi-layer', 'line-color', 'rgb(' + v + ',' + g + ',' + b + ')');
        map.setPaintProperty('hi-layer', 'line-width', 7.2 + 3.8 * t);
      } else if (dt < 720) {
        map.setPaintProperty('hi-layer', 'line-color', '#FFFFFF');
        map.setPaintProperty('hi-layer', 'line-width', 11.0);
      } else if (dt < 1340) {
        var t2 = (dt - 720) / 620.0;
        var v2 = Math.round(255 + (61 - 255) * t2);
        var g2 = Math.round(255 + (174 - 255) * t2);
        var b2 = Math.round(255 + (233 - 255) * t2);
        map.setPaintProperty('hi-layer', 'line-color', 'rgb(' + v2 + ',' + g2 + ',' + b2 + ')');
        map.setPaintProperty('hi-layer', 'line-width', 11.0 - 3.6 * t2);
      } else {
        map.setPaintProperty('hi-layer', 'line-color', '#3DAEE9');
        map.setPaintProperty('hi-layer', 'line-width', 7.5);
        clearInterval(state.hiAnimTimer);
        state.hiAnimTimer = null;
      }
    }, 32);
  }

  function computeFitCamera(bounds, w, h, padT, padB, padS, pitchDeg) {
    var minLon = bounds[0][0], minLat = bounds[0][1];
    var maxLon = bounds[1][0], maxLat = bounds[1][1];
    function mercX(lon) { return (lon + 180.0) / 360.0; }
    function mercY(lat) {
      var clamped = Math.max(-85.0, Math.min(85.0, lat));
      var rad = clamped * Math.PI / 180.0;
      return (180.0 - (180.0 / Math.PI) * Math.log(Math.tan(Math.PI / 4.0 + rad / 2.0))) / 360.0;
    }
    function invMercY(y) {
      var rad = 2.0 * Math.atan(Math.exp((180.0 - 360.0 * y) * Math.PI / 180.0)) - Math.PI / 2.0;
      return rad * 180.0 / Math.PI;
    }
    var dx = Math.max(1e-6, mercX(maxLon) - mercX(minLon));
    var dy = Math.max(1e-6, mercY(minLat) - mercY(maxLat));
    var availW = Math.max(60, w - padS * 2);
    var availH = Math.max(60, h - padT - padB);
    var zx = Math.log2(availW / (512.0 * dx));
    var zy = Math.log2(availH / (512.0 * dy));
    var zoom = Math.max(1.0, Math.min(zx, zy, 16.0)) - (pitchDeg > 5 ? 0.35 : 0.0);
    var cLon = (minLon + maxLon) / 2.0;
    var cLat = invMercY((mercY(minLat) + mercY(maxLat)) / 2.0);
    return {
      center: [cLon, cLat],
      zoom: zoom,
      pitch: pitchDeg,
      bearing: 0,
      padding: { top: padT, bottom: padB, left: padS, right: padS }
    };
  }

  function syncDataAndCamera(p, fromResize) {
    var map = state.map;
    if (!map || !state.styleReady) return;
    var el = document.getElementById('map');

    if (Math.abs((p.exag || 2.2) - state.lastExag) > 0.05) {
      try { map.setTerrain({ source: 'terrain-dem', exaggeration: p.exag || 2.2 }); state.lastExag = p.exag || 2.2; } catch (e) {}
    }
    if (map.getLayer('terrain-hillshade-layer')) {
      map.setLayoutProperty('terrain-hillshade-layer', 'visibility', p.showHillshade ? 'visible' : 'none');
    }
    if (map.getLayer('3d-buildings-layer')) {
      map.setLayoutProperty('3d-buildings-layer', 'visibility', p.showBuildings ? 'visible' : 'none');
    }

    if (p.interactive !== state.lastInteractive) {
      state.lastInteractive = p.interactive;
      var hs = ['dragPan','scrollZoom','boxZoom','dragRotate','keyboard','doubleClickZoom','touchZoomRotate'];
      for (var k = 0; k < hs.length; k++) {
        if (map[hs[k]]) {
          if (p.interactive) map[hs[k]].enable(); else map[hs[k]].disable();
        }
      }
    }

    if (map.getSource('guide-src')) map.getSource('guide-src').setData(lineFc(p.guide));
    if (map.getSource('base-src')) map.getSource('base-src').setData(lineFc(p.base));
    if (map.getSource('active-src')) map.getSource('active-src').setData(p.colorByPace ? emptyFc() : lineFc(p.active));

    if (map.getSource('pace-src')) {
      if (p.colorByPace && p.pace && p.pace.length) {
        var pf = [];
        for (var i = 0; i < p.pace.length; i++) {
          pf.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: p.pace[i].coords }, properties: { col: p.pace[i].col } });
        }
        map.getSource('pace-src').setData({ type: 'FeatureCollection', features: pf });
      } else {
        map.getSource('pace-src').setData(emptyFc());
      }
    }

    if (map.getSource('trim-src')) {
      var tf = [];
      for (var t = 0; t < (p.trim || []).length; t++) {
        if (p.trim[t].length >= 2) tf.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: p.trim[t] }, properties: {} });
      }
      map.getSource('trim-src').setData({ type: 'FeatureCollection', features: tf });
    }

    if (map.getSource('planner-pts-src')) {
      var wf = [];
      for (var w = 0; w < (p.waypoints || []).length; w++) {
        wf.push({ type: 'Feature', geometry: { type: 'Point', coordinates: [p.waypoints[w].lon, p.waypoints[w].lat] }, properties: { kind: p.waypoints[w].kind } });
      }
      map.getSource('planner-pts-src').setData({ type: 'FeatureCollection', features: wf });
    }

    if (map.getSource('user-loc-src')) map.getSource('user-loc-src').setData(p.showUserDot ? pointFc(p.userLoc) : emptyFc());
    if (map.getSource('head-src')) map.getSource('head-src').setData(p.showHead ? pointFc(p.head) : emptyFc());

    if (map.getSource('hi-src')) {
      map.getSource('hi-src').setData(lineFc(p.hi));
      if (p.hi && p.hi.length >= 2 && p.hiTrigger !== state.lastHiTrig) {
        state.lastHiTrig = p.hiTrigger;
        if (p.hiBounds && el && el.clientWidth > 0 && el.clientHeight > 0) {
          var hTop = Math.min(Math.round(p.fitTopClearCss + 14), Math.round(el.clientHeight * 0.36));
          var hBot = Math.min(Math.round((p.hiBotCss || p.fitBotClearCss) + 20), Math.round(el.clientHeight * 0.38));
          var hSide = Math.min(Math.round(p.fitSideCss + 12), Math.round(el.clientWidth * 0.22));
          var hOpts = computeFitCamera(p.hiBounds, el.clientWidth, el.clientHeight, hTop, hBot, hSide, 46);
          hOpts.duration = 520;
          map.easeTo(hOpts);
        }
        pulseHighlight(map, p);
      }
    }

    if (!el || el.clientWidth <= 0 || el.clientHeight <= 0) return;

    var modeChanged = state.lastCamMode !== '' && state.lastCamMode !== p.camMode;
    var safeTop = Math.max(12, Math.round(p.fitTopClearCss));
    var safeBot = Math.max(12, Math.round(p.fitBotClearCss));
    var maxVPadding = Math.max(40, Math.round(el.clientHeight * 0.62));
    if (safeTop + safeBot > maxVPadding) {
      var vScale = maxVPadding / (safeTop + safeBot);
      safeTop = Math.max(12, Math.round(safeTop * vScale));
      safeBot = Math.max(12, Math.round(safeBot * vScale));
    }
    var safeSide = Math.min(Math.max(12, Math.round(p.fitSideCss)), Math.round(el.clientWidth * 0.22));
    var topPad = Math.min(Math.max(0, Math.round(p.topCss)), Math.round(el.clientHeight * 0.42));
    var botPad = Math.min(Math.max(0, Math.round(p.botCss)), Math.round(el.clientHeight * 0.44));

    if (p.camMode === 'FIT_ROUTE') {
      state.lastFollowKey = '';
      if (p.bounds) {
        var fitKey = p.ptsCount + '_' + p.geoHash + '_' + p.recenter + '_' + Math.round(safeTop/12) + '_' + Math.round(safeBot/12) + '_' + Math.round(el.clientWidth/24) + '_' + Math.round(el.clientHeight/24);
        if (fitKey !== state.lastFitKey || modeChanged) {
          state.lastFitKey = fitKey;
          var fitOpts = computeFitCamera(p.bounds, el.clientWidth, el.clientHeight, safeTop, safeBot, safeSide, 48);
          map.stop();
          map.jumpTo(fitOpts);
        }
      } else {
        var fTgt = p.userLoc || p.fallback;
        var fKey = 'empty_' + fTgt[0].toFixed(5) + '_' + fTgt[1].toFixed(5) + '_' + p.recenter;
        if (fKey !== state.lastFitKey || modeChanged) {
          state.lastFitKey = fKey;
          map.jumpTo({ center: fTgt, zoom: 15.6, pitch: 48, bearing: 0, padding: { top: topPad, bottom: botPad, left: 0, right: 0 } });
        }
      }
    } else if (p.camMode === 'FOLLOW_RUNNER') {
      state.lastFitKey = '';
      var tgt = p.head || p.routeEnd || p.userLoc || p.routeStart || p.fallback;
      var flwKey = tgt[0].toFixed(6) + '_' + tgt[1].toFixed(6) + '_' + p.recenter + '_' + Math.round(topPad/12) + '_' + Math.round(botPad/12) + '_' + Math.round(p.liveHeading || 0) + '_' + Math.round(el.clientWidth/24);
      if (flwKey !== state.lastFollowKey || modeChanged) {
        var firstFlw = state.lastFollowKey === '';
        state.lastFollowKey = flwKey;
        var z = (firstFlw || modeChanged || map.getZoom() < 15.6) ? 16.4 : map.getZoom();
        var opts = {
          center: tgt,
          zoom: z,
          pitch: 56,
          bearing: p.liveHeading || 0,
          padding: { top: topPad, bottom: botPad, left: 0, right: 0 }
        };
        if (firstFlw || (!modeChanged && p.showHead)) {
          map.jumpTo(opts);
        } else {
          opts.duration = 340;
          map.easeTo(opts);
        }
      }
    } else {
      state.lastFitKey = '';
      state.lastFollowKey = '';
      if (!state.lastFreeInit) {
        state.lastFreeInit = true;
        var cTgt = p.userLoc || p.routeStart || p.fallback;
        map.jumpTo({ center: cTgt, zoom: 15.6, pitch: 48, bearing: 0, padding: { top: topPad, bottom: botPad, left: 0, right: 0 } });
      }
    }
    state.lastCamMode = p.camMode;
  }

  function initOrSwapMap(p) {
    if (!state.map) {
      state.activeStyle = p.styleUri;
      var center = p.head || p.routeStart || p.userLoc || p.fallback;
      var map = new maplibregl.Map({
        container: 'map',
        style: p.styleUri,
        center: center,
        zoom: 15.2,
        pitch: 48,
        attributionControl: false,
        preserveDrawingBuffer: true
      });
      state.map = map;
      map.on('error', function(ev) {
        if (ev && ev.error && /webgl/i.test(String(ev.error))) {
          StrideBridge.onWebGlUnsupported();
        }
      });
      map.on('webglcontextlost', function() { StrideBridge.onWebGlUnsupported(); });
      var firePan = function(e) { if (e && e.originalEvent) StrideBridge.onUserPan(); };
      map.on('dragstart', firePan);
      map.on('zoomstart', firePan);
      map.on('rotatestart', firePan);
      map.on('pitchstart', firePan);
      map.on('click', function(e) {
        if (e && e.lngLat) StrideBridge.onMapTap(e.lngLat.lat, e.lngLat.lng);
      });
      map.on('resize', function() {
        if (state.lastPayload) syncDataAndCamera(state.lastPayload, true);
      });
      map.on('style.load', function() {
        state.styleReady = true;
        var cur = state.lastPayload || p;
        ensureTerrainAndLayers(map, cur);
        syncDataAndCamera(cur, false);
      });
    } else if (state.activeStyle !== p.styleUri) {
      state.activeStyle = p.styleUri;
      state.styleReady = false;
      state.map.setStyle(p.styleUri);
    }
  }

  window.__scUpdateScene = function(jsonStr) {
    try {
      var p = JSON.parse(jsonStr);
      state.lastPayload = p;
      initOrSwapMap(p);
      if (state.styleReady) {
        syncDataAndCamera(p, false);
      }
    } catch (e) {}
  };

  StrideBridge.onMapReady();
})();
</script>
</body>
</html>
""".trimIndent()

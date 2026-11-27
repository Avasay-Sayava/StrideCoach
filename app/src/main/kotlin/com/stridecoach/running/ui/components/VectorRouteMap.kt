package com.stridecoach.running.ui.components

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.ReplayHead
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.StrideThemeState
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.hillshadeExaggeration
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineGradient
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

const val OPENFREEMAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
const val OPENFREEMAP_LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
const val OPENFREEMAP_LIBERTY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val TERRARIUM_DEM_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

fun resolveMapStyleUri(mapStyleMode: String, isLightTheme: Boolean): String = when (mapStyleMode) {
    "DARK" -> OPENFREEMAP_DARK_STYLE
    "LIGHT" -> OPENFREEMAP_LIGHT_STYLE
    "LIBERTY" -> OPENFREEMAP_LIBERTY_STYLE
    else -> if (isLightTheme) OPENFREEMAP_LIGHT_STYLE else OPENFREEMAP_DARK_STYLE
}

enum class MapCameraMode(val label: String) {
    FIT_ROUTE("Fit"),
    FOLLOW_RUNNER("Follow"),
    FREE_PAN("Free")
}

private class VectorMapHolder {
    var map: MapLibreMap? = null
    var mapView: MapView? = null
    var styleReady: Boolean = false
    var activeStyleUri: String = ""
    var lastRouteKey: String = ""
    var lastFitKey: String = ""
    var lastFollowKey: String = ""
    var lastRecenter: Int = -1
    var lastCamMode: MapCameraMode? = null
    var last3d: Boolean? = null
    var hasSeededRealUserLoc: Boolean = false
    var lastBasePtsKey: String = ""
    var lastGuideKey: String = ""
    var lastPlannerKey: String = ""
    var lastUserLocKey: String = ""
    var lastTrimKey: String = ""
    var lastHiKey: String = ""
    var lastHiTrig: Int = -1
    var hasHiActive: Boolean = false
    var hiAnimUntilMs: Long = 0L
    var lastResetNorth: Int = -1
    var lastBearingDeg: Float = -999f
    var lastPreload: Boolean? = null
    var lastPreloadKey: String = ""
    var lastPaceRenderKey: String = ""
    var lastPaceOpacity: Float = -1f
    var lastBottomOccPx: Int = -1
    var lastHeadKey: String = ""
    var lastNorthLocked: Boolean? = null
    var smoothFollowBearing: Double? = null
    val sliceScratch = ArrayList<Point>(512)
    var activeHiAnim: ValueAnimator? = null
    val mainHandler = Handler(Looper.getMainLooper())
    var pendingFadeRunnable: Runnable? = null

    var syncAction: (() -> Unit)? = null
}

@Composable
fun VectorRouteMap(
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
    is3dMode: Boolean = false,
    show3dBuildings: Boolean = true,
    showTerrainHillshade: Boolean = true,
    physical3dTerrainMesh: Boolean = true,
    terrainExaggeration: Float = 2.2f,
    keepCenteredOnSheetDrag: Boolean = true,
    lockAutoFit: Boolean = false,
    preloadTiles: Boolean = true,
    mapStyleMode: String = "MATCH_THEME",
    liveHeadingDeg: Float? = null,
    fallbackCenter: LatLon = LatLon(32.1133, 34.8044),
    topOcclusionPx: Int = 0,
    bottomOcclusionPx: Int = 0,
    highlightBottomOcclusionPx: Int = 0,
    bottomPadDp: Int = 180,
    topHudDp: Int = 128,
    recenterToken: Int = 0,
    playing: Boolean = false,
    playbackRatePerSec: Float = 0f,
    northLockedInFollow: Boolean = false,
    resetNorthToken: Int = 0,
    onUserManualPan: () -> Unit = {},
    onMapTap: ((LatLon) -> Unit)? = null,
    onBearingChanged: ((Float) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val tapCb = rememberUpdatedState(onMapTap)
    val panCb = rememberUpdatedState(onUserManualPan)
    val brgCb = rememberUpdatedState(onBearingChanged)
    val density = LocalDensity.current
    val staticBottomPx = with(density) { bottomPadDp.dp.roundToPx() }
    val topHudPx = with(density) { topHudDp.dp.roundToPx() }
    val effectiveTopPx = if (topOcclusionPx > 0) topOcclusionPx else topHudPx
    val effectiveBottomPx = if (keepCenteredOnSheetDrag && bottomOcclusionPx > 0) bottomOcclusionPx else staticBottomPx
    val fitTopClearPx = effectiveTopPx + with(density) { 24.dp.roundToPx() }
    val fitBottomClearPx = effectiveBottomPx + with(density) { 24.dp.roundToPx() }
    val fitSidePx = with(density) { 36.dp.roundToPx() }
    val effectiveHiBottomPx = if (highlightBottomOcclusionPx > 0) highlightBottomOcclusionPx else effectiveBottomPx

    var webGlUnsupported by remember { mutableStateOf(false) }
    if (is3dMode && physical3dTerrainMesh && !webGlUnsupported) {
        Terrain3DWebMap(
            pts = pts,
            modifier = modifier,
            replayHead = replayHead,
            guidePts = guidePts,
            plannerWaypoints = plannerWaypoints,
            userLocation = userLocation,
            interactive = interactive,
            showHead = showHead,
            showUserDot = showUserDot,
            colorByPace = colorByPace,
            smoothGradient = smoothGradient,
            refPaceSec = refPaceSec,
            highlightRange = highlightRange,
            highlightTrigger = highlightTrigger,
            animateHighlight = animateHighlight,
            trimStartIdx = trimStartIdx,
            trimEndIdx = trimEndIdx,
            cameraMode = cameraMode,
            show3dBuildings = show3dBuildings,
            showTerrainHillshade = showTerrainHillshade,
            terrainExaggeration = terrainExaggeration,
            lockAutoFit = lockAutoFit,
            mapStyleMode = mapStyleMode,
            liveHeadingDeg = liveHeadingDeg,
            fallbackCenter = fallbackCenter,
            effectiveTopPx = effectiveTopPx,
            effectiveBottomPx = effectiveBottomPx,
            fitTopClearPx = fitTopClearPx,
            fitBottomClearPx = fitBottomClearPx,
            fitSidePx = fitSidePx,
            hiBottomPx = effectiveHiBottomPx,
            recenterToken = recenterToken,
            playing = playing,
            playbackRatePerSec = playbackRatePerSec,
            northLockedInFollow = northLockedInFollow,
            resetNorthToken = resetNorthToken,
            onUserManualPan = onUserManualPan,
            onMapTap = onMapTap,
            onWebGlUnsupported = { webGlUnsupported = true },
            onBearingChanged = onBearingChanged
        )
        return
    }

    val styleUri = resolveMapStyleUri(mapStyleMode, StrideThemeState.isLight)
    val bgArgb = KdeBg.toArgb()
    val prep = remember(pts, refPaceSec, smoothGradient) { prepareRoute(pts, refPaceSec, smoothGradient) }
    val validGuide = remember(guidePts) { guidePts.filter { isValidCoord(it.latitude, it.longitude) } }
    val validWps = remember(plannerWaypoints) { plannerWaypoints.filter { isValidCoord(it.latitude, it.longitude) } }

    val mv = remember(ctx) {
        MapTileCacheManager.configureAmbientCache(ctx)
        val opts = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            .setPrefetchesTiles(true)
            .setPrefetchZoomDelta(4)
            .foregroundLoadColor(bgArgb)
        MapView(ctx, opts).apply { onCreate(null) }
    }
    val holder = remember(mv) { VectorMapHolder().apply { mapView = mv } }
    holder.syncAction = {
        if (holder.styleReady && holder.map != null) {
            syncVectorScene(
                ctx, holder, prep, replayHead, validGuide, validWps, userLocation,
                interactive, showHead, showUserDot, colorByPace,
                highlightRange, highlightTrigger, animateHighlight, trimStartIdx, trimEndIdx,
                cameraMode, is3dMode, show3dBuildings, showTerrainHillshade, keepCenteredOnSheetDrag,
                lockAutoFit, preloadTiles, styleUri, liveHeadingDeg, fallbackCenter,
                effectiveTopPx, effectiveBottomPx, fitTopClearPx, fitBottomClearPx, fitSidePx,
                effectiveHiBottomPx, recenterToken, northLockedInFollow, resetNorthToken
            )
        }
    }

    remember(mv) {
        mv.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
            if (sizeChanged && v.width > 0 && v.height > 0 && holder.styleReady) {
                v.post { holder.syncAction?.invoke() }
            }
        }
        mv.getMapAsync { ml ->
            holder.map = ml
            holder.lastPreload = preloadTiles
            ml.setPrefetchesTiles(preloadTiles)
            ml.setPrefetchZoomDelta(if (preloadTiles) 4 else 0)
            ml.uiSettings.apply {
                isScrollGesturesEnabled = interactive
                isZoomGesturesEnabled = interactive
                isRotateGesturesEnabled = interactive
                isTiltGesturesEnabled = is3dMode && interactive
                isCompassEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
            }
            ml.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && holder.styleReady) {
                    panCb.value()
                }
            }
            ml.addOnCameraMoveListener {
                val deg = ml.cameraPosition.bearing.toFloat()
                if (kotlin.math.abs(deg - holder.lastBearingDeg) >= 1.5f) {
                    holder.lastBearingDeg = deg
                    brgCb.value?.invoke(deg)
                }
            }
            ml.addOnMapClickListener { ll ->
                val cb = tapCb.value ?: return@addOnMapClickListener false
                cb(LatLon(ll.latitude, ll.longitude))
                true
            }
            holder.activeStyleUri = styleUri
            ml.setStyle(Style.Builder().fromUri(styleUri)) { st ->
                installVectorLayers(st)
                holder.styleReady = true
                holder.syncAction?.invoke()
            }
        }
        true
    }

    DisposableEffect(owner, mv) {
        val stNow = owner.lifecycle.currentState
        if (stNow.isAtLeast(Lifecycle.State.STARTED)) mv.onStart()
        if (stNow.isAtLeast(Lifecycle.State.RESUMED)) mv.onResume()
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            holder.activeHiAnim?.cancel()
            holder.pendingFadeRunnable?.let { holder.mainHandler.removeCallbacks(it) }
            owner.lifecycle.removeObserver(obs)
            runCatching { mv.onPause() }
            runCatching { mv.onStop() }
            runCatching { mv.onDestroy() }
        }
    }

    AndroidView(
        factory = { mv },
        update = {
            val ml = holder.map ?: return@AndroidView
            if (holder.activeStyleUri != styleUri) {
                holder.activeStyleUri = styleUri
                holder.styleReady = false
                holder.lastBasePtsKey = ""
                holder.lastGuideKey = ""
                holder.lastPlannerKey = ""
                holder.lastUserLocKey = ""
                holder.lastTrimKey = ""
                holder.lastHiKey = ""
                holder.lastPaceRenderKey = ""
                holder.lastPaceOpacity = -1f
                holder.lastHeadKey = ""
                ml.setStyle(Style.Builder().fromUri(styleUri)) { st ->
                    installVectorLayers(st)
                    holder.styleReady = true
                    holder.syncAction?.invoke()
                }
            } else if (holder.styleReady) {
                if (holder.lastPreload != preloadTiles) {
                    holder.lastPreload = preloadTiles
                    ml.setPrefetchesTiles(preloadTiles)
                    ml.setPrefetchZoomDelta(if (preloadTiles) 4 else 0)
                }
                syncVectorScene(
                    ctx, holder, prep, replayHead, validGuide, validWps, userLocation,
                    interactive, showHead, showUserDot, colorByPace,
                    highlightRange, highlightTrigger, animateHighlight, trimStartIdx, trimEndIdx,
                    cameraMode, is3dMode, show3dBuildings, showTerrainHillshade, keepCenteredOnSheetDrag,
                    lockAutoFit, preloadTiles, styleUri, liveHeadingDeg, fallbackCenter,
                    effectiveTopPx, effectiveBottomPx, fitTopClearPx, fitBottomClearPx, fitSidePx,
                    effectiveHiBottomPx, recenterToken, northLockedInFollow, resetNorthToken
                )
            }
        },
        modifier = modifier
    )
}

private fun installVectorLayers(st: Style) {
    st.transition = TransitionOptions(0L, 0L, false)
    installTerrainAndBuildings(st)
    installRouteLineLayers(st)
    installMarkerLayers(st)
}

private fun installTerrainAndBuildings(st: Style) {
    val demTs = TileSet("2.2.0", TERRARIUM_DEM_TILES).apply {
        encoding = "terrarium"
        minZoom = 0f
        maxZoom = 14f
    }
    st.addSource(RasterDemSource("terrain-dem-src", demTs, 256))
    val hs = HillshadeLayer("terrain-hillshade-layer", "terrain-dem-src").withProperties(
        hillshadeExaggeration(0.28f),
        visibility(Property.NONE)
    )
    when {
        st.getLayer("building") != null -> st.addLayerBelow(hs, "building")
        st.getLayer("water_name") != null -> st.addLayerBelow(hs, "water_name")
        else -> st.addLayer(hs)
    }

    if (st.getSource("openmaptiles") == null) return
    val bld = FillExtrusionLayer("3d-buildings-layer", "openmaptiles").apply {
        sourceLayer = "building"
        minZoom = 13.8f
        setProperties(
            fillExtrusionHeight(Expression.coalesce(Expression.get("render_height"), Expression.literal(8))),
            fillExtrusionBase(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))),
            fillExtrusionColor(Color.parseColor("#2A303B")),
            fillExtrusionOpacity(0.82f),
            visibility(Property.NONE)
        )
    }
    if (st.getLayer("water_name") != null) st.addLayerBelow(bld, "water_name") else st.addLayer(bld)
}

private fun installRouteLineLayers(st: Style) {
    val srcs = listOf("guide-src", "base-src", "active-src", "trim-src", "hi-src", "planner-pts-src", "user-loc-src", "head-src")
    srcs.forEach { st.addSource(GeoJsonSource(it)) }
    st.addSource(GeoJsonSource("pace-src", GeoJsonOptions().withLineMetrics(true)))

    st.addLayer(
        LineLayer("guide-layer", "guide-src").withProperties(
            lineColor(Color.parseColor("#3DAEE9")),
            lineWidth(3.6f),
            lineDasharray(arrayOf(2.2f, 1.8f)),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    st.addLayer(baseLineLayer("base-layer", "base-src", "#473C69", 4.0f))
    st.addLayer(
        LineLayer("hi-layer", "hi-src").withProperties(
            lineColor(Color.parseColor("#256F96")),
            lineWidth(10.5f),
            lineOpacity(1.0f),
            lineBlur(0.0f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    st.addLayer(
        LineLayer("pace-layer", "pace-src").withProperties(
            lineColor(Color.parseColor("#926EE4")),
            lineWidth(5.4f),
            lineCap(Property.LINE_CAP_BUTT),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    st.addLayer(baseLineLayer("active-layer", "active-src", "#926EE4", 5.5f))
    st.addLayer(baseLineLayer("trim-layer", "trim-src", "#F87171", 5.4f))
}

private fun installMarkerLayers(st: Style) {
    st.addLayer(
        CircleLayer("planner-pts-layer", "planner-pts-src").withProperties(
            circleRadius(Expression.match(Expression.get("kind"), Expression.literal(6.0f), Expression.stop("start", 8.5f), Expression.stop("end", 8.5f))),
            circleColor(Expression.match(Expression.get("kind"), Expression.literal("#926EE4"), Expression.stop("start", "#4ADE80"), Expression.stop("end", "#F87171"))),
            circleStrokeColor(Color.WHITE),
            circleStrokeWidth(2.2f)
        )
    )
    addCircleDotTriplet(st, "user-loc", 14f, "#3DAEE9", 0.28f, 7.8f, 5.2f)
    addCircleDotTriplet(st, "head", 13f, "#926EE4", 0.32f, 7.5f, 4.8f)
}

private fun addCircleDotTriplet(
    st: Style,
    prefix: String,
    haloR: Float,
    hex: String,
    haloAlpha: Float,
    ringR: Float,
    coreR: Float
) {
    val src = "$prefix-src"
    val col = Color.parseColor(hex)
    st.addLayer(CircleLayer("$prefix-halo", src).withProperties(circleRadius(haloR), circleColor(col), circleOpacity(haloAlpha)))
    st.addLayer(CircleLayer("$prefix-ring", src).withProperties(circleRadius(ringR), circleColor(Color.WHITE)))
    st.addLayer(CircleLayer("$prefix-core", src).withProperties(circleRadius(coreR), circleColor(col)))
}

private fun baseLineLayer(id: String, src: String, hex: String, width: Float): LineLayer =
    LineLayer(id, src).withProperties(
        lineColor(Color.parseColor(hex)),
        lineWidth(width),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND)
    )

private fun syncVectorScene(
    ctx: android.content.Context,
    h: VectorMapHolder,
    prep: PreparedRoute,
    head: ReplayHead?,
    validGuide: List<LatLon>,
    validWaypoints: List<LatLon>,
    userLoc: LatLon?,
    interactive: Boolean,
    showHead: Boolean,
    showUserDot: Boolean,
    colorByPace: Boolean,
    hiRange: IntRange?,
    hiTrigger: Int,
    animateHighlight: Boolean,
    trimStartIdx: Int?,
    trimEndIdx: Int?,
    cameraMode: MapCameraMode,
    is3dMode: Boolean,
    show3dBuildings: Boolean,
    showTerrainHillshade: Boolean,
    keepCenteredOnSheetDrag: Boolean,
    lockAutoFit: Boolean,
    preloadTiles: Boolean,
    styleUri: String,
    liveHeadingDeg: Float?,
    fallbackCenter: LatLon,
    effectiveTopPx: Int,
    bottomOcclusionPx: Int,
    fitTopClearPx: Int,
    fitBottomClearPx: Int,
    fitSidePx: Int,
    hiBottomOcclusionPx: Int,
    recenter: Int,
    northLocked: Boolean,
    resetNorth: Int
) {
    val ml = h.map ?: return
    val st = ml.style ?: return

    ml.uiSettings.isTiltGesturesEnabled = is3dMode && interactive
    st.getLayer("3d-buildings-layer")?.setProperties(
        visibility(if (is3dMode && show3dBuildings) Property.VISIBLE else Property.NONE)
    )
    st.getLayer("terrain-hillshade-layer")?.setProperties(
        visibility(if (is3dMode && showTerrainHillshade) Property.VISIBLE else Property.NONE)
    )

    if (resetNorth != h.lastResetNorth) {
        val hadNorth = h.lastResetNorth != -1
        h.lastResetNorth = resetNorth
        if (hadNorth) {
            val cur = ml.cameraPosition
            ml.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cur).bearing(0.0).build()), 340)
        }
    }

    val nowUptime = android.os.SystemClock.uptimeMillis()
    if (cameraMode == MapCameraMode.FREE_PAN && keepCenteredOnSheetDrag && h.lastCamMode == MapCameraMode.FREE_PAN && h.lastBottomOccPx >= 0 && nowUptime >= h.hiAnimUntilMs) {
        val delta = bottomOcclusionPx - h.lastBottomOccPx
        if (kotlin.math.abs(delta) >= 8) {
            ml.scrollBy(0f, -delta / 2f)
        }
    }
    h.lastBottomOccPx = bottomOcclusionPx

    val validPts = prep.pts
    val geoHash = prep.geoHash

    syncGuide(h, st, validGuide)
    syncPlannerWaypoints(h, st, validWaypoints)
    syncUserDot(h, st, userLoc, showUserDot)
    syncBase(h, st, validPts, geoHash)
    syncActiveAndPace(h, st, prep, head, colorByPace)
    syncTrimSlices(h, st, validPts, trimStartIdx, trimEndIdx)
    syncHiSlice(ml, h, st, validPts, hiRange, hiTrigger, animateHighlight, is3dMode, effectiveTopPx, hiBottomOcclusionPx)
    syncHead(h, st, validPts, head, showHead)
    updateCamera(
        ml, h, validPts, validGuide, geoHash, userLoc, fallbackCenter, head, cameraMode, is3dMode, lockAutoFit,
        liveHeadingDeg, effectiveTopPx, bottomOcclusionPx, fitTopClearPx, fitBottomClearPx, fitSidePx, recenter, northLocked
    )

    val preloadKey = "${geoHash}_$styleUri"
    if (preloadTiles && preloadKey != h.lastPreloadKey) {
        h.lastPreloadKey = preloadKey
        val seed = validPts.firstOrNull()?.let { LatLon(it.latitude, it.longitude) }
            ?: validGuide.firstOrNull()
            ?: userLoc
        if (seed != null) {
            MapTileCacheManager.preloadAround(ctx, seed.latitude, seed.longitude, styleUri)
        }
    }
}

private fun syncGuide(h: VectorMapHolder, st: Style, guidePts: List<LatLon>) {
    val k = "${guidePts.size}_${guidePts.lastOrNull()?.latitude}_${guidePts.lastOrNull()?.longitude}"
    if (k == h.lastGuideKey) return
    h.lastGuideKey = k
    val fc = if (guidePts.size >= 2) {
        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(guidePts.map { Point.fromLngLat(it.longitude, it.latitude) })))
    } else {
        FeatureCollection.fromFeatures(emptyArray())
    }
    st.getSourceAs<GeoJsonSource>("guide-src")?.setGeoJson(fc)
}

private fun syncPlannerWaypoints(h: VectorMapHolder, st: Style, pts: List<LatLon>) {
    val k = "${pts.size}_${pts.firstOrNull()?.latitude}_${pts.lastOrNull()?.longitude}"
    if (k == h.lastPlannerKey) return
    h.lastPlannerKey = k
    val src = st.getSourceAs<GeoJsonSource>("planner-pts-src") ?: return
    if (pts.isEmpty()) {
        src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        return
    }
    val feats = pts.mapIndexed { idx, p ->
        val kind = when {
            idx == 0 -> "start"
            idx == pts.lastIndex && pts.size > 1 -> "end"
            else -> "mid"
        }
        Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
            addStringProperty("kind", kind)
        }
    }
    src.setGeoJson(FeatureCollection.fromFeatures(feats))
}

private fun syncUserDot(h: VectorMapHolder, st: Style, loc: LatLon?, show: Boolean) {
    val k = if (show && loc != null) "${loc.latitude}_${loc.longitude}" else "off"
    if (k == h.lastUserLocKey) return
    h.lastUserLocKey = k
    val src = st.getSourceAs<GeoJsonSource>("user-loc-src") ?: return
    val fc = if (show && loc != null) {
        FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude)))
    } else {
        FeatureCollection.fromFeatures(emptyArray())
    }
    src.setGeoJson(fc)
}

internal fun computePtsGeoHash(pts: List<TrackPoint>): Int {
    var h = pts.size
    for (p in pts) {
        h = h * 31 + p.latitude.hashCode()
        h = h * 31 + p.longitude.hashCode()
    }
    return h
}

private fun syncBase(h: VectorMapHolder, st: Style, pts: List<TrackPoint>, geoHash: Int) {
    val k = "${pts.size}_$geoHash"
    if (k == h.lastBasePtsKey) return
    h.lastBasePtsKey = k
    val fc = if (pts.size >= 2) {
        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) })))
    } else {
        FeatureCollection.fromFeatures(emptyArray())
    }
    st.getSourceAs<GeoJsonSource>("base-src")?.setGeoJson(fc)
    st.getSourceAs<GeoJsonSource>("pace-src")?.setGeoJson(fc)
    st.getSourceAs<GeoJsonSource>("active-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
}

private fun syncActiveAndPace(
    h: VectorMapHolder,
    st: Style,
    prep: PreparedRoute,
    head: ReplayHead?,
    colorByPace: Boolean
) {
    val layer = st.getLayerAs<LineLayer>("pace-layer") ?: return
    val src = st.getSourceAs<GeoJsonSource>("pace-src") ?: return
    val frac = headProgressFrac(prep, head)
    if (prep.pts.size < 2 || frac <= 0.0005) {
        if (h.lastPaceOpacity != 0f) {
            h.lastPaceOpacity = 0f
            layer.setProperties(lineOpacity(0f))
        }
        h.lastPaceRenderKey = "${prep.geoHash}_off"
        return
    }
    val stopSig = "${prep.stops.size}_${prep.stops.firstOrNull()?.hex}_${prep.stops.lastOrNull()?.hex}"
    if (head == null || frac >= 0.999) {
        val fullKey = "${prep.geoHash}_full_${colorByPace}_$stopSig"
        if (fullKey == h.lastPaceRenderKey) return
        h.lastPaceRenderKey = fullKey
        src.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(prep.mapPoints))))
        if (h.lastPaceOpacity != 1f) {
            h.lastPaceOpacity = 1f
            layer.setProperties(lineOpacity(1f))
        }
        layer.setProperties(lineGradient(buildPaceGradientExpr(prep.stops, 1.0, colorByPace)))
        return
    }
    val slice = slicePointsToHead(h.sliceScratch, prep.mapPoints, head)
    if (slice.size < 2) {
        if (h.lastPaceOpacity != 0f) {
            h.lastPaceOpacity = 0f
            layer.setProperties(lineOpacity(0f))
        }
        return
    }
    val liveKey = "${prep.geoHash}_${colorByPace}_${stopSig}_${head.lat}_${head.lon}"
    if (liveKey == h.lastPaceRenderKey) return
    h.lastPaceRenderKey = liveKey
    src.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(slice))))
    if (h.lastPaceOpacity != 1f) {
        h.lastPaceOpacity = 1f
        layer.setProperties(lineOpacity(1f))
    }
    layer.setProperties(lineGradient(buildPaceGradientExpr(prep.stops, frac, colorByPace)))
}

private fun slicePointsToHead(out: ArrayList<Point>, pts: List<Point>, head: ReplayHead): List<Point> {
    val lastIdx = head.baseIdx.coerceIn(0, pts.lastIndex)
    out.clear()
    for (i in 0..lastIdx) out.add(pts[i])
    val last = out.last()
    if (kotlin.math.abs(head.lon - last.longitude()) + kotlin.math.abs(head.lat - last.latitude()) > 1e-8) {
        out.add(Point.fromLngLat(head.lon, head.lat))
    }
    return out
}

private fun buildPaceGradientExpr(stops: List<PaceStop>, frac: Double, colorByPace: Boolean): Expression {
    if (!colorByPace || stops.isEmpty()) {
        val purple = Expression.color(Color.parseColor("#926EE4"))
        return Expression.interpolate(Expression.linear(), Expression.lineProgress(), Expression.stop(0.0, purple), Expression.stop(1.0, purple))
    }
    val exprStops = ArrayList<Expression.Stop>(stops.size + 2)
    val span = frac.coerceIn(0.0005, 1.0)
    var prevT = -1.0
    for (s in stops) {
        if (s.frac >= span - 1e-5) break
        val normT = (s.frac / span).coerceIn(0.0, 0.999)
        if (normT > prevT + 0.0002) {
            exprStops.add(Expression.stop(normT, Expression.color(Color.parseColor(s.hex))))
            prevT = normT
        }
    }
    if (exprStops.isEmpty()) {
        exprStops.add(Expression.stop(0.0, Expression.color(Color.parseColor(stops[0].hex))))
    }
    exprStops.add(Expression.stop(1.0, Expression.color(interpolateStopColor(stops, span))))
    return Expression.interpolate(Expression.linear(), Expression.lineProgress(), *exprStops.toTypedArray())
}

private fun interpolateStopColor(stops: List<PaceStop>, frac: Double): Int {
    if (frac <= stops[0].frac) return Color.parseColor(stops[0].hex)
    val last = stops.last()
    if (frac >= last.frac) return Color.parseColor(last.hex)
    for (i in 1 until stops.size) {
        if (frac <= stops[i].frac) {
            val a = stops[i - 1]
            val b = stops[i]
            val t = ((frac - a.frac) / (b.frac - a.frac).coerceAtLeast(1e-6)).toFloat()
            return blendArgb(Color.parseColor(a.hex), Color.parseColor(b.hex), t)
        }
    }
    return Color.parseColor(last.hex)
}

private fun syncTrimSlices(
    h: VectorMapHolder,
    st: Style,
    pts: List<TrackPoint>,
    startIdx: Int?,
    endIdx: Int?
) {
    val k = "${startIdx}_${endIdx}_${pts.size}"
    if (h.lastTrimKey == k) return
    h.lastTrimKey = k
    val src = st.getSourceAs<GeoJsonSource>("trim-src") ?: return
    if (pts.size < 2) {
        src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        return
    }
    val feats = mutableListOf<Feature>()
    if (startIdx != null && startIdx >= 1) {
        val e = startIdx.coerceAtMost(pts.lastIndex)
        val line = (0..e).map { i -> Point.fromLngLat(pts[i].longitude, pts[i].latitude) }
        feats.add(Feature.fromGeometry(LineString.fromLngLats(line)))
    }
    if (endIdx != null && endIdx < pts.lastIndex) {
        val s = endIdx.coerceAtLeast(0)
        val line = (s..pts.lastIndex).map { i -> Point.fromLngLat(pts[i].longitude, pts[i].latitude) }
        feats.add(Feature.fromGeometry(LineString.fromLngLats(line)))
    }
    src.setGeoJson(FeatureCollection.fromFeatures(feats))
}

private fun syncHiSlice(
    ml: MapLibreMap,
    h: VectorMapHolder,
    st: Style,
    pts: List<TrackPoint>,
    range: IntRange?,
    trigger: Int,
    animateHighlight: Boolean,
    is3dMode: Boolean,
    topHudPx: Int,
    bottomOcclusionPx: Int
) {
    val k = "${range?.first}_${range?.last}_${pts.size}_$trigger"
    if (h.lastHiKey == k) return
    h.lastHiKey = k
    val src = st.getSourceAs<GeoJsonSource>("hi-src") ?: return
    val hiLayer = st.getLayerAs<LineLayer>("hi-layer")
    if (range == null || pts.size < 2 || range.last - range.first < 1) {
        if (h.hasHiActive && hiLayer != null) {
            h.hasHiActive = false
            fadeOutHighlight2d(h, src, hiLayer)
        } else {
            h.hasHiActive = false
            h.activeHiAnim?.cancel()
            h.pendingFadeRunnable?.let { h.mainHandler.removeCallbacks(it) }
            src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
        return
    }
    h.hasHiActive = true
    h.activeHiAnim?.cancel()
    h.pendingFadeRunnable?.let { h.mainHandler.removeCallbacks(it) }
    hiLayer?.setProperties(lineColor(Color.parseColor("#256F96")), lineWidth(10.5f), lineOpacity(1.0f))

    val s = range.first.coerceIn(0, pts.lastIndex)
    val e = range.last.coerceIn(s, pts.lastIndex)
    val slice = (s..e).map { pts[it] }
    val line = slice.map { Point.fromLngLat(it.longitude, it.latitude) }
    src.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line))))

    if (trigger != h.lastHiTrig) {
        h.lastHiTrig = trigger
        h.hiAnimUntilMs = android.os.SystemClock.uptimeMillis() + 680L
        val bounds = LatLngBounds.Builder().apply {
            slice.forEach { include(LatLng(it.latitude, it.longitude)) }
        }.build()
        val mvW = h.mapView?.width ?: 1080
        val mvH = h.mapView?.height ?: 1920
        val safeTop = (topHudPx + 40).coerceIn(24, (mvH * 0.42f).toInt().coerceAtLeast(32))
        val safeBot = (bottomOcclusionPx + 40).coerceIn(24, (mvH * 0.58f).toInt().coerceAtLeast(32))
        val safeSide = 72.coerceIn(24, (mvW * 0.22f).toInt().coerceAtLeast(24))
        ml.setPadding(0, 0, 0, 0)
        val cam2d = ml.getCameraForLatLngBounds(bounds, intArrayOf(safeSide, safeTop, safeSide, safeBot), 0.0, 0.0)
        val cam = cam2d?.let {
            if (is3dMode) {
                CameraPosition.Builder()
                    .target(it.target)
                    .zoom((it.zoom - 0.35).coerceAtLeast(1.0))
                    .tilt(46.0)
                    .bearing(0.0)
                    .padding(safeSide.toDouble(), safeTop.toDouble(), safeSide.toDouble(), safeBot.toDouble())
                    .build()
            } else {
                CameraPosition.Builder(it)
                    .padding(safeSide.toDouble(), safeTop.toDouble(), safeSide.toDouble(), safeBot.toDouble())
                    .build()
            }
        }

        if (animateHighlight && hiLayer != null) {
            startHighlightPulseIn(h, hiLayer)
        }
        if (cam != null) {
            ml.animateCamera(
                CameraUpdateFactory.newCameraPosition(cam),
                560,
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() {
                        if (animateHighlight && hiLayer != null) scheduleHighlightPulseOut(h, hiLayer)
                    }

                    override fun onCancel() {
                        if (animateHighlight && hiLayer != null) scheduleHighlightPulseOut(h, hiLayer)
                    }
                }
            )
        } else if (animateHighlight && hiLayer != null) {
            scheduleHighlightPulseOut(h, hiLayer)
        }
    }
}

private fun startHighlightPulseIn(h: VectorMapHolder, layer: LineLayer) {
    h.activeHiAnim?.cancel()
    h.pendingFadeRunnable?.let { h.mainHandler.removeCallbacks(it) }
    val cBase = Color.parseColor("#256F96")
    val cPeak = Color.parseColor("#7DD3FC")
    h.activeHiAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 320L
        addUpdateListener { va ->
            val t = va.animatedValue as Float
            val col = blendArgb(cBase, cPeak, t)
            layer.setProperties(lineColor(col), lineWidth(10.5f + 3.0f * t), lineOpacity(1.0f))
        }
        start()
    }
}

private fun scheduleHighlightPulseOut(h: VectorMapHolder, layer: LineLayer) {
    h.pendingFadeRunnable?.let { h.mainHandler.removeCallbacks(it) }
    val cPeak = Color.parseColor("#7DD3FC")
    val cBase = Color.parseColor("#256F96")
    val r = Runnable {
        h.activeHiAnim?.cancel()
        h.activeHiAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                val col = blendArgb(cPeak, cBase, t)
                layer.setProperties(lineColor(col), lineWidth(13.5f - 3.0f * t), lineOpacity(1.0f))
            }
            start()
        }
    }
    h.pendingFadeRunnable = r
    h.mainHandler.postDelayed(r, 380L)
}

private fun blendArgb(a: Int, b: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt()
    val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt()
    val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    return Color.rgb(r, g, bl)
}

private fun fadeOutHighlight2d(h: VectorMapHolder, src: GeoJsonSource, layer: LineLayer) {
    h.activeHiAnim?.cancel()
    h.pendingFadeRunnable?.let { h.mainHandler.removeCallbacks(it) }
    h.activeHiAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 260L
        addUpdateListener { va ->
            val t = va.animatedValue as Float
            layer.setProperties(lineOpacity(1.0f - t), lineWidth(10.5f - 4.0f * t))
            if (t >= 0.99f) {
                src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                layer.setProperties(lineColor(Color.parseColor("#256F96")), lineWidth(10.5f), lineOpacity(1.0f))
            }
        }
        start()
    }
}

private fun syncHead(h: VectorMapHolder, st: Style, pts: List<TrackPoint>, head: ReplayHead?, showHead: Boolean) {
    val hk = if (!showHead || pts.isEmpty()) "off" else "${head?.lat ?: pts.last().latitude}_${head?.lon ?: pts.last().longitude}"
    if (hk == h.lastHeadKey) return
    h.lastHeadKey = hk
    val src = st.getSourceAs<GeoJsonSource>("head-src") ?: return
    if (!showHead || pts.isEmpty()) {
        src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        return
    }
    val pt = if (head != null) {
        Point.fromLngLat(head.lon, head.lat)
    } else {
        Point.fromLngLat(pts.last().longitude, pts.last().latitude)
    }
    src.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(pt)))
}

private fun updateCamera(
    ml: MapLibreMap,
    h: VectorMapHolder,
    pts: List<TrackPoint>,
    guidePts: List<LatLon>,
    geoHash: Int,
    userLoc: LatLon?,
    fallbackCenter: LatLon,
    head: ReplayHead?,
    mode: MapCameraMode,
    is3d: Boolean,
    lockAutoFit: Boolean,
    liveHeading: Float?,
    effectiveTopPx: Int,
    bottomOcclusionPx: Int,
    fitTopClearPx: Int,
    fitBottomClearPx: Int,
    fitSidePx: Int,
    recenter: Int,
    northLocked: Boolean
) {
    val mvW = h.mapView?.width ?: 0
    val mvH = h.mapView?.height ?: 0
    if (mvW <= 0 || mvH <= 0) return

    val routePts = if (pts.isNotEmpty()) pts.map { LatLon(it.latitude, it.longitude) } else guidePts
    val toggle3dChanged = h.last3d != null && h.last3d != is3d
    val modeChanged = h.lastCamMode != null && h.lastCamMode != mode
    if (userLoc != null) h.hasSeededRealUserLoc = true

    when (mode) {
        MapCameraMode.FIT_ROUTE -> {
            h.lastFollowKey = ""
            if (routePts.isNotEmpty()) {
                applyFitRouteCamera(
                    ml, h, routePts, geoHash, head, is3d, lockAutoFit,
                    modeChanged, toggle3dChanged, mvW, mvH,
                    fitTopClearPx, fitBottomClearPx, fitSidePx, recenter
                )
            } else {
                applyEmptyFitCamera(
                    ml, h, userLoc ?: fallbackCenter, is3d, modeChanged, toggle3dChanged,
                    effectiveTopPx, bottomOcclusionPx, recenter
                )
            }
        }
        MapCameraMode.FOLLOW_RUNNER -> applyFollowRunnerCamera(
            ml, h, pts, guidePts, routePts, geoHash, userLoc, fallbackCenter, head,
            is3d, modeChanged, toggle3dChanged, liveHeading, mvW, mvH,
            effectiveTopPx, bottomOcclusionPx, recenter, northLocked
        )
        MapCameraMode.FREE_PAN -> applyFreePanCamera(
            ml, h, guidePts, routePts, geoHash, userLoc, fallbackCenter,
            is3d, toggle3dChanged, effectiveTopPx, bottomOcclusionPx, recenter
        )
    }

    h.last3d = is3d
    h.lastCamMode = mode
}

private fun applyFitRouteCamera(
    ml: MapLibreMap,
    h: VectorMapHolder,
    routePts: List<LatLon>,
    geoHash: Int,
    head: ReplayHead?,
    is3d: Boolean,
    lockAutoFit: Boolean,
    modeChanged: Boolean,
    toggle3dChanged: Boolean,
    mvW: Int,
    mvH: Int,
    fitTopClearPx: Int,
    fitBottomClearPx: Int,
    fitSidePx: Int,
    recenter: Int
) {
    val rKey = if (head != null) h.lastRouteKey.ifEmpty { "${routePts.size}_$geoHash" } else "${routePts.size}_$geoHash"
    val fitKey = "${rKey}_${is3d}_${recenter}_${fitTopClearPx / 16}_${fitBottomClearPx / 16}_${mvW / 32}_${mvH / 32}"
    val keyDirty = fitKey != h.lastFitKey || modeChanged || toggle3dChanged
    if (!keyDirty) return
    val allowed = !lockAutoFit || h.lastFitKey.isEmpty() || modeChanged || toggle3dChanged || recenter != h.lastRecenter
    if (!allowed) return

    val hadPriorFit = h.lastFitKey.isNotEmpty()
    h.lastRouteKey = rKey
    h.lastFitKey = fitKey
    h.lastRecenter = recenter
    ml.setPadding(0, 0, 0, 0)

    if (routePts.size < 2) {
        val pos = CameraPosition.Builder()
            .target(LatLng(routePts.first().latitude, routePts.first().longitude))
            .zoom(16.0).tilt(if (is3d) 45.0 else 0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
        moveOrAnimate(ml, pos, !hadPriorFit, 340)
        return
    }

    val bounds = LatLngBounds.Builder().apply { routePts.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
    val sTop = fitTopClearPx.coerceIn(16, (mvH * 0.44f).toInt().coerceAtLeast(32))
    val sBot = fitBottomClearPx.coerceIn(16, (mvH * 0.46f).toInt().coerceAtLeast(32))
    val sSide = fitSidePx.coerceIn(16, (mvW * 0.22f).toInt().coerceAtLeast(24))
    val cam2d = ml.getCameraForLatLngBounds(bounds, intArrayOf(sSide, sTop, sSide, sBot), 0.0, 0.0)
    if (cam2d == null) {
        ml.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, sSide, sTop, sSide, sBot))
        return
    }
    val finalPos = if (is3d) {
        CameraPosition.Builder().target(cam2d.target).zoom((cam2d.zoom - 0.45).coerceAtLeast(1.0))
            .tilt(45.0).bearing(0.0).padding(sSide.toDouble(), sTop.toDouble(), sSide.toDouble(), sBot.toDouble()).build()
    } else {
        CameraPosition.Builder(cam2d).tilt(0.0).bearing(0.0)
            .padding(sSide.toDouble(), sTop.toDouble(), sSide.toDouble(), sBot.toDouble()).build()
    }
    val immediate = !hadPriorFit && !modeChanged && !toggle3dChanged
    moveOrAnimate(ml, finalPos, immediate, 420)
}

private fun applyEmptyFitCamera(
    ml: MapLibreMap,
    h: VectorMapHolder,
    tgt: LatLon,
    is3d: Boolean,
    modeChanged: Boolean,
    toggle3dChanged: Boolean,
    effectiveTopPx: Int,
    bottomOcclusionPx: Int,
    recenter: Int
) {
    val emptyKey = "empty_${"%.5f".format(tgt.latitude)}_${"%.5f".format(tgt.longitude)}_${is3d}_${recenter}_${effectiveTopPx / 16}_${bottomOcclusionPx / 16}"
    if (emptyKey == h.lastFitKey && !modeChanged && !toggle3dChanged) return
    val hadPrior = h.lastFitKey.isNotEmpty()
    h.lastRouteKey = "empty"
    h.lastFitKey = emptyKey
    h.lastRecenter = recenter
    ml.setPadding(0, effectiveTopPx, 0, bottomOcclusionPx)
    val pos = CameraPosition.Builder().target(LatLng(tgt.latitude, tgt.longitude)).zoom(15.8)
        .tilt(if (is3d) 45.0 else 0.0).bearing(0.0)
        .padding(0.0, effectiveTopPx.toDouble(), 0.0, bottomOcclusionPx.toDouble()).build()
    moveOrAnimate(ml, pos, !hadPrior, 340)
}

private fun applyFollowRunnerCamera(
    ml: MapLibreMap,
    h: VectorMapHolder,
    pts: List<TrackPoint>,
    guidePts: List<LatLon>,
    routePts: List<LatLon>,
    geoHash: Int,
    userLoc: LatLon?,
    fallbackCenter: LatLon,
    head: ReplayHead?,
    is3d: Boolean,
    modeChanged: Boolean,
    toggle3dChanged: Boolean,
    liveHeading: Float?,
    mvW: Int,
    mvH: Int,
    effectiveTopPx: Int,
    bottomOcclusionPx: Int,
    recenter: Int,
    northLocked: Boolean
) {
    h.lastFitKey = ""
    val northToggled = h.lastNorthLocked != null && h.lastNorthLocked != northLocked
    h.lastNorthLocked = northLocked
    val tgt = head?.let { LatLon(it.lat, it.lon) }
        ?: pts.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
        ?: userLoc ?: guidePts.firstOrNull() ?: fallbackCenter
    val targetBrg = if (!northLocked) (head?.bearingDeg ?: liveHeading ?: 0f).toDouble() else 0.0
    val followKey = "${"%.6f".format(tgt.latitude)}_${"%.6f".format(tgt.longitude)}_${is3d}_${recenter}_${northLocked}_${effectiveTopPx / 16}_${bottomOcclusionPx / 16}_${"%.0f".format(targetBrg)}_${mvW / 32}_${mvH / 32}"
    if (followKey == h.lastFollowKey && !modeChanged && !toggle3dChanged && !northToggled) return

    val hadPrior = h.lastFollowKey.isNotEmpty()
    val recenterChanged = recenter != h.lastRecenter
    val animateTransition = modeChanged || toggle3dChanged || recenterChanged || northToggled || (hadPrior && head == null)
    h.lastFollowKey = followKey
    h.lastRouteKey = if (routePts.isEmpty()) "empty" else "${routePts.size}_$geoHash"
    h.lastRecenter = recenter
    val brg = if (animateTransition || h.smoothFollowBearing == null) {
        targetBrg
    } else {
        lerpAngleDeg(h.smoothFollowBearing!!, targetBrg, 0.22)
    }
    h.smoothFollowBearing = brg
    ml.setPadding(0, effectiveTopPx, 0, bottomOcclusionPx)
    val curZoom = ml.cameraPosition.zoom
    val zoom = if (!hadPrior || modeChanged || recenterChanged || curZoom < 15.6) 16.5 else curZoom
    val pos = CameraPosition.Builder().target(LatLng(tgt.latitude, tgt.longitude)).zoom(zoom)
        .tilt(if (is3d) 54.0 else 0.0).bearing(brg)
        .padding(0.0, effectiveTopPx.toDouble(), 0.0, bottomOcclusionPx.toDouble()).build()
    moveOrAnimate(ml, pos, !animateTransition, 420)
}

private fun lerpAngleDeg(cur: Double, target: Double, alpha: Double): Double {
    val diff = ((target - cur + 540.0) % 360.0) - 180.0
    return ((cur + diff * alpha) % 360.0 + 360.0) % 360.0
}

private fun applyFreePanCamera(
    ml: MapLibreMap,
    h: VectorMapHolder,
    guidePts: List<LatLon>,
    routePts: List<LatLon>,
    geoHash: Int,
    userLoc: LatLon?,
    fallbackCenter: LatLon,
    is3d: Boolean,
    toggle3dChanged: Boolean,
    effectiveTopPx: Int,
    bottomOcclusionPx: Int,
    recenter: Int
) {
    h.lastFitKey = ""
    h.lastFollowKey = ""
    val seedRealArrived = userLoc != null && !h.hasSeededRealUserLoc
    if (h.lastCamMode == null || (seedRealArrived && h.lastCamMode == MapCameraMode.FREE_PAN)) {
        if (userLoc != null) h.hasSeededRealUserLoc = true
        val seed = userLoc ?: guidePts.firstOrNull() ?: routePts.firstOrNull() ?: fallbackCenter
        h.lastRouteKey = if (routePts.isEmpty()) "empty" else "${routePts.size}_$geoHash"
        h.lastRecenter = recenter
        ml.setPadding(0, effectiveTopPx, 0, bottomOcclusionPx)
        val pos = CameraPosition.Builder().target(LatLng(seed.latitude, seed.longitude)).zoom(15.8)
            .tilt(if (is3d) 48.0 else 0.0).bearing(0.0)
            .padding(0.0, effectiveTopPx.toDouble(), 0.0, bottomOcclusionPx.toDouble()).build()
        ml.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        return
    }
    if (!toggle3dChanged) return
    h.lastRecenter = recenter
    val cur = ml.cameraPosition
    val pos = CameraPosition.Builder(cur).tilt(if (is3d) 48.0 else 0.0)
        .bearing(if (is3d) cur.bearing else 0.0).build()
    ml.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 300)
}

private fun moveOrAnimate(ml: MapLibreMap, pos: CameraPosition, immediate: Boolean, ms: Int) {
    val upd = CameraUpdateFactory.newCameraPosition(pos)
    if (immediate) ml.moveCamera(upd) else ml.animateCamera(upd, ms)
}

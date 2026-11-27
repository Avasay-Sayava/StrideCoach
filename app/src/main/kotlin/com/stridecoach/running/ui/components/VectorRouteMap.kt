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
import com.stridecoach.running.core.domain.engine.PaceZoneClassifier
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
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.TransitionOptions
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
    var lastPreload: Boolean? = null
    var lastPaceRenderKey: String = ""
    var lastPaceGeoPushMs: Long = 0L
    var lastBottomOccPx: Int = -1
    var lastHeadKey: String = ""
    var activeHiAnim: ValueAnimator? = null
    val mainHandler = Handler(Looper.getMainLooper())
    var pendingFadeRunnable: Runnable? = null

    var cachedColorRuns: List<ColorChunk> = emptyList()
    var cachedPtsRef: List<TrackPoint> = emptyList()
    var cachedGeoHash: Int = 0
    var cachedRefPace: Int = -1
    var cachedSmoothGrad: Boolean = true
    var syncAction: (() -> Unit)? = null
}

internal data class ColorChunk(
    val startIdx: Int,
    val endIdx: Int,
    val hex: String,
    val coords: List<Point>,
    val feature: Feature = Feature.fromGeometry(LineString.fromLngLats(coords)).apply { addStringProperty("col", hex) }
)

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
    onUserManualPan: () -> Unit = {},
    onMapTap: ((LatLon) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val tapCb = rememberUpdatedState(onMapTap)
    val panCb = rememberUpdatedState(onUserManualPan)
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
            onUserManualPan = onUserManualPan,
            onMapTap = onMapTap,
            onWebGlUnsupported = { webGlUnsupported = true }
        )
        return
    }

    val styleUri = resolveMapStyleUri(mapStyleMode, StrideThemeState.isLight)
    val bgArgb = KdeBg.toArgb()

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
                ctx, holder, pts, replayHead, guidePts, plannerWaypoints, userLocation,
                interactive, showHead, showUserDot, colorByPace, smoothGradient, refPaceSec,
                highlightRange, highlightTrigger, animateHighlight, trimStartIdx, trimEndIdx,
                cameraMode, is3dMode, show3dBuildings, showTerrainHillshade, keepCenteredOnSheetDrag,
                lockAutoFit, preloadTiles, styleUri, liveHeadingDeg, fallbackCenter,
                effectiveTopPx, effectiveBottomPx, fitTopClearPx, fitBottomClearPx, fitSidePx,
                effectiveHiBottomPx, recenterToken
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
                holder.cachedGeoHash = 0
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
                    ctx, holder, pts, replayHead, guidePts, plannerWaypoints, userLocation,
                    interactive, showHead, showUserDot, colorByPace, smoothGradient, refPaceSec,
                    highlightRange, highlightTrigger, animateHighlight, trimStartIdx, trimEndIdx,
                    cameraMode, is3dMode, show3dBuildings, showTerrainHillshade, keepCenteredOnSheetDrag,
                    lockAutoFit, preloadTiles, styleUri, liveHeadingDeg, fallbackCenter,
                    effectiveTopPx, effectiveBottomPx, fitTopClearPx, fitBottomClearPx, fitSidePx,
                    effectiveHiBottomPx, recenterToken
                )
            }
        },
        modifier = modifier
    )
}

private fun installVectorLayers(st: Style) {
    st.transition = TransitionOptions(0L, 0L, false)

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

    if (st.getSource("openmaptiles") != null) {
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
        if (st.getLayer("water_name") != null) {
            st.addLayerBelow(bld, "water_name")
        } else {
            st.addLayer(bld)
        }
    }

    val srcs = listOf("guide-src", "base-src", "pace-src", "active-src", "trim-src", "hi-src", "planner-pts-src", "user-loc-src", "head-src")
    srcs.forEach { id -> st.addSource(GeoJsonSource(id)) }

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
        LineLayer("pace-layer", "pace-src").withProperties(
            lineColor(Expression.get("col")),
            lineWidth(5.4f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    st.addLayer(baseLineLayer("active-layer", "active-src", "#926EE4", 5.5f))
    st.addLayer(baseLineLayer("trim-layer", "trim-src", "#F87171", 5.4f))
    st.addLayer(baseLineLayer("hi-layer", "hi-src", "#3DAEE9", 7.4f))

    st.addLayer(
        CircleLayer("planner-pts-layer", "planner-pts-src").withProperties(
            circleRadius(
                Expression.match(
                    Expression.get("kind"),
                    Expression.literal(6.0f),
                    Expression.stop("start", 8.5f),
                    Expression.stop("end", 8.5f)
                )
            ),
            circleColor(
                Expression.match(
                    Expression.get("kind"),
                    Expression.literal("#926EE4"),
                    Expression.stop("start", "#4ADE80"),
                    Expression.stop("end", "#F87171")
                )
            ),
            circleStrokeColor(Color.WHITE),
            circleStrokeWidth(2.2f)
        )
    )

    st.addLayer(
        CircleLayer("user-loc-halo", "user-loc-src").withProperties(
            circleRadius(14f),
            circleColor(Color.parseColor("#3DAEE9")),
            circleOpacity(0.28f)
        )
    )
    st.addLayer(
        CircleLayer("user-loc-ring", "user-loc-src").withProperties(
            circleRadius(7.8f),
            circleColor(Color.WHITE)
        )
    )
    st.addLayer(
        CircleLayer("user-loc-core", "user-loc-src").withProperties(
            circleRadius(5.2f),
            circleColor(Color.parseColor("#3DAEE9"))
        )
    )

    st.addLayer(
        CircleLayer("head-halo", "head-src").withProperties(
            circleRadius(13f),
            circleColor(Color.parseColor("#926EE4")),
            circleOpacity(0.32f)
        )
    )
    st.addLayer(
        CircleLayer("head-ring", "head-src").withProperties(
            circleRadius(7.5f),
            circleColor(Color.WHITE)
        )
    )
    st.addLayer(
        CircleLayer("head-core", "head-src").withProperties(
            circleRadius(4.8f),
            circleColor(Color.parseColor("#926EE4"))
        )
    )
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
    pts: List<TrackPoint>,
    head: ReplayHead?,
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
    recenter: Int
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

    if (cameraMode == MapCameraMode.FREE_PAN && keepCenteredOnSheetDrag && h.lastCamMode == MapCameraMode.FREE_PAN && h.lastBottomOccPx >= 0) {
        val delta = bottomOcclusionPx - h.lastBottomOccPx
        if (kotlin.math.abs(delta) >= 8) {
            ml.scrollBy(0f, -delta / 2f)
        }
    }
    h.lastBottomOccPx = bottomOcclusionPx

    val validPts = pts.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    val validGuide = guidePts.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    val validWaypoints = plannerWaypoints.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    val geoHash = computePtsGeoHash(validPts)

    syncGuide(h, st, validGuide)
    syncPlannerWaypoints(h, st, validWaypoints)
    syncUserDot(h, st, userLoc, showUserDot)
    syncBase(h, st, validPts, geoHash)
    syncActiveAndPace(h, st, validPts, geoHash, head, colorByPace, smoothGradient, refPaceSec)
    syncTrimSlices(h, st, validPts, trimStartIdx, trimEndIdx)
    syncHiSlice(ml, h, st, validPts, hiRange, hiTrigger, animateHighlight, is3dMode, effectiveTopPx, hiBottomOcclusionPx)
    syncHead(h, st, validPts, head, showHead)
    updateCamera(
        ml, h, validPts, validGuide, geoHash, userLoc, fallbackCenter, head, cameraMode, is3dMode, lockAutoFit,
        liveHeadingDeg, effectiveTopPx, bottomOcclusionPx, fitTopClearPx, fitBottomClearPx, fitSidePx, recenter
    )

    if (preloadTiles) {
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
}

private fun syncActiveAndPace(
    h: VectorMapHolder,
    st: Style,
    pts: List<TrackPoint>,
    geoHash: Int,
    head: ReplayHead?,
    colorByPace: Boolean,
    smoothGrad: Boolean,
    refPace: Int
) {
    val paceKey = "${geoHash}_${colorByPace}_${smoothGrad}_${refPace}_${head?.baseIdx}_${head?.let { "%.5f_%.5f".format(it.lat, it.lon) }}"
    if (paceKey == h.lastPaceRenderKey) return

    val paceSrc = st.getSourceAs<GeoJsonSource>("pace-src") ?: return
    val actSrc = st.getSourceAs<GeoJsonSource>("active-src") ?: return
    if (pts.size < 2) {
        h.lastPaceRenderKey = paceKey
        paceSrc.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        actSrc.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        return
    }
    if (!colorByPace) {
        h.lastPaceRenderKey = paceKey
        paceSrc.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        val coords = buildActiveCoords(pts, head)
        val fc = if (coords.size >= 2) {
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(coords)))
        } else {
            FeatureCollection.fromFeatures(emptyArray())
        }
        actSrc.setGeoJson(fc)
        return
    }

    val ptsOrStyleChanged = h.cachedGeoHash != geoHash || h.cachedPtsRef !== pts || h.cachedRefPace != refPace || h.cachedSmoothGrad != smoothGrad
    val nowMs = android.os.SystemClock.uptimeMillis()
    if (!ptsOrStyleChanged && head != null && nowMs - h.lastPaceGeoPushMs < 45L && h.lastPaceRenderKey.isNotEmpty()) {
        return
    }
    h.lastPaceRenderKey = paceKey
    h.lastPaceGeoPushMs = nowMs

    actSrc.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    if (ptsOrStyleChanged) {
        h.cachedGeoHash = geoHash
        h.cachedPtsRef = pts
        h.cachedRefPace = refPace
        h.cachedSmoothGrad = smoothGrad
        h.cachedColorRuns = buildColorChunks(pts, refPace, smoothGrad)
    }
    val feats = sliceChunksToHead(h.cachedColorRuns, pts, head)
    paceSrc.setGeoJson(FeatureCollection.fromFeatures(feats))
}

internal fun buildActiveCoords(pts: List<TrackPoint>, head: ReplayHead?): List<Point> {
    if (head == null) return pts.map { Point.fromLngLat(it.longitude, it.latitude) }
    val lim = head.baseIdx.coerceIn(0, pts.lastIndex)
    val list = ArrayList<Point>(lim + 2)
    for (i in 0..lim) list.add(Point.fromLngLat(pts[i].longitude, pts[i].latitude))
    list.add(Point.fromLngLat(head.lon, head.lat))
    return list
}

internal fun buildColorChunks(pts: List<TrackPoint>, refPace: Int, smoothGrad: Boolean): List<ColorChunk> {
    val smoothedSpeeds = FloatArray(pts.size) { i ->
        val lo = (i - 2).coerceAtLeast(0)
        val hi = (i + 2).coerceAtMost(pts.lastIndex)
        var sum = 0f
        var cnt = 0
        for (k in lo..hi) {
            val v = pts[k].speedMps ?: continue
            sum += v
            cnt++
        }
        if (cnt > 0) sum / cnt else (pts[i].speedMps ?: 0f)
    }

    val out = mutableListOf<ColorChunk>()
    var start = 0
    var col = hexForSpeed(smoothedSpeeds[0], refPace, smoothGrad)
    for (i in 1 until pts.size) {
        val next = hexForSpeed(smoothedSpeeds[i], refPace, smoothGrad)
        if (next != col || i == pts.lastIndex) {
            val end = if (next != col) i else pts.lastIndex
            val coords = (start..end).map { k -> Point.fromLngLat(pts[k].longitude, pts[k].latitude) }
            out.add(ColorChunk(start, end, col, coords))
            start = i
            col = next
        }
    }
    return out
}

private fun hexForSpeed(spd: Float, refPace: Int, smoothGrad: Boolean): String {
    if (!smoothGrad) return PaceZoneClassifier.classifySpeed(spd, refPace)?.hex ?: "#9BA3AC"
    val hex = PaceZoneClassifier.smoothPaceHex(spd, refPace)
    val c = runCatching { Color.parseColor(hex) }.getOrNull() ?: return hex
    val r = Color.red(c) and 0xF0
    val g = Color.green(c) and 0xF0
    val b = Color.blue(c) and 0xF0
    return "#%02X%02X%02X".format(r, g, b)
}

internal fun sliceChunksToHead(
    chunks: List<ColorChunk>,
    pts: List<TrackPoint>,
    head: ReplayHead?
): List<Feature> {
    if (head == null) return chunks.map { it.feature }
    val lim = head.baseIdx.coerceIn(0, pts.lastIndex)
    val feats = ArrayList<Feature>(chunks.size)
    for (ch in chunks) {
        if (ch.startIdx > lim) break
        if (ch.endIdx <= lim) {
            feats.add(ch.feature)
        } else {
            val part = ArrayList<Point>(lim - ch.startIdx + 2)
            for (i in ch.startIdx..lim) part.add(Point.fromLngLat(pts[i].longitude, pts[i].latitude))
            part.add(Point.fromLngLat(head.lon, head.lat))
            if (part.size >= 2) {
                feats.add(Feature.fromGeometry(LineString.fromLngLats(part)).apply { addStringProperty("col", ch.hex) })
            }
            break
        }
    }
    return feats
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
    if (range == null || pts.size < 2 || range.last - range.first < 1) {
        h.activeHiAnim?.cancel()
        h.pendingFadeRunnable?.let { h.mainHandler.removeCallbacks(it) }
        src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        return
    }
    val s = range.first.coerceIn(0, pts.lastIndex)
    val e = range.last.coerceIn(s, pts.lastIndex)
    val slice = (s..e).map { pts[it] }
    val line = slice.map { Point.fromLngLat(it.longitude, it.latitude) }
    src.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line))))

    if (trigger != h.lastHiTrig) {
        h.lastHiTrig = trigger
        val bounds = LatLngBounds.Builder().apply {
            slice.forEach { include(LatLng(it.latitude, it.longitude)) }
        }.build()
        val mvW = h.mapView?.width ?: 1080
        val mvH = h.mapView?.height ?: 1920
        val safeTop = (topHudPx + 56).coerceIn(24, (mvH * 0.42f).toInt().coerceAtLeast(32))
        val safeBot = (bottomOcclusionPx + 56).coerceIn(24, (mvH * 0.44f).toInt().coerceAtLeast(32))
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
                    .padding(0.0, 0.0, 0.0, 0.0)
                    .build()
            } else {
                CameraPosition.Builder(it).padding(0.0, 0.0, 0.0, 0.0).build()
            }
        }

        val hiLayer = st.getLayerAs<LineLayer>("hi-layer")
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
    val cStart = Color.parseColor("#3DAEE9")
    val cWhite = Color.WHITE
    h.activeHiAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 320L
        addUpdateListener { va ->
            val t = va.animatedValue as Float
            val col = blendArgb(cStart, cWhite, t)
            layer.setProperties(lineColor(col), lineWidth(7.0f + 4.0f * t))
        }
        start()
    }
}

private fun scheduleHighlightPulseOut(h: VectorMapHolder, layer: LineLayer) {
    h.pendingFadeRunnable?.let { h.mainHandler.removeCallbacks(it) }
    val cWhite = Color.WHITE
    val cEnd = Color.parseColor("#3DAEE9")
    val r = Runnable {
        h.activeHiAnim?.cancel()
        h.activeHiAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                val col = blendArgb(cWhite, cEnd, t)
                layer.setProperties(lineColor(col), lineWidth(11.0f - 3.6f * t))
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
    recenter: Int
) {
    val mvWidth = h.mapView?.width ?: 0
    val mvHeight = h.mapView?.height ?: 0
    if (mvWidth <= 0 || mvHeight <= 0) return

    val routePts = if (pts.isNotEmpty()) pts.map { LatLon(it.latitude, it.longitude) } else guidePts
    val toggle3dChanged = h.last3d != null && h.last3d != is3d
    val modeChanged = h.lastCamMode != null && h.lastCamMode != mode

    if (userLoc != null) {
        h.hasSeededRealUserLoc = true
    }

    when (mode) {
        MapCameraMode.FIT_ROUTE -> {
            h.lastFollowKey = ""
            if (routePts.isNotEmpty()) {
                val rKey = if (head != null) h.lastRouteKey.ifEmpty { "${routePts.size}_$geoHash" } else "${routePts.size}_$geoHash"
                val fitKey = "${rKey}_${is3d}_${recenter}_${fitTopClearPx / 16}_${fitBottomClearPx / 16}_${mvWidth / 32}_${mvHeight / 32}"
                if (fitKey != h.lastFitKey || modeChanged || toggle3dChanged) {
                    if (!lockAutoFit || h.lastFitKey.isEmpty() || modeChanged || toggle3dChanged || recenter != h.lastRecenter) {
                        val hadPriorFit = h.lastFitKey.isNotEmpty()
                        h.lastRouteKey = rKey
                        h.lastFitKey = fitKey
                        h.lastRecenter = recenter
                        ml.setPadding(0, 0, 0, 0)
                        if (routePts.size >= 2) {
                            val b = LatLngBounds.Builder()
                            routePts.forEach { b.include(LatLng(it.latitude, it.longitude)) }
                            val safeTop = fitTopClearPx.coerceIn(16, (mvHeight * 0.44f).toInt().coerceAtLeast(32))
                            val safeBot = fitBottomClearPx.coerceIn(16, (mvHeight * 0.46f).toInt().coerceAtLeast(32))
                            val safeSide = fitSidePx.coerceIn(16, (mvWidth * 0.22f).toInt().coerceAtLeast(24))
                            val cam2d = ml.getCameraForLatLngBounds(b.build(), intArrayOf(safeSide, safeTop, safeSide, safeBot), 0.0, 0.0)
                            if (cam2d != null) {
                                val finalPos = if (is3d) {
                                    CameraPosition.Builder()
                                        .target(cam2d.target)
                                        .zoom((cam2d.zoom - 0.45).coerceAtLeast(1.0))
                                        .tilt(45.0)
                                        .bearing(0.0)
                                        .padding(safeSide.toDouble(), safeTop.toDouble(), safeSide.toDouble(), safeBot.toDouble())
                                        .build()
                                } else {
                                    CameraPosition.Builder(cam2d)
                                        .tilt(0.0)
                                        .bearing(0.0)
                                        .padding(safeSide.toDouble(), safeTop.toDouble(), safeSide.toDouble(), safeBot.toDouble())
                                        .build()
                                }
                                if (!hadPriorFit && !modeChanged && !toggle3dChanged) {
                                    ml.moveCamera(CameraUpdateFactory.newCameraPosition(finalPos))
                                } else {
                                    ml.animateCamera(CameraUpdateFactory.newCameraPosition(finalPos), 380)
                                }
                            } else {
                                ml.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), safeSide, safeTop, safeSide, safeBot))
                            }
                        } else {
                            val pos = CameraPosition.Builder()
                                .target(LatLng(routePts.first().latitude, routePts.first().longitude))
                                .zoom(16.0)
                                .tilt(if (is3d) 45.0 else 0.0)
                                .bearing(0.0)
                                .padding(0.0, 0.0, 0.0, 0.0)
                                .build()
                            if (!hadPriorFit) ml.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
                            else ml.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 340)
                        }
                    }
                }
            } else {
                val tgt = userLoc ?: fallbackCenter
                val emptyFitKey = "empty_${"%.5f".format(tgt.latitude)}_${"%.5f".format(tgt.longitude)}_${is3d}_${recenter}_${effectiveTopPx / 16}_${bottomOcclusionPx / 16}"
                if (emptyFitKey != h.lastFitKey || modeChanged || toggle3dChanged) {
                    val hadPrior = h.lastFitKey.isNotEmpty()
                    h.lastRouteKey = "empty"
                    h.lastFitKey = emptyFitKey
                    h.lastRecenter = recenter
                    ml.setPadding(0, effectiveTopPx, 0, bottomOcclusionPx)
                    val pos = CameraPosition.Builder()
                        .target(LatLng(tgt.latitude, tgt.longitude))
                        .zoom(15.8)
                        .tilt(if (is3d) 45.0 else 0.0)
                        .bearing(0.0)
                        .padding(0.0, effectiveTopPx.toDouble(), 0.0, bottomOcclusionPx.toDouble())
                        .build()
                    if (!hadPrior) ml.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
                    else ml.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 340)
                }
            }
        }

        MapCameraMode.FOLLOW_RUNNER -> {
            h.lastFitKey = ""
            val tgtPt = head?.let { LatLon(it.lat, it.lon) }
                ?: pts.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
                ?: userLoc
                ?: guidePts.firstOrNull()
                ?: fallbackCenter
            val brg = if (is3d) (head?.bearingDeg ?: liveHeading ?: 0f).toDouble() else 0.0
            val followKey = "${"%.6f".format(tgtPt.latitude)}_${"%.6f".format(tgtPt.longitude)}_${is3d}_${recenter}_${effectiveTopPx / 16}_${bottomOcclusionPx / 16}_${"%.0f".format(brg)}_${mvWidth / 32}_${mvHeight / 32}"
            if (followKey != h.lastFollowKey || modeChanged || toggle3dChanged) {
                val hadPriorFollow = h.lastFollowKey.isNotEmpty()
                val recenterChanged = recenter != h.lastRecenter
                h.lastFollowKey = followKey
                h.lastRouteKey = if (routePts.isEmpty()) "empty" else "${routePts.size}_$geoHash"
                h.lastRecenter = recenter
                ml.setPadding(0, effectiveTopPx, 0, bottomOcclusionPx)
                val curZoom = ml.cameraPosition.zoom
                val zoom = if (!hadPriorFollow || modeChanged || recenterChanged || curZoom < 15.6) 16.5 else curZoom
                val pos = CameraPosition.Builder()
                    .target(LatLng(tgtPt.latitude, tgtPt.longitude))
                    .zoom(zoom)
                    .tilt(if (is3d) 54.0 else 0.0)
                    .bearing(brg)
                    .padding(0.0, effectiveTopPx.toDouble(), 0.0, bottomOcclusionPx.toDouble())
                    .build()
                if (!hadPriorFollow || head != null) {
                    ml.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
                } else {
                    ml.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 340)
                }
            }
        }

        MapCameraMode.FREE_PAN -> {
            h.lastFitKey = ""
            h.lastFollowKey = ""
            val seedRealArrived = userLoc != null && !h.hasSeededRealUserLoc
            if (h.lastCamMode == null || seedRealArrived) {
                if (userLoc != null) h.hasSeededRealUserLoc = true
                val seed = userLoc ?: guidePts.firstOrNull() ?: routePts.firstOrNull() ?: fallbackCenter
                h.lastRouteKey = if (routePts.isEmpty()) "empty" else "${routePts.size}_$geoHash"
                h.lastRecenter = recenter
                ml.setPadding(0, effectiveTopPx, 0, bottomOcclusionPx)
                val pos = CameraPosition.Builder()
                    .target(LatLng(seed.latitude, seed.longitude))
                    .zoom(15.8)
                    .tilt(if (is3d) 48.0 else 0.0)
                    .bearing(0.0)
                    .padding(0.0, effectiveTopPx.toDouble(), 0.0, bottomOcclusionPx.toDouble())
                    .build()
                ml.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
            } else if (toggle3dChanged) {
                h.lastRecenter = recenter
                val cur = ml.cameraPosition
                ml.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(cur)
                            .tilt(if (is3d) 48.0 else 0.0)
                            .bearing(if (is3d) cur.bearing else 0.0)
                            .build()
                    ),
                    300
                )
            }
        }
    }

    h.last3d = is3d
    h.lastCamMode = mode
}

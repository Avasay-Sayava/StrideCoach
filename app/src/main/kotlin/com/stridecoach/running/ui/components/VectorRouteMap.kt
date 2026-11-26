package com.stridecoach.running.ui.components

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

const val OPENFREEMAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

private class VectorMapHolder {
    var map: MapLibreMap? = null
    var styleReady: Boolean = false
    var lastRouteKey: String = ""
    var lastRecenter: Int = -1
    var lastBasePtsKey: String = ""
    var lastGuideKey: String = ""
    var lastTrimKey: String = ""
    var lastHiKey: String = ""
    var cachedColorRuns: List<ColorChunk> = emptyList()
    var cachedPtsRef: List<TrackPoint> = emptyList()
    var cachedRefPace: Int = -1
}

private data class ColorChunk(
    val startIdx: Int,
    val endIdx: Int,
    val hex: String,
    val coords: List<Point>
)

@Composable
fun VectorRouteMap(
    pts: List<TrackPoint>,
    modifier: Modifier = Modifier,
    replayHead: ReplayHead? = null,
    guidePts: List<LatLon> = emptyList(),
    interactive: Boolean = true,
    showHead: Boolean = false,
    colorByPace: Boolean = false,
    refPaceSec: Int = 300,
    highlightRange: IntRange? = null,
    trimCutIdx: Int? = null,
    followHead: Boolean = false,
    bottomPadDp: Int = 180,
    recenterToken: Int = 0,
    onMapTap: ((LatLon) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val tapCb = rememberUpdatedState(onMapTap)
    val padPx = with(LocalDensity.current) { bottomPadDp.dp.roundToPx() }
    val mv = remember(ctx) { MapView(ctx).apply { onCreate(null) } }
    val holder = remember(mv) { VectorMapHolder() }

    remember(mv) {
        mv.getMapAsync { ml ->
            holder.map = ml
            ml.uiSettings.apply {
                isScrollGesturesEnabled = interactive
                isZoomGesturesEnabled = interactive
                isRotateGesturesEnabled = interactive
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
            }
            ml.addOnMapClickListener { ll ->
                val cb = tapCb.value ?: return@addOnMapClickListener false
                cb(LatLon(ll.latitude, ll.longitude))
                true
            }
            ml.setStyle(Style.Builder().fromUri(OPENFREEMAP_DARK_STYLE)) { st ->
                installVectorLayers(st)
                holder.styleReady = true
                syncVectorScene(
                    holder, pts, replayHead, guidePts, showHead, colorByPace,
                    refPaceSec, highlightRange, trimCutIdx, followHead, padPx, recenterToken
                )
            }
        }
        true
    }

    DisposableEffect(owner, mv) {
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
            owner.lifecycle.removeObserver(obs)
            mv.onDestroy()
        }
    }

    AndroidView(
        factory = { mv },
        update = {
            if (holder.styleReady) {
                syncVectorScene(
                    holder, pts, replayHead, guidePts, showHead, colorByPace,
                    refPaceSec, highlightRange, trimCutIdx, followHead, padPx, recenterToken
                )
            }
        },
        modifier = modifier
    )
}

private fun installVectorLayers(st: Style) {
    val srcs = listOf("guide-src", "base-src", "pace-src", "active-src", "trim-src", "hi-src", "head-src")
    srcs.forEach { id -> st.addSource(GeoJsonSource(id)) }

    st.addLayer(
        LineLayer("guide-layer", "guide-src").withProperties(
            lineColor(Color.parseColor("#3DAEE9")),
            lineWidth(3.5f),
            lineDasharray(arrayOf(2.2f, 1.8f)),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    st.addLayer(baseLineLayer("base-layer", "base-src", "#473C69", 4.0f))
    st.addLayer(
        LineLayer("pace-layer", "pace-src").withProperties(
            lineColor(Expression.get("col")),
            lineWidth(5.2f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    st.addLayer(baseLineLayer("active-layer", "active-src", "#926EE4", 5.5f))
    st.addLayer(baseLineLayer("trim-layer", "trim-src", "#F87171", 5.0f))
    st.addLayer(baseLineLayer("hi-layer", "hi-src", "#3DAEE9", 7.2f))

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
    h: VectorMapHolder,
    pts: List<TrackPoint>,
    head: ReplayHead?,
    guidePts: List<LatLon>,
    showHead: Boolean,
    colorByPace: Boolean,
    refPaceSec: Int,
    hiRange: IntRange?,
    trimIdx: Int?,
    followHead: Boolean,
    padPx: Int,
    recenter: Int
) {
    val ml = h.map ?: return
    val st = ml.style ?: return

    val validPts = pts.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    val validGuide = guidePts.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    syncGuide(h, st, validGuide)
    syncBase(h, st, validPts)
    syncActiveAndPace(h, st, validPts, head, colorByPace, refPaceSec)
    syncSlice(h, st, "trim-src", validPts, if (trimIdx != null && trimIdx < validPts.lastIndex) trimIdx..validPts.lastIndex else null) { k ->
        val hit = h.lastTrimKey == k
        h.lastTrimKey = k
        hit
    }
    syncSlice(h, st, "hi-src", validPts, hiRange) { k ->
        val hit = h.lastHiKey == k
        h.lastHiKey = k
        hit
    }
    syncHead(st, validPts, head, showHead)
    updateCamera(ml, h, validPts, validGuide, head, followHead, padPx, recenter)
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

private fun syncBase(h: VectorMapHolder, st: Style, pts: List<TrackPoint>) {
    val k = "${pts.size}_${pts.firstOrNull()?.latitude}_${pts.lastOrNull()?.longitude}"
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
    head: ReplayHead?,
    colorByPace: Boolean,
    refPace: Int
) {
    val paceSrc = st.getSourceAs<GeoJsonSource>("pace-src") ?: return
    val actSrc = st.getSourceAs<GeoJsonSource>("active-src") ?: return
    if (pts.size < 2) {
        paceSrc.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        actSrc.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        return
    }
    if (!colorByPace) {
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

    actSrc.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    if (h.cachedPtsRef !== pts || h.cachedRefPace != refPace) {
        h.cachedPtsRef = pts
        h.cachedRefPace = refPace
        h.cachedColorRuns = buildColorChunks(pts, refPace)
    }
    val feats = sliceChunksToHead(h.cachedColorRuns, pts, head)
    paceSrc.setGeoJson(FeatureCollection.fromFeatures(feats))
}

private fun buildActiveCoords(pts: List<TrackPoint>, head: ReplayHead?): List<Point> {
    if (head == null) return pts.map { Point.fromLngLat(it.longitude, it.latitude) }
    val lim = head.baseIdx.coerceIn(0, pts.lastIndex)
    val list = ArrayList<Point>(lim + 2)
    for (i in 0..lim) list.add(Point.fromLngLat(pts[i].longitude, pts[i].latitude))
    list.add(Point.fromLngLat(head.lon, head.lat))
    return list
}

private fun buildColorChunks(pts: List<TrackPoint>, refPace: Int): List<ColorChunk> {
    val out = mutableListOf<ColorChunk>()
    var start = 0
    var col = zoneHexForPoint(pts.first(), refPace)
    for (i in 1 until pts.size) {
        val next = zoneHexForPoint(pts[i], refPace)
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

private fun sliceChunksToHead(
    chunks: List<ColorChunk>,
    pts: List<TrackPoint>,
    head: ReplayHead?
): List<Feature> {
    if (head == null) {
        return chunks.map { ch ->
            Feature.fromGeometry(LineString.fromLngLats(ch.coords)).apply { addStringProperty("col", ch.hex) }
        }
    }
    val lim = head.baseIdx.coerceIn(0, pts.lastIndex)
    val feats = ArrayList<Feature>(chunks.size)
    for (ch in chunks) {
        if (ch.startIdx > lim) break
        if (ch.endIdx <= lim) {
            feats.add(Feature.fromGeometry(LineString.fromLngLats(ch.coords)).apply { addStringProperty("col", ch.hex) })
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

private fun syncSlice(
    h: VectorMapHolder,
    st: Style,
    srcId: String,
    pts: List<TrackPoint>,
    range: IntRange?,
    dedup: (String) -> Boolean
) {
    val k = "${range?.first}_${range?.last}_${pts.size}"
    if (dedup(k)) return
    val src = st.getSourceAs<GeoJsonSource>(srcId) ?: return
    if (range == null || pts.size < 2 || range.last - range.first < 1) {
        src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        return
    }
    val s = range.first.coerceIn(0, pts.lastIndex)
    val e = range.last.coerceIn(s, pts.lastIndex)
    val line = (s..e).map { i -> Point.fromLngLat(pts[i].longitude, pts[i].latitude) }
    src.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line))))
}

private fun syncHead(st: Style, pts: List<TrackPoint>, head: ReplayHead?, showHead: Boolean) {
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
    head: ReplayHead?,
    followHead: Boolean,
    padPx: Int,
    recenter: Int
) {
    val all = if (pts.isNotEmpty()) pts.map { LatLon(it.latitude, it.longitude) } else guidePts
    if (all.isEmpty()) {
        if (h.lastRouteKey != "empty") {
            h.lastRouteKey = "empty"
            ml.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(32.0853, 34.7818)).zoom(13.8).build()))
        }
        return
    }

    val rKey = "${all.size}_${all.first().latitude}_${all.last().longitude}"
    val needFit = rKey != h.lastRouteKey || recenter != h.lastRecenter
    if (needFit) {
        h.lastRouteKey = rKey
        h.lastRecenter = recenter
        if (followHead && pts.isNotEmpty()) {
            val ll = head?.let { LatLng(it.lat, it.lon) } ?: LatLng(pts.last().latitude, pts.last().longitude)
            ml.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 16.0), 400)
        } else if (all.size >= 2) {
            val b = LatLngBounds.Builder()
            all.forEach { b.include(LatLng(it.latitude, it.longitude)) }
            ml.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 72, 190, 72, padPx), 400)
        } else {
            ml.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all.first().latitude, all.first().longitude), 15.0))
        }
    } else if (followHead && pts.isNotEmpty()) {
        val ll = head?.let { LatLng(it.lat, it.lon) } ?: LatLng(pts.last().latitude, pts.last().longitude)
        ml.moveCamera(CameraUpdateFactory.newLatLng(ll))
    }
}

private fun zoneHexForPoint(pt: TrackPoint, refPace: Int): String {
    val spd = pt.speedMps ?: return "#926EE4"
    if (spd < 0.6f) return "#9BA3AC"
    val pace = (1000.0 / spd).toInt()
    val d = pace - refPace
    return when {
        d > 45 -> "#4ADE80"
        d in 16..45 -> "#86EFAC"
        d in -5..15 -> "#926EE4"
        d in -20..-6 -> "#FBBF24"
        else -> "#F87171"
    }
}

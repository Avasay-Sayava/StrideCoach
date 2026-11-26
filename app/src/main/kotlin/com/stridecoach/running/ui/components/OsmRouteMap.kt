package com.stridecoach.running.ui.components

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stridecoach.running.core.domain.model.TrackPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

private class RunnerDotOverlay(private val pt: GeoPoint) : Overlay() {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#926EE4") }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val px = map.projection.toPixels(pt, null)
        c.drawCircle(px.x.toFloat(), px.y.toFloat(), 14f, ring)
        c.drawCircle(px.x.toFloat(), px.y.toFloat(), 9f, fill)
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun OsmRouteMap(
    pts: List<TrackPoint>,
    modifier: Modifier = Modifier,
    endIdx: Int = pts.lastIndex,
    interactive: Boolean = false,
    showHead: Boolean = false
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val map = remember(ctx) {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMultiTouchControls(interactive)
            if (!interactive) setOnTouchListener { _, _ -> false }
        }
    }

    DisposableEffect(owner, map) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) map.onResume()
            if (ev == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            map.onDetach()
        }
    }

    AndroidView(
        factory = { map },
        update = { mv -> renderMap(mv, pts, endIdx, showHead) },
        modifier = modifier.clip(RoundedCornerShape(12.dp))
    )
}

private fun renderMap(mv: MapView, pts: List<TrackPoint>, endIdx: Int, showHead: Boolean) {
    mv.overlays.clear()
    if (pts.isEmpty()) {
        mv.controller.setZoom(14.5)
        mv.controller.setCenter(GeoPoint(32.0853, 34.7818))
        mv.invalidate()
        return
    }
    val geo = pts.map { GeoPoint(it.latitude, it.longitude) }
    val baseLine = Polyline(mv).apply {
        setPoints(geo)
        outlinePaint.color = Color.parseColor("#544382")
        outlinePaint.strokeWidth = 7f
    }
    mv.overlays.add(baseLine)

    val lim = endIdx.coerceIn(0, geo.lastIndex)
    if (lim >= 1) {
        val activeLine = Polyline(mv).apply {
            setPoints(geo.subList(0, lim + 1))
            outlinePaint.color = Color.parseColor("#926EE4")
            outlinePaint.strokeWidth = 10f
        }
        mv.overlays.add(activeLine)
    }
    if (showHead) mv.overlays.add(RunnerDotOverlay(geo[lim]))

    if (geo.size >= 2) {
        val box = BoundingBox.fromGeoPointsSafe(geo)
        mv.post { mv.zoomToBoundingBox(box, false, 40) }
    } else {
        mv.controller.setZoom(15.5)
        mv.controller.setCenter(geo.first())
    }
    mv.invalidate()
}

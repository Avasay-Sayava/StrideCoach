package com.stridecoach.running.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.LruCache
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.ui.theme.KdeBgDeep
import com.stridecoach.running.ui.theme.KdeCardBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.StrideThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

internal data class ThumbCam(val cLat: Double, val cLon: Double, val zoom: Double)

object StaticMapSnapshotter {
    private const val W = 680
    private const val H = 280
    private const val PAD_Y = 64
    private const val H_SNAP = H + PAD_Y * 2

    private val ram = LruCache<String, ImageBitmap>(64)
    private val glLock = Mutex()

    suspend fun preloadAll(ctx: Context, runs: List<RunRecord>, styleUri: String = OPENFREEMAP_DARK_STYLE) {
        kotlinx.coroutines.delay(1_800L)
        val subset = runs.asSequence().filter { it.hasTrack() }.take(8).toList()
        for (r in subset) {
            runCatching { loadOrRender(ctx, r, styleUri) }
            kotlinx.coroutines.delay(180L)
        }
    }

    suspend fun loadOrRender(
        ctx: Context,
        run: RunRecord,
        styleUri: String = OPENFREEMAP_DARK_STYLE
    ): ImageBitmap? {
        if (!run.hasTrack()) return null
        val styleTag = styleUri.substringAfterLast('/')
        val key = "${run.id}_${run.encodedPolyline.hashCode()}_${run.distanceMeters}_${run.isSnapped}_$styleTag"
        ram.get(key)?.let { return it }

        val dir = withContext(Dispatchers.IO) { File(ctx.cacheDir, "vec_thumbs_v3").apply { mkdirs() } }
        val file = File(dir, "$key.png")
        val disk = withContext(Dispatchers.IO) { decodeCached(file) }
        if (disk != null) {
            ram.put(key, disk)
            return disk
        }

        val pts = withContext(Dispatchers.Default) { extractPts(run) }
        if (pts.size < 2) return null

        val rendered = glLock.withLock {
            decodeCached(file) ?: renderVectorSnapshot(ctx, pts, styleUri, file)
        }
        rendered?.let { ram.put(key, it) }
        return rendered
    }

    private fun decodeCached(f: File): ImageBitmap? {
        if (!f.exists() || f.length() <= 256L) return null
        return BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    }

    private fun extractPts(run: RunRecord): List<LatLon> {
        val raw = if (run.trackPoints.size >= 2) {
            run.trackPoints.map { LatLon(it.latitude, it.longitude) }
        } else {
            EncodedPolylineCodec.decode(run.encodedPolyline)
        }
        return raw.filter { it.latitude in -85.0..85.0 && it.longitude in -179.9..179.9 }
    }

    internal fun computeThumbCam(pts: List<LatLon>): ThumbCam {
        val mxs = pts.map { (it.longitude + 180.0) / 360.0 }
        val mys = pts.map { latToNormY(it.latitude) }
        val minX = mxs.min()
        val maxX = mxs.max()
        val minY = mys.min()
        val maxY = mys.max()
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val dx = (maxX - minX).coerceAtLeast(1e-6)
        val dy = (maxY - minY).coerceAtLeast(1e-6)
        val zx = log2((W * 0.74) / (512.0 * dx))
        val zy = log2((H * 0.72) / (512.0 * dy))
        val z = min(zx, zy).coerceIn(10.0, 16.5)
        val lon = cx * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * cy))))
        return ThumbCam(lat, lon, z)
    }

    internal fun projectPoint(pt: LatLon, cam: ThumbCam, scale: Float, ox: Float, oy: Float): Offset {
        val worldPx = 512.0 * 2.0.pow(cam.zoom)
        val cx = ((cam.cLon + 180.0) / 360.0) * worldPx
        val cy = latToNormY(cam.cLat) * worldPx
        val px = ((pt.longitude + 180.0) / 360.0) * worldPx
        val py = latToNormY(pt.latitude) * worldPx
        val x = (W / 2.0 + (px - cx)).toFloat() * scale + ox
        val y = (H / 2.0 + (py - cy)).toFloat() * scale + oy
        return Offset(x, y)
    }

    private fun latToNormY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    private suspend fun renderVectorSnapshot(
        ctx: Context,
        pts: List<LatLon>,
        styleUri: String,
        dest: File
    ): ImageBitmap? {
        val sampled = if (pts.size <= 180) pts else {
            val step = (pts.size / 180).coerceAtLeast(1)
            pts.filterIndexed { i, _ -> i == 0 || i == pts.lastIndex || i % step == 0 }
        }
        val cam = computeThumbCam(sampled)
        val rawPair = withTimeoutOrNull(8_000L) {
            withContext(Dispatchers.Main) { takeMapLibreSnap(ctx.applicationContext, cam, sampled, styleUri) }
        } ?: return null
        val bmp = withContext(Dispatchers.Default) {
            val (raw, pxPts) = rawPair
            val cropY = PAD_Y.coerceAtMost((raw.height - H).coerceAtLeast(0))
            val cropH = H.coerceAtMost(raw.height)
            val cropped = Bitmap.createBitmap(raw, 0, cropY, raw.width, cropH)
            drawRouteOverlay(cropped, pxPts.map { PointF(it.x, it.y - cropY) })
            cropped
        }
        withContext(Dispatchers.IO) {
            runCatching { FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) } }
        }
        return bmp.asImageBitmap()
    }

    private suspend fun takeMapLibreSnap(
        ctx: Context,
        cam: ThumbCam,
        pts: List<LatLon>,
        styleUri: String
    ): Pair<Bitmap, List<PointF>>? = suspendCancellableCoroutine { cont ->
        val pos = CameraPosition.Builder().target(LatLng(cam.cLat, cam.cLon)).zoom(cam.zoom).build()
        val opts = MapSnapshotter.Options(W, H_SNAP)
            .withStyleBuilder(Style.Builder().fromUri(styleUri))
            .withCameraPosition(pos)
            .withLogo(false)

        val snap = MapSnapshotter(ctx, opts)
        cont.invokeOnCancellation { runCatching { snap.cancel() } }
        snap.start(
            { res ->
                try {
                    val raw = res.bitmap
                    val pxPts = pts.map { p -> res.pixelForLatLng(LatLng(p.latitude, p.longitude)) }
                    if (cont.isActive) cont.resume(raw to pxPts)
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                } finally {
                    runCatching { snap.cancel() }
                }
            },
            { _ ->
                runCatching { snap.cancel() }
                if (cont.isActive) cont.resume(null)
            }
        )
    }

    private fun drawRouteOverlay(bmp: Bitmap, pxPts: List<PointF>) {
        if (pxPts.size < 2) return
        val cv = Canvas(bmp)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(100, 146, 110, 228)
            strokeWidth = 12f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#926EE4")
            strokeWidth = 5.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        pxPts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        cv.drawPath(path, glow)
        cv.drawPath(path, stroke)

        val s = pxPts.first()
        val e = pxPts.last()
        cv.drawCircle(s.x, s.y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#4ADE80") })
        cv.drawCircle(e.x, e.y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#3DAEE9") })
    }

    internal const val SNAP_W = W
    internal const val SNAP_H = H
}

@Composable
fun StaticRouteMapThumbnail(
    run: RunRecord,
    modifier: Modifier = Modifier,
    styleUri: String = resolveMapStyleUri("MATCH_THEME", StrideThemeState.isLight),
    showAttribution: Boolean = true
) {
    val ctx = LocalContext.current
    var img by remember(run.id, run.encodedPolyline, run.isSnapped, styleUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(run.id, run.encodedPolyline, run.distanceMeters, run.isSnapped, styleUri) {
        img = StaticMapSnapshotter.loadOrRender(ctx, run, styleUri)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(KdeBgDeep)
    ) {
        val bmp = img
        if (bmp != null) {
            Image(bmp, "Route map preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            VectorPlaceholderCanvas(run.encodedPolyline)
        }
        if (showAttribution) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                shape = RoundedCornerShape(6.dp),
                color = KdeCardBg.copy(alpha = 0.84f)
            ) {
                Text(
                    text = "© OpenFreeMap • OSM",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = KdeMuted,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun VectorPlaceholderCanvas(poly: String) {
    val pts = remember(poly) { EncodedPolylineCodec.decode(poly) }
    val cam = remember(pts) { if (pts.size >= 2) StaticMapSnapshotter.computeThumbCam(pts) else null }

    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
        val grid = if (StrideThemeState.isLight) Color(0xFFDCE2EC) else Color(0xFF23272E)
        val step = 28.dp.toPx()
        var gx = 0f
        while (gx < size.width) {
            drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), 1f)
            gx += step
        }
        val c = cam ?: return@ComposeCanvas
        val scale = maxOf(size.width / StaticMapSnapshotter.SNAP_W, size.height / StaticMapSnapshotter.SNAP_H)
        val ox = (size.width - StaticMapSnapshotter.SNAP_W * scale) / 2f
        val oy = (size.height - StaticMapSnapshotter.SNAP_H * scale) / 2f

        val path = androidx.compose.ui.graphics.Path()
        var firstOff = Offset.Zero
        var lastOff = Offset.Zero
        pts.forEachIndexed { idx, pt ->
            val off = StaticMapSnapshotter.projectPoint(pt, c, scale, ox, oy)
            if (idx == 0) {
                firstOff = off
                path.moveTo(off.x, off.y)
            } else {
                path.lineTo(off.x, off.y)
            }
            lastOff = off
        }
        drawPath(path, Color(0xFF926EE4), style = Stroke(width = 5.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color(0xFF4ADE80), radius = 6f * scale, center = firstOff)
        drawCircle(Color(0xFF3DAEE9), radius = 6f * scale, center = lastOff)
    }
}

package com.stridecoach.running.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import com.stridecoach.running.ui.theme.RunBlack
import com.stridecoach.running.ui.theme.RunBorder
import com.stridecoach.running.ui.theme.RunCardBg
import com.stridecoach.running.ui.theme.RunGreen
import com.stridecoach.running.ui.theme.RunMuted

@Composable
fun StrideGlowBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val fg = MaterialTheme.colorScheme.onBackground
    CompositionLocalProvider(LocalContentColor provides fg) {
        Box(modifier = modifier.fillMaxSize().background(RunBlack)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension * 0.82f
                val brush = Brush.radialGradient(
                    colors = listOf(
                        RunGreen.copy(alpha = 0.24f),
                        RunGreen.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, -size.height * 0.06f),
                    radius = r
                )
                drawRect(brush = brush)
            }
            content()
        }
    }
}

@Composable
fun StrideCard(
    modifier: Modifier = Modifier,
    borderColor: Color = RunBorder,
    bgColor: Color = RunCardBg,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun KpiStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFFAFAFA)
) {
    StrideCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = RunMuted)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatusPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SmoothPolylineCanvas(
    pts: List<TrackPoint>,
    targetPaceSec: Int,
    modifier: Modifier = Modifier,
    endIdx: Int = pts.lastIndex,
    strokePx: Float = 8f,
    showHead: Boolean = false
) {
    Canvas(modifier = modifier) {
        if (pts.size < 2) return@Canvas
        val minLat = pts.minOf { it.latitude }
        val maxLat = pts.maxOf { it.latitude }
        val minLon = pts.minOf { it.longitude }
        val maxLon = pts.maxOf { it.longitude }
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-5)
        val lonSpan = (maxLon - minLon).coerceAtLeast(1e-5)
        val pad = 16f
        val w = (size.width - pad * 2f).coerceAtLeast(1f)
        val h = (size.height - pad * 2f).coerceAtLeast(1f)

        fun proj(pt: TrackPoint): Offset {
            val x = pad + ((pt.longitude - minLon) / lonSpan).toFloat() * w
            val y = pad + (1f - ((pt.latitude - minLat) / latSpan).toFloat()) * h
            return Offset(x, y)
        }

        for (i in 1..pts.lastIndex) {
            if (!pts[i].isPaused) {
                drawLine(Color(0xFF2B322E), proj(pts[i - 1]), proj(pts[i]), strokePx * 0.6f, StrokeCap.Round)
            }
        }
        val limit = endIdx.coerceIn(0, pts.lastIndex)
        for (i in 1..limit) {
            val b = pts[i]
            if (!b.isPaused) {
                val c = speedGradientColor(b.speedMps, targetPaceSec)
                drawLine(c, proj(pts[i - 1]), proj(b), strokePx, StrokeCap.Round)
            }
        }
        if (showHead && limit in pts.indices) {
            val hd = proj(pts[limit])
            drawCircle(Color.White, radius = 12f, center = hd)
            drawCircle(RunGreen, radius = 7f, center = hd)
        }
    }
}

private fun speedGradientColor(speedMps: Float?, targetPaceSec: Int): Color {
    val sp = speedMps?.takeIf { it > 0.4f } ?: return PaceOnTarget
    val pace = (1000f / sp).toInt()
    val diff = pace - targetPaceSec
    return when {
        diff <= -12 -> lerp(PaceOnTarget, PaceTooFast, 0.9f)
        diff < 0 -> lerp(PaceOnTarget, PaceTooFast, (-diff / 12f).coerceIn(0f, 1f))
        diff <= 20 -> lerp(PaceOnTarget, PaceTooSlow, (diff / 20f).coerceIn(0f, 1f))
        else -> PaceTooSlow
    }
}

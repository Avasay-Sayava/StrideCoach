package com.stridecoach.running.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import kotlinx.coroutines.delay

@Composable
fun RunReplayPlayer(
    points: List<TrackPoint>,
    targetPaceSecPerKm: Int,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        Text("No GPS track available for replay.", style = MaterialTheme.typography.bodySmall)
        return
    }

    val totalElapsed = points.last().elapsedMillis.coerceAtLeast(1_000L)
    var isPlaying by remember(points) { mutableStateOf(false) }
    var speedMultiplier by remember(points) { mutableIntStateOf(10) }
    var progress by remember(points) { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, speedMultiplier, points) {
        while (isPlaying) {
            delay(50L)
            val stepFrac = (50f * speedMultiplier) / totalElapsed.toFloat()
            val next = progress + stepFrac
            if (next >= 1f) {
                progress = 1f
                isPlaying = false
            } else {
                progress = next
            }
        }
    }

    val currentMillis = (progress * totalElapsed).toLong()
    val currentIdx = points.indexOfLast { it.elapsedMillis <= currentMillis }.coerceAtLeast(0)
    val currentPt = points[currentIdx]
    val currentPace = currentPt.speedMps?.takeIf { it > 0.4f }?.let { (1000f / it).toInt() } ?: 0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Animated Route Replay", style = MaterialTheme.typography.titleMedium)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            val latSpan = (maxLat - minLat).coerceAtLeast(1e-5)
            val lonSpan = (maxLon - minLon).coerceAtLeast(1e-5)

            fun project(pt: TrackPoint): Offset {
                val x = ((pt.longitude - minLon) / lonSpan).toFloat() * size.width
                val y = (1f - ((pt.latitude - minLat) / latSpan).toFloat()) * size.height
                return Offset(x, y)
            }

            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                if (b.isPaused) continue
                drawLine(
                    color = Color(0xFF334155),
                    start = project(a),
                    end = project(b),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
            }

            for (i in 1..currentIdx) {
                val a = points[i - 1]
                val b = points[i]
                if (b.isPaused) continue
                val paceSec = b.speedMps?.takeIf { it > 0.4f }?.let { (1000f / it).toInt() } ?: targetPaceSecPerKm
                val color = when {
                    paceSec < targetPaceSecPerKm - 10 -> PaceTooFast
                    paceSec > targetPaceSecPerKm + 15 -> PaceTooSlow
                    else -> PaceOnTarget
                }
                drawLine(
                    color = color,
                    start = project(a),
                    end = project(b),
                    strokeWidth = 9f,
                    cap = StrokeCap.Round
                )
            }

            val head = project(currentPt)
            drawCircle(color = Color.White, radius = 12f, center = head)
            drawCircle(color = PaceOnTarget, radius = 7f, center = head)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(currentMillis), style = MaterialTheme.typography.labelLarge)
            Text("%.2f km".format(currentPt.cumulativeDistanceMeters / 1000.0), style = MaterialTheme.typography.labelLarge)
            Text("${formatPace(currentPace)} /km", style = MaterialTheme.typography.labelLarge)
        }

        Slider(
            value = progress,
            onValueChange = {
                isPlaying = false
                progress = it
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (progress >= 0.99f) progress = 0f
                isPlaying = !isPlaying
            }) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            listOf(1, 4, 10, 25).forEach { mult ->
                FilterChip(
                    selected = speedMultiplier == mult,
                    onClick = { speedMultiplier = mult },
                    label = { Text("${mult}x") }
                )
            }
        }
    }
}

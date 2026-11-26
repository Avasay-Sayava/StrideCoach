package com.stridecoach.running.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.components.SmoothPolylineCanvas
import com.stridecoach.running.ui.theme.RunMuted
import kotlinx.coroutines.delay

@Composable
fun RunReplayPlayer(
    points: List<TrackPoint>,
    targetPaceSecPerKm: Int,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        Text("No GPS track available for replay.", style = MaterialTheme.typography.bodySmall, color = RunMuted)
        return
    }
    val totalMs = points.last().elapsedMillis.coerceAtLeast(1_000L)
    var playing by remember(points) { mutableStateOf(false) }
    var mult by remember(points) { mutableIntStateOf(10) }
    var prog by remember(points) { mutableFloatStateOf(1f) }

    LaunchedEffect(playing, mult, points) {
        while (playing) {
            delay(50L)
            val next = prog + ((50f * mult) / totalMs.toFloat())
            if (next >= 1f) {
                prog = 1f
                playing = false
            } else {
                prog = next
            }
        }
    }

    val curMs = (prog * totalMs).toLong()
    val idx = points.indexOfLast { it.elapsedMillis <= curMs }.coerceAtLeast(0)
    val pt = points[idx]
    val pace = pt.speedMps?.takeIf { it > 0.4f }?.let { (1000f / it).toInt() } ?: 0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Animated Route Replay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SmoothPolylineCanvas(
            pts = points,
            targetPaceSec = targetPaceSecPerKm,
            endIdx = idx,
            strokePx = 9f,
            showHead = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .background(Color(0xFF0C0F0D), RoundedCornerShape(12.dp))
                .padding(10.dp)
        )
        ReplayMetricsRow(curMs, pt.cumulativeDistanceMeters, pace)
        Slider(value = prog, onValueChange = { playing = false; prog = it })
        ReplayControlsRow(playing, mult, {
            if (prog >= 0.99f) prog = 0f
            playing = !playing
        }, { mult = it })
    }
}

@Composable
private fun ReplayMetricsRow(ms: Long, distM: Double, pace: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(ms), style = MaterialTheme.typography.labelLarge)
        Text("%.2f km".format(distM / 1000.0), style = MaterialTheme.typography.labelLarge)
        Text("${formatPace(pace)} /km", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ReplayControlsRow(playing: Boolean, mult: Int, onToggle: () -> Unit, onMult: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onToggle, shape = RoundedCornerShape(999.dp)) {
            Text(if (playing) "Pause" else "Play")
        }
        listOf(1, 4, 10, 25).forEach { m ->
            FilterChip(selected = mult == m, onClick = { onMult(m) }, label = { Text("${m}x") })
        }
    }
}

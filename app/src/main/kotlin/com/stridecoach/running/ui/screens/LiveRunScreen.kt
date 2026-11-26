package com.stridecoach.running.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.tracking.LiveTrackingState
import com.stridecoach.running.core.tracking.MovementState
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow

@Composable
fun LiveRunScreen(
    liveState: LiveTrackingState,
    keepScreenAwake: Boolean,
    onToggleWakeLock: (Boolean) -> Unit,
    onStartFreeRun: () -> Unit,
    onPauseResume: () -> Unit,
    onNextStep: () -> Unit,
    onStopRun: () -> Unit
) {
    var locked by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!liveState.isActive) {
            IdleRunCard(onStartFreeRun)
            return
        }
        ActiveStepBanner(liveState)
        LiveMetricsGrid(liveState)
        liveState.snapshot?.projectedFinishMillis?.let { ProjectedFinishCard(it) }
        ToggleRow("Keep Screen Awake (WakeLock)", keepScreenAwake, onToggleWakeLock)
        ToggleRow("Pocket Touch Lock", locked) { locked = it }
        LiveControlsRow(liveState.isPaused, locked, onPauseResume, onNextStep, onStopRun)
    }
}

@Composable
private fun IdleRunCard(onStart: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No Active Run", style = MaterialTheme.typography.headlineSmall)
            Text("Start a guided workout from Coach, or begin a free run. Lock-screen controls stay active in notifications.")
            Button(onClick = onStart) { Text("Start Free Run") }
        }
    }
}

@Composable
private fun ActiveStepBanner(st: LiveTrackingState) {
    val snap = st.snapshot
    val bg = when (st.currentPaceStatus) {
        PaceStatus.ON_TARGET -> PaceOnTarget
        PaceStatus.TOO_FAST -> PaceTooFast
        PaceStatus.TOO_SLOW -> PaceTooSlow
        PaceStatus.NO_SIGNAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val rem = when {
        snap?.stepRemainingMeters != null -> "${snap.stepRemainingMeters} m left"
        snap?.stepRemainingSeconds != null -> "${formatDuration(snap.stepRemainingSeconds * 1000L)} left"
        else -> "Open segment"
    }
    Box(
        modifier = Modifier.fillMaxWidth().background(bg.copy(alpha = 0.22f), RoundedCornerShape(16.dp)).padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(snap?.activeStep?.displayName() ?: st.workout?.title ?: "Free Run", style = MaterialTheme.typography.titleLarge)
            Text(rem, style = MaterialTheme.typography.bodyMedium)
            snap?.activeRacePhase?.let { ph ->
                Text("${ph.name} • ${ph.paceBounds.formatRange()}", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LiveMetricsGrid(st: LiveTrackingState) {
    val snap = st.snapshot
    val km = "%.2f km".format((snap?.totalDistanceMeters ?: 0.0) / 1000.0)
    val dur = formatDuration(snap?.totalElapsedMillis ?: 0L)
    val pace = "${formatPace(snap?.currentPaceSecPerKm ?: 0)} /km"
    val flag = when {
        st.isPaused -> "PAUSED"
        st.movementState == MovementState.AUTO_PAUSED -> "AUTO-PAUSE"
        st.movementState == MovementState.AUTO_FINISH_SUGGESTED -> "STOPPED?"
        else -> st.currentPaceStatus.name.replace('_', ' ')
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile("Distance", km, Modifier.weight(1f))
        MetricTile("Time", dur, Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile("200m Pace", pace, Modifier.weight(1f))
        MetricTile("Status", flag, Modifier.weight(1f))
    }
}

@Composable
private fun ProjectedFinishCard(projMs: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Projected Race Finish", style = MaterialTheme.typography.labelMedium)
            Text(formatDuration(projMs), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LiveControlsRow(
    paused: Boolean,
    locked: Boolean,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPause, enabled = !locked, modifier = Modifier.weight(1f)) {
            Text(if (paused) "Resume" else "Pause")
        }
        OutlinedButton(onClick = onNext, enabled = !locked, modifier = Modifier.weight(1f)) {
            Text("Next Step")
        }
        Button(
            onClick = onStop,
            enabled = !locked,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
            modifier = Modifier.weight(1f)
        ) { Text("Stop") }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

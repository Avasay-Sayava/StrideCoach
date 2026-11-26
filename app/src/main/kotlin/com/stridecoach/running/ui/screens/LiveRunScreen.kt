package com.stridecoach.running.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.tracking.LiveTrackingState
import com.stridecoach.running.core.tracking.MovementState
import com.stridecoach.running.ui.components.OsmRouteMap
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeText
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
    val st = liveState
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Live Run", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (!st.isActive) {
            IdleRunCard(onStartFreeRun)
            LiveMapPreviewCard(st, Modifier.weight(1f))
            return
        }
        LiveHudCard(st)
        LiveMapPreviewCard(st, Modifier.weight(1f))
        if (st.isPaused) PauseConfirmCard(locked, onPauseResume, onStopRun)
        SafetyToggles(keepScreenAwake, locked, onToggleWakeLock) { locked = it }
        BottomControlsBar(st.isPaused, locked, onPauseResume, onNextStep)
    }
}

@Composable
private fun IdleRunCard(onStart: () -> Unit) {
    StrideCard {
        Text("Ready to Run", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Launch a guided workout from Coach or start an open GPS run. Lock-screen controls remain active in notifications.",
            style = MaterialTheme.typography.bodySmall,
            color = KdeMuted
        )
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start Free Run", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun LiveHudCard(st: LiveTrackingState) {
    val sn = st.snapshot
    val rm = sn?.stepRemainingMeters
    val rs = sn?.stepRemainingSeconds
    val tag = when {
        rm != null -> "$rm m left"
        rs != null -> "${formatDuration(rs * 1000L)} left"
        else -> "Open"
    }
    val sc = when (st.currentPaceStatus) {
        PaceStatus.ON_TARGET -> PaceOnTarget
        PaceStatus.TOO_FAST -> PaceTooFast
        PaceStatus.TOO_SLOW -> PaceTooSlow
        PaceStatus.NO_SIGNAL -> KdePurple
    }
    StrideCard(borderColor = sc.copy(alpha = 0.65f)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(sn?.activeStep?.displayName() ?: st.workout?.title ?: "Free Run", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill(tag, sc)
        }
        Text(
            text = formatDuration(sn?.totalElapsedMillis ?: 0L),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold
        )
        HudMetricsRow(st, sc)
        sn?.projectedFinishMillis?.let { p ->
            Text("Projected Finish: ${formatDuration(p)}", style = MaterialTheme.typography.labelLarge, color = KdePurple)
        }
    }
}

@Composable
private fun HudMetricsRow(st: LiveTrackingState, sc: Color) {
    val sn = st.snapshot
    val km = "%.2f km".format((sn?.totalDistanceMeters ?: 0.0) / 1000.0)
    val pc = "${formatPace(sn?.currentPaceSecPerKm ?: 0)} /km"
    val md = when {
        st.isPaused -> "PAUSED"
        st.movementState == MovementState.AUTO_PAUSED -> "AUTO-PAUSE"
        st.movementState == MovementState.AUTO_FINISH_SUGGESTED -> "STOPPED?"
        else -> st.currentPaceStatus.name.replace('_', ' ')
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HudMetricCol("Distance", km)
        HudMetricCol("200m Pace", pc, sc)
        HudMetricCol("Zone", md, sc)
    }
}

@Composable
private fun HudMetricCol(label: String, value: String, color: Color = KdeText) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LiveMapPreviewCard(st: LiveTrackingState, modifier: Modifier) {
    StrideCard(modifier = modifier) {
        Text("Live Street Map", style = MaterialTheme.typography.labelMedium, color = KdeMuted)
        OsmRouteMap(
            pts = st.trackPoints,
            interactive = true,
            showHead = st.trackPoints.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
private fun PauseConfirmCard(locked: Boolean, onResume: () -> Unit, onStop: () -> Unit) {
    StrideCard(borderColor = PaceTooFast.copy(alpha = 0.6f)) {
        Text("Run is paused", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Resume your workout or finish and save.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onStop,
                enabled = !locked,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PaceTooSlow),
                modifier = Modifier.weight(1f)
            ) { Text("Finish & Save") }
            Button(
                onClick = onResume,
                enabled = !locked,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Resume") }
        }
    }
}

@Composable
private fun SafetyToggles(wake: Boolean, locked: Boolean, onWake: (Boolean) -> Unit, onLock: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("WakeLock", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Switch(checked = wake, onCheckedChange = onWake)
        Text("Pocket Lock", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Switch(checked = locked, onCheckedChange = onLock)
    }
}

@Composable
private fun BottomControlsBar(paused: Boolean, locked: Boolean, onPause: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingActionButton(
            onClick = { if (!locked) onPause() },
            shape = CircleShape,
            containerColor = if (locked) KdeMuted else KdePurple,
            contentColor = Color.White,
            modifier = Modifier.size(68.dp)
        ) {
            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = "Pause or Resume", modifier = Modifier.size(34.dp))
        }
        OutlinedButton(
            onClick = onNext,
            enabled = !locked,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.padding(start = 16.dp)
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = null)
            Text("Next Step")
        }
    }
}

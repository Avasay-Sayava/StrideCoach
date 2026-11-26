package com.stridecoach.running.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.tracking.LiveTrackingState
import com.stridecoach.running.core.tracking.MovementState
import com.stridecoach.running.ui.components.FloatingMapIconButton
import com.stridecoach.running.ui.components.HoldToFinishButton
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.TelemetryStatStrip
import com.stridecoach.running.ui.components.VectorRouteMap
import com.stridecoach.running.ui.theme.KdeBorder
import com.stridecoach.running.ui.theme.KdeCardBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.KdeText
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import com.stridecoach.running.ui.theme.TabularNumberStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRunScreen(
    liveState: LiveTrackingState,
    refPaceSec: Int,
    guidePts: List<LatLon>,
    keepScreenAwake: Boolean,
    todayWorkoutLabel: String?,
    onStartTodayWorkout: (() -> Unit)?,
    onOpenPlanner: () -> Unit,
    onToggleWakeLock: (Boolean) -> Unit,
    onStartFreeRun: () -> Unit,
    onPauseResume: () -> Unit,
    onNextStep: () -> Unit,
    onStopRun: () -> Unit
) {
    var locked by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var recenterTick by remember { mutableIntStateOf(0) }
    val scaffold = rememberBottomSheetScaffoldState()

    BottomSheetScaffold(
        scaffoldState = scaffold,
        sheetPeekHeight = if (liveState.isActive) 210.dp else 168.dp,
        sheetContainerColor = KdeCardBg,
        sheetContentColor = KdeText,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = Color.Transparent,
        sheetContent = {
            if (!liveState.isActive) {
                IdleSheetBody(todayWorkoutLabel, onStartTodayWorkout, onStartFreeRun)
            } else {
                ActiveHudSheetBody(
                    liveState, keepScreenAwake, locked,
                    onToggleWakeLock, { locked = it }, onPauseResume, onNextStep, onStopRun
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            VectorRouteMap(
                pts = liveState.trackPoints,
                guidePts = guidePts,
                interactive = true,
                showHead = liveState.trackPoints.isNotEmpty(),
                colorByPace = true,
                refPaceSec = refPaceSec,
                followHead = follow && liveState.isActive,
                bottomPadDp = 210,
                recenterToken = recenterTick,
                modifier = Modifier.fillMaxSize()
            )
            LiveTopFloatingHud(
                st = liveState,
                locked = locked,
                follow = follow,
                onToggleLock = { locked = !locked },
                onOpenPlanner = onOpenPlanner,
                onRecenter = { follow = true; recenterTick++ }
            )
        }
    }
}

@Composable
private fun LiveTopFloatingHud(
    st: LiveTrackingState,
    locked: Boolean,
    follow: Boolean,
    onToggleLock: () -> Unit,
    onOpenPlanner: () -> Unit,
    onRecenter: () -> Unit
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    val accLabel = st.gpsAccM?.let { "±${it.toInt()}m" } ?: "GPS"
    val actColor = when (st.actType) {
        ActivitySegmentType.RUNNING -> KdePurple
        ActivitySegmentType.WALKING -> KdeSky
        ActivitySegmentType.STRETCH_REST -> PaceTooFast
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = topPad, start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = KdeCardBg.copy(alpha = 0.90f),
            border = BorderStroke(1.dp, KdeBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = st.snapshot?.activeStep?.displayName() ?: st.workout?.title ?: "Live Vector Map"
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val mode = if (st.isActive) "${st.actType.name.replace('_', ' ')} • $accLabel" else "Ready • $accLabel"
                    Text(mode, style = MaterialTheme.typography.labelSmall, color = actColor)
                }
                StepCountdownPill(st)
            }
        }
        if (!st.isActive) FloatingMapIconButton(Icons.AutoMirrored.Filled.AltRoute, "Plan Route", onOpenPlanner, KdePurple)
        FloatingMapIconButton(Icons.Default.MyLocation, "Center", onRecenter, tint = if (follow) KdeSky else KdeMuted)
        if (st.isActive) {
            FloatingMapIconButton(
                if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                "Pocket Lock",
                onToggleLock,
                tint = if (locked) PaceTooFast else KdeText
            )
        }
    }
}

@Composable
private fun StepCountdownPill(st: LiveTrackingState) {
    val sn = st.snapshot ?: return
    val rm = sn.stepRemainingMeters
    val rs = sn.stepRemainingSeconds
    val txt = when {
        rm != null -> "$rm m"
        rs != null -> formatDuration(rs * 1000L)
        else -> "Open"
    }
    val sc = when (st.currentPaceStatus) {
        PaceStatus.ON_TARGET -> PaceOnTarget
        PaceStatus.TOO_FAST -> PaceTooFast
        PaceStatus.TOO_SLOW -> PaceTooSlow
        PaceStatus.NO_SIGNAL -> KdePurple
    }
    StatusPill(txt, sc)
}

@Composable
private fun IdleSheetBody(
    todayLabel: String?,
    onStartToday: (() -> Unit)?,
    onStartFree: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Ready to Run", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "GPS + Hardware Step Cadence + English Voice Coach remain active even with your phone locked.",
            style = MaterialTheme.typography.bodySmall,
            color = KdeMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (todayLabel != null && onStartToday != null) {
                Button(onClick = onStartToday, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
                    Text(todayLabel, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(onClick = onStartFree, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
                Text("Open Run")
            }
        }
    }
}

@Composable
private fun ActiveHudSheetBody(
    st: LiveTrackingState,
    wake: Boolean,
    locked: Boolean,
    onWake: (Boolean) -> Unit,
    onLock: (Boolean) -> Unit,
    onPauseResume: () -> Unit,
    onNextStep: () -> Unit,
    onStopRun: () -> Unit
) {
    val sn = st.snapshot
    val distText = "%.2f km".format((sn?.totalDistanceMeters ?: 0.0) / 1000.0)
    val paceText = "${formatPace(sn?.currentPaceSecPerKm ?: 0)}/km"
    val timeText = formatDuration(sn?.totalElapsedMillis ?: 0L)
    val cadText = if (st.cadenceSpm > 0) "${st.cadenceSpm} spm" else "—"

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TelemetryStatStrip(items = listOf(timeText to "Time", distText to "Dist", paceText to "Pace", cadText to "Cadence"))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingActionButton(
                onClick = { if (!locked) onPauseResume() },
                shape = CircleShape,
                containerColor = if (locked) KdeMuted else KdePurple,
                contentColor = Color.White,
                modifier = Modifier.size(54.dp)
            ) {
                Icon(if (st.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, "Pause")
            }
            OutlinedButton(onClick = onNextStep, enabled = !locked, shape = RoundedCornerShape(999.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = null)
                Text("Next")
            }
            HoldToFinishButton(enabled = !locked, onFinished = onStopRun)
        }
        val stateMsg = when {
            st.isPaused -> "PAUSED — Tap play or hold Finish to save"
            st.movementState == MovementState.AUTO_PAUSED -> "AUTO-PAUSED (Movement stopped)"
            st.movementState == MovementState.AUTO_FINISH_SUGGESTED -> "Workout stopped? Hold Finish to save."
            else -> "Target: ${sn?.activeStep?.step?.paceBounds?.formatRange() ?: "Open pace"}"
        }
        Text(stateMsg, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Pace Readout", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
                Text(paceText, style = MaterialTheme.typography.headlineMedium.merge(TabularNumberStyle), fontWeight = FontWeight.Bold, color = KdePurple)
            }
            sn?.projectedFinishMillis?.let { pf ->
                Column(horizontalAlignment = Alignment.End) {
                    Text("Projected Finish", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
                    Text(formatDuration(pf), style = MaterialTheme.typography.titleLarge.merge(TabularNumberStyle), fontWeight = FontWeight.Bold, color = KdeSky)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("WakeLock", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
            Switch(checked = wake, onCheckedChange = onWake)
            Text("Pocket Lock", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
            Switch(checked = locked, onCheckedChange = onLock)
        }
    }
}

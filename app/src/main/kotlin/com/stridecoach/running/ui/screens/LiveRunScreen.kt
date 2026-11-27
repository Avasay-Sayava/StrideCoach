package com.stridecoach.running.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.tracking.LiveTrackingState
import com.stridecoach.running.core.tracking.MovementState
import com.stridecoach.running.ui.components.FloatingMapIconButton
import com.stridecoach.running.ui.components.HoldToFinishButton
import com.stridecoach.running.ui.components.MapCameraMode
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.TelemetryStatStrip
import com.stridecoach.running.ui.components.TopTelemetryMapHud
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRunScreen(
    liveState: LiveTrackingState,
    prefs: CoachPreferences,
    refPaceSec: Int,
    guidePts: List<LatLon>,
    userLocation: LatLon?,
    passiveSpeedKmh: Float = 0f,
    fallbackCenter: LatLon = LatLon(32.1133, 34.8044),
    todayWorkoutLabel: String?,
    onStartTodayWorkout: (() -> Unit)?,
    onOpenPlanner: () -> Unit,
    onToggleWakeLock: (Boolean) -> Unit,
    onStartActivity: (ActivityKind) -> Unit,
    onPauseResume: () -> Unit,
    onNextStep: () -> Unit,
    onStopRun: () -> Unit
) {
    var locked by remember { mutableStateOf(false) }
    LaunchedEffect(liveState.isActive, prefs.autoPocketLockOnStart) {
        if (liveState.isActive && prefs.autoPocketLockOnStart) locked = true
        if (!liveState.isActive) locked = false
    }

    var camMode by remember { mutableStateOf(MapCameraMode.FOLLOW_RUNNER) }
    var is3d by remember(prefs.defaultMap3dEnabled) { mutableStateOf(prefs.defaultMap3dEnabled) }
    var selectedKind by remember { mutableStateOf(ActivityKind.RUNNING) }
    var recenterTick by remember { mutableIntStateOf(0) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var topOverlayPx by remember { mutableIntStateOf(0) }

    val hasPath = liveState.trackPoints.size >= 2 || guidePts.size >= 2
    val scaffold = rememberBottomSheetScaffoldState()
    val sheetOffsetPx = runCatching { scaffold.bottomSheetState.requireOffset() }.getOrNull()
    val bottomOcclusionPx = if (sheetOffsetPx != null && containerHeightPx > 0) {
        (containerHeightPx - sheetOffsetPx).roundToInt().coerceIn(0, (containerHeightPx * 0.72f).toInt())
    } else 0

    BottomSheetScaffold(
        scaffoldState = scaffold,
        sheetPeekHeight = if (liveState.isActive) 220.dp else if (todayWorkoutLabel != null) 272.dp else 220.dp,
        sheetContainerColor = KdeCardBg,
        sheetContentColor = KdeText,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = Color.Transparent,
        sheetContent = {
            if (!liveState.isActive) {
                IdleSheetBody(selectedKind, { selectedKind = it }, todayWorkoutLabel, onStartTodayWorkout, onStartActivity)
            } else {
                ActiveHudSheetBody(
                    liveState, prefs.keepScreenAwake, locked,
                    onToggleWakeLock, { locked = it }, onPauseResume, onNextStep, onStopRun
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords -> containerHeightPx = coords.size.height }
        ) {
            VectorRouteMap(
                pts = liveState.trackPoints,
                guidePts = guidePts,
                userLocation = userLocation,
                interactive = true,
                showHead = liveState.trackPoints.isNotEmpty(),
                showUserDot = prefs.showUserLocationDot,
                colorByPace = true,
                smoothGradient = prefs.smoothTrackGradient,
                refPaceSec = refPaceSec,
                cameraMode = if (!hasPath && camMode == MapCameraMode.FIT_ROUTE) MapCameraMode.FOLLOW_RUNNER else camMode,
                is3dMode = is3d,
                show3dBuildings = prefs.show3dBuildings,
                showTerrainHillshade = prefs.showTerrainHillshade,
                physical3dTerrainMesh = prefs.physical3dTerrainMesh,
                terrainExaggeration = prefs.terrainExaggerationTenths / 10f,
                keepCenteredOnSheetDrag = prefs.keepMapCenteredOnSheetDrag,
                preloadTiles = prefs.preloadNearbyTiles,
                mapStyleMode = prefs.mapStyleMode,
                liveHeadingDeg = liveState.headingDeg,
                fallbackCenter = fallbackCenter,
                topOcclusionPx = topOverlayPx,
                bottomOcclusionPx = bottomOcclusionPx,
                topHudDp = if (prefs.showTopMapHud) 202 else 124,
                bottomPadDp = 272,
                recenterToken = recenterTick,
                onUserManualPan = { camMode = MapCameraMode.FREE_PAN },
                modifier = Modifier.fillMaxSize()
            )
            LiveTopFloatingHud(
                st = liveState,
                prefs = prefs,
                passiveSpeedKmh = passiveSpeedKmh,
                locked = locked,
                hasPath = hasPath,
                camMode = camMode,
                is3d = is3d,
                onMeasuredHeight = { topOverlayPx = it },
                onToggleLock = { locked = !locked },
                onOpenPlanner = onOpenPlanner,
                onPickMode = { mode ->
                    camMode = mode
                    if (mode != MapCameraMode.FREE_PAN) recenterTick++
                },
                onToggle3d = { is3d = !is3d }
            )
        }
    }
}

@Composable
private fun LiveTopFloatingHud(
    st: LiveTrackingState,
    prefs: CoachPreferences,
    passiveSpeedKmh: Float,
    locked: Boolean,
    hasPath: Boolean,
    camMode: MapCameraMode,
    is3d: Boolean,
    onMeasuredHeight: (Int) -> Unit,
    onToggleLock: () -> Unit,
    onOpenPlanner: () -> Unit,
    onPickMode: (MapCameraMode) -> Unit,
    onToggle3d: () -> Unit
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    val accLabel = st.gpsAccM?.let { "±${it.toInt()}m" } ?: "GPS"
    val actColor = when (st.actType) {
        ActivitySegmentType.RUNNING -> KdePurple
        ActivitySegmentType.WALKING -> KdeSky
        ActivitySegmentType.STRETCH_REST -> PaceTooFast
    }
    val sn = st.snapshot
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onMeasuredHeight(coords.size.height) }
            .padding(top = topPad, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    val title = sn?.activeStep?.displayName() ?: st.workout?.title ?: "Live Vector Map"
                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val gyroTxt = if (st.gyroActive && st.strideStabilityPct > 0) " • Form ${st.strideStabilityPct}%" else ""
                        val gradeTxt = if (kotlin.math.abs(st.currentGradePct) >= 1.5f) " • ${"%.0f".format(st.currentGradePct)}% slope" else ""
                        val mode = if (st.isActive) "${st.actType.name.replace('_', ' ')} • $accLabel$gyroTxt$gradeTxt" else "Ready • $accLabel"
                        Text(mode, style = MaterialTheme.typography.labelSmall, color = actColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StepCountdownPill(st)
                }
            }
            if (!st.isActive) FloatingMapIconButton(Icons.AutoMirrored.Filled.AltRoute, "Plan Route", onOpenPlanner, KdePurple)
            if (st.isActive) {
                FloatingMapIconButton(
                    if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    "Pocket Lock",
                    onToggleLock,
                    tint = if (locked) PaceTooFast else KdeText
                )
            }
        }

        if (prefs.showTopMapHud) {
            val spdKmh = if (st.isActive) st.currentSpeedKmh else passiveSpeedKmh
            val livePaceSec = when {
                st.isActive && st.currentSpeedKmh >= 1.4f -> (3600f / st.currentSpeedKmh).roundToInt()
                st.isActive -> sn?.currentPaceSecPerKm ?: 0
                passiveSpeedKmh >= 1.5f -> (3600f / passiveSpeedKmh).roundToInt()
                else -> 0
            }
            val paceStr = if (livePaceSec > 0) "${formatPace(livePaceSec)}/km" else "--:--/km"
            TopTelemetryMapHud(
                speedText = if (prefs.showSpeedKmh) "%.1f km/h".format(spdKmh) else null,
                timeText = formatDuration(sn?.totalElapsedMillis ?: 0L),
                paceText = paceStr,
                distText = "%.2f km".format((sn?.totalDistanceMeters ?: 0.0) / 1000.0)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MapFocusControlBar(
                camMode = camMode,
                is3d = is3d,
                hasPath = hasPath,
                onPickMode = onPickMode,
                onToggle3d = onToggle3d
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
    selectedKind: ActivityKind,
    onSelectKind: (ActivityKind) -> Unit,
    todayLabel: String?,
    onStartToday: (() -> Unit)?,
    onStartActivity: (ActivityKind) -> Unit
) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    val startVerb = when (selectedKind) {
        ActivityKind.RUNNING -> "Start Open Run"
        ActivityKind.WALKING -> "Start Walk"
        ActivityKind.CYCLING -> "Start Ride"
        ActivityKind.HIKING -> "Start Hike"
        ActivityKind.OTHER -> "Start"
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Ready to Track", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(ActivityKind.RUNNING, ActivityKind.WALKING, ActivityKind.CYCLING, ActivityKind.HIKING).forEach { k ->
                FilterChip(
                    selected = selectedKind == k,
                    onClick = { onSelectKind(k) },
                    colors = cc,
                    label = { Text(k.label, maxLines = 1, softWrap = false) }
                )
            }
        }
        if (todayLabel != null && onStartToday != null) {
            Button(
                onClick = onStartToday,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("Start Today: $todayLabel", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        OutlinedButton(
            onClick = { onStartActivity(selectedKind) },
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(startVerb, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
    val distM = sn?.totalDistanceMeters ?: 0.0
    val elapsedMs = sn?.totalElapsedMillis ?: 0L
    val distKm = distM / 1000.0
    val distText = "%.2f km".format(distKm)
    val overallAvgPaceSec = if (distM >= 25.0 && elapsedMs > 0L) {
        ((elapsedMs / 1000.0) / distKm).roundToInt()
    } else {
        sn?.currentPaceSecPerKm ?: 0
    }
    val overallAvgSpeedKmh = if (elapsedMs > 2_000L && distM >= 5.0) {
        ((distM / (elapsedMs / 1000.0)) * 3.6).toFloat()
    } else {
        0f
    }
    val paceText = if (overallAvgPaceSec > 0) "${formatPace(overallAvgPaceSec)}/km" else "--:--/km"
    val timeText = formatDuration(elapsedMs)
    val cadText = if (st.cadenceSpm > 0) "${st.cadenceSpm} spm" else "—"
    val calFactor = if (st.activityKind == ActivityKind.CYCLING) 32.0 else 62.0
    val liveKcal = (distKm * calFactor).roundToInt()
    val elevGainText = "+${st.elevM}m"

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TelemetryStatStrip(
            items = listOf(
                timeText to "Total Time",
                distText to "Total Dist",
                paceText to "Avg Pace",
                "%.1f km/h".format(overallAvgSpeedKmh) to "Avg Speed"
            )
        )
        TelemetryStatStrip(
            items = listOf(
                cadText to "Cadence",
                "$liveKcal kcal" to "Calories",
                elevGainText to "Elev Gain",
                "${st.steps}" to "Steps"
            )
        )
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
                Text("Next", maxLines = 1, softWrap = false)
            }
            HoldToFinishButton(enabled = !locked, onFinished = onStopRun)
        }
        val stateMsg = when {
            locked -> "POCKET LOCKED — Toggle switch below or lock icon to prevent mistouches"
            st.isPaused -> "PAUSED — Tap play or hold Finish to save"
            st.movementState == MovementState.AUTO_PAUSED -> "AUTO-PAUSED (Movement stopped)"
            st.movementState == MovementState.AUTO_FINISH_SUGGESTED -> "Workout stopped? Hold Finish to save."
            else -> "Target: ${sn?.activeStep?.step?.paceBounds?.formatRange() ?: "Open pace"}"
        }
        Text(stateMsg, style = MaterialTheme.typography.bodySmall, color = if (locked) PaceTooFast else KdeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Workout Avg Pace", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
                Text(paceText, style = MaterialTheme.typography.headlineSmall.merge(TabularNumberStyle), fontWeight = FontWeight.Bold, color = KdePurple)
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

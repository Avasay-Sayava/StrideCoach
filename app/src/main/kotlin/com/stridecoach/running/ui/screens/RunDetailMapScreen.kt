package com.stridecoach.running.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.PaceZone
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import com.stridecoach.running.core.domain.engine.SnapMergeResult
import com.stridecoach.running.core.domain.engine.ReplayHead
import com.stridecoach.running.core.domain.engine.SplitEvaluation
import com.stridecoach.running.core.domain.engine.TopographyPaceEngine
import com.stridecoach.running.core.domain.engine.TopographySummary
import com.stridecoach.running.core.domain.engine.TrackInterpolator
import com.stridecoach.running.core.domain.engine.UnsnappedReason
import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.DetectedSegment
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import com.stridecoach.running.ui.components.FloatingMapIconButton
import com.stridecoach.running.ui.components.MapCameraMode
import com.stridecoach.running.ui.components.MapCompassButton
import com.stridecoach.running.ui.components.RenameExerciseDialog
import com.stridecoach.running.ui.components.SheetDetent
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.TelemetryStatStrip
import com.stridecoach.running.ui.components.ThreeStageBottomSheet
import com.stridecoach.running.ui.components.TopTelemetryMapHud
import com.stridecoach.running.ui.components.VectorRouteMap
import com.stridecoach.running.ui.components.rememberThreeStageSheetState
import com.stridecoach.running.ui.theme.KdeBorder
import com.stridecoach.running.ui.theme.KdeCardAlt
import com.stridecoach.running.ui.theme.KdeCardBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.KdeText
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import com.stridecoach.running.ui.theme.TabularNumberStyle
import com.stridecoach.running.ui.theme.paceZoneColor
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val SPEED_CYCLE_STEPS = listOf(1f, 2f, 4f, 10f, 25f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailMapScreen(
    run: RunRecord,
    prefs: CoachPreferences,
    currentBaselinePaceSec: Int,
    snapPreview: SnapMergeResult?,
    isSnapping: Boolean,
    isFetchingTopo: Boolean,
    onToggleSnap: (Boolean) -> Unit,
    onToggleSnapSection: (Int) -> Unit,
    onSnapAllGreenery: (Boolean) -> Unit,
    onFetchDemElevation: () -> Unit,
    onMoveToTrash: () -> Unit,
    onRenameRun: (String) -> Unit,
    onBack: () -> Unit,
    onTrimRange: (Long, Long) -> Unit,
    onExportGpx: () -> Unit
) {
    val pts = remember(run.id, run.encodedPolyline, run.isSnapped, run.durationMillis, run.trackPoints) {
        val flat = run.trackPoints.size < 2 || run.trackPoints.mapNotNull { it.speedMps }.distinct().size <= 1
        if (run.trackPoints.isNotEmpty() && (!flat || run.source != RunSource.BACKUP_IMPORT || run.splits.isEmpty())) {
            run.trackPoints
        } else {
            EncodedPolylineCodec.synthesizeReplayTrack(
                run.encodedPolyline, run.startTimestampMillis, run.durationMillis, run.splits
            )
        }
    }
    val segs = remember(run.id, pts.size) { run.segs.ifEmpty { ActivitySegmentClassifier.classify(pts) } }
    val stats = remember { RunAnalyticsEngine() }
    val evals = remember(run.splits, currentBaselinePaceSec) {
        stats.evaluateSplitDiscipline(run.splits, currentBaselinePaceSec)
    }
    val topo = remember(pts) {
        TopographyPaceEngine.analyzeTrack(pts, run.paceSecondsPerKm.takeIf { it > 0 } ?: currentBaselinePaceSec)
    }

    var playing by remember(run.id) { mutableStateOf(false) }
    var speedSlider by remember(run.id) { mutableFloatStateOf(speedToSlider(10f)) }
    val mult = sliderToSpeed(speedSlider)
    val liveMult = rememberUpdatedState(mult)

    var prog by remember(run.id) { mutableFloatStateOf(1f) }
    var scrubbing by remember(run.id) { mutableStateOf(false) }
    var colorByPace by remember(run.id) { mutableStateOf(true) }
    var camMode by remember(run.id) { mutableStateOf(MapCameraMode.FIT_ROUTE) }
    var is3d by remember(run.id, prefs.defaultMap3dEnabled) { mutableStateOf(prefs.defaultMap3dEnabled) }
    var recenterTick by remember { mutableIntStateOf(0) }
    var sheetTab by remember(run.id) { mutableIntStateOf(0) }
    var trimRange by remember(run.id, run.durationMillis) { mutableStateOf(0f..1f) }
    var hiRange by remember(run.id) { mutableStateOf<IntRange?>(null) }
    var hiTrigger by remember(run.id) { mutableIntStateOf(0) }
    var showRename by remember(run.id) { mutableStateOf(false) }
    var mapBearing by remember(run.id) { mutableFloatStateOf(0f) }
    var northLocked by remember(run.id) { mutableStateOf(false) }
    var resetNorthTick by remember(run.id) { mutableIntStateOf(0) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var topOverlayPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(run.encodedPolyline, run.isSnapped) { hiRange = null }

    val totalMs = pts.lastOrNull()?.elapsedMillis?.coerceAtLeast(1_000L) ?: run.durationMillis.coerceAtLeast(1_000L)
    LaunchedEffect(playing, run.id, totalMs) {
        if (!playing) return@LaunchedEffect
        var prevNs = withFrameNanos { it }
        var curProg = prog
        while (playing) {
            val nowNs = withFrameNanos { it }
            val dtMs = ((nowNs - prevNs).coerceIn(0L, 100_000_000L)) / 1_000_000f
            prevNs = nowNs
            val next = curProg + ((dtMs * liveMult.value) / totalMs.toFloat())
            curProg = next
            if (next >= 1f) {
                prog = 1f
                playing = false
            } else {
                prog = next
            }
        }
    }

    val curMs = (prog * totalMs).toLong()
    val replayHead = remember(pts, curMs, playing, scrubbing, prog) {
        if ((playing || scrubbing || prog < 0.999f) && pts.size >= 2) TrackInterpolator.sampleAt(pts, curMs) else null
    }
    val trimStartIdx = if (sheetTab == 3 && trimRange.start > 0.01f && pts.isNotEmpty()) {
        val cutMs = (run.durationMillis * trimRange.start).toLong()
        pts.indexOfFirst { it.elapsedMillis >= cutMs }.takeIf { it > 0 }
    } else null
    val trimEndIdx = if (sheetTab == 3 && trimRange.endInclusive < 0.99f && pts.isNotEmpty()) {
        val cutMs = (run.durationMillis * trimRange.endInclusive).toLong()
        pts.indexOfFirst { it.elapsedMillis >= cutMs }.takeIf { it in 1 until pts.lastIndex }
    } else null

    val density = LocalDensity.current
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peekSheetPx = with(density) { (204.dp + navBarPad).roundToPx() }
    val sheetState = rememberThreeStageSheetState(SheetDetent.PEEK)
    val scope = rememberCoroutineScope()
    val bottomOcclusionPx = sheetState.heightPx
    val targetHiBottomPx = if (sheetState.detent == SheetDetent.PEEK) peekSheetPx else (containerHeightPx * 0.50f).roundToInt().coerceAtLeast(peekSheetPx)

    ThreeStageBottomSheet(
        state = sheetState,
        peekHeightDp = 204.dp + navBarPad,
        halfFraction = 0.50f,
        fullFraction = 0.90f,
        sheetContent = {
            RunDetailSheetBody(
                run = run,
                pts = pts,
                replayHead = replayHead,
                baselinePaceSec = currentBaselinePaceSec,
                topo = topo,
                isFetchingTopo = isFetchingTopo,
                playing = playing,
                speedSlider = speedSlider,
                speedMult = mult,
                prog = prog,
                curMs = curMs,
                totalMs = totalMs,
                sheetTab = sheetTab,
                evals = evals,
                segs = segs,
                trimRange = trimRange,
                snapPreview = snapPreview,
                isSnapping = isSnapping,
                hiRange = hiRange,
                onClearHighlight = { hiRange = null },
                onTogglePlay = {
                    if (prog >= 0.99f) prog = 0f
                    playing = !playing
                },
                onSpeedSlider = { speedSlider = it },
                onCycleSpeed = {
                    val curRounded = SPEED_CYCLE_STEPS.minByOrNull { kotlin.math.abs(it - mult) } ?: 1f
                    val next = SPEED_CYCLE_STEPS[(SPEED_CYCLE_STEPS.indexOf(curRounded) + 1) % SPEED_CYCLE_STEPS.size]
                    speedSlider = speedToSlider(next)
                },
                onScrub = { playing = false; scrubbing = true; prog = it },
                onScrubDone = { scrubbing = false },
                onTab = { sheetTab = it },
                onHighlight = { rng ->
                    if (hiRange == rng) {
                        hiRange = null
                    } else {
                        hiRange = rng
                        hiTrigger++
                        camMode = MapCameraMode.FREE_PAN
                        if (sheetState.detent == SheetDetent.FULL) scope.launch { sheetState.animateTo(SheetDetent.HALF) }
                    }
                },
                onTrimRange = { trimRange = it },
                onApplyTrim = onTrimRange,
                onToggleSnap = onToggleSnap,
                onToggleSnapSection = onToggleSnapSection,
                onSnapAllGreenery = onSnapAllGreenery,
                onFetchDemElevation = onFetchDemElevation
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords -> containerHeightPx = coords.size.height }
        ) {
            VectorRouteMap(
                pts = pts,
                replayHead = replayHead,
                interactive = true,
                showHead = pts.size >= 2,
                showUserDot = false,
                colorByPace = colorByPace,
                smoothGradient = prefs.smoothTrackGradient,
                refPaceSec = currentBaselinePaceSec,
                highlightRange = hiRange,
                highlightTrigger = hiTrigger,
                animateHighlight = prefs.animateSectionHighlight,
                trimStartIdx = trimStartIdx,
                trimEndIdx = trimEndIdx,
                cameraMode = camMode,
                is3dMode = is3d,
                show3dBuildings = prefs.show3dBuildings,
                showTerrainHillshade = prefs.showTerrainHillshade,
                physical3dTerrainMesh = prefs.physical3dTerrainMesh,
                terrainExaggeration = prefs.terrainExaggerationTenths / 10f,
                keepCenteredOnSheetDrag = prefs.keepMapCenteredOnSheetDrag,
                preloadTiles = prefs.preloadNearbyTiles,
                mapStyleMode = prefs.mapStyleMode,
                liveHeadingDeg = replayHead?.bearingDeg,
                topOcclusionPx = topOverlayPx,
                bottomOcclusionPx = bottomOcclusionPx,
                highlightBottomOcclusionPx = targetHiBottomPx,
                topHudDp = if (prefs.showTopMapHud) 202 else 124,
                bottomPadDp = 252,
                recenterToken = recenterTick,
                playing = playing,
                playbackRatePerSec = if (playing) mult / (totalMs / 1000f) else 0f,
                northLockedInFollow = northLocked,
                resetNorthToken = resetNorthTick,
                onUserManualPan = { camMode = MapCameraMode.FREE_PAN },
                onMapTap = { if (hiRange != null) hiRange = null },
                onBearingChanged = { mapBearing = it },
                modifier = Modifier.fillMaxSize()
            )
            DetailMapControlsOverlay(
                run = run,
                prefs = prefs,
                livePaceSec = replayHead?.paceSec ?: run.paceSecondsPerKm,
                liveSpeedKmh = replayHead?.speedKmh ?: if (run.paceSecondsPerKm > 0) 3600f / run.paceSecondsPerKm else 0f,
                liveDistM = replayHead?.distM ?: run.distanceMeters.toDouble(),
                liveElapsedMs = replayHead?.elapsedMs ?: run.durationMillis,
                hasPath = pts.size >= 2,
                colorByPace = colorByPace,
                camMode = camMode,
                is3d = is3d,
                bearingDeg = mapBearing,
                northLocked = northLocked,
                onMeasuredHeight = { topOverlayPx = it },
                onBack = onBack,
                onToggleColor = { colorByPace = !colorByPace },
                onExportGpx = onExportGpx,
                onTrash = onMoveToTrash,
                onOpenRename = { showRename = true },
                onPickMode = { mode ->
                    camMode = mode
                    if (mode == MapCameraMode.FIT_ROUTE || mode == MapCameraMode.FOLLOW_RUNNER) {
                        recenterTick++
                    }
                },
                onToggle3d = { is3d = !is3d },
                onCompassClick = {
                    if (camMode == MapCameraMode.FOLLOW_RUNNER) northLocked = !northLocked
                    else resetNorthTick++
                }
            )
        }
    }
    if (showRename) {
        RenameExerciseDialog(
            currentName = run.name,
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                showRename = false
                onRenameRun(newName)
            }
        )
    }
}

@Composable
private fun DetailMapControlsOverlay(
    run: RunRecord,
    prefs: CoachPreferences,
    livePaceSec: Int,
    liveSpeedKmh: Float,
    liveDistM: Double,
    liveElapsedMs: Long,
    hasPath: Boolean,
    colorByPace: Boolean,
    camMode: MapCameraMode,
    is3d: Boolean,
    bearingDeg: Float,
    northLocked: Boolean,
    onMeasuredHeight: (Int) -> Unit,
    onBack: () -> Unit,
    onToggleColor: () -> Unit,
    onExportGpx: () -> Unit,
    onTrash: () -> Unit,
    onOpenRename: () -> Unit,
    onPickMode: (MapCameraMode) -> Unit,
    onToggle3d: () -> Unit,
    onCompassClick: () -> Unit
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
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
            FloatingMapIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            RunTitleButton(run.name, Modifier.weight(1f), onOpenRename)
            FloatingMapIconButton(Icons.Default.Palette, "Pace colors", onToggleColor, if (colorByPace) KdePurple else KdeMuted)
            FloatingMapIconButton(Icons.Default.Share, "Export GPX", onExportGpx, KdeSky)
            FloatingMapIconButton(Icons.Default.DeleteOutline, "Move to Trash", onTrash, PaceTooSlow)
        }

        if (prefs.showTopMapHud) {
            TopTelemetryMapHud(
                speedText = if (prefs.showSpeedKmh) "%.1f km/h".format(liveSpeedKmh) else null,
                timeText = formatDuration(liveElapsedMs),
                paceText = "${formatPace(livePaceSec)}/km",
                distText = "${"%.2f".format(liveDistM / 1000.0)} km"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MapCompassButton(bearingDeg, camMode == MapCameraMode.FOLLOW_RUNNER && northLocked, onCompassClick)
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
fun MapFocusControlBar(
    camMode: MapCameraMode,
    is3d: Boolean,
    hasPath: Boolean = true,
    onPickMode: (MapCameraMode) -> Unit,
    onToggle3d: () -> Unit
) {
    val visibleModes = remember(hasPath) {
        if (hasPath) {
            listOf(MapCameraMode.FIT_ROUTE, MapCameraMode.FOLLOW_RUNNER, MapCameraMode.FREE_PAN)
        } else {
            listOf(MapCameraMode.FOLLOW_RUNNER, MapCameraMode.FREE_PAN)
        }
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = KdeCardBg.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, KdeBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleModes.forEach { m ->
                FocusModePill(m, camMode == m) { onPickMode(m) }
            }
            Box(modifier = Modifier.size(width = 1.dp, height = 16.dp).background(KdeBorder))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (is3d) KdeSky.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onToggle3d() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ViewInAr, contentDescription = null, tint = if (is3d) KdeSky else KdeMuted, modifier = Modifier.size(14.dp))
                    Text(
                        text = if (is3d) "3D" else "2D",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (is3d) KdeSky else KdeMuted,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusModePill(m: MapCameraMode, active: Boolean, onClick: () -> Unit) {
    val icon = when (m) {
        MapCameraMode.FIT_ROUTE -> Icons.Default.CenterFocusStrong
        MapCameraMode.FOLLOW_RUNNER -> Icons.Default.MyLocation
        MapCameraMode.FREE_PAN -> Icons.Default.Explore
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) KdePurple.copy(alpha = 0.28f) else Color.Transparent)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (active) Icon(icon, contentDescription = null, tint = KdePurple, modifier = Modifier.size(14.dp))
            Text(
                text = m.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) KdePurple else KdeMuted,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun RunDetailSheetBody(
    run: RunRecord,
    pts: List<TrackPoint>,
    replayHead: ReplayHead?,
    baselinePaceSec: Int,
    topo: TopographySummary?,
    isFetchingTopo: Boolean,
    playing: Boolean,
    speedSlider: Float,
    speedMult: Float,
    prog: Float,
    curMs: Long,
    totalMs: Long,
    sheetTab: Int,
    evals: List<SplitEvaluation>,
    segs: List<DetectedSegment>,
    trimRange: ClosedFloatingPointRange<Float>,
    snapPreview: SnapMergeResult?,
    isSnapping: Boolean,
    hiRange: IntRange?,
    onClearHighlight: () -> Unit,
    onTogglePlay: () -> Unit,
    onSpeedSlider: (Float) -> Unit,
    onCycleSpeed: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubDone: () -> Unit,
    onTab: (Int) -> Unit,
    onHighlight: (IntRange) -> Unit,
    onTrimRange: (ClosedFloatingPointRange<Float>) -> Unit,
    onApplyTrim: (Long, Long) -> Unit,
    onToggleSnap: (Boolean) -> Unit,
    onToggleSnapSection: (Int) -> Unit,
    onSnapAllGreenery: (Boolean) -> Unit,
    onFetchDemElevation: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ReplayDockRow(
            playing = playing,
            prog = prog,
            curMs = curMs,
            totalMs = totalMs,
            wholeRunSummary = "${formatPace(run.paceSecondsPerKm)}/km • ${run.caloriesBurned} kcal",
            speedSlider = speedSlider,
            speedMult = speedMult,
            onTogglePlay = onTogglePlay,
            onScrub = onScrub,
            onScrubDone = onScrubDone,
            onSpeedSlider = onSpeedSlider,
            onCycleSpeed = onCycleSpeed
        )
        WholeRunTelemetryStrip(run)
        ActionTabsRow(sheetTab, onTab)
        val activeSegIdx = replayHead?.elapsedMs?.let { t -> segs.indexOfFirst { t in it.startMs..it.endMs } }
        when (sheetTab) {
            0 -> SplitsTable(evals, baselinePaceSec, pts, replayHead?.currentKm, hiRange, onHighlight, onClearHighlight)
            1 -> TopographyPanel(run, topo, isFetchingTopo, replayHead?.let { (it.distM / 1000.0).toFloat() }, onFetchDemElevation)
            2 -> SegmentsList(segs, pts, activeSegIdx, onHighlight)
            3 -> TwoSidedTrimmerPanel(run, pts, trimRange, onTrimRange, onApplyTrim)
            else -> SnapStreetsTogglePanel(run, snapPreview, isSnapping, pts, onToggleSnap, onToggleSnapSection, onSnapAllGreenery, onHighlight)
        }
    }
}

@Composable
private fun WholeRunTelemetryStrip(run: RunRecord) {
    val speedTxt = formatSpeedKmh(run.paceSecondsPerKm)
    val avgPaceTxt = "${formatPace(run.paceSecondsPerKm)}/km"
    val distTxt = "${"%.2f".format(run.km())} km"
    val tail = if (run.elevGainM > 0) "+${run.elevGainM}m" to "Elev Gain" else "${run.caloriesBurned} kcal" to "Calories"
    TelemetryStatStrip(
        items = listOf(
            distTxt to "Total Dist",
            avgPaceTxt to "Avg Pace",
            speedTxt to "Avg Speed",
            tail
        )
    )
}

@Composable
private fun ReplayDockRow(
    playing: Boolean,
    prog: Float,
    curMs: Long,
    totalMs: Long,
    wholeRunSummary: String,
    speedSlider: Float,
    speedMult: Float,
    onTogglePlay: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubDone: () -> Unit,
    onSpeedSlider: (Float) -> Unit,
    onCycleSpeed: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(onClick = onTogglePlay, shape = CircleShape, color = KdePurple, modifier = Modifier.size(44.dp)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Replay", tint = Color.White)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = prog,
                onValueChange = onScrub,
                onValueChangeFinished = onScrubDone,
                colors = SliderDefaults.colors(thumbColor = KdePurple, activeTrackColor = KdePurple),
                modifier = Modifier.height(26.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatDuration(curMs)} / ${formatDuration(totalMs)}", style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), color = KdeMuted)
                Text(wholeRunSummary, style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), color = KdeText, fontWeight = FontWeight.Bold)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("SPEED", style = MaterialTheme.typography.labelSmall, color = KdeMuted, fontWeight = FontWeight.Bold)
        Slider(
            value = speedSlider,
            onValueChange = onSpeedSlider,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = KdeSky, activeTrackColor = KdeSky, inactiveTrackColor = KdeBorder),
            modifier = Modifier.weight(1f).height(26.dp)
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = KdeSky.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, KdeSky.copy(alpha = 0.45f)),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { onCycleSpeed() }
        ) {
            Text(
                text = formatSpeedMult(speedMult),
                style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle),
                color = KdeSky,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RunTitleButton(name: String, modifier: Modifier, onOpenRename: () -> Unit) {
    Surface(
        onClick = onOpenRename,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = KdeCardBg.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, KdeBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = KdeText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Edit, contentDescription = "Rename Exercise", tint = KdeMuted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ActionTabsRow(tab: Int, onTab: (Int) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdeSky.copy(alpha = 0.22f), selectedLabelColor = KdeSky)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Splits", "Topo", "Segs", "Trim", "Snap").forEachIndexed { idx, lbl ->
            ActionTabChip(lbl, tab == idx, cc, Modifier.weight(1f)) { onTab(idx) }
        }
    }
}

@Composable
private fun ActionTabChip(label: String, selected: Boolean, colors: SelectableChipColors, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = colors,
        modifier = modifier,
        label = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) } }
    )
}

@Composable
private fun TopographyPanel(
    run: RunRecord,
    topo: TopographySummary?,
    busy: Boolean,
    liveDistKm: Float?,
    onFetchDem: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("TERRAIN & GRADE-ADJUSTED PACE", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = onFetchDem,
            enabled = !busy,
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(if (topo == null) "Load DEM" else "Refresh DEM", maxLines = 1, softWrap = false)
        }
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KdeSky)
    if (topo == null) {
        Text(
            "No altitude samples recorded yet. Tap Load DEM to pull terrain elevation & compute Grade-Adjusted Pace.",
            style = MaterialTheme.typography.bodySmall,
            color = KdeMuted
        )
        return
    }
    val gapPace = topo.gradeAdjustedPaceSec.takeIf { it > 0 } ?: run.gradeAdjustedPaceSec
    TelemetryStatStrip(
        items = listOf(
            "+${topo.gainMeters}m" to "Gain",
            "-${topo.lossMeters}m" to "Loss",
            "${"%.1f".format(topo.maxGradePct)}%" to "Max Grade",
            "${formatPace(gapPace)}/km" to "GAP Effort"
        )
    )
    Text(
        "Elevation range: ${topo.minAltMeters}m .. ${topo.maxAltMeters}m • Flat-effort GAP ${formatPace(gapPace)}/km vs Actual ${formatPace(run.paceSecondsPerKm)}/km",
        style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle),
        color = KdeMuted
    )
    ElevationProfileCanvas(topo, liveDistKm, Modifier.fillMaxWidth().height(92.dp))
}

@Composable
private fun ElevationProfileCanvas(topo: TopographySummary, liveDistKm: Float?, modifier: Modifier = Modifier) {
    val pts = topo.profilePoints
    if (pts.size < 2) return
    val minA = topo.minAltMeters.toFloat()
    val span = (topo.maxAltMeters - topo.minAltMeters).coerceAtLeast(5).toFloat()
    val maxD = pts.last().distKm.coerceAtLeast(0.05f)

    Canvas(
        modifier = modifier
            .background(KdeCardAlt.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        val path = Path()
        pts.forEachIndexed { idx, s ->
            val x = (s.distKm / maxD) * size.width
            val y = size.height - ((s.altitudeM - minA) / span) * (size.height * 0.86f) - size.height * 0.06f
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, KdeSky, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val x0 = (a.distKm / maxD) * size.width
            val x1 = (b.distKm / maxD) * size.width
            val y0 = size.height - ((a.altitudeM - minA) / span) * (size.height * 0.86f) - size.height * 0.06f
            val y1 = size.height - ((b.altitudeM - minA) / span) * (size.height * 0.86f) - size.height * 0.06f
            val gradeCol = when {
                b.gradePct > 3.5f -> PaceTooSlow
                b.gradePct > 1.2f -> PaceTooFast
                b.gradePct < -2.0f -> PaceOnTarget
                else -> KdePurple
            }
            drawLine(gradeCol, Offset(x0, y0), Offset(x1, y1), strokeWidth = 4.5f)
        }
        if (liveDistKm != null) {
            val clamped = liveDistKm.coerceIn(0f, maxD)
            val lx = (clamped / maxD) * size.width
            drawLine(Color.White.copy(alpha = 0.5f), Offset(lx, 0f), Offset(lx, size.height), strokeWidth = 2.5f)
            val nearest = pts.minByOrNull { kotlin.math.abs(it.distKm - clamped) }
            if (nearest != null) {
                val ly = size.height - ((nearest.altitudeM - minA) / span) * (size.height * 0.86f) - size.height * 0.06f
                drawCircle(Color.White, radius = 8f, center = Offset(lx, ly))
                drawCircle(KdeSky, radius = 4.5f, center = Offset(lx, ly))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapStreetsTogglePanel(
    run: RunRecord,
    res: SnapMergeResult?,
    busy: Boolean,
    pts: List<TrackPoint>,
    onToggleSnap: (Boolean) -> Unit,
    onToggleSection: (Int) -> Unit,
    onSnapAll: (Boolean) -> Unit,
    onHighlight: (IntRange) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text("PARK-SAFE STREET SNAPPING", style = MaterialTheme.typography.labelMedium, color = KdeText, fontWeight = FontWeight.Bold)
            Text(
                "Clean GPS drift onto OSM streets while keeping park trails and stop loops.",
                style = MaterialTheme.typography.bodySmall,
                color = KdeMuted
            )
        }
        Switch(checked = run.isSnapped, onCheckedChange = onToggleSnap, enabled = !busy)
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KdePurple)
    if (!run.isSnapped) return

    val stPct = res?.snappedPct ?: run.snapStreetPct
    val grPct = res?.greeneryPct ?: run.snapGreeneryPct
    val loops = res?.circlesKept ?: run.snapLoopsKept

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusPill("$stPct% Streets", KdePurple)
        StatusPill("$grPct% Greenery Kept", PaceOnTarget)
        if (loops > 0) StatusPill("$loops Stop Loops", KdeSky)
    }

    if (res == null) {
        Text("Turn Snap off and on to inspect individual park sections.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        return
    }
    if (res.unsnappedSections.isEmpty()) {
        Text("Entire route aligns with mapped streets.", style = MaterialTheme.typography.bodySmall, color = PaceOnTarget)
        return
    }

    val allForced = res.unsnappedSections.all { it.id in res.forcedSnapSectionIds }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("UNSNAPPED SECTIONS (${res.unsnappedSections.size})", style = MaterialTheme.typography.labelSmall, color = KdeMuted, fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = { onSnapAll(!allForced) },
            enabled = !busy,
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(if (allForced) "Restore All Raw" else "Snap All Greenery", maxLines = 1, softWrap = false)
        }
    }

    res.unsnappedSections.forEach { sec ->
        val forced = sec.id in res.forcedSnapSectionIds
        val reasonLbl = if (sec.reason == UnsnappedReason.STOP_LOOP) "Stop Loop" else "Greenery / Trail"
        val badgeColor = if (sec.reason == UnsnappedReason.STOP_LOOP) KdeSky else PaceOnTarget
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(KdeCardAlt.copy(alpha = 0.5f))
                .clickable {
                    val s = sec.startIdx.coerceIn(0, pts.lastIndex.coerceAtLeast(0))
                    val e = sec.endIdx.coerceIn(s, pts.lastIndex.coerceAtLeast(s))
                    if (e > s) onHighlight(s..e)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StatusPill("$reasonLbl • ${sec.distMeters}m", badgeColor)
                Text("${sec.avgDriftMeters}m off road • Tap to focus", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            }
            OutlinedButton(
                onClick = { onToggleSection(sec.id) },
                enabled = !busy,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(if (forced) "Keep Raw" else "Snap", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun SplitsTable(
    evals: List<SplitEvaluation>,
    baselinePaceSec: Int,
    pts: List<TrackPoint>,
    activeKm: Int?,
    selectedRange: IntRange?,
    onSelect: (IntRange) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("KM SPLITS (tap to toggle)", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedRange != null) {
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF3DAEE9),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF3DAEE9).copy(alpha = 0.16f))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            Text("Fitness ref ${formatPace(baselinePaceSec)}/km", style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = KdePurple)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PaceZone.entries.forEach { z ->
            val col = paceZoneColor(z.zoneNum)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(modifier = Modifier.size(7.dp).background(col, CircleShape))
                Text(z.label, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            }
        }
    }
    if (evals.isEmpty()) {
        Text("No split checkpoints recorded for this run.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        return
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("KM", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(0.5f))
        Text("PACE", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(0.9f))
        Text("ZONE BAR", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(1.7f))
        Text("ELEV", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(0.7f))
    }
    evals.forEachIndexed { i, ev ->
        val rowRange = findKmRange(pts, ev.split.splitNumber)
        KmSplitRow(
            ev = ev,
            evenRow = i % 2 == 0,
            isLiveKm = activeKm == ev.split.splitNumber,
            isSelected = selectedRange != null && rowRange == selectedRange,
            onClick = { rowRange?.let(onSelect) }
        )
    }
}

@Composable
private fun KmSplitRow(
    ev: SplitEvaluation,
    evenRow: Boolean,
    isLiveKm: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val zoneColor = paceZoneColor(ev.zone.zoneNum)
    val barFrac = (1f - (ev.deltaFromTargetSec.coerceIn(-45, 70) / 140f)).coerceIn(0.22f, 1f)
    val rowBg = when {
        isSelected -> Color(0xFF3DAEE9).copy(alpha = 0.24f)
        isLiveKm -> KdePurple.copy(alpha = 0.24f)
        else -> KdeCardAlt.copy(alpha = if (evenRow) 0.55f else 0.2f)
    }
    val numColor = when {
        isSelected -> Color(0xFF7DD3FC)
        isLiveKm -> KdePurple
        else -> KdeText
    }
    val elevTxt = when {
        ev.split.elevDeltaM > 0 -> "+${ev.split.elevDeltaM}m"
        ev.split.elevDeltaM < 0 -> "${ev.split.elevDeltaM}m"
        ev.split.avgCadenceSpm > 0 -> "${ev.split.avgCadenceSpm}"
        else -> "—"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(rowBg).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isLiveKm) "${ev.split.splitNumber}•" else "${ev.split.splitNumber}",
            style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = numColor,
            fontWeight = if (isLiveKm || isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(0.5f)
        )
        Text(formatPace(ev.paceSecPerKm), style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = zoneColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
        Box(modifier = Modifier.weight(1.7f).padding(end = 10.dp)) {
            Box(modifier = Modifier.fillMaxWidth(barFrac).height(8.dp).background(zoneColor, RoundedCornerShape(4.dp)))
        }
        Text(elevTxt, style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdeMuted, modifier = Modifier.weight(0.7f))
    }
}

@Composable
private fun SegmentsList(segs: List<DetectedSegment>, pts: List<TrackPoint>, activeSegIdx: Int?, onSelect: (IntRange) -> Unit) {
    Text("AUTO-DETECTED ACTIVITY SEGMENTS", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
    if (segs.isEmpty()) {
        Text("Not enough GPS/step telemetry to classify segments.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        return
    }
    segs.forEachIndexed { idx, sg ->
        val isActive = idx == activeSegIdx
        val color = when (sg.type) {
            ActivitySegmentType.RUNNING -> KdePurple
            ActivitySegmentType.WALKING -> KdeSky
            ActivitySegmentType.STRETCH_REST -> PaceTooFast
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isActive) color.copy(alpha = 0.22f) else KdeCardAlt.copy(alpha = 0.45f))
                .clickable { findTimeRange(pts, sg.startMs, sg.endMs)?.let(onSelect) }
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StatusPill(if (isActive) "${sg.type.name.replace('_', ' ')} • LIVE" else sg.type.name.replace('_', ' '), color)
                Text("${formatDuration(sg.startMs)} -> ${formatDuration(sg.endMs)}", style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = KdeMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${sg.distM} m • ${formatPace(sg.paceSec)}/km", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), fontWeight = FontWeight.Bold)
                if (sg.steps > 0) Text("${sg.steps} steps", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            }
        }
    }
}

@Composable
private fun TwoSidedTrimmerPanel(
    run: RunRecord,
    pts: List<TrackPoint>,
    range: ClosedFloatingPointRange<Float>,
    onRange: (ClosedFloatingPointRange<Float>) -> Unit,
    onTrim: (Long, Long) -> Unit
) {
    val totalMs = run.durationMillis.coerceAtLeast(1_000L)
    val startMs = (totalMs * range.start).toLong()
    val endMs = (totalMs * range.endInclusive).toLong().coerceAtLeast(startMs + 5_000L)
    val suggestion = remember(run.id, pts.size) { RunTailTrimmer().suggestAutoTrim(pts, run.activityKind) }

    Text("TWO-SIDED ROUTE TRIMMER", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
    Text(
        "Keep ${formatDuration(startMs)} -> ${formatDuration(endMs)} of ${formatDuration(totalMs)} (cut start & tail shown in red)",
        style = MaterialTheme.typography.bodySmall,
        color = KdeMuted
    )
    RangeSlider(
        value = range,
        onValueChange = { next ->
            val lo = next.start.coerceIn(0f, 0.90f)
            val hi = next.endInclusive.coerceAtLeast(lo + 0.08f).coerceAtMost(1f)
            onRange(lo..hi)
        },
        valueRange = 0f..1f
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggestion != null) {
            OutlinedButton(
                onClick = { onTrim(suggestion.startMs, suggestion.endMs) },
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("Auto-Trim", maxLines = 1, softWrap = false)
            }
        }
        if (range.start > 0.01f || range.endInclusive < 0.99f) {
            Button(
                onClick = { onTrim(startMs, endMs) },
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("Apply Trim", maxLines = 1, softWrap = false)
            }
        }
    }
    if (suggestion != null) {
        Text(suggestion.reason, style = MaterialTheme.typography.labelSmall, color = KdeSky)
    }
}

private fun speedToSlider(mult: Float): Float =
    (ln(mult.coerceIn(0.5f, 60f) / 0.5f) / ln(120f)).coerceIn(0f, 1f)

private fun sliderToSpeed(frac: Float): Float =
    (0.5f * 120f.pow(frac.coerceIn(0f, 1f))).coerceIn(0.5f, 60f)

private fun formatSpeedMult(mult: Float): String = when {
    mult < 2f -> "${"%.1f".format(mult)}x"
    mult < 10f -> "${"%.1f".format(mult).removeSuffix(".0")}x"
    else -> "${mult.roundToInt()}x"
}

private fun findKmRange(pts: List<TrackPoint>, kmNum: Int): IntRange? {
    if (pts.size < 2) return null
    val d0 = (kmNum - 1) * 1000.0
    val d1 = kmNum * 1000.0
    val i0 = pts.indexOfFirst { it.cumulativeDistanceMeters >= d0 }.coerceAtLeast(0)
    val i1 = pts.indexOfLast { it.cumulativeDistanceMeters <= d1 }.coerceAtLeast(i0)
    return if (i1 > i0) i0..i1 else null
}

private fun findTimeRange(pts: List<TrackPoint>, t0: Long, t1: Long): IntRange? {
    if (pts.size < 2) return null
    val i0 = pts.indexOfFirst { it.elapsedMillis >= t0 }.coerceAtLeast(0)
    val i1 = pts.indexOfLast { it.elapsedMillis <= t1 }.coerceAtLeast(i0)
    return if (i1 > i0) i0..i1 else null
}

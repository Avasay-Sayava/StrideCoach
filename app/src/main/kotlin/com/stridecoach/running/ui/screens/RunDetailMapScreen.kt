package com.stridecoach.running.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.engine.SnapMergeResult
import com.stridecoach.running.core.domain.engine.SplitDisciplineFlag
import com.stridecoach.running.core.domain.engine.SplitEvaluation
import com.stridecoach.running.core.domain.engine.TrackInterpolator
import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.DetectedSegment
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.components.FloatingMapIconButton
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.VectorRouteMap
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailMapScreen(
    run: RunRecord,
    refPaceSec: Int,
    snapPreview: SnapMergeResult?,
    isSnapping: Boolean,
    onSnapRun: () -> Unit,
    onSaveSnap: () -> Unit,
    onClearSnap: () -> Unit,
    onBack: () -> Unit,
    onTrimTail: (Long) -> Unit,
    onExportGpx: () -> Unit
) {
    val rawPts = remember(run.id, run.durationMillis, run.trackPoints.size) {
        val flat = run.trackPoints.size < 2 || run.trackPoints.mapNotNull { it.speedMps }.distinct().size <= 1
        if (run.trackPoints.isNotEmpty() && (!flat || run.source != RunSource.BACKUP_IMPORT || run.splits.isEmpty())) {
            run.trackPoints
        } else {
            EncodedPolylineCodec.synthesizeReplayTrack(
                run.encodedPolyline, run.startTimestampMillis, run.durationMillis, run.splits
            )
        }
    }
    var useSnapped by remember(snapPreview) { mutableStateOf(snapPreview != null) }
    val pts = if (useSnapped && snapPreview != null) snapPreview.points else rawPts
    val segs = remember(run.id, pts.size) { run.segs.ifEmpty { ActivitySegmentClassifier.classify(pts) } }
    val stats = remember { RunAnalyticsEngine() }
    val evals = remember(run.splits, refPaceSec) { stats.evaluateSplitDiscipline(run.splits, refPaceSec) }

    var playing by remember(run.id) { mutableStateOf(false) }
    var mult by remember(run.id) { mutableIntStateOf(10) }
    var prog by remember(run.id) { mutableFloatStateOf(1f) }
    var scrubbing by remember(run.id) { mutableStateOf(false) }
    var colorByPace by remember(run.id) { mutableStateOf(true) }
    var recenterTick by remember { mutableIntStateOf(0) }
    var sheetTab by remember(run.id) { mutableIntStateOf(0) }
    var trimFrac by remember(run.id, run.durationMillis) { mutableFloatStateOf(1f) }
    var hiRange by remember(run.id) { mutableStateOf<IntRange?>(null) }

    val totalMs = pts.lastOrNull()?.elapsedMillis?.coerceAtLeast(1_000L) ?: run.durationMillis.coerceAtLeast(1_000L)
    LaunchedEffect(playing, mult, run.id, totalMs) {
        if (!playing) return@LaunchedEffect
        var prevNs = withFrameNanos { it }
        while (playing) {
            val nowNs = withFrameNanos { it }
            val dtMs = ((nowNs - prevNs).coerceIn(0L, 100_000_000L)) / 1_000_000f
            prevNs = nowNs
            val next = prog + ((dtMs * mult) / totalMs.toFloat())
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
    val trimCut = if (sheetTab == 2 && trimFrac < 0.99f && pts.isNotEmpty()) {
        val cutMs = (run.durationMillis * trimFrac).toLong()
        pts.indexOfFirst { it.elapsedMillis >= cutMs }.takeIf { it > 0 }
    } else {
        null
    }

    val scaffoldState = rememberBottomSheetScaffoldState()
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 196.dp,
        sheetContainerColor = KdeCardBg,
        sheetContentColor = KdeText,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = Color.Transparent,
        sheetContent = {
            RunDetailSheetBody(
                run = run,
                pts = pts,
                playing = playing,
                mult = mult,
                prog = prog,
                curMs = curMs,
                totalMs = totalMs,
                headPace = replayHead?.paceSec ?: run.paceSecondsPerKm,
                sheetTab = sheetTab,
                evals = evals,
                segs = segs,
                trimFrac = trimFrac,
                snapPreview = snapPreview,
                isSnapping = isSnapping,
                useSnapped = useSnapped,
                onTogglePlay = {
                    if (prog >= 0.99f) prog = 0f
                    playing = !playing
                },
                onMult = { mult = it },
                onScrub = { playing = false; scrubbing = true; prog = it },
                onScrubDone = { scrubbing = false },
                onTab = { sheetTab = it },
                onHighlight = { hiRange = if (hiRange == it) null else it },
                onTrimFrac = { trimFrac = it },
                onApplyTrim = onTrimTail,
                onSnap = onSnapRun,
                onToggleSnapped = { useSnapped = it },
                onSaveSnap = onSaveSnap,
                onClearSnap = onClearSnap,
                onExportGpx = onExportGpx
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            VectorRouteMap(
                pts = pts,
                replayHead = replayHead,
                interactive = true,
                showHead = pts.size >= 2,
                colorByPace = colorByPace,
                refPaceSec = refPaceSec,
                highlightRange = hiRange,
                trimCutIdx = trimCut,
                bottomPadDp = 210,
                recenterToken = recenterTick,
                modifier = Modifier.fillMaxSize()
            )
            DetailTopOverlay(
                run = run,
                colorByPace = colorByPace,
                onBack = onBack,
                onToggleColor = { colorByPace = !colorByPace },
                onRecenter = { recenterTick++ }
            )
        }
    }
}

@Composable
private fun DetailTopOverlay(
    run: RunRecord,
    colorByPace: Boolean,
    onBack: () -> Unit,
    onToggleColor: () -> Unit,
    onRecenter: () -> Unit
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    val cadText = if (run.avgCadenceSpm > 0) " | ${run.avgCadenceSpm} spm" else ""
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = topPad, start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingMapIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = KdeCardBg.copy(alpha = 0.90f),
            border = BorderStroke(1.dp, KdeBorder)
        ) {
            Text(
                text = "${"%.2f".format(run.km())} km | ${formatPace(run.paceSecondsPerKm)}/km$cadText",
                style = MaterialTheme.typography.titleSmall.merge(TabularNumberStyle),
                fontWeight = FontWeight.Bold,
                color = KdeText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        FloatingMapIconButton(Icons.Default.Palette, "Pace color", onToggleColor, if (colorByPace) KdePurple else KdeMuted)
        FloatingMapIconButton(Icons.Default.CenterFocusStrong, "Recenter", onRecenter)
    }
}

@Composable
private fun RunDetailSheetBody(
    run: RunRecord,
    pts: List<TrackPoint>,
    playing: Boolean,
    mult: Int,
    prog: Float,
    curMs: Long,
    totalMs: Long,
    headPace: Int,
    sheetTab: Int,
    evals: List<SplitEvaluation>,
    segs: List<DetectedSegment>,
    trimFrac: Float,
    snapPreview: SnapMergeResult?,
    isSnapping: Boolean,
    useSnapped: Boolean,
    onTogglePlay: () -> Unit,
    onMult: (Int) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubDone: () -> Unit,
    onTab: (Int) -> Unit,
    onHighlight: (IntRange) -> Unit,
    onTrimFrac: (Float) -> Unit,
    onApplyTrim: (Long) -> Unit,
    onSnap: () -> Unit,
    onToggleSnapped: (Boolean) -> Unit,
    onSaveSnap: () -> Unit,
    onClearSnap: () -> Unit,
    onExportGpx: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ReplayDockRow(playing, prog, curMs, totalMs, headPace, mult, onTogglePlay, onScrub, onScrubDone, onMult)
        ActionTabsRow(sheetTab, onTab, onExportGpx)
        when (sheetTab) {
            0 -> SplitsTable(evals, pts, onHighlight)
            1 -> SegmentsList(segs, pts, onHighlight)
            2 -> TailTrimmerPanel(run, trimFrac, onTrimFrac, onApplyTrim)
            else -> SnapStreetsPanel(snapPreview, isSnapping, useSnapped, onSnap, onToggleSnapped, onSaveSnap, onClearSnap)
        }
    }
}

@Composable
private fun ReplayDockRow(
    playing: Boolean,
    prog: Float,
    curMs: Long,
    totalMs: Long,
    livePace: Int,
    mult: Int,
    onTogglePlay: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubDone: () -> Unit,
    onMult: (Int) -> Unit
) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = KdePurple, modifier = Modifier.size(44.dp)) {
            IconButton(onClick = onTogglePlay) {
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
                Text("${formatPace(livePace)}/km", style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), color = KdeText, fontWeight = FontWeight.Bold)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1, 4, 10, 25).forEach { m ->
            FilterChip(selected = mult == m, onClick = { onMult(m) }, colors = cc, label = { Text("${m}x") })
        }
    }
}

@Composable
private fun ActionTabsRow(tab: Int, onTab: (Int) -> Unit, onExportGpx: () -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdeSky.copy(alpha = 0.22f), selectedLabelColor = KdeSky)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Splits", "Segs", "Trim", "Snap").forEachIndexed { idx, lbl ->
                FilterChip(selected = tab == idx, onClick = { onTab(idx) }, colors = cc, label = { Text(lbl) })
            }
        }
        OutlinedButton(onClick = onExportGpx, shape = RoundedCornerShape(999.dp)) { Text("GPX") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapStreetsPanel(
    res: SnapMergeResult?,
    busy: Boolean,
    useSnapped: Boolean,
    onSnap: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Text("PARK-SAFE STREET SNAPPING", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
    Text(
        "Snaps sidewalk GPS drift to streets while preserving off-road greenery and traffic-stop waiting loops.",
        style = MaterialTheme.typography.bodySmall,
        color = KdeMuted
    )
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KdePurple)
    if (res == null) {
        Button(onClick = onSnap, enabled = !busy, shape = RoundedCornerShape(999.dp)) {
            Text("Snap Streets (Keep Greenery)")
        }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusPill("${res.snappedPct}% Streets", KdePurple)
        StatusPill("${res.greeneryPct}% Greenery Kept", PaceOnTarget)
        if (res.circlesKept > 0) StatusPill("${res.circlesKept} Stop Loops", KdeSky)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, shape = RoundedCornerShape(999.dp)) { Text("Save Snapped", maxLines = 1) }
        OutlinedButton(onClick = { onToggle(!useSnapped) }, shape = RoundedCornerShape(999.dp)) {
            Text(if (useSnapped) "Show Raw" else "Show Snapped", maxLines = 1)
        }
        OutlinedButton(onClick = onClear, shape = RoundedCornerShape(999.dp)) { Text("Discard", maxLines = 1) }
    }
}

@Composable
private fun SplitsTable(evals: List<SplitEvaluation>, pts: List<TrackPoint>, onSelect: (IntRange) -> Unit) {
    Text("KM SPLITS (tap row to highlight on map)", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
    if (evals.isEmpty()) {
        Text("No split checkpoints recorded for this run.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        return
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("KM", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(0.5f))
        Text("PACE", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(0.9f))
        Text("ZONE BAR", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(1.7f))
        Text("CAD", style = MaterialTheme.typography.labelSmall, color = KdeMuted, modifier = Modifier.weight(0.7f))
    }
    evals.forEachIndexed { i, ev ->
        val c = when (ev.flag) {
            SplitDisciplineFlag.EARLY_SURGE -> PaceTooFast
            SplitDisciplineFlag.LATE_FADE -> PaceTooSlow
            SplitDisciplineFlag.ON_TARGET -> PaceOnTarget
        }
        val barFrac = (1f - (ev.deltaFromTargetSec.coerceIn(-40, 60) / 140f)).coerceIn(0.25f, 1f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KdeCardAlt.copy(alpha = if (i % 2 == 0) 0.55f else 0.2f), RoundedCornerShape(6.dp))
                .clickable { findKmRange(pts, ev.split.splitNumber)?.let(onSelect) }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${ev.split.splitNumber}", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), modifier = Modifier.weight(0.5f))
            Text(formatPace(ev.paceSecPerKm), style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = c, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
            Box(modifier = Modifier.weight(1.7f).padding(end = 10.dp)) {
                Box(modifier = Modifier.fillMaxWidth(barFrac).height(8.dp).background(c, RoundedCornerShape(4.dp)))
            }
            val cad = if (ev.split.avgCadenceSpm > 0) "${ev.split.avgCadenceSpm}" else "—"
            Text(cad, style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdeMuted, modifier = Modifier.weight(0.7f))
        }
    }
}

@Composable
private fun SegmentsList(segs: List<DetectedSegment>, pts: List<TrackPoint>, onSelect: (IntRange) -> Unit) {
    Text("AUTO-DETECTED ACTIVITY SEGMENTS", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
    if (segs.isEmpty()) {
        Text("Not enough GPS/step telemetry to classify segments.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        return
    }
    segs.forEach { sg ->
        val color = when (sg.type) {
            ActivitySegmentType.RUNNING -> KdePurple
            ActivitySegmentType.WALKING -> KdeSky
            ActivitySegmentType.STRETCH_REST -> PaceTooFast
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KdeCardAlt.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .clickable { findTimeRange(pts, sg.startMs, sg.endMs)?.let(onSelect) }
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StatusPill(sg.type.name.replace('_', ' '), color)
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
private fun TailTrimmerPanel(run: RunRecord, frac: Float, onFrac: (Float) -> Unit, onTrim: (Long) -> Unit) {
    val keep = (run.durationMillis * frac).toLong().coerceAtLeast(10_000L)
    Text("POST-RUN TAIL TRIMMER", style = MaterialTheme.typography.labelMedium, color = KdeMuted, fontWeight = FontWeight.Bold)
    Text("Keep first ${formatDuration(keep)} of ${formatDuration(run.durationMillis)} (discarded tail shown in red on map)", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    Slider(value = frac, onValueChange = onFrac, valueRange = 0.25f..1f)
    if (frac < 0.99f) {
        Button(onClick = { onTrim(keep) }, shape = RoundedCornerShape(999.dp)) { Text("Apply Tail Trim") }
    }
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

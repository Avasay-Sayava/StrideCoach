package com.stridecoach.running.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.PersonalRecord
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.engine.SplitDisciplineFlag
import com.stridecoach.running.core.domain.engine.SplitEvaluation
import com.stridecoach.running.core.domain.engine.WeeklyLoadPoint
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.CoachUiState
import com.stridecoach.running.ui.components.KpiStatTile
import com.stridecoach.running.ui.components.OsmRouteMap
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import java.time.DayOfWeek

@Composable
fun HistoryAnalyticsScreen(
    state: CoachUiState,
    selectedRun: RunRecord?,
    onSelectRun: (String) -> Unit,
    onTrimTail: (Long) -> Unit,
    onExportGpx: () -> Unit
) {
    val stats = remember { RunAnalyticsEngine() }
    val pace = state.activePlan?.targetPaceSecPerKm ?: 295

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("hdr") {
            Text("History & Analytics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        if (selectedRun != null) {
            item("detail") { RunDetailCard(selectedRun, pace, stats, onTrimTail, onExportGpx) }
        }
        item("kpis") { AnalyticsKpiGrid(state) }
        if (state.runs.size >= 2) {
            item("trend") { DistanceTrendCard(state.runs.take(8).reversed()) }
        }
        item("load") { WeeklyLoadCard(state.weeklyLoad) }
        item("heat") { WalkingHeatmapCard(state.weekdayWalkHeatmap) }
        if (state.personalRecords.isNotEmpty()) {
            item("prs") { PersonalRecordsCard(state.personalRecords) }
        }
        item("runs_hdr") {
            Text("Saved Runs (${state.runs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (state.runs.isEmpty()) {
            item("empty") {
                StrideCard { Text("No runs yet. Record a run or import a .backup.", style = MaterialTheme.typography.bodySmall, color = KdeMuted) }
            }
        } else {
            items(state.runs, key = { it.id }) { r -> RunVisualCard(r, onSelectRun) }
        }
    }
}

@Composable
private fun AnalyticsKpiGrid(state: CoachUiState) {
    val runKm = state.runs.sumOf { it.distanceMeters } / 1000.0
    val avgPace = if (state.runs.isEmpty()) 0 else state.runs.map { it.paceSecondsPerKm }.filter { it > 0 }.average().toInt()
    val walkKm = if (state.walkLogs.isEmpty()) 0.0 else state.walkLogs.map { it.km() }.average()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiStatTile("Total Run", "%.1f km".format(runKm), Modifier.weight(1f), KdePurple)
            KpiStatTile("Runs Logged", "${state.runs.size}", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiStatTile("Avg Pace", "${formatPace(avgPace)} /km", Modifier.weight(1f))
            KpiStatTile("Avg Walk/Day", "%.1f km".format(walkKm), Modifier.weight(1f), KdeSky)
        }
    }
}

@Composable
private fun DistanceTrendCard(recent: List<RunRecord>) {
    StrideCard {
        Text("Recent Run Distances (km)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val maxKm = recent.maxOf { it.km() }.coerceAtLeast(1.0).toFloat()
            val stepX = size.width / (recent.size - 1).coerceAtLeast(1)
            val pts = recent.mapIndexed { i, r ->
                val y = size.height - ((r.km().toFloat() / maxKm) * (size.height - 20f)) - 10f
                Offset(i * stepX, y)
            }
            val line = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (i in 0 until pts.lastIndex) {
                    val cx = (pts[i].x + pts[i + 1].x) / 2f
                    cubicTo(cx, pts[i].y, cx, pts[i + 1].y, pts[i + 1].x, pts[i + 1].y)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(pts.last().x, size.height)
                lineTo(pts.first().x, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(KdePurple.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(line, KdePurple, style = Stroke(width = 5f, cap = StrokeCap.Round))
            pts.forEach { drawCircle(Color.White, radius = 5f, center = it) }
        }
    }
}

@Composable
private fun WeeklyLoadCard(weeks: List<WeeklyLoadPoint>) {
    StrideCard {
        Text("Combined On-Feet Load (Run + Walk)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (weeks.isEmpty()) {
            Text("Import runs or Timeline.json to view weekly load.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
            return@StrideCard
        }
        val maxM = weeks.maxOf { it.totalMeters }.coerceAtLeast(1_000)
        weeks.takeLast(6).forEach { w -> WeeklyBarRow(w, maxM) }
    }
}

@Composable
private fun WeeklyBarRow(w: WeeklyLoadPoint, maxM: Int) {
    val rFrac = (w.runMeters.toFloat() / maxM).coerceIn(0f, 1f)
    val wFrac = (w.walkMeters.toFloat() / maxM).coerceIn(0f, 1f)
    Text("${w.weekLabel}: ${"%.1f".format(w.runMeters / 1000.0)} km run + ${"%.1f".format(w.walkMeters / 1000.0)} km walk", style = MaterialTheme.typography.bodySmall)
    Row(modifier = Modifier.fillMaxWidth().height(8.dp)) {
        if (rFrac > 0f) Box(Modifier.fillMaxWidth(rFrac).height(8.dp).background(KdePurple, RoundedCornerShape(4.dp)))
        if (wFrac > 0f) Box(Modifier.fillMaxWidth(wFrac).height(8.dp).background(KdeSky, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun WalkingHeatmapCard(map: Map<DayOfWeek, Int>) {
    StrideCard {
        Text("Weekday Walking Heatmap (Avg km)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { d -> HeatmapCell(d.name.take(3), (map[d] ?: 0) / 1000.0) }
        }
    }
}

@Composable
private fun HeatmapCell(day: String, km: Double) {
    Column {
        Text(day, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        Text("%.1f".format(km), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PersonalRecordsCard(prs: List<PersonalRecord>) {
    StrideCard {
        Text("Personal Records", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        prs.forEach { pr ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pr.label, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
                Text(formatDuration(pr.bestDurationMillis), style = MaterialTheme.typography.bodySmall, color = KdePurple, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RunVisualCard(r: RunRecord, onSelect: (String) -> Unit) {
    val thumb = remember(r.id, r.encodedPolyline) {
        EncodedPolylineCodec.synthesizeReplayTrack(r.encodedPolyline, r.startTimestampMillis, r.durationMillis, r.splits)
    }
    StrideCard(modifier = Modifier.clickable { onSelect(r.id) }) {
        Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${"%.2f".format(r.km())} km", style = MaterialTheme.typography.bodySmall, color = KdePurple, fontWeight = FontWeight.Bold)
            Text(formatDuration(r.durationMillis), style = MaterialTheme.typography.bodySmall)
            Text("${formatPace(r.paceSecondsPerKm)} /km", style = MaterialTheme.typography.bodySmall)
            Text("${r.caloriesBurned} kcal", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        }
        if (thumb.size >= 2) {
            OsmRouteMap(
                pts = thumb,
                interactive = false,
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
        }
    }
}

@Composable
private fun RunDetailCard(run: RunRecord, targetPace: Int, stats: RunAnalyticsEngine, onTrim: (Long) -> Unit, onGpx: () -> Unit) {
    val pts = remember(run.id, run.durationMillis, run.trackPoints.size) {
        run.trackPoints.ifEmpty {
            EncodedPolylineCodec.synthesizeReplayTrack(run.encodedPolyline, run.startTimestampMillis, run.durationMillis, run.splits)
        }
    }
    val evals = remember(run.splits, targetPace) { stats.evaluateSplitDiscipline(run.splits, targetPace) }
    StrideCard {
        RunHeaderRow(run, onGpx)
        RunReplayPlayer(points = pts)
        if (evals.isNotEmpty()) SplitDisciplineList(evals, targetPace)
        TailTrimmerSection(run, onTrim)
    }
}

@Composable
private fun RunHeaderRow(run: RunRecord, onGpx: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(run.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${"%.2f".format(run.km())} km • ${formatDuration(run.durationMillis)} • ${formatPace(run.paceSecondsPerKm)}/km", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        }
        OutlinedButton(onClick = onGpx, shape = RoundedCornerShape(999.dp)) { Text("GPX") }
    }
}

@Composable
private fun SplitDisciplineList(evals: List<SplitEvaluation>, targetPace: Int) {
    Text("Split Discipline vs. ${formatPace(targetPace)}/km", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    evals.forEach { ev ->
        val c = when (ev.flag) {
            SplitDisciplineFlag.EARLY_SURGE -> PaceTooFast
            SplitDisciplineFlag.LATE_FADE -> PaceTooSlow
            SplitDisciplineFlag.ON_TARGET -> PaceOnTarget
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("KM ${ev.split.splitNumber}", style = MaterialTheme.typography.bodySmall)
            Text("${formatPace(ev.paceSecPerKm)} /km", color = c, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(ev.flag.name.replace('_', ' '), color = c, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TailTrimmerSection(run: RunRecord, onTrim: (Long) -> Unit) {
    var frac by remember(run.id, run.durationMillis) { mutableFloatStateOf(1f) }
    val keep = (run.durationMillis * frac).toLong().coerceAtLeast(10_000L)
    Text("Post-Run Tail Trimmer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text("Keep first ${formatDuration(keep)} of ${formatDuration(run.durationMillis)}", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    Slider(value = frac, onValueChange = { frac = it }, valueRange = 0.25f..1f)
    if (frac < 0.99f) Button(onClick = { onTrim(keep) }, shape = RoundedCornerShape(999.dp)) { Text("Apply Tail Trim") }
}

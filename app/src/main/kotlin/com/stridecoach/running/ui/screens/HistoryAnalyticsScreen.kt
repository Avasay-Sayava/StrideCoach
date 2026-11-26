package com.stridecoach.running.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import com.stridecoach.running.ui.theme.WalkLoadAccent
import java.time.DayOfWeek

@Composable
fun HistoryAnalyticsScreen(
    state: CoachUiState,
    selectedRun: RunRecord?,
    onSelectRun: (String) -> Unit,
    onTrimTail: (Long) -> Unit,
    onExportGpx: () -> Unit
) {
    val scroll = rememberScrollState()
    val stats = remember { RunAnalyticsEngine() }
    val pace = state.activePlan?.targetPaceSecPerKm ?: 295

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        selectedRun?.let { RunDetailCard(it, pace, stats, onTrimTail, onExportGpx) }
        WeeklyLoadCard(state.weeklyLoad)
        WalkingHeatmapCard(state.weekdayWalkHeatmap)
        if (state.personalRecords.isNotEmpty()) PersonalRecordsCard(state.personalRecords)
        SavedRunsCard(state.runs, onSelectRun)
    }
}

@Composable
private fun WeeklyLoadCard(weeks: List<WeeklyLoadPoint>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Combined On-Feet Load (Run + Walk)", style = MaterialTheme.typography.titleMedium)
            if (weeks.isEmpty()) {
                Text("Import runs or Timeline.json to view weekly load.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val maxM = weeks.maxOf { it.totalMeters }.coerceAtLeast(1_000)
            weeks.takeLast(6).forEach { w -> WeeklyBarRow(w, maxM) }
        }
    }
}

@Composable
private fun WeeklyBarRow(w: WeeklyLoadPoint, maxM: Int) {
    val rFrac = (w.runMeters.toFloat() / maxM).coerceIn(0f, 1f)
    val wFrac = (w.walkMeters.toFloat() / maxM).coerceIn(0f, 1f)
    val label = "${w.weekLabel}: ${"%.1f".format(w.runMeters / 1000.0)} km run + ${"%.1f".format(w.walkMeters / 1000.0)} km walk"
    Text(label, style = MaterialTheme.typography.bodySmall)
    Row(modifier = Modifier.fillMaxWidth().height(10.dp)) {
        if (rFrac > 0f) Box(Modifier.fillMaxWidth(rFrac).height(10.dp).background(PaceOnTarget, RoundedCornerShape(4.dp)))
        if (wFrac > 0f) Box(Modifier.fillMaxWidth(wFrac).height(10.dp).background(WalkLoadAccent, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun WalkingHeatmapCard(map: Map<DayOfWeek, Int>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Day-of-Week Walking Heatmap (Avg km)", style = MaterialTheme.typography.titleMedium)
            HeatmapRow(map)
        }
    }
}

@Composable
private fun HeatmapRow(map: Map<DayOfWeek, Int>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEach { d ->
            HeatmapCell(d.name.take(3), (map[d] ?: 0) / 1000.0)
        }
    }
}

@Composable
private fun HeatmapCell(day: String, km: Double) {
    Column {
        Text(day, style = MaterialTheme.typography.labelSmall)
        Text("%.1f".format(km), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PersonalRecordsCard(prs: List<PersonalRecord>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Personal Records", style = MaterialTheme.typography.titleMedium)
            prs.forEach { pr ->
                Text("${pr.label}: ${formatDuration(pr.bestDurationMillis)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SavedRunsCard(runs: List<RunRecord>, onSelect: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved Runs (${runs.size})", style = MaterialTheme.typography.titleMedium)
            if (runs.isEmpty()) {
                Text("No runs yet. Record a run or import a backup.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            runs.forEach { r -> SavedRunRow(r, onSelect) }
        }
    }
}

@Composable
private fun SavedRunRow(r: RunRecord, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(r.id) }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(r.name, style = MaterialTheme.typography.bodyMedium)
            Text("${"%.2f".format(r.km())} km • ${formatDuration(r.durationMillis)}", style = MaterialTheme.typography.bodySmall)
        }
        Text("${formatPace(r.paceSecondsPerKm)} /km", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RunDetailCard(
    run: RunRecord,
    targetPace: Int,
    stats: RunAnalyticsEngine,
    onTrim: (Long) -> Unit,
    onGpx: () -> Unit
) {
    val pts = remember(run.id, run.durationMillis, run.trackPoints.size) {
        run.trackPoints.ifEmpty {
            EncodedPolylineCodec.synthesizeReplayTrack(run.encodedPolyline, run.startTimestampMillis, run.durationMillis, run.splits)
        }
    }
    val evals = remember(run.splits, targetPace) { stats.evaluateSplitDiscipline(run.splits, targetPace) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RunHeaderRow(run, onGpx)
            RunReplayPlayer(points = pts, targetPaceSecPerKm = targetPace)
            if (evals.isNotEmpty()) SplitDisciplineList(evals, targetPace)
            TailTrimmerSection(run, onTrim)
        }
    }
}

@Composable
private fun RunHeaderRow(run: RunRecord, onGpx: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(run.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${"%.2f".format(run.km())} km • ${formatDuration(run.durationMillis)} • ${formatPace(run.paceSecondsPerKm)}/km",
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedButton(onClick = onGpx) { Text("GPX") }
    }
}

@Composable
private fun SplitDisciplineList(evals: List<SplitEvaluation>, targetPace: Int) {
    Text("Split Discipline vs. ${formatPace(targetPace)}/km", style = MaterialTheme.typography.titleSmall)
    evals.forEach { ev ->
        val c = when (ev.flag) {
            SplitDisciplineFlag.EARLY_SURGE -> PaceTooFast
            SplitDisciplineFlag.LATE_FADE -> PaceTooSlow
            SplitDisciplineFlag.ON_TARGET -> PaceOnTarget
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("KM ${ev.split.splitNumber}", style = MaterialTheme.typography.bodySmall)
            Text("${formatPace(ev.paceSecPerKm)} /km", color = c, style = MaterialTheme.typography.bodySmall)
            Text(ev.flag.name.replace('_', ' '), color = c, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TailTrimmerSection(run: RunRecord, onTrim: (Long) -> Unit) {
    var frac by remember(run.id, run.durationMillis) { mutableFloatStateOf(1f) }
    val keep = (run.durationMillis * frac).toLong().coerceAtLeast(10_000L)
    Text("Post-Run Tail Trimmer", style = MaterialTheme.typography.titleSmall)
    Text("Keep first ${formatDuration(keep)} of ${formatDuration(run.durationMillis)}", style = MaterialTheme.typography.bodySmall)
    Slider(value = frac, onValueChange = { frac = it }, valueRange = 0.25f..1f)
    if (frac < 0.99f) {
        Button(onClick = { onTrim(keep) }) { Text("Apply Tail Trim") }
    }
}

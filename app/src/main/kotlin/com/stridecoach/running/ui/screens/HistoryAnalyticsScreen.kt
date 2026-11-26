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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.DailyLoadBar
import com.stridecoach.running.core.domain.engine.PaceZoneBucket
import com.stridecoach.running.core.domain.engine.PersonalRecord
import com.stridecoach.running.core.domain.engine.WeeklyLoadPoint
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.CoachUiState
import com.stridecoach.running.ui.TelemetrySummary
import com.stridecoach.running.ui.components.StaticRouteMapThumbnail
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.components.TelemetryStatStrip
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.KdeText
import com.stridecoach.running.ui.theme.TabularNumberStyle
import com.stridecoach.running.ui.theme.paceZoneColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryAnalyticsScreen(
    state: CoachUiState,
    onSelectRun: (String) -> Unit,
    analyticsScrollState: LazyListState = rememberLazyListState(),
    historyScrollState: LazyListState = rememberLazyListState()
) {
    var subTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        StyleCHeader(subTab, state.runs.size) { subTab = it }
        if (subTab == 0) {
            AnalyticsFeed(state, analyticsScrollState, onSelectRun)
        } else {
            HistoryFeed(state.runs, historyScrollState, onSelectRun)
        }
    }
}

@Composable
private fun StyleCHeader(subTab: Int, runCount: Int, onPick: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(KdeBg).padding(top = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Telemetry", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            StatusPill("$runCount runs", KdePurple)
        }
        val tabs = listOf("Analytics", "History")
        TabRow(
            selectedTabIndex = subTab,
            containerColor = KdeBg,
            contentColor = KdePurple,
            indicator = { pos ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(pos[subTab]),
                    color = KdePurple
                )
            }
        ) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = subTab == idx,
                    onClick = { onPick(idx) },
                    text = {
                        Text(
                            title,
                            fontWeight = if (subTab == idx) FontWeight.Bold else FontWeight.Medium,
                            color = if (subTab == idx) KdeText else KdeMuted
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AnalyticsFeed(
    state: CoachUiState,
    listState: LazyListState,
    onSelectRun: (String) -> Unit
) {
    val tel = state.telemetry
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("summary") { TelemetrySummaryBar(tel) }
        item("weekly") { WeeklyStackedLoadCard(tel.last7DaysLoad, tel.weeklyLoad) }
        item("zones") { PaceDistributionCard(tel.paceZones) }
        state.runs.firstOrNull()?.let { latest ->
            item("latest") { LatestRunCard(latest, onSelectRun) }
        }
        if (tel.personalRecords.isNotEmpty()) {
            item("prs") { PersonalRecordsCard(tel.personalRecords) }
        }
    }
}

@Composable
private fun HistoryFeed(
    runs: List<RunRecord>,
    listState: LazyListState,
    onSelectRun: (String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (runs.isEmpty()) {
            item("empty") {
                StrideCard {
                    Text(
                        "No runs yet. Record a run or import a .backup in Data & Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KdeMuted
                    )
                }
            }
        } else {
            items(runs, key = { it.id }) { run ->
                RunTelemetryCard(run, onSelectRun)
            }
        }
    }
}

@Composable
private fun TelemetrySummaryBar(t: TelemetrySummary) {
    val hrs = t.totalDurationMillis / 3_600_000L
    val mins = (t.totalDurationMillis % 3_600_000L) / 60_000L
    val durText = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    val cadText = if (t.avgCadenceSpm > 0) "${t.avgCadenceSpm} spm" else "—"
    TelemetryStatStrip(
        items = listOf(
            "%.1f km".format(t.totalRunKm) to "Distance",
            durText to "Time",
            "${formatPace(t.avgPaceSecPerKm)}/km" to "Avg Pace",
            cadText to "Cadence"
        )
    )
}

@Composable
private fun WeeklyStackedLoadCard(bars: List<DailyLoadBar>, weeks: List<WeeklyLoadPoint>) {
    var mode by remember { mutableIntStateOf(0) }
    StrideCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("WEEKLY LOAD (RUN + WALK)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot("Run", KdePurple)
                LegendDot("Walk", KdeSky)
                Text(
                    if (mode == 0) "7D" else "6W",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdePurple,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { mode = (mode + 1) % 2 }
                )
            }
        }
        val items = if (mode == 0) {
            bars.map { Triple(it.dayLabel, it.runMeters, it.walkMeters) }
        } else {
            weeks.takeLast(6).map { Triple(it.weekLabel.takeLast(3), it.runMeters, it.walkMeters) }
        }
        VerticalStackedBarsChart(items)
    }
}

@Composable
private fun VerticalStackedBarsChart(items: List<Triple<String, Int, Int>>) {
    if (items.isEmpty()) {
        Text("Import runs or Timeline.json to view load.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        return
    }
    val maxM = items.maxOf { it.second + it.third }.coerceAtLeast(1_000)
    Canvas(modifier = Modifier.fillMaxWidth().height(92.dp)) {
        val slotW = size.width / items.size.coerceAtLeast(1)
        val barW = (slotW * 0.48f).coerceAtMost(28.dp.toPx())
        items.forEachIndexed { idx, (_, runM, walkM) ->
            val x = idx * slotW + (slotW - barW) / 2f
            val runH = (runM.toFloat() / maxM) * (size.height - 6f)
            val walkH = (walkM.toFloat() / maxM) * (size.height - 6f)
            val totalH = (runH + walkH).coerceAtLeast(if (runM + walkM > 0) 4f else 0f)
            if (walkH > 0f) {
                drawRoundRect(
                    color = KdeSky,
                    topLeft = Offset(x, size.height - totalH),
                    size = Size(barW, walkH),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
            if (runH > 0f) {
                drawRoundRect(
                    color = KdePurple,
                    topLeft = Offset(x, size.height - runH),
                    size = Size(barW, runH),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEach { (lbl, _, _) ->
            Text(lbl, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
    }
}

@Composable
private fun PaceDistributionCard(zones: List<PaceZoneBucket>) {
    StrideCard {
        Text("PACE DISTRIBUTION", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (zones.all { it.durationMillis == 0L }) {
            Text("Log a run to unlock pace zone distribution.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
            return@StrideCard
        }
        zones.forEach { z ->
            val col = paceZoneColor(z.zone)
            val frac = (z.percent / 100f).coerceIn(0.03f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Z${z.zone}", style = MaterialTheme.typography.labelMedium, color = col, fontWeight = FontWeight.Bold)
                Box(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth(frac).height(9.dp).background(col, RoundedCornerShape(4.dp)))
                }
                Text("${z.percent}%", style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), color = KdeText)
            }
        }
    }
}

@Composable
private fun LatestRunCard(r: RunRecord, onSelect: (String) -> Unit) {
    StrideCard(modifier = Modifier.clickable { onSelect(r.id) }) {
        Text("LATEST RUN • ${r.name.uppercase()}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = KdeMuted)
        RunPreviewHeaderMetrics(r)
        if (r.hasTrack()) {
            StaticRouteMapThumbnail(run = r, modifier = Modifier.fillMaxWidth().height(132.dp))
        }
    }
}

@Composable
private fun RunTelemetryCard(r: RunRecord, onSelect: (String) -> Unit) {
    val dt = remember(r.startTimestampMillis) {
        DateTimeFormatter.ofPattern("MMM d, HH:mm").format(
            Instant.ofEpochMilli(r.startTimestampMillis).atZone(ZoneId.systemDefault())
        )
    }
    StrideCard(modifier = Modifier.clickable { onSelect(r.id) }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(dt, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            }
            if (r.avgCadenceSpm > 0) StatusPill("${r.avgCadenceSpm} spm", KdeSky)
        }
        RunPreviewHeaderMetrics(r)
        if (r.hasTrack()) {
            StaticRouteMapThumbnail(run = r, modifier = Modifier.fillMaxWidth().height(136.dp))
        }
    }
}

@Composable
private fun RunPreviewHeaderMetrics(r: RunRecord) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${"%.2f".format(r.km())} km", style = MaterialTheme.typography.bodyMedium.merge(TabularNumberStyle), color = KdePurple, fontWeight = FontWeight.Bold)
        Text(formatDuration(r.durationMillis), style = MaterialTheme.typography.bodyMedium.merge(TabularNumberStyle), fontWeight = FontWeight.SemiBold)
        Text("${formatPace(r.paceSecondsPerKm)}/km", style = MaterialTheme.typography.bodyMedium.merge(TabularNumberStyle))
        Text("${r.caloriesBurned} kcal", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdeMuted)
    }
}

@Composable
private fun PersonalRecordsCard(prs: List<PersonalRecord>) {
    StrideCard {
        Text("PERSONAL RECORDS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        prs.forEach { pr ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pr.label, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
                Text(
                    formatDuration(pr.bestDurationMillis),
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle),
                    color = KdePurple,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

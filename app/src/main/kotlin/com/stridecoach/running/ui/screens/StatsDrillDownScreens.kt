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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.HistoricalLoadBucket
import com.stridecoach.running.core.domain.engine.HistoricalWindowStats
import com.stridecoach.running.core.domain.engine.PaceZone
import com.stridecoach.running.core.domain.engine.PaceZoneClassifier
import com.stridecoach.running.core.domain.engine.PersonalRecordHistory
import com.stridecoach.running.core.domain.engine.PrProgressionEntry
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.components.TelemetryStatStrip
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.KdeCardAlt
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.KdeText
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.TabularNumberStyle
import com.stridecoach.running.ui.theme.paceZoneColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TimeGranularity(val label: String, val span: Int, val bucket: Int) {
    WEEK("7D", 7, 1),
    SIX_WEEKS("6W", 42, 7),
    MONTH("Month", 30, 5),
    YEAR("Year", 365, 30)
}

@Composable
fun LoadTimeMachineScreen(runs: List<RunRecord>, walks: List<WalkLog>, onBack: () -> Unit, onSelectRun: (String) -> Unit) {
    var gran by remember { mutableStateOf(TimeGranularity.WEEK) }
    var off by remember { mutableIntStateOf(0) }
    val today = remember { LocalDate.now().toEpochDay() }
    val endDay = today - (off * gran.span.toLong())
    val engine = remember { RunAnalyticsEngine() }
    val stats = remember(runs, walks, endDay, gran) { engine.computeHistoricalWindow(runs, walks, endDay, gran.span, gran.bucket) }
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, yy") }
    val label = "${LocalDate.ofEpochDay(stats.startEpochDay).format(fmt)} - ${LocalDate.ofEpochDay(stats.endEpochDay).format(fmt)}"

    Column(modifier = Modifier.fillMaxSize().background(KdeBg).statusBarsPadding().navigationBarsPadding()) {
        DrillTopBar("Load History Explorer", onBack)
        LoadTimeLazyList(gran, off, stats, label, { gran = it; off = 0 }, { off++ }, { if (off > 0) off-- }, { off = 0 }, onSelectRun)
    }
}

@Composable
private fun LoadTimeLazyList(
    gran: TimeGranularity,
    off: Int,
    stats: HistoricalWindowStats,
    label: String,
    onPickGran: (TimeGranularity) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSelectRun: (String) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("gran") { GranularityBar(gran, onPickGran) }
        item("nav") { PeriodChartCard(label, off, stats.bars, onPrev, onNext, onToday) }
        item("metrics") { LoadMetricsStrip(stats) }
        item("hdr") {
            Text("SESSIONS IN THIS PERIOD (${stats.matchingRuns.size})", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
        }
        if (stats.matchingRuns.isEmpty()) {
            item("none") { EmptyLoadCard() }
        } else {
            items(stats.matchingRuns, key = { it.id }) { r ->
                HistoricalRunMiniRow(r) { onSelectRun(r.id) }
            }
        }
    }
}

@Composable
private fun LoadMetricsStrip(stats: HistoricalWindowStats) {
    TelemetryStatStrip(listOf(
        "%.1f km".format(stats.totalRunMeters / 1000.0) to "Runs",
        "%.1f km".format(stats.totalWalkMeters / 1000.0) to "Walks",
        "${stats.sessionCount}" to "Sessions",
        "+${stats.totalElevGainM}m" to "Elev Gain"
    ))
}

@Composable
private fun EmptyLoadCard() {
    StrideCard {
        Text("No workouts logged in this window.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    }
}

@Composable
private fun GranularityBar(cur: TimeGranularity, onPick: (TimeGranularity) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TimeGranularity.entries.forEach { g ->
            GranularityChip(g.label, cur == g, cc, Modifier.weight(1f)) { onPick(g) }
        }
    }
}

@Composable
private fun GranularityChip(label: String, selected: Boolean, colors: SelectableChipColors, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = colors,
        modifier = modifier,
        label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(label, maxLines = 1, softWrap = false) } }
    )
}

@Composable
private fun PeriodChartCard(
    label: String,
    off: Int,
    bars: List<HistoricalLoadBucket>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous Period") }
            PeriodHeaderLabel(label, off > 0, onToday)
            IconButton(onClick = onNext, enabled = off > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Period") }
        }
        HistoricalWindowChart(bars)
    }
}

@Composable
private fun PeriodHeaderLabel(label: String, canReset: Boolean, onToday: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleSmall.merge(TabularNumberStyle), fontWeight = FontWeight.Bold)
        if (!canReset) return
        Text(
            "Tap Today to jump back", style = MaterialTheme.typography.labelSmall, color = KdeSky,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onToday).padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun PaceZonesDetailScreen(runs: List<RunRecord>, baselinePaceSec: Int, onBack: () -> Unit) {
    var kind by remember { mutableStateOf<ActivityKind?>(ActivityKind.RUNNING) }
    var span by remember { mutableIntStateOf(0) }
    var off by remember { mutableIntStateOf(0) }
    val base = remember(runs, kind, baselinePaceSec) { PaceZoneClassifier.computeCurrentBaselinePace(runs, kind ?: ActivityKind.RUNNING, baselinePaceSec) }
    val today = remember { LocalDate.now().toEpochDay() }
    val filtered = remember(runs, kind, span, off) { filterRunsByWindow(runs, kind, span, off, today) }
    val dist = remember(filtered, base) { RunAnalyticsEngine().computePaceZoneDistribution(filtered, base) }

    Column(modifier = Modifier.fillMaxSize().background(KdeBg).statusBarsPadding().navigationBarsPadding()) {
        DrillTopBar("Pace Zones & History", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("kind_window") { ZoneFilterSection(kind, span, off, today, { kind = it }, { span = it; off = 0 }, { off++ }, { if (off > 0) off-- }) }
            item("summary") { ZoneDistributionCard(base, filtered.size, dist) }
            items(PaceZone.entries, key = { it.zoneNum }) { z -> ZoneExplainCard(z, base) }
        }
    }
}

private fun filterRunsByWindow(runs: List<RunRecord>, kind: ActivityKind?, span: Int, off: Int, today: Long): List<RunRecord> {
    val byKind = if (kind == null) runs else runs.filter { it.activityKind == kind }
    if (span <= 0) return byKind
    val endD = today - off * span.toLong()
    val startD = endD - span + 1L
    return byKind.filter { r ->
        Instant.ofEpochMilli(r.startTimestampMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() in startD..endD
    }
}

@Composable
private fun ZoneFilterSection(
    kind: ActivityKind?,
    span: Int,
    off: Int,
    today: Long,
    onKind: (ActivityKind?) -> Unit,
    onSpan: (Int) -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit
) {
    val kinds = listOf(ActivityKind.RUNNING to "Runs", ActivityKind.WALKING to "Walks", ActivityKind.CYCLING to "Rides", null to "All")
    val spans = listOf(0 to "All Time", 30 to "30D", 90 to "90D", 365 to "1Y")
    FilterChipBar(kinds, kind, onKind)
    Box(modifier = Modifier.padding(top = 4.dp)) { FilterChipBar(spans, span, onSpan) }
    if (span <= 0) return
    val endD = LocalDate.ofEpochDay(today - off * span.toLong())
    val startD = LocalDate.ofEpochDay(today - off * span.toLong() - span + 1L)
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onOlder, shape = RoundedCornerShape(999.dp)) { Text("Older", maxLines = 1, softWrap = false) }
        Text("$startD -> $endD", style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), color = KdeMuted)
        OutlinedButton(onClick = onNewer, enabled = off > 0, shape = RoundedCornerShape(999.dp)) { Text("Newer", maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun <T> FilterChipBar(items: List<Pair<T, String>>, cur: T, onPick: (T) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, lbl) ->
            FilterChip(
                selected = cur == v, onClick = { onPick(v) }, colors = cc, modifier = Modifier.weight(1f),
                label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(lbl, maxLines = 1, softWrap = false) } }
            )
        }
    }
}

@Composable
private fun ZoneDistributionCard(base: Int, count: Int, dist: List<com.stridecoach.running.core.domain.engine.PaceZoneBucket>) {
    StrideCard {
        Text(
            "BASELINE: ${formatPace(base)}/km (${formatSpeedKmh(base)}) • $count sessions",
            style = MaterialTheme.typography.labelLarge.merge(TabularNumberStyle), color = KdePurple, fontWeight = FontWeight.Bold
        )
        dist.forEach { b -> ZoneBarRow(b) }
    }
}

@Composable
private fun ZoneBarRow(b: com.stridecoach.running.core.domain.engine.PaceZoneBucket) {
    val col = paceZoneColor(b.zone)
    val hrs = b.durationMillis / 3_600_000L
    val mins = (b.durationMillis % 3_600_000L) / 60_000L
    val dur = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Z${b.zone} ${b.label}", style = MaterialTheme.typography.labelSmall, color = col, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
        Box(modifier = Modifier.weight(1.8f)) {
            Box(modifier = Modifier.fillMaxWidth((b.percent / 100f).coerceIn(0.03f, 1f)).height(9.dp).background(col, RoundedCornerShape(4.dp)))
        }
        Text("${b.percent}% ($dur)", style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = KdeText)
    }
}

@Composable
private fun ZoneExplainCard(z: PaceZone, base: Int) {
    val exp = remember(z, base) { PaceZoneClassifier.explainZone(z, base) }
    val col = paceZoneColor(z.zoneNum)
    StrideCard(borderColor = col.copy(alpha = 0.45f)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusPill("Z${z.zoneNum} • ${z.label}", col)
            Text("${exp.paceRangeText} (${exp.speedRangeText})", style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), fontWeight = FontWeight.Bold, color = col)
        }
        Text(exp.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(exp.coachingTip, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    }
}

@Composable
fun PersonalRecordsDetailScreen(prHistories: List<PersonalRecordHistory>, runs: List<RunRecord>, onBack: () -> Unit, onSelectRun: (String) -> Unit) {
    val longRun = remember(runs) { runs.filter { it.activityKind == ActivityKind.RUNNING }.maxByOrNull { it.distanceMeters } }
    val longWalk = remember(runs) { runs.filter { it.activityKind == ActivityKind.WALKING }.maxByOrNull { it.distanceMeters } }
    val topClimb = remember(runs) { runs.maxByOrNull { it.elevGainM }?.takeIf { it.elevGainM > 0 } }
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, yyyy • HH:mm").withZone(ZoneId.systemDefault()) }

    Column(modifier = Modifier.fillMaxSize().background(KdeBg).statusBarsPadding().navigationBarsPadding()) {
        DrillTopBar("Personal Records & Milestones", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (longRun != null || longWalk != null || topClimb != null) {
                item("milestones") { MilestonesCard(longRun, longWalk, topClimb, onSelectRun) }
            }
            items(prHistories, key = { it.label }) { h -> PrHistoryCard(h, fmt, onSelectRun) }
        }
    }
}

@Composable
private fun MilestonesCard(longRun: RunRecord?, longWalk: RunRecord?, topClimb: RunRecord?, onPick: (String) -> Unit) {
    StrideCard {
        Text("VOLUME MILESTONES", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
        longRun?.let { r -> MilestoneRow("Longest Run (${r.name})", "${"%.2f".format(r.km())} km", KdePurple) { onPick(r.id) } }
        longWalk?.let { r -> MilestoneRow("Longest Walk (${r.name})", "${"%.2f".format(r.km())} km", KdeSky) { onPick(r.id) } }
        topClimb?.let { r -> MilestoneRow("Biggest Climb (${r.name})", "+${r.elevGainM} m", PaceOnTarget) { onPick(r.id) } }
    }
}

@Composable
private fun MilestoneRow(title: String, valTxt: String, col: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(valTxt, style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = col, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PrHistoryCard(h: PersonalRecordHistory, fmt: DateTimeFormatter, onPick: (String) -> Unit) {
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusPill("${h.label} PR", KdePurple)
            Text("${formatDuration(h.currentBest.durationMillis)} • ${formatPace(h.currentBest.paceSecPerKm)}/km", style = MaterialTheme.typography.titleMedium.merge(TabularNumberStyle), fontWeight = FontWeight.Bold, color = KdePurple)
        }
        Text("Set on ${fmt.format(Instant.ofEpochMilli(h.currentBest.timestampMillis))} (${formatSpeedKmh(h.currentBest.paceSecPerKm)})", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        Text("PROGRESSION HISTORY (${h.history.size} records)", style = MaterialTheme.typography.labelSmall, color = KdeSky, fontWeight = FontWeight.Bold)
        h.history.forEachIndexed { idx, entry -> PrProgressionRow(idx, entry, fmt) { onPick(entry.runId) } }
    }
}

@Composable
private fun PrProgressionRow(idx: Int, entry: PrProgressionEntry, fmt: DateTimeFormatter, onClick: () -> Unit) {
    val gain = if (entry.improvementMillis > 0) "-${formatDuration(entry.improvementMillis)}" else "Initial"
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(KdeCardAlt.copy(alpha = if (idx == 0) 0.7f else 0.35f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(entry.runName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmt.format(Instant.ofEpochMilli(entry.timestampMillis)), style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(entry.durationMillis), style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), fontWeight = FontWeight.Bold)
            Text(gain, style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = if (entry.improvementMillis > 0) PaceOnTarget else KdeMuted)
        }
    }
}

@Composable
private fun HistoricalWindowChart(bars: List<HistoricalLoadBucket>) {
    if (bars.isEmpty()) return
    val maxM = bars.maxOf { it.runMeters + it.walkMeters }.coerceAtLeast(1_000)
    Canvas(modifier = Modifier.fillMaxWidth().height(98.dp)) {
        val slotW = size.width / bars.size.coerceAtLeast(1)
        val barW = (slotW * 0.50f).coerceAtMost(28.dp.toPx())
        bars.forEachIndexed { idx, b ->
            val x = idx * slotW + (slotW - barW) / 2f
            val runH = (b.runMeters.toFloat() / maxM) * (size.height - 6f)
            val walkH = (b.walkMeters.toFloat() / maxM) * (size.height - 6f)
            val totH = (runH + walkH).coerceAtLeast(if (b.runMeters + b.walkMeters > 0) 4f else 0f)
            if (walkH > 0f) drawRoundRect(KdeSky, Offset(x, size.height - totH), Size(barW, walkH), CornerRadius(4f, 4f))
            if (runH > 0f) drawRoundRect(KdePurple, Offset(x, size.height - runH), Size(barW, runH), CornerRadius(4f, 4f))
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        bars.forEach { b -> Text(b.label, style = MaterialTheme.typography.labelSmall, color = KdeMuted, maxLines = 1) }
    }
}

@Composable
private fun HistoricalRunMiniRow(r: RunRecord, onClick: () -> Unit) {
    val dt = remember(r.startTimestampMillis) {
        DateTimeFormatter.ofPattern("MMM d, HH:mm").format(Instant.ofEpochMilli(r.startTimestampMillis).atZone(ZoneId.systemDefault()))
    }
    StrideCard(onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$dt • ${r.activityKind.singularTag}", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${"%.2f".format(r.km())} km", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdePurple, fontWeight = FontWeight.Bold)
                Text("${formatPace(r.paceSecondsPerKm)}/km • ${formatDuration(r.durationMillis)}", style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = KdeMuted)
            }
        }
    }
}

@Composable
fun DrillTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KdeText) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = KdeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

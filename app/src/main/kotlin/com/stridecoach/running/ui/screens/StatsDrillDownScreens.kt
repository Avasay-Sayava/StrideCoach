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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.HistoricalLoadBucket
import com.stridecoach.running.core.domain.engine.PaceZone
import com.stridecoach.running.core.domain.engine.PaceZoneClassifier
import com.stridecoach.running.core.domain.engine.PersonalRecordHistory
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

private enum class TimeMachineGranularity(val label: String, val spanDays: Int, val bucketDays: Int) {
    WEEK("7D", 7, 1),
    SIX_WEEKS("6W", 42, 7),
    MONTH("Month", 30, 5),
    YEAR("Year", 365, 30)
}

@Composable
fun LoadTimeMachineScreen(
    runs: List<RunRecord>,
    walks: List<WalkLog>,
    onBack: () -> Unit,
    onSelectRun: (String) -> Unit
) {
    var gran by remember { mutableStateOf(TimeMachineGranularity.WEEK) }
    var offsetSteps by remember { mutableIntStateOf(0) }
    val today = remember { LocalDate.now().toEpochDay() }
    val endDay = today - (offsetSteps * gran.spanDays.toLong())
    val engine = remember { RunAnalyticsEngine() }
    val stats = remember(runs, walks, endDay, gran) {
        engine.computeHistoricalWindow(runs, walks, endDay, gran.spanDays, gran.bucketDays)
    }

    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, yy") }
    val rangeLabel = "${LocalDate.ofEpochDay(stats.startEpochDay).format(fmt)} - ${LocalDate.ofEpochDay(stats.endEpochDay).format(fmt)}"
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KdeBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DrillTopBar("Load History Explorer", onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("gran") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimeMachineGranularity.entries.forEach { g ->
                        FilterChip(
                            selected = gran == g,
                            onClick = { gran = g; offsetSteps = 0 },
                            colors = cc,
                            modifier = Modifier.weight(1f),
                            label = {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(g.label, maxLines = 1, softWrap = false)
                                }
                            }
                        )
                    }
                }
            }
            item("nav") {
                StrideCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { offsetSteps++ }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous Period")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(rangeLabel, style = MaterialTheme.typography.titleSmall.merge(TabularNumberStyle), fontWeight = FontWeight.Bold)
                            if (offsetSteps > 0) {
                                Text("Tap Today to jump back", style = MaterialTheme.typography.labelSmall, color = KdeSky, modifier = Modifier.clickable { offsetSteps = 0 })
                            }
                        }
                        IconButton(onClick = { if (offsetSteps > 0) offsetSteps-- }, enabled = offsetSteps > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Period")
                        }
                    }
                    HistoricalWindowChart(stats.bars)
                }
            }
            item("metrics") {
                TelemetryStatStrip(
                    items = listOf(
                        "%.1f km".format(stats.totalRunMeters / 1000.0) to "Runs",
                        "%.1f km".format(stats.totalWalkMeters / 1000.0) to "Walks",
                        "${stats.sessionCount}" to "Sessions",
                        "+${stats.totalElevGainM}m" to "Elev Gain"
                    )
                )
            }
            item("hdr") {
                Text("SESSIONS IN THIS PERIOD (${stats.matchingRuns.size})", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
            }
            if (stats.matchingRuns.isEmpty()) {
                item("none") {
                    StrideCard { Text("No workouts logged in this window.", style = MaterialTheme.typography.bodySmall, color = KdeMuted) }
                }
            } else {
                items(stats.matchingRuns, key = { it.id }) { r ->
                    HistoricalRunMiniRow(r) { onSelectRun(r.id) }
                }
            }
        }
    }
}

@Composable
fun PaceZonesDetailScreen(
    runs: List<RunRecord>,
    baselinePaceSec: Int,
    onBack: () -> Unit
) {
    var selectedKind by remember { mutableStateOf<ActivityKind?>(ActivityKind.RUNNING) }
    var spanDays by remember { mutableIntStateOf(0) }
    var offsetSteps by remember { mutableIntStateOf(0) }
    val kindBaseline = remember(runs, selectedKind, baselinePaceSec) {
        PaceZoneClassifier.computeCurrentBaselinePace(runs, selectedKind ?: ActivityKind.RUNNING, baselinePaceSec)
    }
    val today = remember { LocalDate.now().toEpochDay() }
    val filteredRuns = remember(runs, selectedKind, spanDays, offsetSteps) {
        val byKind = if (selectedKind == null) runs else runs.filter { it.activityKind == selectedKind }
        if (spanDays <= 0) byKind
        else {
            val endDay = today - offsetSteps * spanDays.toLong()
            val startDay = endDay - spanDays + 1L
            byKind.filter { r ->
                val d = Instant.ofEpochMilli(r.startTimestampMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
                d in startDay..endDay
            }
        }
    }
    val engine = remember { RunAnalyticsEngine() }
    val dist = remember(filteredRuns, kindBaseline) { engine.computePaceZoneDistribution(filteredRuns, kindBaseline) }
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KdeBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DrillTopBar("Pace Zones & History", onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("kind_window") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ActivityKind.RUNNING to "Runs",
                        ActivityKind.WALKING to "Walks",
                        ActivityKind.CYCLING to "Rides",
                        null to "All"
                    ).forEach { (k, lbl) ->
                        FilterChip(
                            selected = selectedKind == k,
                            onClick = { selectedKind = k },
                            colors = cc,
                            modifier = Modifier.weight(1f),
                            label = {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(lbl, maxLines = 1, softWrap = false)
                                }
                            }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "All Time", 30 to "30D", 90 to "90D", 365 to "1Y").forEach { (days, lbl) ->
                        FilterChip(
                            selected = spanDays == days,
                            onClick = { spanDays = days; offsetSteps = 0 },
                            colors = cc,
                            modifier = Modifier.weight(1f),
                            label = {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(lbl, maxLines = 1, softWrap = false)
                                }
                            }
                        )
                    }
                }
                if (spanDays > 0) {
                    val endD = LocalDate.ofEpochDay(today - offsetSteps * spanDays.toLong())
                    val startD = LocalDate.ofEpochDay(today - offsetSteps * spanDays.toLong() - spanDays + 1L)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { offsetSteps++ }, shape = RoundedCornerShape(999.dp)) { Text("Older", maxLines = 1, softWrap = false) }
                        Text("$startD -> $endD", style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), color = KdeMuted)
                        OutlinedButton(onClick = { if (offsetSteps > 0) offsetSteps-- }, enabled = offsetSteps > 0, shape = RoundedCornerShape(999.dp)) { Text("Newer", maxLines = 1, softWrap = false) }
                    }
                }
            }
            item("summary") {
                StrideCard {
                    Text(
                        "BASELINE: ${formatPace(kindBaseline)}/km (${formatSpeedKmh(kindBaseline)}) • ${filteredRuns.size} sessions",
                        style = MaterialTheme.typography.labelLarge.merge(TabularNumberStyle),
                        color = KdePurple,
                        fontWeight = FontWeight.Bold
                    )
                    dist.forEach { b ->
                        val col = paceZoneColor(b.zone)
                        val hrs = b.durationMillis / 3_600_000L
                        val mins = (b.durationMillis % 3_600_000L) / 60_000L
                        val durTxt = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Z${b.zone} ${b.label}", style = MaterialTheme.typography.labelSmall, color = col, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                            Box(modifier = Modifier.weight(1.8f)) {
                                Box(modifier = Modifier.fillMaxWidth((b.percent / 100f).coerceIn(0.03f, 1f)).height(9.dp).background(col, RoundedCornerShape(4.dp)))
                            }
                            Text("${b.percent}% ($durTxt)", style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = KdeText)
                        }
                    }
                }
            }
            items(PaceZone.entries, key = { it.zoneNum }) { z ->
                val exp = remember(z, kindBaseline) { PaceZoneClassifier.explainZone(z, kindBaseline) }
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
        }
    }
}

@Composable
fun PersonalRecordsDetailScreen(
    prHistories: List<PersonalRecordHistory>,
    runs: List<RunRecord>,
    onBack: () -> Unit,
    onSelectRun: (String) -> Unit
) {
    val longestRun = remember(runs) { runs.filter { it.activityKind == ActivityKind.RUNNING }.maxByOrNull { it.distanceMeters } }
    val longestWalk = remember(runs) { runs.filter { it.activityKind == ActivityKind.WALKING }.maxByOrNull { it.distanceMeters } }
    val highestClimb = remember(runs) { runs.maxByOrNull { it.elevGainM }?.takeIf { it.elevGainM > 0 } }
    val dtFmt = remember { DateTimeFormatter.ofPattern("MMM d, yyyy • HH:mm").withZone(ZoneId.systemDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KdeBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DrillTopBar("Personal Records & Milestones", onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (longestRun != null || longestWalk != null || highestClimb != null) {
                item("milestones") {
                    StrideCard {
                        Text("VOLUME MILESTONES", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
                        longestRun?.let { lr ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectRun(lr.id) }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Longest Run (${lr.name})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${"%.2f".format(lr.km())} km", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdePurple, fontWeight = FontWeight.Bold)
                            }
                        }
                        longestWalk?.let { lw ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectRun(lw.id) }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Longest Walk (${lw.name})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${"%.2f".format(lw.km())} km", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdeSky, fontWeight = FontWeight.Bold)
                            }
                        }
                        highestClimb?.let { hc ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectRun(hc.id) }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Biggest Climb (${hc.name})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("+${hc.elevGainM} m", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = PaceOnTarget, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            items(prHistories, key = { it.label }) { h ->
                StrideCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("${h.label} PR", KdePurple)
                        Text(
                            "${formatDuration(h.currentBest.durationMillis)} • ${formatPace(h.currentBest.paceSecPerKm)}/km",
                            style = MaterialTheme.typography.titleMedium.merge(TabularNumberStyle),
                            fontWeight = FontWeight.Bold,
                            color = KdePurple
                        )
                    }
                    Text(
                        "Set on ${dtFmt.format(Instant.ofEpochMilli(h.currentBest.timestampMillis))} (${formatSpeedKmh(h.currentBest.paceSecPerKm)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdeMuted
                    )
                    Text("PROGRESSION HISTORY (${h.history.size} records)", style = MaterialTheme.typography.labelSmall, color = KdeSky, fontWeight = FontWeight.Bold)
                    h.history.forEachIndexed { idx, entry ->
                        val gainTxt = if (entry.improvementMillis > 0) "-${formatDuration(entry.improvementMillis)}" else "Initial"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(KdeCardAlt.copy(alpha = if (idx == 0) 0.7f else 0.35f), RoundedCornerShape(8.dp))
                                .clickable { onSelectRun(entry.runId) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(entry.runName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(dtFmt.format(Instant.ofEpochMilli(entry.timestampMillis)), style = MaterialTheme.typography.labelSmall, color = KdeMuted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatDuration(entry.durationMillis), style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), fontWeight = FontWeight.Bold)
                                Text(gainTxt, style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = if (entry.improvementMillis > 0) PaceOnTarget else KdeMuted)
                            }
                        }
                    }
                }
            }
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
            if (walkH > 0f) {
                drawRoundRect(KdeSky, Offset(x, size.height - totH), Size(barW, walkH), CornerRadius(4f, 4f))
            }
            if (runH > 0f) {
                drawRoundRect(KdePurple, Offset(x, size.height - runH), Size(barW, runH), CornerRadius(4f, 4f))
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        bars.forEach { b -> Text(b.label, style = MaterialTheme.typography.labelSmall, color = KdeMuted, maxLines = 1) }
    }
}

@Composable
private fun HistoricalRunMiniRow(r: RunRecord, onClick: () -> Unit) {
    val dt = remember(r.startTimestampMillis) {
        DateTimeFormatter.ofPattern("MMM d, HH:mm").format(
            Instant.ofEpochMilli(r.startTimestampMillis).atZone(ZoneId.systemDefault())
        )
    }
    StrideCard(modifier = Modifier.clickable { onClick() }) {
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KdeText)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = KdeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

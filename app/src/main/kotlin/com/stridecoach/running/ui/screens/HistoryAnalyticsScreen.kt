package com.stridecoach.running.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.DailyLoadBar
import com.stridecoach.running.core.domain.engine.PaceZoneBucket
import com.stridecoach.running.core.domain.engine.PersonalRecord
import com.stridecoach.running.core.domain.engine.WeeklyLoadPoint
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import com.stridecoach.running.ui.CoachUiState
import com.stridecoach.running.ui.TelemetrySummary
import com.stridecoach.running.ui.components.RenameExerciseDialog
import com.stridecoach.running.ui.components.StaticRouteMapThumbnail
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.components.TelemetryStatStrip
import com.stridecoach.running.ui.components.resolveMapStyleUri
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.KdeText
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooSlow
import com.stridecoach.running.ui.theme.StrideThemeState
import com.stridecoach.running.ui.theme.TabularNumberStyle
import com.stridecoach.running.ui.theme.paceZoneColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CARD_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

private enum class DateWindow(val label: String, val days: Long?) {
    ALL("All", null), D7("7D", 7L), D30("30D", 30L), D90("90D", 90L), Y1("1Y", 365L)
}

private enum class DistBucket(val label: String, val minM: Int, val maxM: Int) {
    ALL("All", 0, Int.MAX_VALUE),
    SHORT("<3k", 0, 2_999),
    MID("3-8k", 3_000, 8_000),
    LONG("8-15k", 8_001, 15_000),
    ULTRA("15k+", 15_001, Int.MAX_VALUE)
}

private enum class SortOption(val label: String) {
    NEWEST("Newest"), LONGEST("Longest"), FASTEST("Fastest"), ELEV("Elev")
}

private data class HistoryFilterState(
    val query: String = "",
    val kind: ActivityKind? = null,
    val dateWindow: DateWindow = DateWindow.ALL,
    val distBucket: DistBucket = DistBucket.ALL,
    val source: RunSource? = null,
    val sort: SortOption = SortOption.NEWEST
) {
    fun activeCount(): Int = listOfNotNull(
        query.takeIf { it.isNotBlank() },
        kind,
        dateWindow.takeIf { it != DateWindow.ALL },
        distBucket.takeIf { it != DistBucket.ALL },
        source,
        sort.takeIf { it != SortOption.NEWEST }
    ).size
}

@Composable
fun HistoryAnalyticsScreen(
    state: CoachUiState,
    onSelectRun: (String) -> Unit,
    onTrashRun: (String) -> Unit,
    onRenameRun: (String, String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenLoadHistory: () -> Unit,
    onOpenPaceZones: () -> Unit,
    onOpenPersonalRecords: () -> Unit,
    analyticsScrollState: LazyListState = rememberLazyListState(),
    historyScrollState: LazyListState = rememberLazyListState()
) {
    var subTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        StyleCHeader(subTab, state.telemetry, state.trashedRuns.size, onOpenTrash) { subTab = it }
        if (subTab == 0) {
            AnalyticsFeed(
                state = state,
                listState = analyticsScrollState,
                onSelectRun = onSelectRun,
                onOpenLoadHistory = onOpenLoadHistory,
                onOpenPaceZones = onOpenPaceZones,
                onOpenPersonalRecords = onOpenPersonalRecords
            )
        } else {
            HistoryFeed(
                state = state,
                listState = historyScrollState,
                onSelectRun = onSelectRun,
                onTrashRun = onTrashRun,
                onRenameRun = onRenameRun
            )
        }
    }
}

@Composable
private fun StyleCHeader(
    subTab: Int,
    tel: TelemetrySummary,
    trashCnt: Int,
    onOpenTrash: () -> Unit,
    onPick: (Int) -> Unit
) {
    val pillTxt = "${tel.runCount}R • ${tel.walkCount}W • ${tel.cycleCount}C"
    Column(modifier = Modifier.fillMaxWidth().background(KdeBg).padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Telemetry", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (trashCnt > 0) {
                    StatusPill("Trash ($trashCnt)", PaceTooSlow, onClick = onOpenTrash)
                }
                StatusPill(pillTxt, KdePurple)
            }
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
                            color = if (subTab == idx) KdeText else KdeMuted,
                            maxLines = 1
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
    onSelectRun: (String) -> Unit,
    onOpenLoadHistory: () -> Unit,
    onOpenPaceZones: () -> Unit,
    onOpenPersonalRecords: () -> Unit
) {
    val tel = state.telemetry
    val mapStyle = resolveMapStyleUri(state.preferences.mapStyleMode, StrideThemeState.isLight)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("summary") { TelemetrySummaryBar(tel) }
        item("counts") { ActivityBreakdownStrip(tel) }
        item("weekly") {
            WeeklyStackedLoadCard(tel.last7DaysLoad, tel.weeklyLoad, onOpenLoadHistory)
        }
        item("zones") {
            PaceDistributionCard(tel.paceZones, tel.currentBaselinePaceSec, onOpenPaceZones)
        }
        state.runs.firstOrNull()?.let { latest ->
            item("latest") { LatestRunCard(latest, state.preferences.showSpeedKmh, mapStyle, state.preferences.showMapAttribution, onSelectRun) }
        }
        if (tel.personalRecords.isNotEmpty()) {
            item("prs") { PersonalRecordsCard(tel.personalRecords, onOpenPersonalRecords) }
        }
    }
}

@Composable
private fun ActivityBreakdownStrip(t: TelemetrySummary) {
    TelemetryStatStrip(
        items = listOf(
            "${t.runCount}" to "Runs",
            "${t.walkCount}" to "Walks",
            "${t.cycleCount}" to "Rides",
            "${t.walkDaysCount}" to "Walk Days"
        )
    )
}

@Composable
private fun HistoryFeed(
    state: CoachUiState,
    listState: LazyListState,
    onSelectRun: (String) -> Unit,
    onTrashRun: (String) -> Unit,
    onRenameRun: (String, String) -> Unit
) {
    val runs = state.runs
    val tel = state.telemetry
    val mapStyle = resolveMapStyleUri(state.preferences.mapStyleMode, StrideThemeState.isLight)
    var filters by remember { mutableStateOf(HistoryFilterState()) }
    var showAdv by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RunRecord?>(null) }
    val nowMs = remember(runs.size) { System.currentTimeMillis() }
    val filtered = remember(runs, filters) { applyFilters(runs, filters, nowMs) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("kind_bar") {
            ActivityKindFilterBar(
                runsCount = tel.runCount,
                walksCount = tel.walkCount,
                cyclesCount = tel.cycleCount,
                otherCount = tel.otherCount,
                totalCount = runs.size,
                selectedKind = filters.kind,
                showAdv = showAdv,
                activeAdvCount = filters.activeCount(),
                onSelectKind = { filters = filters.copy(kind = it) },
                onToggleAdv = { showAdv = !showAdv }
            )
        }
        if (showAdv) {
            item("adv_filters") {
                AdvancedFiltersCard(
                    filters = filters,
                    shownCount = filtered.size,
                    onUpdate = { filters = it },
                    onReset = { filters = HistoryFilterState() }
                )
            }
        }
        if (filtered.isEmpty()) {
            item("empty") {
                StrideCard {
                    Text(
                        if (runs.isEmpty()) "No sessions yet. Record a run or import a .backup in Data." else "No activities match the current filters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KdeMuted
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }, contentType = { "run_card" }) { run ->
                RunTelemetryCard(
                    r = run,
                    showSpeed = state.preferences.showSpeedKmh,
                    styleUri = mapStyle,
                    showAttribution = state.preferences.showMapAttribution,
                    onSelect = onSelectRun,
                    onRename = { renameTarget = run },
                    onTrash = { onTrashRun(run.id) }
                )
            }
        }
    }
    renameTarget?.let { target ->
        RenameExerciseDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameRun(target.id, newName)
                renameTarget = null
            }
        )
    }
}

@Composable
private fun ActivityKindFilterBar(
    runsCount: Int,
    walksCount: Int,
    cyclesCount: Int,
    otherCount: Int,
    totalCount: Int,
    selectedKind: ActivityKind?,
    showAdv: Boolean,
    activeAdvCount: Int,
    onSelectKind: (ActivityKind?) -> Unit,
    onToggleAdv: () -> Unit
) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    val fc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdeSky.copy(alpha = 0.22f), selectedLabelColor = KdeSky)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedKind == null,
                onClick = { onSelectKind(null) },
                colors = cc,
                label = { Text("All ($totalCount)", maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = selectedKind == ActivityKind.RUNNING,
                onClick = { onSelectKind(ActivityKind.RUNNING) },
                colors = cc,
                label = { Text("Runs ($runsCount)", maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = selectedKind == ActivityKind.WALKING,
                onClick = { onSelectKind(ActivityKind.WALKING) },
                colors = cc,
                label = { Text("Walks ($walksCount)", maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = selectedKind == ActivityKind.CYCLING,
                onClick = { onSelectKind(ActivityKind.CYCLING) },
                colors = cc,
                label = { Text("Rides ($cyclesCount)", maxLines = 1, softWrap = false) }
            )
            if (otherCount > 0) {
                FilterChip(
                    selected = selectedKind == ActivityKind.HIKING,
                    onClick = { onSelectKind(ActivityKind.HIKING) },
                    colors = cc,
                    label = { Text("Hikes ($otherCount)", maxLines = 1, softWrap = false) }
                )
            }
        }
        val fLbl = if (activeAdvCount > 0) "Filters ($activeAdvCount)" else "Filters"
        FilterChip(
            selected = showAdv || activeAdvCount > 0,
            onClick = onToggleAdv,
            colors = fc,
            leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text(fLbl, maxLines = 1, softWrap = false) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedFiltersCard(
    filters: HistoryFilterState,
    shownCount: Int,
    onUpdate: (HistoryFilterState) -> Unit,
    onReset: () -> Unit
) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ADVANCED FILTERS ($shownCount shown)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onReset, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Reset", maxLines = 1, softWrap = false)
            }
        }
        OutlinedTextField(
            value = filters.query,
            onValueChange = { onUpdate(filters.copy(query = it)) },
            label = { Text("Search name or description") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text("Date Window", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DateWindow.entries.forEach { d ->
                FilterChip(selected = filters.dateWindow == d, onClick = { onUpdate(filters.copy(dateWindow = d)) }, colors = cc, label = { Text(d.label, maxLines = 1, softWrap = false) })
            }
        }
        Text("Distance", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DistBucket.entries.forEach { db ->
                FilterChip(selected = filters.distBucket == db, onClick = { onUpdate(filters.copy(distBucket = db)) }, colors = cc, label = { Text(db.label, maxLines = 1, softWrap = false) })
            }
        }
        Text("Source", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filters.source == null, onClick = { onUpdate(filters.copy(source = null)) }, colors = cc, label = { Text("All", maxLines = 1, softWrap = false) })
            listOf(
                RunSource.RECORDED to "Recorded",
                RunSource.BACKUP_IMPORT to "Backup",
                RunSource.HEALTH_CONNECT to "Health",
                RunSource.TIMELINE_IMPORT to "Timeline"
            ).forEach { (src, lbl) ->
                FilterChip(selected = filters.source == src, onClick = { onUpdate(filters.copy(source = src)) }, colors = cc, label = { Text(lbl, maxLines = 1, softWrap = false) })
            }
        }
        Text("Sort By", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SortOption.entries.forEach { s ->
                FilterChip(selected = filters.sort == s, onClick = { onUpdate(filters.copy(sort = s)) }, colors = cc, label = { Text(s.label, maxLines = 1, softWrap = false) })
            }
        }
    }
}

private fun applyFilters(runs: List<RunRecord>, f: HistoryFilterState, nowMs: Long): List<RunRecord> {
    val minTs = f.dateWindow.days?.let { nowMs - it * 86_400_000L } ?: Long.MIN_VALUE
    val q = f.query.trim().lowercase()
    val filtered = runs.filter { r ->
        (f.kind == null || r.activityKind == f.kind) &&
            r.startTimestampMillis >= minTs &&
            r.distanceMeters in f.distBucket.minM..f.distBucket.maxM &&
            (f.source == null || r.source == f.source) &&
            (q.isEmpty() || r.name.lowercase().contains(q) || r.description.lowercase().contains(q))
    }
    return when (f.sort) {
        SortOption.NEWEST -> filtered.sortedByDescending { it.startTimestampMillis }
        SortOption.LONGEST -> filtered.sortedByDescending { it.distanceMeters }
        SortOption.FASTEST -> filtered.sortedBy { if (it.paceSecondsPerKm > 0) it.paceSecondsPerKm else Int.MAX_VALUE }
        SortOption.ELEV -> filtered.sortedByDescending { it.elevGainM }
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
private fun WeeklyStackedLoadCard(
    bars: List<DailyLoadBar>,
    weeks: List<WeeklyLoadPoint>,
    onOpenHistory: () -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }
    StrideCard(onClick = onOpenHistory) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("WEEKLY LOAD", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open Load History", tint = KdeMuted, modifier = Modifier.size(18.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot("Run", KdePurple)
                LegendDot("Walk", KdeSky)
                StatusPill(if (mode == 0) "7D" else "6W", KdePurple, onClick = { mode = (mode + 1) % 2 })
            }
        }
        val items = if (mode == 0) {
            bars.map { Triple(it.dayLabel, it.runMeters, it.walkMeters) }
        } else {
            weeks.takeLast(6).map { Triple(it.weekLabel.takeLast(3), it.runMeters, it.walkMeters) }
        }
        VerticalStackedBarsChart(items)
        Text("Tap card to browse all past weeks, months & years", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
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
private fun PaceDistributionCard(
    zones: List<PaceZoneBucket>,
    baselinePaceSec: Int,
    onOpenZones: () -> Unit
) {
    StrideCard(onClick = onOpenZones) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("PACE ZONES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open Pace Zones Detail", tint = KdeMuted, modifier = Modifier.size(18.dp))
            }
            Text("Baseline ${formatPace(baselinePaceSec)}/km", style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = KdePurple)
        }
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
                Text("Z${z.zone} ${z.label}", style = MaterialTheme.typography.labelSmall, color = col, fontWeight = FontWeight.Bold, modifier = Modifier.size(width = 64.dp, height = 16.dp))
                Box(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth(frac).height(9.dp).background(col, RoundedCornerShape(4.dp)))
                }
                Text("${z.percent}%", style = MaterialTheme.typography.labelMedium.merge(TabularNumberStyle), color = KdeText)
            }
        }
        Text("Tap for zone pace ranges, history & training guide", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
    }
}

@Composable
private fun LatestRunCard(
    r: RunRecord,
    showSpeed: Boolean,
    styleUri: String,
    showAttribution: Boolean,
    onSelect: (String) -> Unit
) {
    StrideCard(onClick = { onSelect(r.id) }) {
        Text("LATEST • ${r.name.uppercase()}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = KdeMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        RunPreviewHeaderMetrics(r, showSpeed)
        if (r.hasTrack()) {
            StaticRouteMapThumbnail(run = r, modifier = Modifier.fillMaxWidth().height(132.dp), styleUri = styleUri, showAttribution = showAttribution)
        }
    }
}

@Composable
private fun RunTelemetryCard(
    r: RunRecord,
    showSpeed: Boolean,
    styleUri: String,
    showAttribution: Boolean,
    onSelect: (String) -> Unit,
    onRename: () -> Unit,
    onTrash: () -> Unit
) {
    val dt = remember(r.startTimestampMillis) {
        CARD_DATE_FMT.format(Instant.ofEpochMilli(r.startTimestampMillis))
    }
    StrideCard(onClick = { onSelect(r.id) }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dt, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    r.activityKind.singularTag,
                    if (r.activityKind == ActivityKind.RUNNING) KdePurple else KdeSky
                )
                if (r.isSnapped) StatusPill("Snapped", PaceOnTarget)
                IconButton(onClick = onRename, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename Exercise", tint = KdeMuted, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onTrash, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Move to Trash", tint = KdeMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
        RunPreviewHeaderMetrics(r, showSpeed)
        if (r.hasTrack()) {
            StaticRouteMapThumbnail(run = r, modifier = Modifier.fillMaxWidth().height(136.dp), styleUri = styleUri, showAttribution = showAttribution)
        }
    }
}

@Composable
private fun RunPreviewHeaderMetrics(r: RunRecord, showSpeed: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${"%.2f".format(r.km())} km", style = MaterialTheme.typography.bodyMedium.merge(TabularNumberStyle), color = KdePurple, fontWeight = FontWeight.Bold)
        Text(formatDuration(r.durationMillis), style = MaterialTheme.typography.bodyMedium.merge(TabularNumberStyle), fontWeight = FontWeight.SemiBold)
        Text("${formatPace(r.paceSecondsPerKm)}/km", style = MaterialTheme.typography.bodyMedium.merge(TabularNumberStyle))
        val tail = if (showSpeed && r.paceSecondsPerKm > 0) formatSpeedKmh(r.paceSecondsPerKm) else "${r.caloriesBurned} kcal"
        Text(tail, style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdeMuted)
    }
}

@Composable
private fun PersonalRecordsCard(prs: List<PersonalRecord>, onOpenPrs: () -> Unit) {
    StrideCard(onClick = onOpenPrs) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("PERSONAL RECORDS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open PR Progression", tint = KdeMuted, modifier = Modifier.size(18.dp))
        }
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
        Text("Tap to see record dates & progression timeline", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
    }
}

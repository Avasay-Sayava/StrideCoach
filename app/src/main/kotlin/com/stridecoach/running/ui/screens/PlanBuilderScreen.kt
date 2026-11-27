package com.stridecoach.running.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.PlanPresets
import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.GoalDistance
import com.stridecoach.running.core.domain.model.PaceBounds
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.SegmentTarget
import com.stridecoach.running.core.domain.model.StepKind
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.WalkingLoadRule
import com.stridecoach.running.core.domain.model.WorkoutSegment
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.CoachUiState
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import com.stridecoach.running.ui.theme.TabularNumberStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

@Composable
fun PlanBuilderScreen(
    state: CoachUiState,
    onActivatePlan: (TrainingPlanConfig) -> Unit,
    onRecalibrate5k: (Long) -> Unit,
    onSaveTemplate: (StructuredWorkout) -> Unit,
    onTrashTemplate: (String) -> Unit,
    onStartWorkout: (StructuredWorkout) -> Unit,
    onOpenRoutePlanner: () -> Unit = {}
) {
    var subTab by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val labels = listOf("Schedule", "Builder", "Workouts")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Training Plans", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            OutlinedButton(onClick = onOpenRoutePlanner, shape = RoundedCornerShape(999.dp)) {
                Text("Draw Route", maxLines = 1, softWrap = false)
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { idx, lbl ->
                SegmentedButton(
                    selected = subTab == idx,
                    onClick = { subTab = idx },
                    shape = SegmentedButtonDefaults.itemShape(idx, labels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = KdePurple.copy(alpha = 0.22f),
                        activeContentColor = KdePurple
                    )
                ) { Text(lbl, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
        }
        when (subTab) {
            0 -> ScheduleList(
                plan = state.activePlan,
                days = state.plannedDays,
                showFinale = state.preferences.showCompetitionFinale,
                onRun = onStartWorkout
            )
            1 -> {
                PlanCreatorCard(onActivatePlan)
                BenchmarkCard(onRecalibrate5k)
            }
            2 -> WorkoutBuilderCard(
                paceSec = state.activePlan?.targetPaceSecPerKm ?: 295,
                templates = state.templates,
                onSave = onSaveTemplate,
                onTrash = onTrashTemplate,
                onRun = onStartWorkout
            )
        }
    }
}

@Composable
private fun ScheduleList(
    plan: TrainingPlanConfig?,
    days: List<PlannedDay>,
    showFinale: Boolean,
    onRun: (StructuredWorkout) -> Unit
) {
    if (days.isEmpty()) {
        StrideCard { Text("No plan active. Open Builder to generate one.", color = KdeMuted) }
        return
    }
    StrideCard {
        Text(plan?.name ?: "Active Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${days.size} scheduled days • Culminating on Race Day", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    }
    val nonRaceWeeks = days.filter { it.dayRole != DayRole.RACE_DAY }.groupBy { it.weekNumber }
    val raceDay = days.firstOrNull { it.dayRole == DayRole.RACE_DAY }
    var showAllWeeks by remember { mutableStateOf(false) }
    val visibleWeeks = if (showAllWeeks) nonRaceWeeks.entries.toList() else nonRaceWeeks.entries.take(3)

    visibleWeeks.forEach { (wk, list) -> WeekCard(wk, list, onRun) }
    if (nonRaceWeeks.size > 3) {
        OutlinedButton(
            onClick = { showAllWeeks = !showAllWeeks },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                if (showAllWeeks) "Collapse Weeks" else "Show All ${nonRaceWeeks.size} Weeks",
                maxLines = 1,
                softWrap = false
            )
        }
    }
    if (showFinale && raceDay != null) {
        CompetitionFinaleCard(plan, raceDay, onRun)
    }
}

@Composable
private fun CompetitionFinaleCard(plan: TrainingPlanConfig?, raceDay: PlannedDay, onRun: (StructuredWorkout) -> Unit) {
    val w = raceDay.workout
    val dt = LocalDate.ofEpochDay(raceDay.dateEpochDay)
    val estFinishMs = plan?.let {
        val m = it.goalDistance.meters.takeIf { d -> d > 0 } ?: 10_000
        ((m / 1000.0) * it.targetPaceSecPerKm * 1000.0).toLong()
    }
    StrideCard(
        borderColor = KdePurple.copy(alpha = 0.7f),
        bgColor = KdePurple.copy(alpha = 0.12f)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = KdePurple)
                Text("FINAL COMPETITION", style = MaterialTheme.typography.labelLarge, color = KdePurple, fontWeight = FontWeight.Bold)
            }
            StatusPill("Week ${raceDay.weekNumber} • $dt", KdeSky)
        }
        Text(w?.title ?: "${plan?.goalDistance?.label ?: "10K"} Race Day", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val goalStr = if (plan != null && estFinishMs != null) {
            "Goal pace ${formatPace(plan.targetPaceSecPerKm)}/km • Est. finish ${formatDuration(estFinishMs)}"
        } else {
            "Championship pacing finale with live finish projection."
        }
        Text(goalStr, style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdeMuted)
        plan?.racePhases?.forEach { ph ->
            Surface(shape = RoundedCornerShape(8.dp), color = KdeSky.copy(alpha = 0.10f), border = BorderStroke(1.dp, KdeSky.copy(alpha = 0.25f))) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(ph.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(ph.paceBounds.formatRange(), style = MaterialTheme.typography.labelSmall.merge(TabularNumberStyle), color = KdeSky)
                }
            }
        }
        if (w != null) {
            Button(onClick = { onRun(w) }, shape = RoundedCornerShape(999.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Start Competition Race Pacer", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun WeekCard(wk: Int, days: List<PlannedDay>, onRun: (StructuredWorkout) -> Unit) {
    val first = days.firstOrNull()
    val tag = when {
        first?.isTaperWeek == true -> "Taper"
        first?.isCutbackWeek == true -> "Cutback"
        else -> "Build"
    }
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Week $wk", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill(tag, if (tag == "Build") KdePurple else KdeSky)
        }
        days.forEach { d -> DayRow(d, onRun) }
    }
}

@Composable
private fun DayRow(d: PlannedDay, onRun: (StructuredWorkout) -> Unit) {
    val dt = LocalDate.ofEpochDay(d.dateEpochDay)
    val c = when (d.dayRole) {
        DayRole.LONG_RUN, DayRole.RACE_DAY -> KdePurple
        DayRole.FAST_WORKOUT -> PaceTooFast
        DayRole.EASY_RUN -> KdeSky
        else -> KdeMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("${dt.dayOfWeek.name.take(3)} $dt", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            Text(d.workout?.title ?: d.dayRole.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val w = d.workout
        if (w != null && d.dayRole != DayRole.WALK_ONLY) {
            OutlinedButton(onClick = { onRun(w) }, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                Text("Run", maxLines = 1, softWrap = false)
            }
        } else {
            StatusPill(d.dayRole.label, c)
        }
    }
}

@Composable
private fun PlanCreatorCard(onActivate: (TrainingPlanConfig) -> Unit) {
    var name by remember { mutableStateOf("My Custom Training Block") }
    var goal by remember { mutableStateOf(GoalDistance.TEN_K) }
    var weeks by remember { mutableStateOf("12") }
    var pace by remember { mutableStateOf("295") }
    var startKm by remember { mutableStateOf("8") }
    var peakKm by remember { mutableStateOf("15") }
    var walkCap by remember { mutableStateOf("7") }
    var roles by remember { mutableStateOf(defaultWeekRoles()) }

    StrideCard {
        Text("Reusable Training Plan Builder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, label = { Text("Plan Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        GoalChipsRow(goal) { goal = it }
        TwoFieldRow("Weeks", weeks, { weeks = it }, "Goal sec/km", pace) { pace = it }
        ThreeFieldRow("Start km", startKm, { startKm = it }, "Peak km", peakKm, { peakKm = it }, "Walk km", walkCap) { walkCap = it }
        Text("Weekly Day Roles (tap to cycle)", style = MaterialTheme.typography.labelLarge, color = KdeMuted)
        WeekdayRoleChips(roles) { roles = it }
        PlanActionButtons(
            onCreate = { onActivate(buildCustomConfig(name, goal, weeks, pace, startKm, peakKm, walkCap, roles)) },
            onPreset = { onActivate(PlanPresets.telAviv10kPreset()) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalChipsRow(sel: GoalDistance, onPick: (GoalDistance) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(
        selectedContainerColor = KdePurple.copy(alpha = 0.22f),
        selectedLabelColor = KdePurple
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GoalDistance.entries.forEach { d ->
            FilterChip(selected = sel == d, onClick = { onPick(d) }, colors = cc, label = { Text(d.label, maxLines = 1, softWrap = false) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayRoleChips(roles: Map<DayOfWeek, DayRole>, onChange: (Map<DayOfWeek, DayRole>) -> Unit) {
    val cycle = listOf(DayRole.LONG_RUN, DayRole.FAST_WORKOUT, DayRole.EASY_RUN, DayRole.FULL_REST, DayRole.WALK_ONLY, DayRole.CROSS_TRAIN)
    val cc = FilterChipDefaults.filterChipColors(
        selectedContainerColor = KdePurple.copy(alpha = 0.22f),
        selectedLabelColor = KdePurple
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { dow ->
            val r = roles[dow] ?: DayRole.FULL_REST
            FilterChip(
                selected = r != DayRole.FULL_REST,
                onClick = { onChange(roles + (dow to cycle[(cycle.indexOf(r) + 1) % cycle.size])) },
                colors = cc,
                label = { Text("${dow.name.take(3)}: ${r.label}", maxLines = 1, softWrap = false) }
            )
        }
    }
}

@Composable
private fun PlanActionButtons(onCreate: () -> Unit, onPreset: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCreate, shape = RoundedCornerShape(999.dp)) { Text("Generate Plan", maxLines = 1, softWrap = false) }
        OutlinedButton(onClick = onPreset, shape = RoundedCornerShape(999.dp)) { Text("10K Preset", maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun BenchmarkCard(onRecalibrate: (Long) -> Unit) {
    var mins by remember { mutableStateOf("23.5") }
    StrideCard {
        Text("5K Benchmark Auto-Recalibration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(mins, { mins = it }, label = { Text("5K time (min)") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(shape = RoundedCornerShape(999.dp), onClick = {
                val v = mins.toDoubleOrNull() ?: return@Button
                if (v > 12.0) onRecalibrate((v * 60_000.0).toLong())
            }) { Text("Recalibrate", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun WorkoutBuilderCard(
    paceSec: Int,
    templates: List<StructuredWorkout>,
    onSave: (StructuredWorkout) -> Unit,
    onTrash: (String) -> Unit,
    onRun: (StructuredWorkout) -> Unit
) {
    var title by remember { mutableStateOf("4x1K Intervals") }
    var reps by remember { mutableStateOf("4") }
    var workM by remember { mutableStateOf("1000") }
    var restS by remember { mutableStateOf("120") }

    StrideCard {
        Text("Custom Structured Workout Builder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(title, { title = it }, label = { Text("Workout Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        ThreeFieldRow("Reps", reps, { reps = it }, "Work (m)", workM, { workM = it }, "Rest (s)", restS) { restS = it }
        Button(shape = RoundedCornerShape(999.dp), onClick = {
            onSave(buildIntervalTemplate(title, reps, workM, restS, paceSec))
        }) { Text("Save Workout Template", maxLines = 1, softWrap = false) }
        templates.forEach { tpl -> TemplateRow(tpl, onTrash, onRun) }
    }
}

@Composable
private fun TemplateRow(tpl: StructuredWorkout, onTrash: (String) -> Unit, onRun: (StructuredWorkout) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(tpl.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onRun(tpl) }, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Run", maxLines = 1, softWrap = false)
            }
            IconButton(onClick = { onTrash(tpl.id) }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Move template to Trash", tint = PaceTooSlow)
            }
        }
    }
}

@Composable
private fun TwoFieldRow(l1: String, v1: String, c1: (String) -> Unit, l2: String, v2: String, c2: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(v1, c1, label = { Text(l1) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(v2, c2, label = { Text(l2) }, modifier = Modifier.weight(1f), singleLine = true)
    }
}

@Composable
private fun ThreeFieldRow(l1: String, v1: String, c1: (String) -> Unit, l2: String, v2: String, c2: (String) -> Unit, l3: String, v3: String, c3: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(v1, c1, label = { Text(l1) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(v2, c2, label = { Text(l2) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(v3, c3, label = { Text(l3) }, modifier = Modifier.weight(1f), singleLine = true)
    }
}

private fun defaultWeekRoles(): Map<DayOfWeek, DayRole> = mapOf(
    DayOfWeek.MONDAY to DayRole.FAST_WORKOUT,
    DayOfWeek.TUESDAY to DayRole.FULL_REST,
    DayOfWeek.WEDNESDAY to DayRole.EASY_RUN,
    DayOfWeek.THURSDAY to DayRole.EASY_RUN,
    DayOfWeek.FRIDAY to DayRole.WALK_ONLY,
    DayOfWeek.SATURDAY to DayRole.LONG_RUN,
    DayOfWeek.SUNDAY to DayRole.FULL_REST
)

private fun buildCustomConfig(
    name: String, goal: GoalDistance, wRaw: String, pRaw: String, sRaw: String, pkRaw: String, cRaw: String, roles: Map<DayOfWeek, DayRole>
): TrainingPlanConfig {
    val w = (wRaw.toIntOrNull() ?: 12).coerceIn(2, 52)
    val p = (pRaw.toIntOrNull() ?: 295).coerceIn(180, 540)
    val s = (sRaw.toIntOrNull() ?: 8).coerceIn(3, 30)
    val pk = (pkRaw.toIntOrNull() ?: 15).coerceAtLeast(s)
    val cap = (cRaw.toIntOrNull() ?: 7).coerceAtLeast(2)
    val d0 = LocalDate.now().toEpochDay()
    return TrainingPlanConfig(
        id = "plan_${UUID.randomUUID()}",
        name = name.ifBlank { "${goal.label} Plan" },
        goalDistance = goal,
        startEpochDay = d0,
        raceEpochDay = d0 + (w * 7L) - 1L,
        targetPaceSecPerKm = p,
        easyPaceBounds = PaceBounds(p + 60, p + 85),
        startLongRunMeters = s * 1_000,
        peakLongRunMeters = pk * 1_000,
        weeklyTemplate = roles,
        walkingLoadRule = WalkingLoadRule(walkThresholdMeters = cap * 1_000)
    )
}

private fun buildIntervalTemplate(title: String, rRaw: String, wRaw: String, rsRaw: String, pace: Int): StructuredWorkout {
    val r = (rRaw.toIntOrNull() ?: 4).coerceIn(1, 30)
    val wm = (wRaw.toIntOrNull() ?: 1_000).coerceIn(100, 10_000)
    val rs = (rsRaw.toIntOrNull() ?: 120).coerceIn(15, 600)
    return StructuredWorkout(
        id = "tpl_${UUID.randomUUID()}",
        title = title.ifBlank { "${r}x${wm}m" },
        segments = listOf(
            WorkoutSegment.AtomicStep("Warm-Up", StepKind.WARM_UP, SegmentTarget.Distance(1_000)),
            WorkoutSegment.RepeatBlock(
                label = "Main Repeats",
                repetitions = r,
                steps = listOf(
                    WorkoutSegment.AtomicStep("Fast Interval", StepKind.RUN, SegmentTarget.Distance(wm), PaceBounds(pace - 10, pace)),
                    WorkoutSegment.AtomicStep("Recovery", StepKind.REST, SegmentTarget.Duration(rs))
                )
            ),
            WorkoutSegment.AtomicStep("Cool-Down", StepKind.COOL_DOWN, SegmentTarget.Distance(1_000))
        )
    )
}

package com.stridecoach.running.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.stridecoach.running.ui.CoachUiState
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.PaceTooFast
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

@Composable
fun PlanBuilderScreen(
    state: CoachUiState,
    onActivatePlan: (TrainingPlanConfig) -> Unit,
    onRecalibrate5k: (Long) -> Unit,
    onSaveTemplate: (StructuredWorkout) -> Unit,
    onStartWorkout: (StructuredWorkout) -> Unit,
    onOpenRoutePlanner: () -> Unit = {}
) {
    var subTab by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val labels = listOf("Schedule", "Plan Builder", "Workouts")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Training Plans", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onOpenRoutePlanner, shape = RoundedCornerShape(999.dp)) { Text("Draw Route") }
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
                ) { Text(lbl) }
            }
        }
        when (subTab) {
            0 -> ScheduleList(state.activePlan?.name, state.plannedDays, onStartWorkout)
            1 -> {
                PlanCreatorCard(onActivatePlan)
                BenchmarkCard(onRecalibrate5k)
            }
            2 -> WorkoutBuilderCard(state.activePlan?.targetPaceSecPerKm ?: 295, state.templates, onSaveTemplate, onStartWorkout)
        }
    }
}

@Composable
private fun ScheduleList(planName: String?, days: List<PlannedDay>, onRun: (StructuredWorkout) -> Unit) {
    if (days.isEmpty()) {
        StrideCard { Text("No plan active. Open Plan Builder to generate one.", color = KdeMuted) }
        return
    }
    StrideCard {
        Text(planName ?: "Active Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${days.size} scheduled days • Showing upcoming weeks", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    }
    val byWeek = days.groupBy { it.weekNumber }.entries.take(6)
    byWeek.forEach { (wk, list) -> WeekCard(wk, list, onRun) }
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text("${dt.dayOfWeek.name.take(3)} $dt", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
            Text(d.workout?.title ?: d.dayRole.label, style = MaterialTheme.typography.bodyMedium)
        }
        val w = d.workout
        if (w != null && d.dayRole != DayRole.WALK_ONLY) {
            OutlinedButton(onClick = { onRun(w) }, shape = RoundedCornerShape(999.dp)) { Text("Run") }
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
            FilterChip(selected = sel == d, onClick = { onPick(d) }, colors = cc, label = { Text(d.label) })
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
                label = { Text("${dow.name.take(3)}: ${r.label}") }
            )
        }
    }
}

@Composable
private fun PlanActionButtons(onCreate: () -> Unit, onPreset: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCreate, shape = RoundedCornerShape(999.dp)) { Text("Generate Plan") }
        OutlinedButton(onClick = onPreset, shape = RoundedCornerShape(999.dp)) { Text("10K Preset") }
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
            }) { Text("Recalibrate") }
        }
    }
}

@Composable
private fun WorkoutBuilderCard(
    paceSec: Int,
    templates: List<StructuredWorkout>,
    onSave: (StructuredWorkout) -> Unit,
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
        }) { Text("Save Workout Template") }
        templates.forEach { tpl -> TemplateRow(tpl, onRun) }
    }
}

@Composable
private fun TemplateRow(tpl: StructuredWorkout, onRun: (StructuredWorkout) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(tpl.title, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onRun(tpl) }, shape = RoundedCornerShape(999.dp)) { Text("Run") }
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

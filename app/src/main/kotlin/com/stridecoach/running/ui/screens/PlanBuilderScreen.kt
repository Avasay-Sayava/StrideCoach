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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

@Composable
fun PlanBuilderScreen(
    state: CoachUiState,
    onActivatePlan: (TrainingPlanConfig) -> Unit,
    onRecalibrate5k: (Long) -> Unit,
    onSaveTemplate: (StructuredWorkout) -> Unit,
    onStartWorkout: (StructuredWorkout) -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PlanCreatorCard(onActivatePlan)
        BenchmarkCard(onRecalibrate5k)
        WorkoutBuilderCard(state.activePlan?.targetPaceSecPerKm ?: 295, state.templates, onSaveTemplate, onStartWorkout)
        SchedulePreviewCard(state.plannedDays)
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reusable Training Plan Builder", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Plan Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            GoalChipsRow(goal) { goal = it }
            TwoFieldRow("Weeks", weeks, { weeks = it }, "Goal sec/km", pace) { pace = it }
            ThreeFieldRow("Start Long km", startKm, { startKm = it }, "Peak Long km", peakKm, { peakKm = it }, "Walk Cap km", walkCap) { walkCap = it }
            Text("Weekly Day Roles (tap to cycle)", style = MaterialTheme.typography.labelLarge)
            WeekdayRoleChips(roles) { roles = it }
            PlanActionButtons(
                onCreate = {
                    val cfg = buildCustomConfig(name, goal, weeks, pace, startKm, peakKm, walkCap, roles)
                    onActivate(cfg)
                },
                onPreset = { onActivate(PlanPresets.telAviv10kPreset()) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalChipsRow(selected: GoalDistance, onSelect: (GoalDistance) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GoalDistance.entries.forEach { d ->
            FilterChip(selected = selected == d, onClick = { onSelect(d) }, label = { Text(d.label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayRoleChips(roles: Map<DayOfWeek, DayRole>, onChange: (Map<DayOfWeek, DayRole>) -> Unit) {
    val cycle = listOf(DayRole.LONG_RUN, DayRole.FAST_WORKOUT, DayRole.EASY_RUN, DayRole.FULL_REST, DayRole.WALK_ONLY, DayRole.CROSS_TRAIN)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { dow ->
            val role = roles[dow] ?: DayRole.FULL_REST
            FilterChip(
                selected = role != DayRole.FULL_REST,
                onClick = {
                    val next = cycle[(cycle.indexOf(role) + 1) % cycle.size]
                    onChange(roles + (dow to next))
                },
                label = { Text("${dow.name.take(3)}: ${role.label}") }
            )
        }
    }
}

@Composable
private fun PlanActionButtons(onCreate: () -> Unit, onPreset: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCreate) { Text("Generate Plan") }
        OutlinedButton(onClick = onPreset) { Text("Load 10K Preset") }
    }
}

@Composable
private fun BenchmarkCard(onRecalibrate: (Long) -> Unit) {
    var mins by remember { mutableStateOf("23.5") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("5K Benchmark Auto-Recalibration", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(mins, { mins = it }, label = { Text("5K time (min)") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = {
                    val v = mins.toDoubleOrNull() ?: return@Button
                    if (v > 12.0) onRecalibrate((v * 60_000.0).toLong())
                }) { Text("Recalibrate") }
            }
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Custom Structured Workout Builder", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, label = { Text("Workout Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            ThreeFieldRow("Reps", reps, { reps = it }, "Work (m)", workM, { workM = it }, "Rest (s)", restS) { restS = it }
            Button(onClick = { onSave(buildIntervalTemplate(title, reps, workM, restS, paceSec)) }) {
                Text("Save Workout Template")
            }
            templates.forEach { tpl -> TemplateRow(tpl, onRun) }
        }
    }
}

@Composable
private fun TemplateRow(tpl: StructuredWorkout, onRun: (StructuredWorkout) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(tpl.title, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onRun(tpl) }) { Text("Run") }
    }
}

@Composable
private fun SchedulePreviewCard(days: List<PlannedDay>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Active Schedule (${days.size} days)", style = MaterialTheme.typography.titleMedium)
            days.take(21).forEach { d ->
                val tag = when {
                    d.isBenchmarkDay -> " [5K TEST]"
                    d.isTaperWeek -> " [TAPER]"
                    d.isCutbackWeek -> " [CUTBACK]"
                    else -> ""
                }
                val dt = LocalDate.ofEpochDay(d.dateEpochDay)
                Text("W${d.weekNumber} $dt • ${d.workout?.title ?: d.dayRole.label}$tag", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TwoFieldRow(
    l1: String, v1: String, c1: (String) -> Unit,
    l2: String, v2: String, c2: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(v1, c1, label = { Text(l1) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(v2, c2, label = { Text(l2) }, modifier = Modifier.weight(1f), singleLine = true)
    }
}

@Composable
private fun ThreeFieldRow(
    l1: String, v1: String, c1: (String) -> Unit,
    l2: String, v2: String, c2: (String) -> Unit,
    l3: String, v3: String, c3: (String) -> Unit
) {
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
    name: String,
    goal: GoalDistance,
    weeksRaw: String,
    paceRaw: String,
    startRaw: String,
    peakRaw: String,
    capRaw: String,
    roles: Map<DayOfWeek, DayRole>
): TrainingPlanConfig {
    val w = (weeksRaw.toIntOrNull() ?: 12).coerceIn(2, 52)
    val p = (paceRaw.toIntOrNull() ?: 295).coerceIn(180, 540)
    val s = (startRaw.toIntOrNull() ?: 8).coerceIn(3, 30)
    val pk = (peakRaw.toIntOrNull() ?: 15).coerceAtLeast(s)
    val cap = (capRaw.toIntOrNull() ?: 7).coerceAtLeast(2)
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

private fun buildIntervalTemplate(
    title: String,
    repsRaw: String,
    workRaw: String,
    restRaw: String,
    pace: Int
): StructuredWorkout {
    val r = (repsRaw.toIntOrNull() ?: 4).coerceIn(1, 30)
    val wm = (workRaw.toIntOrNull() ?: 1_000).coerceIn(100, 10_000)
    val rs = (restRaw.toIntOrNull() ?: 120).coerceIn(15, 600)
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

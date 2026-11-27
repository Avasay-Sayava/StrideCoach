package com.stridecoach.running.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.engine.WalkingLoadAlert
import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.CoachUiState
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.components.TelemetryStatStrip
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.TabularNumberStyle
import java.time.LocalDate

@Composable
fun CoachScreen(
    state: CoachUiState,
    onStartWorkout: (StructuredWorkout?, String?) -> Unit,
    onApplyDowngrade: () -> Unit,
    onApplySwap: () -> Unit,
    onLogManualWalk: (Int) -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("StrideCoach", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (state.preferences.healthConnectEnabled) StatusPill("Health Synced", KdeSky)
        }
        state.activePlan?.let { PlanHeroCard(it, state) }
        state.walkingAlert?.let { WalkAlertCard(it, onApplyDowngrade, onApplySwap) }
        TodayWorkoutCard(state.todayDay, onStartWorkout)
        if (state.preferences.showCompetitionFinale) {
            state.plannedDays.firstOrNull { it.dayRole == DayRole.RACE_DAY }?.let { race ->
                RaceModeCard(race, onStartWorkout)
            }
        }
        QuickWalkCard(onLogManualWalk)
    }
}

@Composable
private fun PlanHeroCard(plan: TrainingPlanConfig, state: CoachUiState) {
    val now = LocalDate.now().toEpochDay()
    val left = (plan.raceEpochDay - now).coerceAtLeast(0L)
    val span = (plan.raceEpochDay - plan.startEpochDay).coerceAtLeast(1L)
    val pct = ((now - plan.startEpochDay).toFloat() / span).coerceIn(0f, 1f)
    val walkToday = state.walkLogs.firstOrNull { it.dateEpochDay == now }?.km() ?: 0.0

    StrideCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusPill("${formatPace(state.telemetry.currentBaselinePaceSec)} /km", KdePurple)
        }
        Text(
            "$left days to race day",
            style = MaterialTheme.typography.headlineSmall.merge(TabularNumberStyle),
            color = KdePurple,
            fontWeight = FontWeight.Bold
        )
        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(6.dp), color = KdePurple)
        TelemetryStatStrip(
            items = listOf(
                "%.1f km".format(state.telemetry.totalRunKm) to "Runs",
                "${state.telemetry.runCount}" to "Logged",
                "%.1f km".format(walkToday) to "Walk Today"
            )
        )
    }
}

@Composable
private fun WalkAlertCard(alert: WalkingLoadAlert, onDowngrade: () -> Unit, onSwap: () -> Unit) {
    val km = "%.1f".format(alert.triggerWalkMeters / 1000.0)
    StrideCard(
        borderColor = PaceTooFast.copy(alpha = 0.65f),
        bgColor = PaceTooFast.copy(alpha = 0.12f)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Heavy Walking Load", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            StatusPill("$km km ${alert.triggerDayLabel}", PaceTooFast)
        }
        Text("Protect fresh legs before today's fast session:", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDowngrade, shape = RoundedCornerShape(999.dp)) {
                Text("Downgrade Easy", maxLines = 1, softWrap = false)
            }
            if (alert.canSwapWithTomorrow) {
                OutlinedButton(onClick = onSwap, shape = RoundedCornerShape(999.dp)) {
                    Text("Swap w/ Rest", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun TodayWorkoutCard(today: PlannedDay?, onStart: (StructuredWorkout?, String?) -> Unit) {
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY'S SESSION", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
            StatusPill(today?.dayRole?.label ?: "Open Run", KdeSky)
        }
        val w = today?.workout
        if (w == null) {
            Text("Rest & Recover", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Keep walking light and hydrate.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
            OutlinedButton(onClick = { onStart(null, null) }, shape = RoundedCornerShape(999.dp)) {
                Text("Start Open Run", maxLines = 1, softWrap = false)
            }
        } else {
            WorkoutPreviewBody(w, today.id, onStart)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkoutPreviewBody(w: StructuredWorkout, dayId: String, onStart: (StructuredWorkout?, String?) -> Unit) {
    Text(w.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        w.flattenSteps().take(5).forEach { s ->
            val pace = s.step.paceBounds?.formatRange() ?: "Easy"
            StatusPill("${s.displayName()} • $pace", KdePurple)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onStart(w, dayId) }, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Start Guided Run", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        OutlinedButton(onClick = { onStart(null, null) }, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Open Run", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun RaceModeCard(race: PlannedDay, onStart: (StructuredWorkout?, String?) -> Unit) {
    val w = race.workout ?: return
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Race-Day Pacer Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("3-phase pacing + live finish projection", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
            }
            Button(onClick = { onStart(w, race.id) }, shape = RoundedCornerShape(999.dp)) {
                Text("Launch", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun QuickWalkCard(onLog: (Int) -> Unit) {
    var input by remember { mutableStateOf("") }
    StrideCard {
        Text("Quick Daily Walk Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Walk km today") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(shape = RoundedCornerShape(999.dp), onClick = {
                val km = input.toDoubleOrNull() ?: return@Button
                onLog((km * 1000.0).toInt())
                input = ""
            }) { Text("Save", maxLines = 1, softWrap = false) }
        }
    }
}

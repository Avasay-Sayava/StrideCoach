package com.stridecoach.running.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.stridecoach.running.core.domain.engine.WalkingLoadAlert
import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.ui.CoachUiState
import com.stridecoach.running.ui.theme.PaceTooFast
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        state.activePlan?.let { PlanHeaderCard(it) }
        state.walkingAlert?.let { WalkAlertCard(it, onApplyDowngrade, onApplySwap) }
        TodayWorkoutCard(state.todayDay, onStartWorkout)
        state.plannedDays.firstOrNull { it.dayRole == DayRole.RACE_DAY }?.let { race ->
            RaceModeCard(race, onStartWorkout)
        }
        QuickWalkCard(onLogManualWalk)
    }
}

@Composable
private fun PlanHeaderCard(plan: TrainingPlanConfig) {
    val days = (plan.raceEpochDay - LocalDate.now().toEpochDay()).coerceAtLeast(0L)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("$days days to target • Goal ${formatPace(plan.targetPaceSecPerKm)} /km", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WalkAlertCard(alert: WalkingLoadAlert, onDowngrade: () -> Unit, onSwap: () -> Unit) {
    val km = "%.1f".format(alert.triggerWalkMeters / 1000.0)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PaceTooFast.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Heavy Walking Load Detected", style = MaterialTheme.typography.titleMedium)
            Text("Logged $km km walking ${alert.triggerDayLabel}. Adjust today's fast session:", style = MaterialTheme.typography.bodyMedium)
            WalkAlertActions(alert.canSwapWithTomorrow, onDowngrade, onSwap)
        }
    }
}

@Composable
private fun WalkAlertActions(canSwap: Boolean, onDowngrade: () -> Unit, onSwap: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDowngrade) { Text("Downgrade to Easy") }
        if (canSwap) {
            OutlinedButton(onClick = onSwap) { Text("Swap w/ Tomorrow") }
        }
    }
}

@Composable
private fun TodayWorkoutCard(today: PlannedDay?, onStart: (StructuredWorkout?, String?) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today • ${today?.dayRole?.label ?: "Free Run"}", style = MaterialTheme.typography.titleMedium)
            val w = today?.workout
            if (w == null) {
                Text("Scheduled rest day. Recover well.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { onStart(null, null) }) { Text("Start Free Run") }
            } else {
                WorkoutPreviewBody(w, today.id, onStart)
            }
        }
    }
}

@Composable
private fun WorkoutPreviewBody(w: StructuredWorkout, dayId: String, onStart: (StructuredWorkout?, String?) -> Unit) {
    Text(w.title, style = MaterialTheme.typography.titleLarge)
    if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodyMedium)
    w.flattenSteps().take(6).forEach { s ->
        val pace = s.step.paceBounds?.formatRange() ?: "Easy / Open"
        Text("• ${s.displayName()} — $pace", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onStart(w, dayId) }) { Text("Start Guided Run") }
        OutlinedButton(onClick = { onStart(null, null) }) { Text("Free Run") }
    }
}

@Composable
private fun RaceModeCard(race: PlannedDay, onStart: (StructuredWorkout?, String?) -> Unit) {
    val w = race.workout ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Race-Day Pacer Mode", style = MaterialTheme.typography.titleMedium)
                Text("3-phase split guidance + live finish projection", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onStart(w, race.id) }) { Text("Launch") }
        }
    }
}

@Composable
private fun QuickWalkCard(onLog: (Int) -> Unit) {
    var input by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick Daily Walk Log", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Walk km today") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(onClick = {
                    val km = input.toDoubleOrNull() ?: return@Button
                    onLog((km * 1000.0).toInt())
                    input = ""
                }) { Text("Save") }
            }
        }
    }
}

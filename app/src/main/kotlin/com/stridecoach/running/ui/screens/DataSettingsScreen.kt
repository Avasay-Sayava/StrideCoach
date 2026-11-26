package com.stridecoach.running.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.data.repository.CoachPreferences

@Composable
fun DataSettingsScreen(
    preferences: CoachPreferences,
    isImporting: Boolean,
    lastImportSummary: String?,
    runCount: Int,
    walkDayCount: Int,
    isBatteryUnrestricted: Boolean,
    onUpdatePrefs: ((CoachPreferences) -> CoachPreferences) -> Unit,
    onImportBackupClick: () -> Unit,
    onExportBackupClick: () -> Unit,
    onImportTimelineClick: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DataPortabilityCard(
            busy = isImporting,
            summary = lastImportSummary,
            runs = runCount,
            walkDays = walkDayCount,
            onImpBackup = onImportBackupClick,
            onExpBackup = onExportBackupClick,
            onImpTimeline = onImportTimelineClick
        )
        BatteryProtectionCard(isBatteryUnrestricted, onRequestBatteryUnrestricted)
        ImportGuideCard()
        CoachSettingsCard(preferences, onUpdatePrefs)
    }
}

@Composable
private fun DataPortabilityCard(
    busy: Boolean,
    summary: String?,
    runs: Int,
    walkDays: Int,
    onImpBackup: () -> Unit,
    onExpBackup: () -> Unit,
    onImpTimeline: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Lossless Data Import & Export", style = MaterialTheme.typography.titleMedium)
            Text("$runs runs • $walkDays walk days stored locally", style = MaterialTheme.typography.labelLarge)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImpBackup, enabled = !busy) { Text("Import .backup") }
                OutlinedButton(onClick = onExpBackup, enabled = !busy) { Text("Export .backup") }
            }
            Button(onClick = onImpTimeline, enabled = !busy) { Text("Import Google Timeline.json") }
        }
    }
}

@Composable
private fun BatteryProtectionCard(unrestricted: Boolean, onRequest: () -> Unit) {
    val status = if (unrestricted) "Status: Unrestricted (Safe for lock-screen GPS)" else "Status: Optimized (Samsung One UI may pause GPS)"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Samsung / Android Battery Protection", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (!unrestricted) {
                Button(onClick = onRequest) { Text("Allow Unrestricted Battery") }
            }
        }
    }
}

@Composable
private fun ImportGuideCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How to Import Your Existing Data", style = MaterialTheme.typography.titleMedium)
            Text("1. Running Tracker (Play Store): Open Settings → Backup/Export → save running-tracker-*.backup, then tap Import .backup (or Open With StrideCoach).", style = MaterialTheme.typography.bodySmall)
            Text("2. Google Maps Timeline: On Android open Settings → Location → Location Services → Timeline → Export Timeline data (Timeline.json), then tap Import Google Timeline.json.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CoachSettingsCard(p: CoachPreferences, onUpdate: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Audio, Haptics & Lock-Screen Safety", style = MaterialTheme.typography.titleMedium)
            SettingToggle("English Voice Coach (TTS)", p.voiceCoachEnabled) { v -> onUpdate { it.copy(voiceCoachEnabled = v) } }
            SettingToggle("Haptic Vibration Cues", p.hapticCuesEnabled) { v -> onUpdate { it.copy(hapticCuesEnabled = v) } }
            SettingToggle("Smart Auto-Pause When Stopped", p.autoPauseEnabled) { v -> onUpdate { it.copy(autoPauseEnabled = v) } }
            SettingToggle("Keep Screen Awake During Run", p.keepScreenAwake) { v -> onUpdate { it.copy(keepScreenAwake = v) } }
        }
    }
}

@Composable
private fun SettingToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

package com.stridecoach.running.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.RunGreen
import com.stridecoach.running.ui.theme.RunMuted

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
        Text("Data & Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        DataPortabilityCard(isImporting, lastImportSummary, runCount, walkDayCount, onImportBackupClick, onExportBackupClick, onImportTimelineClick)
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
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lossless Data Import & Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill("$runs runs • $walkDays walks", RunGreen)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = RunGreen)
        if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = RunGreen)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImpBackup, enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("Import .backup") }
            OutlinedButton(onClick = onExpBackup, enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("Export .backup") }
        }
        Button(onClick = onImpTimeline, enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("Import Google Timeline.json") }
    }
}

@Composable
private fun BatteryProtectionCard(unrestricted: Boolean, onRequest: () -> Unit) {
    val msg = if (unrestricted) "Unrestricted (Safe for lock-screen GPS)" else "Optimized (Samsung One UI may pause GPS)"
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Samsung Battery Guard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill(if (unrestricted) "Safe" else "Action Needed", if (unrestricted) RunGreen else PaceTooFast)
        }
        Text(msg, style = MaterialTheme.typography.bodySmall, color = RunMuted)
        if (!unrestricted) {
            Button(onClick = onRequest, shape = RoundedCornerShape(999.dp)) { Text("Allow Unrestricted Battery") }
        }
    }
}

@Composable
private fun ImportGuideCard() {
    StrideCard {
        Text("How to Import Your Existing Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("1. Running Tracker (Play Store): Settings → Backup/Export → save .backup, then tap Import .backup.", style = MaterialTheme.typography.bodySmall, color = RunMuted)
        Text("2. Google Maps Timeline: Settings → Location → Timeline → Export Timeline data (Timeline.json).", style = MaterialTheme.typography.bodySmall, color = RunMuted)
    }
}

@Composable
private fun CoachSettingsCard(p: CoachPreferences, onUpdate: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    StrideCard {
        Text("Audio, Haptics & Lock-Screen Safety", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SettingToggle("English Voice Coach (TTS)", p.voiceCoachEnabled) { v -> onUpdate { it.copy(voiceCoachEnabled = v) } }
        SettingToggle("Haptic Vibration Cues", p.hapticCuesEnabled) { v -> onUpdate { it.copy(hapticCuesEnabled = v) } }
        SettingToggle("Smart Auto-Pause When Stopped", p.autoPauseEnabled) { v -> onUpdate { it.copy(autoPauseEnabled = v) } }
        SettingToggle("Keep Screen Awake During Run", p.keepScreenAwake) { v -> onUpdate { it.copy(keepScreenAwake = v) } }
    }
}

@Composable
private fun SettingToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

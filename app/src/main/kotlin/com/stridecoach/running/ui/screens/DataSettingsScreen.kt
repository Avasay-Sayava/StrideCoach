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
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast

@Composable
fun DataSettingsScreen(
    prefs: CoachPreferences,
    busy: Boolean,
    summary: String?,
    runs: Int,
    walkDays: Int,
    battOk: Boolean,
    hcAvail: Boolean,
    hcOk: Boolean,
    onHcConnect: () -> Unit,
    onHcSync: () -> Unit,
    onAutoDetect: (Boolean) -> Unit,
    onUpdate: ((CoachPreferences) -> CoachPreferences) -> Unit,
    onImpBak: () -> Unit,
    onExpBak: () -> Unit,
    onImpTl: () -> Unit,
    onBattReq: () -> Unit
) {
    val scrl = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrl).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Data & Automation", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        AutoCard(hcAvail, hcOk, prefs.autoDetectRunsEnabled, busy, onHcConnect, onHcSync, onAutoDetect)
        PortabilityCard(busy, summary, runs, walkDays, onImpBak, onExpBak, onImpTl)
        BattCard(battOk, onBattReq)
        CoachCard(prefs, onUpdate)
        GuideCard()
    }
}

@Composable
private fun AutoCard(
    hcAvail: Boolean,
    hcOk: Boolean,
    auto: Boolean,
    busy: Boolean,
    onCon: () -> Unit,
    onSync: () -> Unit,
    onAuto: (Boolean) -> Unit
) {
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Samsung Health & Auto-Detect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val tag = when {
                !hcAvail -> "No HC"
                hcOk -> "Connected"
                else -> "Ready"
            }
            StatusPill(tag, if (hcOk) PaceOnTarget else KdeSky)
        }
        Text(
            "Sync daily steps, walks, and exercise routes automatically via Android Health Connect.",
            style = MaterialTheme.typography.bodySmall,
            color = KdeMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hcOk && hcAvail) {
                Button(onClick = onCon, shape = RoundedCornerShape(999.dp)) { Text("Connect Health") }
            }
            OutlinedButton(onClick = onSync, enabled = hcAvail && !busy, shape = RoundedCornerShape(999.dp)) {
                Text("Sync Now")
            }
        }
        ToggleRow("Prompt When Running Is Detected", auto, onAuto)
    }
}

@Composable
private fun PortabilityCard(
    busy: Boolean,
    sum: String?,
    runs: Int,
    walks: Int,
    onImp: () -> Unit,
    onExp: () -> Unit,
    onTl: () -> Unit
) {
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lossless Import & Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill("$runs runs • $walks walks", KdePurple)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KdePurple)
        if (!sum.isNullOrBlank()) Text(sum, style = MaterialTheme.typography.bodySmall, color = PaceOnTarget)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImp, enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("Import .backup") }
            OutlinedButton(onClick = onExp, enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("Export .backup") }
        }
        Button(onClick = onTl, enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("Import Google Timeline.json") }
    }
}

@Composable
private fun BattCard(ok: Boolean, onReq: () -> Unit) {
    val msg = if (ok) "Unrestricted (Safe for lock-screen GPS)" else "Optimized (Samsung One UI may pause GPS)"
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Samsung Battery Guard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill(if (ok) "Safe" else "Action Needed", if (ok) PaceOnTarget else PaceTooFast)
        }
        Text(msg, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        if (!ok) {
            Button(onClick = onReq, shape = RoundedCornerShape(999.dp)) { Text("Allow Unrestricted Battery") }
        }
    }
}

@Composable
private fun CoachCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    StrideCard {
        Text("Audio, Haptics & Lock-Screen Safety", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ToggleRow("English Voice Coach (TTS)", p.voiceCoachEnabled) { v -> onUp { it.copy(voiceCoachEnabled = v) } }
        ToggleRow("Haptic Vibration Cues", p.hapticCuesEnabled) { v -> onUp { it.copy(hapticCuesEnabled = v) } }
        ToggleRow("Smart Auto-Pause When Stopped", p.autoPauseEnabled) { v -> onUp { it.copy(autoPauseEnabled = v) } }
        ToggleRow("Keep Screen Awake During Run", p.keepScreenAwake) { v -> onUp { it.copy(keepScreenAwake = v) } }
    }
}

@Composable
private fun GuideCard() {
    StrideCard {
        Text("About Google Timeline & Samsung Health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("1. Running Tracker: Settings -> Backup -> save .backup -> tap Import .backup.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Text("2. Google Timeline: Export Timeline.json on-device and import here for past walks.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Text("3. Ongoing walks sync automatically from Samsung Health via Android Health Connect.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    }
}

@Composable
private fun ToggleRow(lbl: String, chk: Boolean, onChg: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(lbl, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = chk, onCheckedChange = onChg)
    }
}

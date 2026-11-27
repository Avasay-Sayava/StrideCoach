package com.stridecoach.running.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.domain.engine.PaceBaselineMode
import com.stridecoach.running.ui.QuadCounts
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.theme.AccentOption
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooFast
import com.stridecoach.running.ui.theme.PaceTooSlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DataSettingsScreen(
    prefs: CoachPreferences,
    busy: Boolean,
    summary: String?,
    activityCounts: QuadCounts,
    trashCount: Int,
    battOk: Boolean,
    hcAvail: Boolean,
    hcOk: Boolean,
    onHcConnect: () -> Unit,
    onHcSync: () -> Unit,
    onHealthAutoSync: (Boolean) -> Unit,
    onAutoDetect: (Boolean) -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onPickSyncFolder: () -> Unit,
    onSyncFolderNow: () -> Unit,
    onRestoreVaultFile: () -> Unit,
    onOpenTrash: () -> Unit,
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
        Text("Data & Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        AutoCard(prefs, hcAvail, hcOk, busy, onHcConnect, onHcSync, onHealthAutoSync, onAutoDetect, onAutoStart)
        VaultSyncCard(prefs, busy, onPickSyncFolder, onSyncFolderNow, onRestoreVaultFile, onUpdate)
        ThemeSettingsCard(prefs, onUpdate)
        MapTopographySettingsCard(prefs, onUpdate)
        CoachingSensorSettingsCard(prefs, onUpdate)
        PortabilityAndTrashCard(busy, summary, activityCounts, trashCount, onImpBak, onExpBak, onImpTl, onOpenTrash)
        BattCard(battOk, onBattReq)
        GuideCard()
    }
}

@Composable
private fun AutoCard(
    p: CoachPreferences,
    hcAvail: Boolean,
    hcOk: Boolean,
    busy: Boolean,
    onCon: () -> Unit,
    onSync: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onAutoDetect: (Boolean) -> Unit,
    onAutoStart: (Boolean) -> Unit
) {
    val syncLabel = if (p.lastHealthSyncMillis > 0L) {
        val dt = DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .format(Instant.ofEpochMilli(p.lastHealthSyncMillis).atZone(ZoneId.systemDefault()))
        "Last sync: $dt"
    } else {
        "Sync daily steps, walks, runs, and rides automatically via Android Health Connect."
    }
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Health Connect & Auto-Detect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            val tag = when {
                !hcAvail -> "No HC"
                hcOk -> "Connected"
                else -> "Ready"
            }
            StatusPill(tag, if (hcOk) PaceOnTarget else KdeSky)
        }
        Text(syncLabel, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hcOk && hcAvail) {
                Button(onClick = onCon, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("Connect Health", maxLines = 1, softWrap = false)
                }
            }
            OutlinedButton(onClick = onSync, enabled = hcAvail && !busy, shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Sync Now", maxLines = 1, softWrap = false)
            }
        }
        ToggleRow("Auto-Sync Health Connect (6h)", p.healthAutoSyncEnabled, onAutoSync)
        ToggleRow("Detect Runs, Walks & Rides", p.autoDetectRunsEnabled, onAutoDetect)
        ToggleRow("Auto-Start Exercise When Run Detected", p.autoStartOnRunDetected, onAutoStart)
    }
}

@Composable
private fun VaultSyncCard(
    p: CoachPreferences,
    busy: Boolean,
    onPickFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onRestoreVault: () -> Unit,
    onUp: ((CoachPreferences) -> CoachPreferences) -> Unit
) {
    val linked = p.syncFolderUri.isNotBlank()
    val statusText = if (linked) {
        val last = if (p.lastFolderSyncMillis > 0L) {
            val dt = DateTimeFormatter.ofPattern("MMM d, HH:mm").format(Instant.ofEpochMilli(p.lastFolderSyncMillis).atZone(ZoneId.systemDefault()))
            " • Last vault & Timeline sync: $dt"
        } else ""
        "Folder linked (auto-restores even if app is uninstalled)$last"
    } else {
        "Link your Timeline / Downloads folder to auto-sync Timeline.json and keep an uninstall-safe StrideCoach Vault backup."
    }
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Uninstall-Safe Vault & Timeline Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            StatusPill(if (linked) "Vault Linked" else "Not Linked", if (linked) PaceOnTarget else KdeSky)
        }
        Text(statusText, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFolder, enabled = !busy, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
                Text(if (linked) "Change Folder" else "Link Sync Folder", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            if (linked) {
                OutlinedButton(onClick = onSyncNow, enabled = !busy, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
                    Text("Sync Folder Now", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OutlinedButton(onClick = onRestoreVault, enabled = !busy, shape = RoundedCornerShape(999.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Restore from stridecoach-vault.json", maxLines = 1, softWrap = false)
        }
        ToggleRow("Auto-Sync Folder Timeline & Vault", p.folderAutoSyncEnabled) { v -> onUp { it.copy(folderAutoSyncEnabled = v) } }
        ToggleRow("Auto-Fetch DEM Terrain Elevation", p.autoFetchDemElevation) { v -> onUp { it.copy(autoFetchDemElevation = v) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettingsCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    StrideCard {
        Text("Appearance & Theming", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Theme Mode", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("SYSTEM" to "System", "DARK" to "Dark", "LIGHT" to "Light").forEach { (k, lbl) ->
                FilterChip(selected = p.themeMode == k, onClick = { onUp { it.copy(themeMode = k) } }, colors = cc, label = { Text(lbl, maxLines = 1, softWrap = false) })
            }
        }
        Text("Accent Color", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccentOption.entries.forEach { acc ->
                FilterChip(selected = p.accentName == acc.name, onClick = { onUp { it.copy(accentName = acc.name) } }, colors = cc, label = { Text(acc.label, maxLines = 1, softWrap = false) })
            }
        }
        Text("Map Style", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("MATCH_THEME" to "Match Theme", "DARK" to "Dark", "LIGHT" to "Positron", "LIBERTY" to "Liberty").forEach { (k, lbl) ->
                FilterChip(selected = p.mapStyleMode == k, onClick = { onUp { it.copy(mapStyleMode = k) } }, colors = cc, label = { Text(lbl, maxLines = 1, softWrap = false) })
            }
        }
        ToggleRow("Show Map Copyright on Previews", p.showMapAttribution) { v -> onUp { it.copy(showMapAttribution = v) } }
    }
}

@Composable
private fun MapTopographySettingsCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    StrideCard {
        Text("Maps, 3D & Topography", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ToggleRow("Default 3D Map Perspective", p.defaultMap3dEnabled) { v -> onUp { it.copy(defaultMap3dEnabled = v) } }
        ToggleRow("Show 3D Buildings", p.show3dBuildings) { v -> onUp { it.copy(show3dBuildings = v) } }
        ToggleRow("Topography DEM Terrain Hillshade", p.showTerrainHillshade) { v -> onUp { it.copy(showTerrainHillshade = v) } }
        ToggleRow("3D Elevation Route Ribbon", p.show3dRouteRibbon) { v -> onUp { it.copy(show3dRouteRibbon = v) } }
        ToggleRow("Keep Map Centered When Sheet Moves", p.keepMapCenteredOnSheetDrag) { v -> onUp { it.copy(keepMapCenteredOnSheetDrag = v) } }
        ToggleRow("Animate Section Highlight Pulse", p.animateSectionHighlight) { v -> onUp { it.copy(animateSectionHighlight = v) } }
        ToggleRow("Smooth Continuous Pace Gradient", p.smoothTrackGradient) { v -> onUp { it.copy(smoothTrackGradient = v) } }
        ToggleRow("Show Live User Location Dot", p.showUserLocationDot) { v -> onUp { it.copy(showUserLocationDot = v) } }
        ToggleRow("Preload & Cache Nearby Vector Tiles", p.preloadNearbyTiles) { v -> onUp { it.copy(preloadNearbyTiles = v) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoachingSensorSettingsCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    StrideCard {
        Text("Coaching, Sensors & Widget Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Pace Color Baseline", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PaceBaselineMode.entries.forEach { m ->
                FilterChip(selected = p.paceBaselineMode == m.name, onClick = { onUp { it.copy(paceBaselineMode = m.name) } }, colors = cc, label = { Text(m.label, maxLines = 1, softWrap = false) })
            }
        }
        ToggleRow("Slope-Adjusted Target Pace on Hills", p.slopeAdjustedPaceEnabled) { v -> onUp { it.copy(slopeAdjustedPaceEnabled = v) } }
        ToggleRow("Top-of-Map Live Telemetry HUD", p.showTopMapHud) { v -> onUp { it.copy(showTopMapHud = v) } }
        ToggleRow("Show Speed in km/h", p.showSpeedKmh) { v -> onUp { it.copy(showSpeedKmh = v) } }
        ToggleRow("Show Competition Finale in Plans", p.showCompetitionFinale) { v -> onUp { it.copy(showCompetitionFinale = v) } }
        ToggleRow("Auto-Trim Idle Start/End on Save", p.autoTrimRunsEnabled) { v -> onUp { it.copy(autoTrimRunsEnabled = v) } }
        ToggleRow("Gyroscope & IMU Form/3D Heading", p.gyroTelemetryEnabled) { v -> onUp { it.copy(gyroTelemetryEnabled = v) } }
        ToggleRow("Lockscreen Widget 2-Tap Mistouch Guard", p.widgetTapGuardEnabled) { v -> onUp { it.copy(widgetTapGuardEnabled = v) } }
        ToggleRow("Auto Pocket-Lock on Workout Start", p.autoPocketLockOnStart) { v -> onUp { it.copy(autoPocketLockOnStart = v) } }
        ToggleRow("English Voice Coach (TTS)", p.voiceCoachEnabled) { v -> onUp { it.copy(voiceCoachEnabled = v) } }
        ToggleRow("Haptic Vibration Cues", p.hapticCuesEnabled) { v -> onUp { it.copy(hapticCuesEnabled = v) } }
        ToggleRow("Smart Auto-Pause When Stopped", p.autoPauseEnabled) { v -> onUp { it.copy(autoPauseEnabled = v) } }
        ToggleRow("Keep Screen Awake During Run", p.keepScreenAwake) { v -> onUp { it.copy(keepScreenAwake = v) } }
    }
}

@Composable
private fun PortabilityAndTrashCard(
    busy: Boolean,
    sum: String?,
    c: QuadCounts,
    trashCount: Int,
    onImp: () -> Unit,
    onExp: () -> Unit,
    onTl: () -> Unit,
    onOpenTrash: () -> Unit
) {
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Import, Export & Trash", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            StatusPill("${c.runs}R • ${c.walks}W • ${c.cycles}C", KdePurple)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KdePurple)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImp, enabled = !busy, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
                Text("Import .backup", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onExp, enabled = !busy, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
                Text("Export .backup", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        Button(onClick = onTl, enabled = !busy, shape = RoundedCornerShape(999.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Import Google Timeline.json", maxLines = 1, softWrap = false)
        }
        OutlinedButton(onClick = onOpenTrash, shape = RoundedCornerShape(999.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Open Trash & Recovery ($trashCount)", color = if (trashCount > 0) PaceTooSlow else KdeMuted, maxLines = 1, softWrap = false)
        }
        if (!sum.isNullOrBlank()) Text(sum, style = MaterialTheme.typography.bodySmall, color = PaceOnTarget)
    }
}

@Composable
private fun BattCard(ok: Boolean, onReq: () -> Unit) {
    val msg = if (ok) "Unrestricted (Safe for lock-screen GPS)" else "Optimized (Samsung One UI may pause GPS)"
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Samsung Battery Guard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            StatusPill(if (ok) "Safe" else "Action Needed", if (ok) PaceOnTarget else PaceTooFast)
        }
        Text(msg, style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        if (!ok) {
            Button(onClick = onReq, shape = RoundedCornerShape(999.dp)) {
                Text("Allow Unrestricted Battery", maxLines = 1, softWrap = false)
            }
        } else {
            OutlinedButton(onClick = onReq, shape = RoundedCornerShape(999.dp)) {
                Text("Manage Battery Setting", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun GuideCard() {
    StrideCard {
        Text("Lockscreen Widget & Persistence Tips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("1. Add the 'StrideCoach Live' widget to your Samsung lockscreen or home screen; tap 2x to unlock Pause/Finish controls safely.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        Text("2. Link a Sync Folder above so Google Timeline.json and all runs/plans auto-sync and survive app uninstalls.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    }
}

@Composable
private fun ToggleRow(lbl: String, chk: Boolean, onChg: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(lbl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = chk, onCheckedChange = onChg)
    }
}

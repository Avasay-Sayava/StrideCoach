package com.stridecoach.running.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.domain.engine.PaceBaselineMode
import com.stridecoach.running.ui.QuadCounts
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.widget.LiveWorkoutWidgetProvider
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

private enum class SettingsPage(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color
) {
    APPEARANCE(
        title = "Appearance & Themes",
        subtitle = "Dark/Light mode, accent palette, map theme",
        icon = Icons.Default.Palette,
        accent = KdePurple
    ),
    MAPS_3D(
        title = "Maps, 3D & Topography",
        subtitle = "Physical 3D terrain hills, buildings, pace gradient",
        icon = Icons.Default.Map,
        accent = KdeSky
    ),
    COACHING(
        title = "Coaching, Pace & Sensors",
        subtitle = "Pace zones baseline, slope targets, TTS & IMU",
        icon = Icons.AutoMirrored.Filled.DirectionsRun,
        accent = PaceOnTarget
    ),
    LOCK_DETECT(
        title = "Lockscreen, Mistouch & Health",
        subtitle = "2-tap widget guard, Health Connect, battery, auto-detect",
        icon = Icons.Default.Lock,
        accent = PaceTooFast
    ),
    SYNC_VAULT(
        title = "Sync, Timeline & Vault",
        subtitle = "Uninstall-safe backup folder & Timeline auto-sync",
        icon = Icons.Default.CloudSync,
        accent = KdeSky
    ),
    BACKUP_TRASH(
        title = "Backup, Import & Trash",
        subtitle = "Export/import .backup, Timeline.json & Trash recovery",
        icon = Icons.Default.DeleteSweep,
        accent = PaceTooSlow
    )
}

private sealed interface SearchResultEntry {
    val page: SettingsPage
    val keywords: String

    data class ToggleEntry(
        override val page: SettingsPage,
        val label: String,
        val subtitle: String,
        override val keywords: String,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit
    ) : SearchResultEntry

    data class BlockEntry(
        override val page: SettingsPage,
        val title: String,
        override val keywords: String,
        val content: @Composable () -> Unit
    ) : SearchResultEntry
}

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
    var activePage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = query.isNotBlank() || activePage != null) {
        if (query.isNotBlank()) query = "" else activePage = null
    }

    val scrl = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrl).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (activePage != null && query.isBlank()) {
                IconButton(onClick = { activePage = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings")
                }
            }
            Text(
                text = when {
                    query.isNotBlank() -> "Search Settings"
                    activePage != null -> activePage!!.title
                    else -> "Settings & Data"
                },
                style = if (activePage != null && query.isBlank()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (query.isBlank() && activePage == null) {
                StatusPill("${activityCounts.runs}R • ${activityCounts.walks}W", KdePurple)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search settings, 3D terrain, sync, lockscreen…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = KdeMuted) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = KdeMuted)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        val trimmed = query.trim().lowercase()
        when {
            trimmed.isNotEmpty() -> {
                SearchResultsBody(
                    query = trimmed,
                    prefs = prefs,
                    busy = busy,
                    summary = summary,
                    activityCounts = activityCounts,
                    trashCount = trashCount,
                    battOk = battOk,
                    hcAvail = hcAvail,
                    hcOk = hcOk,
                    onHcConnect = onHcConnect,
                    onHcSync = onHcSync,
                    onHealthAutoSync = onHealthAutoSync,
                    onAutoDetect = onAutoDetect,
                    onAutoStart = onAutoStart,
                    onPickSyncFolder = onPickSyncFolder,
                    onSyncFolderNow = onSyncFolderNow,
                    onRestoreVaultFile = onRestoreVaultFile,
                    onOpenTrash = onOpenTrash,
                    onUpdate = onUpdate,
                    onImpBak = onImpBak,
                    onExpBak = onExpBak,
                    onImpTl = onImpTl,
                    onBattReq = onBattReq,
                    onJumpToPage = { p ->
                        query = ""
                        activePage = p
                    }
                )
            }

            activePage == null -> {
                SettingsHubBody(
                    prefs = prefs,
                    trashCount = trashCount,
                    battOk = battOk,
                    hcOk = hcOk,
                    counts = activityCounts,
                    onPickPage = { activePage = it }
                )
            }

            else -> {
                when (activePage!!) {
                    SettingsPage.APPEARANCE -> {
                        ThemeModeAccentCard(prefs, onUpdate)
                        MapStyleAttributionCard(prefs, onUpdate)
                    }
                    SettingsPage.MAPS_3D -> {
                        Terrain3DSettingsCard(prefs, onUpdate)
                        MapRenderSettingsCard(prefs, onUpdate)
                    }
                    SettingsPage.COACHING -> {
                        PaceBaselineCoachingCard(prefs, onUpdate)
                        AudioSensorCoachingCard(prefs, onUpdate)
                    }
                    SettingsPage.LOCK_DETECT -> {
                        LockscreenProtectionCard(prefs, onUpdate)
                        AutoCard(prefs, hcAvail, hcOk, busy, onHcConnect, onHcSync, onHealthAutoSync, onAutoDetect, onAutoStart)
                        BattCard(battOk, onBattReq)
                        GuideCard()
                    }
                    SettingsPage.SYNC_VAULT -> {
                        VaultSyncCard(prefs, busy, onPickSyncFolder, onSyncFolderNow, onRestoreVaultFile, onUpdate)
                    }
                    SettingsPage.BACKUP_TRASH -> {
                        PortabilityAndTrashCard(busy, summary, activityCounts, trashCount, onImpBak, onExpBak, onImpTl, onOpenTrash)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubBody(
    prefs: CoachPreferences,
    trashCount: Int,
    battOk: Boolean,
    hcOk: Boolean,
    counts: QuadCounts,
    onPickPage: (SettingsPage) -> Unit
) {
    SettingsPage.entries.forEach { page ->
        val badge = when (page) {
            SettingsPage.APPEARANCE -> "${prefs.themeMode.lowercase().replaceFirstChar { it.uppercase() }} • ${AccentOption.entries.firstOrNull { it.name == prefs.accentName }?.label ?: "KDE Purple"}"
            SettingsPage.MAPS_3D -> if (prefs.physical3dTerrainMesh) "Terrain ${"%.1f".format(prefs.terrainExaggerationTenths / 10f)}x" else "Native 3D"
            SettingsPage.COACHING -> if (prefs.voiceCoachEnabled) "Coach On" else "Muted"
            SettingsPage.LOCK_DETECT -> if (!battOk) "Battery Action" else if (hcOk) "HC Linked" else "Ready"
            SettingsPage.SYNC_VAULT -> if (prefs.syncFolderUri.isNotBlank()) "Vault Linked" else "Not Linked"
            SettingsPage.BACKUP_TRASH -> if (trashCount > 0) "Trash ($trashCount)" else "${counts.runs + counts.walks + counts.cycles} Saved"
        }
        StrideCard(onClick = { onPickPage(page) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(page.accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(page.icon, contentDescription = null, tint = page.accent, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(page.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(page.subtitle, style = MaterialTheme.typography.bodySmall, color = KdeMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(badge, page.accent)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = KdeMuted)
            }
        }
    }
}

@Composable
private fun SearchResultsBody(
    query: String,
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
    onBattReq: () -> Unit,
    onJumpToPage: (SettingsPage) -> Unit
) {
    val entries: List<SearchResultEntry> = listOf(
        SearchResultEntry.BlockEntry(SettingsPage.APPEARANCE, "Theme Mode, Accent Color & Map Copyright", "theme dark light system accent purple kde positron liberty map style copyright attribution") {
            ThemeModeAccentCard(prefs, onUpdate)
            MapStyleAttributionCard(prefs, onUpdate)
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Physical 3D Terrain Mesh (Stretched Hills)", "Render mountains & elevation relief on 3D map", "3d terrain hill mountain mesh topography relief stretch dem", prefs.physical3dTerrainMesh) { v ->
            onUpdate { it.copy(physical3dTerrainMesh = v) }
        },
        SearchResultEntry.BlockEntry(SettingsPage.MAPS_3D, "3D Hill Exaggeration (1.0x / 2.2x / 3.2x)", "terrain exaggeration steep relief hill stretch", {
            TerrainExaggerationChips(prefs, onUpdate)
        }),
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Default 3D Map Perspective", "Open maps tilted in 3D view by default", "3d default perspective tilt map", prefs.defaultMap3dEnabled) { v ->
            onUpdate { it.copy(defaultMap3dEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Show 3D Buildings", "Extrude urban buildings in 3D view", "3d buildings extrusion architecture", prefs.show3dBuildings) { v ->
            onUpdate { it.copy(show3dBuildings = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Topography DEM Terrain Hillshade", "Shade elevation ridges & slopes", "hillshade topography dem terrain", prefs.showTerrainHillshade) { v ->
            onUpdate { it.copy(showTerrainHillshade = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Auto-Fetch DEM Terrain Elevation", "Populate elevation profiles on routes", "dem elevation altitude fetch slope", prefs.autoFetchDemElevation) { v ->
            onUpdate { it.copy(autoFetchDemElevation = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Keep Map Centered When Sheet Moves", "Adjust padding as bottom drawer expands/collapses", "center bottom sheet drag occlusion padding", prefs.keepMapCenteredOnSheetDrag) { v ->
            onUpdate { it.copy(keepMapCenteredOnSheetDrag = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Animate Section Highlight Pulse", "Pulse white/blue when focusing split or snap section", "highlight pulse animation focus split snap", prefs.animateSectionHighlight) { v ->
            onUpdate { it.copy(animateSectionHighlight = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Smooth Continuous Pace Gradient", "Blend speed colors smoothly along track", "pace gradient smooth colors", prefs.smoothTrackGradient) { v ->
            onUpdate { it.copy(smoothTrackGradient = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Show Live User Location Dot", "Display blue GPS dot on Live & Planner maps", "gps dot user location live", prefs.showUserLocationDot) { v ->
            onUpdate { it.copy(showUserLocationDot = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.MAPS_3D, "Preload & Cache Nearby Vector Tiles", "Cache offline tiles around runs", "preload cache tiles offline vector", prefs.preloadNearbyTiles) { v ->
            onUpdate { it.copy(preloadNearbyTiles = v) }
        },
        SearchResultEntry.BlockEntry(SettingsPage.COACHING, "Pace Color Baseline Mode", "pace baseline zone target fitness average", {
            PaceBaselineCoachingCard(prefs, onUpdate)
        }),
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Slope-Adjusted Target Pace on Hills", "Compensate pace targets on climbs & descents", "slope adjusted pace grade gap hill", prefs.slopeAdjustedPaceEnabled) { v ->
            onUpdate { it.copy(slopeAdjustedPaceEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Top-of-Map Live Telemetry HUD", "Show Speed, Time, Pace, Dist banner on map", "hud top banner telemetry speed pace", prefs.showTopMapHud) { v ->
            onUpdate { it.copy(showTopMapHud = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Show Speed in km/h", "Show km/h alongside min/km pace", "speed kmh velocity", prefs.showSpeedKmh) { v ->
            onUpdate { it.copy(showSpeedKmh = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Show Competition Finale in Plans", "Include race countdown milestone in Plans", "competition race finale plan", prefs.showCompetitionFinale) { v ->
            onUpdate { it.copy(showCompetitionFinale = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Auto-Trim Idle Start/End on Save", "Trim waiting time before/after run automatically", "auto trim idle start end save", prefs.autoTrimRunsEnabled) { v ->
            onUpdate { it.copy(autoTrimRunsEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Gyroscope & IMU Form/3D Heading", "Use IMU for running form stability & map heading", "gyroscope imu sensor heading form", prefs.gyroTelemetryEnabled) { v ->
            onUpdate { it.copy(gyroTelemetryEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "English Voice Coach (TTS)", "Spoken pacing & workout step prompts", "voice tts audio speech coach", prefs.voiceCoachEnabled) { v ->
            onUpdate { it.copy(voiceCoachEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Haptic Vibration Cues", "Vibrate on pace transitions & intervals", "haptic vibration cues", prefs.hapticCuesEnabled) { v ->
            onUpdate { it.copy(hapticCuesEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.COACHING, "Smart Auto-Pause When Stopped", "Pause timer automatically at traffic lights", "auto pause stop light", prefs.autoPauseEnabled) { v ->
            onUpdate { it.copy(autoPauseEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.LOCK_DETECT, "Lockscreen Widget 2-Tap Mistouch Guard", "Require 2 taps on lockscreen widget to pause/stop", "lockscreen widget mistouch tap guard", prefs.widgetTapGuardEnabled) { v ->
            onUpdate { it.copy(widgetTapGuardEnabled = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.LOCK_DETECT, "Auto Pocket-Lock on Workout Start", "Prevent accidental screen touches inside pocket", "pocket lock mistouch accidental touch", prefs.autoPocketLockOnStart) { v ->
            onUpdate { it.copy(autoPocketLockOnStart = v) }
        },
        SearchResultEntry.ToggleEntry(SettingsPage.LOCK_DETECT, "Keep Screen Awake During Run", "Prevent screen timeout while Live screen is open", "wake screen awake display", prefs.keepScreenAwake) { v ->
            onUpdate { it.copy(keepScreenAwake = v) }
        },
        SearchResultEntry.BlockEntry(SettingsPage.LOCK_DETECT, "Health Connect, Auto-Detect & Battery Guard", "health connect sync auto detect run walk ride samsung battery unrestricted lockscreen widget", {
            AutoCard(prefs, hcAvail, hcOk, busy, onHcConnect, onHcSync, onHealthAutoSync, onAutoDetect, onAutoStart)
            BattCard(battOk, onBattReq)
            GuideCard()
        }),
        SearchResultEntry.BlockEntry(SettingsPage.SYNC_VAULT, "Uninstall-Safe Vault & Timeline Sync", "sync folder vault timeline.json backup restore auto sync", {
            VaultSyncCard(prefs, busy, onPickSyncFolder, onSyncFolderNow, onRestoreVaultFile, onUpdate)
        }),
        SearchResultEntry.BlockEntry(SettingsPage.BACKUP_TRASH, "Import, Export .backup, Timeline & Trash", "backup export import timeline.json trash delete recover restore", {
            PortabilityAndTrashCard(busy, summary, activityCounts, trashCount, onImpBak, onExpBak, onImpTl, onOpenTrash)
        })
    )

    val matched = remember(query, prefs, busy, trashCount, battOk, hcOk) {
        entries.filter { e ->
            val hay = when (e) {
                is SearchResultEntry.ToggleEntry -> "${e.label} ${e.subtitle} ${e.keywords} ${e.page.title}".lowercase()
                is SearchResultEntry.BlockEntry -> "${e.title} ${e.keywords} ${e.page.title}".lowercase()
            }
            hay.contains(query)
        }
    }

    if (matched.isEmpty()) {
        StrideCard {
            Text("No settings match \"$query\".", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Try keywords like \"3d terrain\", \"theme\", \"pocket\", \"sync\", \"pace\", or \"trash\".", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
        }
        return
    }

    Text("${matched.size} MATCHING SETTINGS", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
    matched.forEach { item ->
        when (item) {
            is SearchResultEntry.ToggleEntry -> StrideCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(item.page.title, item.page.accent, onClick = { onJumpToPage(item.page) })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = KdeMuted)
                    }
                    Switch(checked = item.checked, onCheckedChange = item.onToggle)
                }
            }
            is SearchResultEntry.BlockEntry -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("Section: ${item.page.title}", item.page.accent, onClick = { onJumpToPage(item.page) })
                }
                item.content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeModeAccentCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    StrideCard {
        Text("Theme & Accent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapStyleAttributionCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdeSky.copy(alpha = 0.22f), selectedLabelColor = KdeSky)
    StrideCard {
        Text("Map Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Base Map Style", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("MATCH_THEME" to "Match Theme", "DARK" to "Dark", "LIGHT" to "Positron", "LIBERTY" to "Liberty").forEach { (k, lbl) ->
                FilterChip(selected = p.mapStyleMode == k, onClick = { onUp { it.copy(mapStyleMode = k) } }, colors = cc, label = { Text(lbl, maxLines = 1, softWrap = false) })
            }
        }
        ToggleRow("Show Map Copyright on Previews", p.showMapAttribution) { v -> onUp { it.copy(showMapAttribution = v) } }
    }
}

@Composable
private fun Terrain3DSettingsCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    StrideCard {
        Text("3D Relief & Physical Terrain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ToggleRow("Default 3D Map Perspective", p.defaultMap3dEnabled) { v -> onUp { it.copy(defaultMap3dEnabled = v) } }
        ToggleRow("Physical 3D Terrain Mesh (Stretched Hills)", p.physical3dTerrainMesh) { v -> onUp { it.copy(physical3dTerrainMesh = v) } }
        TerrainExaggerationChips(p, onUp)
        ToggleRow("Show 3D Buildings", p.show3dBuildings) { v -> onUp { it.copy(show3dBuildings = v) } }
        ToggleRow("Topography DEM Terrain Hillshade", p.showTerrainHillshade) { v -> onUp { it.copy(showTerrainHillshade = v) } }
        ToggleRow("Auto-Fetch DEM Terrain Elevation", p.autoFetchDemElevation) { v -> onUp { it.copy(autoFetchDemElevation = v) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerrainExaggerationChips(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdeSky.copy(alpha = 0.22f), selectedLabelColor = KdeSky)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("3D Hill Exaggeration", style = MaterialTheme.typography.labelSmall, color = KdeMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10 to "1.0x True", 22 to "2.2x Natural", 32 to "3.2x Steep").forEach { (tenths, lbl) ->
                FilterChip(
                    selected = p.terrainExaggerationTenths == tenths,
                    enabled = p.physical3dTerrainMesh,
                    onClick = { onUp { it.copy(terrainExaggerationTenths = tenths) } },
                    colors = cc,
                    label = { Text(lbl, maxLines = 1, softWrap = false) }
                )
            }
        }
    }
}

@Composable
private fun MapRenderSettingsCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    StrideCard {
        Text("Map Behavior & Caching", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ToggleRow("Keep Map Centered When Sheet Moves", p.keepMapCenteredOnSheetDrag) { v -> onUp { it.copy(keepMapCenteredOnSheetDrag = v) } }
        ToggleRow("Animate Section Highlight Pulse", p.animateSectionHighlight) { v -> onUp { it.copy(animateSectionHighlight = v) } }
        ToggleRow("Smooth Continuous Pace Gradient", p.smoothTrackGradient) { v -> onUp { it.copy(smoothTrackGradient = v) } }
        ToggleRow("Show Live User Location Dot", p.showUserLocationDot) { v -> onUp { it.copy(showUserLocationDot = v) } }
        ToggleRow("Preload & Cache Nearby Vector Tiles", p.preloadNearbyTiles) { v -> onUp { it.copy(preloadNearbyTiles = v) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaceBaselineCoachingCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    StrideCard {
        Text("Pace & Telemetry Display", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    }
}

@Composable
private fun AudioSensorCoachingCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    StrideCard {
        Text("Voice, Sensors & Auto-Trim", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ToggleRow("Auto-Trim Idle Start/End on Save", p.autoTrimRunsEnabled) { v -> onUp { it.copy(autoTrimRunsEnabled = v) } }
        ToggleRow("Gyroscope & IMU Form/3D Heading", p.gyroTelemetryEnabled) { v -> onUp { it.copy(gyroTelemetryEnabled = v) } }
        ToggleRow("English Voice Coach (TTS)", p.voiceCoachEnabled) { v -> onUp { it.copy(voiceCoachEnabled = v) } }
        ToggleRow("Haptic Vibration Cues", p.hapticCuesEnabled) { v -> onUp { it.copy(hapticCuesEnabled = v) } }
        ToggleRow("Smart Auto-Pause When Stopped", p.autoPauseEnabled) { v -> onUp { it.copy(autoPauseEnabled = v) } }
    }
}

@Composable
private fun LockscreenProtectionCard(p: CoachPreferences, onUp: ((CoachPreferences) -> CoachPreferences) -> Unit) {
    val ctx = LocalContext.current
    StrideCard {
        Text("Lockscreen & Mistouch Guard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "When active, both the lockscreen notification and the StrideCoach Live widget require 2 deliberate taps to unlock controls so pocket or accidental screen touches never pause your run.",
            style = MaterialTheme.typography.bodySmall,
            color = KdeMuted
        )
        ToggleRow("Lockscreen Widget 2-Tap Mistouch Guard", p.widgetTapGuardEnabled) { v -> onUp { it.copy(widgetTapGuardEnabled = v) } }
        ToggleRow("Auto Pocket-Lock on Workout Start", p.autoPocketLockOnStart) { v -> onUp { it.copy(autoPocketLockOnStart = v) } }
        ToggleRow("Keep Screen Awake During Run", p.keepScreenAwake) { v -> onUp { it.copy(keepScreenAwake = v) } }
        OutlinedButton(
            onClick = {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ok = mgr.isRequestPinAppWidgetSupported && mgr.requestPinAppWidget(
                    ComponentName(ctx, LiveWorkoutWidgetProvider::class.java),
                    null,
                    null
                )
                if (!ok) {
                    Toast.makeText(
                        ctx,
                        "Open your lockscreen or launcher Widgets picker and add 'StrideCoach Live'.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add / Pin 'StrideCoach Live' Widget", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
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
        Text("2. Link a Sync Folder so Google Timeline.json and all runs/plans auto-sync and survive app uninstalls.", style = MaterialTheme.typography.bodySmall, color = KdeMuted)
    }
}

@Composable
private fun ToggleRow(lbl: String, chk: Boolean, onChg: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(lbl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = chk, onCheckedChange = onChg)
    }
}

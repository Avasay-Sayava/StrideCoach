package com.stridecoach.running.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey as bKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey as iKey
import androidx.datastore.preferences.core.longPreferencesKey as lKey
import androidx.datastore.preferences.core.stringPreferencesKey as sKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefsStore by preferencesDataStore(name = "stridecoach_prefs")

data class CoachPreferences(
    val voiceCoachEnabled: Boolean = true,
    val hapticCuesEnabled: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val autoPauseEnabled: Boolean = true,
    val autoFinishStopSeconds: Int = 180,
    val stepLengthCm: Int = 74,
    val healthConnectEnabled: Boolean = false,
    val autoDetectRunsEnabled: Boolean = true,
    val healthAutoSyncEnabled: Boolean = true,
    val lastHealthSyncMillis: Long = 0L,
    val autoStartOnRunDetected: Boolean = false,
    val autoTrimRunsEnabled: Boolean = true,
    val gyroTelemetryEnabled: Boolean = true,
    val defaultMap3dEnabled: Boolean = false,
    val themeMode: String = "DARK",
    val accentName: String = "KDE_PURPLE",
    val mapStyleMode: String = "MATCH_THEME",
    val showMapAttribution: Boolean = true,
    val show3dBuildings: Boolean = true,
    val showTerrainHillshade: Boolean = true,
    val physical3dTerrainMesh: Boolean = true,
    val terrainExaggerationTenths: Int = 22,
    val keepMapCenteredOnSheetDrag: Boolean = true,
    val animateSectionHighlight: Boolean = true,
    val smoothTrackGradient: Boolean = true,
    val showUserLocationDot: Boolean = true,
    val preloadNearbyTiles: Boolean = true,
    val slopeAdjustedPaceEnabled: Boolean = true,
    val showTopMapHud: Boolean = true,
    val showSpeedKmh: Boolean = true,
    val paceBaselineMode: String = "CURRENT_FITNESS",
    val showCompetitionFinale: Boolean = true,
    val autoPocketLockOnStart: Boolean = false,
    val widgetTapGuardEnabled: Boolean = true,
    val folderAutoSyncEnabled: Boolean = true,
    val autoFetchDemElevation: Boolean = true,
    val syncFolderUri: String = "",
    val lastFolderSyncMillis: Long = 0L,
    val lastTimelineFileModMillis: Long = 0L
)

private object PrefKeys {
    val v = bKey("v"); val h = bKey("h"); val w = bKey("w"); val p = bKey("p")
    val s = iKey("s"); val l = iKey("l"); val hc = bKey("hc"); val ad = bKey("ad")
    val has = bKey("has"); val lhs = lKey("lhs"); val asr = bKey("asr"); val atr = bKey("atr")
    val gyr = bKey("gyr"); val m3d = bKey("m3d"); val th = sKey("theme"); val acc = sKey("accent")
    val mst = sKey("mstyle"); val attr = bKey("attr"); val bld = bKey("bld3d"); val hil = bKey("hills")
    val mesh = bKey("mesh3d"); val tex = iKey("terexag"); val shc = bKey("shctr"); val hia = bKey("hianim")
    val smg = bKey("smgrad"); val udot = bKey("udot"); val pret = bKey("pretile"); val slp = bKey("slppe")
    val hud = bKey("tophud"); val spk = bKey("spdkmh"); val bsm = sKey("basemode"); val fin = bKey("compfin")
    val pkl = bKey("pklock"); val wtg = bKey("wtgrd"); val fas = bKey("fsync"); val dem = bKey("autodem")
    val furi = sKey("furi"); val lfs = lKey("lfs"); val ltf = lKey("ltf")
}

class AppPreferencesStore(private val ctx: Context) {
    val preferences: Flow<CoachPreferences> = ctx.prefsStore.data.map(::read)

    suspend fun update(fn: (CoachPreferences) -> CoachPreferences) {
        ctx.prefsStore.edit { m ->
            val cur = read(m)
            val nxt = fn(cur)
            if (nxt == cur) return@edit
            writeRun(m, nxt)
            writeMap(m, nxt)
            writeSync(m, nxt)
        }
    }

    private fun writeRun(m: MutablePreferences, n: CoachPreferences) {
        m[PrefKeys.v] = n.voiceCoachEnabled; m[PrefKeys.h] = n.hapticCuesEnabled
        m[PrefKeys.w] = n.keepScreenAwake; m[PrefKeys.p] = n.autoPauseEnabled
        m[PrefKeys.s] = n.autoFinishStopSeconds.coerceIn(30, 900)
        m[PrefKeys.l] = n.stepLengthCm.coerceIn(40, 120)
        m[PrefKeys.asr] = n.autoStartOnRunDetected; m[PrefKeys.atr] = n.autoTrimRunsEnabled
        m[PrefKeys.gyr] = n.gyroTelemetryEnabled; m[PrefKeys.slp] = n.slopeAdjustedPaceEnabled
        m[PrefKeys.bsm] = n.paceBaselineMode; m[PrefKeys.fin] = n.showCompetitionFinale
        m[PrefKeys.pkl] = n.autoPocketLockOnStart; m[PrefKeys.wtg] = n.widgetTapGuardEnabled
    }

    private fun writeMap(m: MutablePreferences, n: CoachPreferences) {
        m[PrefKeys.m3d] = n.defaultMap3dEnabled; m[PrefKeys.th] = n.themeMode
        m[PrefKeys.acc] = n.accentName; m[PrefKeys.mst] = n.mapStyleMode
        m[PrefKeys.attr] = n.showMapAttribution; m[PrefKeys.bld] = n.show3dBuildings
        m[PrefKeys.hil] = n.showTerrainHillshade; m[PrefKeys.mesh] = n.physical3dTerrainMesh
        m[PrefKeys.tex] = n.terrainExaggerationTenths.coerceIn(10, 40)
        m[PrefKeys.shc] = n.keepMapCenteredOnSheetDrag; m[PrefKeys.hia] = n.animateSectionHighlight
        m[PrefKeys.smg] = n.smoothTrackGradient; m[PrefKeys.udot] = n.showUserLocationDot
        m[PrefKeys.pret] = n.preloadNearbyTiles; m[PrefKeys.hud] = n.showTopMapHud
        m[PrefKeys.spk] = n.showSpeedKmh
    }

    private fun writeSync(m: MutablePreferences, n: CoachPreferences) {
        m[PrefKeys.hc] = n.healthConnectEnabled; m[PrefKeys.ad] = n.autoDetectRunsEnabled
        m[PrefKeys.has] = n.healthAutoSyncEnabled; m[PrefKeys.lhs] = n.lastHealthSyncMillis
        m[PrefKeys.fas] = n.folderAutoSyncEnabled; m[PrefKeys.dem] = n.autoFetchDemElevation
        m[PrefKeys.furi] = n.syncFolderUri; m[PrefKeys.lfs] = n.lastFolderSyncMillis
        m[PrefKeys.ltf] = n.lastTimelineFileModMillis
    }

    private fun read(p: Preferences) = CoachPreferences(
        voiceCoachEnabled = p[PrefKeys.v] ?: true, hapticCuesEnabled = p[PrefKeys.h] ?: true,
        keepScreenAwake = p[PrefKeys.w] ?: false, autoPauseEnabled = p[PrefKeys.p] ?: true,
        autoFinishStopSeconds = p[PrefKeys.s] ?: 180, stepLengthCm = p[PrefKeys.l] ?: 74,
        healthConnectEnabled = p[PrefKeys.hc] ?: false, autoDetectRunsEnabled = p[PrefKeys.ad] ?: true,
        healthAutoSyncEnabled = p[PrefKeys.has] ?: true, lastHealthSyncMillis = p[PrefKeys.lhs] ?: 0L,
        autoStartOnRunDetected = p[PrefKeys.asr] ?: false, autoTrimRunsEnabled = p[PrefKeys.atr] ?: true,
        gyroTelemetryEnabled = p[PrefKeys.gyr] ?: true, defaultMap3dEnabled = p[PrefKeys.m3d] ?: false,
        themeMode = p[PrefKeys.th] ?: "DARK", accentName = p[PrefKeys.acc] ?: "KDE_PURPLE",
        mapStyleMode = p[PrefKeys.mst] ?: "MATCH_THEME", showMapAttribution = p[PrefKeys.attr] ?: true,
        show3dBuildings = p[PrefKeys.bld] ?: true, showTerrainHillshade = p[PrefKeys.hil] ?: true,
        physical3dTerrainMesh = p[PrefKeys.mesh] ?: true, terrainExaggerationTenths = (p[PrefKeys.tex] ?: 22).coerceIn(10, 40),
        keepMapCenteredOnSheetDrag = p[PrefKeys.shc] ?: true, animateSectionHighlight = p[PrefKeys.hia] ?: true,
        smoothTrackGradient = p[PrefKeys.smg] ?: true, showUserLocationDot = p[PrefKeys.udot] ?: true,
        preloadNearbyTiles = p[PrefKeys.pret] ?: true, slopeAdjustedPaceEnabled = p[PrefKeys.slp] ?: true,
        showTopMapHud = p[PrefKeys.hud] ?: true, showSpeedKmh = p[PrefKeys.spk] ?: true,
        paceBaselineMode = p[PrefKeys.bsm] ?: "CURRENT_FITNESS", showCompetitionFinale = p[PrefKeys.fin] ?: true,
        autoPocketLockOnStart = p[PrefKeys.pkl] ?: false, widgetTapGuardEnabled = p[PrefKeys.wtg] ?: true,
        folderAutoSyncEnabled = p[PrefKeys.fas] ?: true, autoFetchDemElevation = p[PrefKeys.dem] ?: true,
        syncFolderUri = p[PrefKeys.furi] ?: "", lastFolderSyncMillis = p[PrefKeys.lfs] ?: 0L,
        lastTimelineFileModMillis = p[PrefKeys.ltf] ?: 0L
    )
}

package com.stridecoach.running.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

class AppPreferencesStore(private val ctx: Context) {
    private val kV = booleanPreferencesKey("v")
    private val kH = booleanPreferencesKey("h")
    private val kW = booleanPreferencesKey("w")
    private val kP = booleanPreferencesKey("p")
    private val kS = intPreferencesKey("s")
    private val kL = intPreferencesKey("l")
    private val kHc = booleanPreferencesKey("hc")
    private val kAd = booleanPreferencesKey("ad")
    private val kHas = booleanPreferencesKey("has")
    private val kLhs = longPreferencesKey("lhs")
    private val kAsr = booleanPreferencesKey("asr")
    private val kAtr = booleanPreferencesKey("atr")
    private val kGyr = booleanPreferencesKey("gyr")
    private val kM3d = booleanPreferencesKey("m3d")

    private val kTh = stringPreferencesKey("theme")
    private val kAcc = stringPreferencesKey("accent")
    private val kMst = stringPreferencesKey("mstyle")
    private val kAttr = booleanPreferencesKey("attr")
    private val kBld = booleanPreferencesKey("bld3d")
    private val kHil = booleanPreferencesKey("hills")
    private val kMesh = booleanPreferencesKey("mesh3d")
    private val kTexag = intPreferencesKey("terexag")
    private val kShc = booleanPreferencesKey("shctr")
    private val kHia = booleanPreferencesKey("hianim")
    private val kSmg = booleanPreferencesKey("smgrad")
    private val kUdot = booleanPreferencesKey("udot")
    private val kPret = booleanPreferencesKey("pretile")
    private val kSlp = booleanPreferencesKey("slppe")
    private val kHud = booleanPreferencesKey("tophud")
    private val kSpk = booleanPreferencesKey("spdkmh")
    private val kBsm = stringPreferencesKey("basemode")
    private val kFin = booleanPreferencesKey("compfin")
    private val kPkl = booleanPreferencesKey("pklock")
    private val kWtg = booleanPreferencesKey("wtgrd")
    private val kFas = booleanPreferencesKey("fsync")
    private val kDem = booleanPreferencesKey("autodem")
    private val kFuri = stringPreferencesKey("furi")
    private val kLfs = longPreferencesKey("lfs")
    private val kLtf = longPreferencesKey("ltf")

    val preferences: Flow<CoachPreferences> = ctx.prefsStore.data.map(::read)

    suspend fun update(fn: (CoachPreferences) -> CoachPreferences) {
        ctx.prefsStore.edit { map ->
            val cur = read(map)
            val nxt = fn(cur)
            if (nxt == cur) return@edit
            map[kV] = nxt.voiceCoachEnabled
            map[kH] = nxt.hapticCuesEnabled
            map[kW] = nxt.keepScreenAwake
            map[kP] = nxt.autoPauseEnabled
            map[kS] = nxt.autoFinishStopSeconds.coerceIn(30, 900)
            map[kL] = nxt.stepLengthCm.coerceIn(40, 120)
            map[kHc] = nxt.healthConnectEnabled
            map[kAd] = nxt.autoDetectRunsEnabled
            map[kHas] = nxt.healthAutoSyncEnabled
            map[kLhs] = nxt.lastHealthSyncMillis
            map[kAsr] = nxt.autoStartOnRunDetected
            map[kAtr] = nxt.autoTrimRunsEnabled
            map[kGyr] = nxt.gyroTelemetryEnabled
            map[kM3d] = nxt.defaultMap3dEnabled

            map[kTh] = nxt.themeMode
            map[kAcc] = nxt.accentName
            map[kMst] = nxt.mapStyleMode
            map[kAttr] = nxt.showMapAttribution
            map[kBld] = nxt.show3dBuildings
            map[kHil] = nxt.showTerrainHillshade
            map[kMesh] = nxt.physical3dTerrainMesh
            map[kTexag] = nxt.terrainExaggerationTenths.coerceIn(10, 40)
            map[kShc] = nxt.keepMapCenteredOnSheetDrag
            map[kHia] = nxt.animateSectionHighlight
            map[kSmg] = nxt.smoothTrackGradient
            map[kUdot] = nxt.showUserLocationDot
            map[kPret] = nxt.preloadNearbyTiles
            map[kSlp] = nxt.slopeAdjustedPaceEnabled
            map[kHud] = nxt.showTopMapHud
            map[kSpk] = nxt.showSpeedKmh
            map[kBsm] = nxt.paceBaselineMode
            map[kFin] = nxt.showCompetitionFinale
            map[kPkl] = nxt.autoPocketLockOnStart
            map[kWtg] = nxt.widgetTapGuardEnabled
            map[kFas] = nxt.folderAutoSyncEnabled
            map[kDem] = nxt.autoFetchDemElevation
            map[kFuri] = nxt.syncFolderUri
            map[kLfs] = nxt.lastFolderSyncMillis
            map[kLtf] = nxt.lastTimelineFileModMillis
        }
    }

    private fun read(p: Preferences): CoachPreferences {
        return CoachPreferences(
            voiceCoachEnabled = p[kV] ?: true,
            hapticCuesEnabled = p[kH] ?: true,
            keepScreenAwake = p[kW] ?: false,
            autoPauseEnabled = p[kP] ?: true,
            autoFinishStopSeconds = p[kS] ?: 180,
            stepLengthCm = p[kL] ?: 74,
            healthConnectEnabled = p[kHc] ?: false,
            autoDetectRunsEnabled = p[kAd] ?: true,
            healthAutoSyncEnabled = p[kHas] ?: true,
            lastHealthSyncMillis = p[kLhs] ?: 0L,
            autoStartOnRunDetected = p[kAsr] ?: false,
            autoTrimRunsEnabled = p[kAtr] ?: true,
            gyroTelemetryEnabled = p[kGyr] ?: true,
            defaultMap3dEnabled = p[kM3d] ?: false,
            themeMode = p[kTh] ?: "DARK",
            accentName = p[kAcc] ?: "KDE_PURPLE",
            mapStyleMode = p[kMst] ?: "MATCH_THEME",
            showMapAttribution = p[kAttr] ?: true,
            show3dBuildings = p[kBld] ?: true,
            showTerrainHillshade = p[kHil] ?: true,
            physical3dTerrainMesh = p[kMesh] ?: true,
            terrainExaggerationTenths = (p[kTexag] ?: 22).coerceIn(10, 40),
            keepMapCenteredOnSheetDrag = p[kShc] ?: true,
            animateSectionHighlight = p[kHia] ?: true,
            smoothTrackGradient = p[kSmg] ?: true,
            showUserLocationDot = p[kUdot] ?: true,
            preloadNearbyTiles = p[kPret] ?: true,
            slopeAdjustedPaceEnabled = p[kSlp] ?: true,
            showTopMapHud = p[kHud] ?: true,
            showSpeedKmh = p[kSpk] ?: true,
            paceBaselineMode = p[kBsm] ?: "CURRENT_FITNESS",
            showCompetitionFinale = p[kFin] ?: true,
            autoPocketLockOnStart = p[kPkl] ?: false,
            widgetTapGuardEnabled = p[kWtg] ?: true,
            folderAutoSyncEnabled = p[kFas] ?: true,
            autoFetchDemElevation = p[kDem] ?: true,
            syncFolderUri = p[kFuri] ?: "",
            lastFolderSyncMillis = p[kLfs] ?: 0L,
            lastTimelineFileModMillis = p[kLtf] ?: 0L
        )
    }
}

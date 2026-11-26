package com.stridecoach.running.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val autoDetectRunsEnabled: Boolean = true
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
            autoDetectRunsEnabled = p[kAd] ?: true
        )
    }
}

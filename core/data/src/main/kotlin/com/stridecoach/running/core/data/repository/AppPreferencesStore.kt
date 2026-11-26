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
    val stepLengthCm: Int = 74
)

class AppPreferencesStore(private val ctx: Context) {
    private val kV = booleanPreferencesKey("v")
    private val kH = booleanPreferencesKey("h")
    private val kW = booleanPreferencesKey("w")
    private val kP = booleanPreferencesKey("p")
    private val kS = intPreferencesKey("s")
    private val kL = intPreferencesKey("l")

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
        }
    }

    private fun read(p: Preferences): CoachPreferences {
        val v = p[kV] ?: true
        val h = p[kH] ?: true
        val w = p[kW] ?: false
        val ap = p[kP] ?: true
        val s = p[kS] ?: 180
        val l = p[kL] ?: 74
        return CoachPreferences(v, h, w, ap, s, l)
    }
}

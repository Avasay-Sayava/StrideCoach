package com.stridecoach.running.core.tracking

import android.Manifest.permission.ACTIVITY_RECOGNITION as ACT
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import androidx.core.content.ContextCompat.checkSelfPermission
import com.google.android.gms.location.ActivityRecognition as ArApi
import com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER as ENTER
import com.google.android.gms.location.ActivityTransition.Builder as TrBuild
import com.google.android.gms.location.ActivityTransitionRequest as TrReq
import com.google.android.gms.location.DetectedActivity.ON_BICYCLE
import com.google.android.gms.location.DetectedActivity.RUNNING
import com.google.android.gms.location.DetectedActivity.WALKING

object AutoActivityDetector {
    private const val REQ = 8801
    internal const val PREFS = "stridecoach_auto_detect"
    internal const val KEY_AUTO_START = "auto_start_run"

    @SuppressLint("MissingPermission")
    fun setEnabled(c: Context, on: Boolean) {
        if (!hasPerm(c)) return
        val pi = piOf(c)
        runCatching {
            val api = ArApi.getClient(c)
            if (!on) {
                api.removeActivityTransitionUpdates(pi)
                return@runCatching
            }
            val list = listOf(RUNNING, WALKING, ON_BICYCLE).map { kind ->
                TrBuild().setActivityType(kind).setActivityTransition(ENTER).build()
            }
            api.requestActivityTransitionUpdates(TrReq(list), pi)
        }
    }

    fun setAutoStartEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START, on)
            .apply()
    }

    internal fun isAutoStartEnabled(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_START, false)

    private fun hasPerm(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || checkSelfPermission(c, ACT) == PERMISSION_GRANTED

    private fun piOf(c: Context): PendingIntent {
        val i = Intent(c, ActivityTransitionReceiver::class.java)
        val f = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(c, REQ, i, f)
    }
}

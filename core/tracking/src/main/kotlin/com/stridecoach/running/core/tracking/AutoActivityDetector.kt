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
import com.google.android.gms.location.DetectedActivity.RUNNING as RUN

object AutoActivityDetector {
    private const val REQ = 8801

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
            val t = TrBuild().setActivityType(RUN).setActivityTransition(ENTER).build()
            api.requestActivityTransitionUpdates(TrReq(listOf(t)), pi)
        }
    }

    private fun hasPerm(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || checkSelfPermission(c, ACT) == PERMISSION_GRANTED

    private fun piOf(c: Context): PendingIntent {
        val i = Intent(c, ActivityTransitionReceiver::class.java)
        val f = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(c, REQ, i, f)
    }
}

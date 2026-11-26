package com.stridecoach.running.core.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (!ActivityTransitionResult.hasResult(i)) return
        val res = ActivityTransitionResult.extractResult(i) ?: return
        val hit = res.transitionEvents.any {
            it.activityType == DetectedActivity.RUNNING &&
                it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        }
        if (!hit || TrackingSessionBus.isLive() || !canNotify(ctx)) return
        notifyUser(ctx)
    }

    private fun canNotify(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = sp.getLong(KEY_TS, 0L)
        val now = System.currentTimeMillis()
        if (now - prev < COOL_MS) return false
        sp.edit().putLong(KEY_TS, now).apply()
        return true
    }

    private fun notifyUser(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(CH, "Run Auto Detect", NotificationManager.IMPORTANCE_HIGH))

        val svc = Intent(ctx, RunTrackingService::class.java).setAction(RunTrackingService.ACTION_START)
        val flg = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val goPi = PendingIntent.getForegroundService(ctx, 901, svc, flg)
        val openPi = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 902, it, flg)
        }

        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Looks like you're running!")
            .setContentText("Tap Start Run to launch live GPS tracking & pace coaching.")
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, "Start Run", goPi)
            .build()
        mgr.notify(NID, n)
    }

    companion object {
        private const val CH = "stridecoach_auto_detect"
        private const val NID = 4302
        private const val PREFS = "stridecoach_auto_detect"
        private const val KEY_TS = "last_ms"
        private const val COOL_MS = 10 * 60_000L
    }
}

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
import com.stridecoach.running.core.domain.model.ActivityKind

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (!ActivityTransitionResult.hasResult(i)) return
        val res = ActivityTransitionResult.extractResult(i) ?: return
        val detectedKind = res.transitionEvents.firstNotNullOfOrNull { ev ->
            if (ev.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) null
            else when (ev.activityType) {
                DetectedActivity.RUNNING -> ActivityKind.RUNNING
                DetectedActivity.ON_BICYCLE -> ActivityKind.CYCLING
                DetectedActivity.WALKING -> ActivityKind.WALKING
                else -> null
            }
        } ?: return

        if (TrackingSessionBus.isLive() || !canNotify(ctx)) return

        if (detectedKind == ActivityKind.RUNNING && AutoActivityDetector.isAutoStartEnabled(ctx)) {
            val started = runCatching {
                RunTrackingService.startAuto(ctx, ActivityKind.RUNNING)
            }.isSuccess
            if (started) return
        }
        if (detectedKind == ActivityKind.RUNNING || detectedKind == ActivityKind.CYCLING) {
            notifyUser(ctx, detectedKind)
        }
    }

    private fun canNotify(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = sp.getLong(KEY_TS, 0L)
        val now = System.currentTimeMillis()
        if (now - prev < COOL_MS) return false
        sp.edit().putLong(KEY_TS, now).apply()
        return true
    }

    private fun notifyUser(ctx: Context, kind: ActivityKind) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(CH, "Activity Auto Detect", NotificationManager.IMPORTANCE_HIGH))

        val svc = Intent(ctx, RunTrackingService::class.java)
            .setAction(RunTrackingService.ACTION_START_AUTO)
            .putExtra(RunTrackingService.EXTRA_ACTIVITY_KIND, kind.name)
        val flg = PendingIntent.getForegroundService(ctx, 901, svc, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 902, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val isRun = kind == ActivityKind.RUNNING
        val title = if (isRun) "Looks like you're running!" else "Outdoor cycling detected!"
        val text = if (isRun) "Tap Start Run for live GPS tracking & pace coaching." else "Tap Start Ride to record your route."
        val actionLabel = if (isRun) "Start Run" else "Start Ride"

        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, actionLabel, flg)
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

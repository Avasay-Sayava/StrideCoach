package com.stridecoach.running.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.stridecoach.running.MainActivity
import com.stridecoach.running.R
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import com.stridecoach.running.core.tracking.RunTrackingService
import com.stridecoach.running.core.tracking.TrackingSessionBus

/**
 * Home & lockscreen workout widget implementing a 2-tap mistouch guard so accidental
 * pocket/palm presses during a run never pause or terminate active GPS tracking.
 */
class LiveWorkoutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        when (intent.action) {
            "com.samsung.android.intent.action.REQUEST_SERVICEBOX_REMOTEVIEWS",
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> refreshAll(context)
            ACT_GUARD_TAP -> handleGuardTap(context, sp, now)
            ACT_PAUSE -> if (isUnlocked(sp, now) || !TrackingSessionBus.widgetTapGuardEnabled) {
                RunTrackingService.sendAction(context, RunTrackingService.ACTION_PAUSE_RESUME)
                sp.edit().putLong(KEY_UNLOCK_UNTIL, now + 6_000L).apply()
                refreshAll(context)
            }
            ACT_NEXT -> if (isUnlocked(sp, now) || !TrackingSessionBus.widgetTapGuardEnabled) {
                RunTrackingService.sendAction(context, RunTrackingService.ACTION_NEXT_STEP)
                sp.edit().putLong(KEY_UNLOCK_UNTIL, now + 6_000L).apply()
                refreshAll(context)
            }
            ACT_STOP_CONFIRM -> {
                if (!TrackingSessionBus.isLive()) {
                    RunTrackingService.start(context)
                    sp.edit().remove(KEY_ARMED_AT).remove(KEY_UNLOCK_UNTIL).remove(KEY_STOP_ARMED).apply()
                } else if (isUnlocked(sp, now) || !TrackingSessionBus.widgetTapGuardEnabled) {
                    val stopArmedAt = sp.getLong(KEY_STOP_ARMED, 0L)
                    if (now - stopArmedAt <= 3_500L) {
                        RunTrackingService.sendAction(context, RunTrackingService.ACTION_STOP)
                        sp.edit().remove(KEY_ARMED_AT).remove(KEY_UNLOCK_UNTIL).remove(KEY_STOP_ARMED).apply()
                    } else {
                        sp.edit().putLong(KEY_STOP_ARMED, now).putLong(KEY_UNLOCK_UNTIL, now + 6_000L).apply()
                    }
                }
                refreshAll(context)
            }
        }
    }

    private fun handleGuardTap(ctx: Context, sp: android.content.SharedPreferences, now: Long) {
        val armedAt = sp.getLong(KEY_ARMED_AT, 0L)
        if (now - armedAt <= 2_800L) {
            sp.edit().putLong(KEY_UNLOCK_UNTIL, now + 6_000L).remove(KEY_ARMED_AT).remove(KEY_STOP_ARMED).apply()
        } else {
            sp.edit().putLong(KEY_ARMED_AT, now).apply()
        }
        refreshAll(ctx)
    }

    private fun isUnlocked(sp: android.content.SharedPreferences, now: Long): Boolean =
        now < sp.getLong(KEY_UNLOCK_UNTIL, 0L)

    companion object {
        private const val PREFS = "stridecoach_widget_guard"
        private const val KEY_ARMED_AT = "armed_at"
        private const val KEY_UNLOCK_UNTIL = "unlock_until"
        private const val KEY_STOP_ARMED = "stop_armed"

        private const val ACT_GUARD_TAP = "com.stridecoach.running.widget.GUARD_TAP"
        private const val ACT_PAUSE = "com.stridecoach.running.widget.PAUSE"
        private const val ACT_NEXT = "com.stridecoach.running.widget.NEXT"
        private const val ACT_STOP_CONFIRM = "com.stridecoach.running.widget.STOP_CONFIRM"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, LiveWorkoutWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(ctx)
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_live_workout)
            val st = TrackingSessionBus.state.value
            val sn = st.snapshot
            val distM = sn?.totalDistanceMeters ?: 0.0
            val durMs = sn?.totalElapsedMillis ?: 0L
            val paceSec = sn?.currentPaceSecPerKm ?: 0
            val spdKmh = if (st.currentSpeedKmh > 0.2f) "%.1f km/h".format(st.currentSpeedKmh) else formatSpeedKmh(paceSec)

            rv.setTextViewText(R.id.widget_title, st.workout?.title ?: "StrideCoach Live")
            rv.setTextViewText(R.id.widget_status, when {
                !st.isActive -> "IDLE"
                st.isPaused -> "PAUSED"
                else -> st.activityKind.singularTag.uppercase()
            })
            rv.setTextViewText(R.id.widget_dist, "%.2f km".format(distM / 1000.0))
            rv.setTextViewText(R.id.widget_time, formatDuration(durMs))
            rv.setTextViewText(R.id.widget_pace, "${formatPace(paceSec)} /km")
            rv.setTextViewText(R.id.widget_speed, spdKmh)

            val launchPi = PendingIntent.getActivity(
                ctx, 701, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_title, launchPi)

            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val guardActive = st.isActive && TrackingSessionBus.widgetTapGuardEnabled
            val unlocked = !guardActive || now < sp.getLong(KEY_UNLOCK_UNTIL, 0L)
            val guardArmed = now - sp.getLong(KEY_ARMED_AT, 0L) <= 2_800L
            val stopArmed = now - sp.getLong(KEY_STOP_ARMED, 0L) <= 3_500L

            if (!unlocked) {
                rv.setViewVisibility(R.id.widget_btn_guard, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_btn_pause, View.GONE)
                rv.setViewVisibility(R.id.widget_btn_next, View.GONE)
                rv.setViewVisibility(R.id.widget_btn_stop, View.GONE)
                rv.setTextViewText(
                    R.id.widget_btn_guard,
                    if (guardArmed) "Tap Again to Unlock Controls" else "Locked • Tap 2x to Control"
                )
            } else {
                rv.setViewVisibility(R.id.widget_btn_guard, View.GONE)
                rv.setViewVisibility(R.id.widget_btn_pause, if (st.isActive) View.VISIBLE else View.GONE)
                rv.setViewVisibility(R.id.widget_btn_next, if (st.isActive) View.VISIBLE else View.GONE)
                rv.setViewVisibility(R.id.widget_btn_stop, View.VISIBLE)
                rv.setTextViewText(R.id.widget_btn_pause, if (st.isPaused) "Resume" else "Pause")
                rv.setTextViewText(
                    R.id.widget_btn_stop,
                    when {
                        !st.isActive -> "Start Run"
                        stopArmed -> "Confirm?"
                        else -> "Finish"
                    }
                )
            }

            rv.setOnClickPendingIntent(R.id.widget_btn_guard, actionPi(ctx, ACT_GUARD_TAP, 710))
            rv.setOnClickPendingIntent(R.id.widget_btn_pause, actionPi(ctx, ACT_PAUSE, 711))
            rv.setOnClickPendingIntent(R.id.widget_btn_next, actionPi(ctx, ACT_NEXT, 712))
            rv.setOnClickPendingIntent(R.id.widget_btn_stop, actionPi(ctx, ACT_STOP_CONFIRM, 713))
            return rv
        }

        private fun actionPi(ctx: Context, action: String, code: Int): PendingIntent {
            val i = Intent(ctx, LiveWorkoutWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

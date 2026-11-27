package com.stridecoach.running.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.RemoteViews
import com.stridecoach.running.MainActivity
import com.stridecoach.running.R
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import com.stridecoach.running.core.tracking.LiveTrackingState
import com.stridecoach.running.core.tracking.RunTrackingService as Svc
import com.stridecoach.running.core.tracking.TrackingSessionBus as Bus

class LiveWorkoutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = buildViews(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        when (intent.action) {
            SAMSUNG_UPDATE, AppWidgetManager.ACTION_APPWIDGET_UPDATE -> refreshAll(ctx)
            ACT_GUARD_TAP -> handleGuardTap(ctx, sp, now)
            ACT_PAUSE -> handleControlTap(ctx, sp, now, Svc.ACTION_PAUSE_RESUME)
            ACT_NEXT -> handleControlTap(ctx, sp, now, Svc.ACTION_NEXT_STEP)
            ACT_STOP_CONFIRM -> handleStopTap(ctx, sp, now)
        }
    }

    private fun handleControlTap(ctx: Context, sp: SharedPreferences, now: Long, act: String) {
        if (!canAct(sp, now)) return
        Svc.sendAction(ctx, act)
        sp.edit().putLong(KEY_UNLOCK_UNTIL, now + 6_000L).apply()
        refreshAll(ctx)
    }

    private fun handleStopTap(ctx: Context, sp: SharedPreferences, now: Long) {
        if (!Bus.isLive()) {
            Svc.start(ctx)
            clearArmState(sp)
            refreshAll(ctx)
            return
        }
        if (!canAct(sp, now)) { refreshAll(ctx); return }
        val armed = sp.getLong(KEY_STOP_ARMED, 0L)
        if (now - armed <= 3_500L) {
            Svc.sendAction(ctx, Svc.ACTION_STOP)
            clearArmState(sp)
        } else {
            sp.edit().putLong(KEY_STOP_ARMED, now).putLong(KEY_UNLOCK_UNTIL, now + 6_000L).apply()
        }
        refreshAll(ctx)
    }

    private fun handleGuardTap(ctx: Context, sp: SharedPreferences, now: Long) {
        val armed = sp.getLong(KEY_ARMED_AT, 0L)
        if (now - armed <= 2_800L) sp.edit().putLong(KEY_UNLOCK_UNTIL, now + 6_000L).remove(KEY_ARMED_AT).remove(KEY_STOP_ARMED).apply()
        else sp.edit().putLong(KEY_ARMED_AT, now).apply()
        refreshAll(ctx)
    }

    private fun canAct(sp: SharedPreferences, now: Long): Boolean = !Bus.widgetTapGuardEnabled || now < sp.getLong(KEY_UNLOCK_UNTIL, 0L)

    private fun clearArmState(sp: SharedPreferences) {
        sp.edit().remove(KEY_ARMED_AT).remove(KEY_UNLOCK_UNTIL).remove(KEY_STOP_ARMED).apply()
    }

    companion object {
        private const val PREFS = "stridecoach_widget_guard"
        private const val KEY_ARMED_AT = "armed_at"
        private const val KEY_UNLOCK_UNTIL = "unlock_until"
        private const val KEY_STOP_ARMED = "stop_armed"
        private const val SAMSUNG_UPDATE = "com.samsung.android.intent.action.REQUEST_SERVICEBOX_REMOTEVIEWS"
        private const val ACT_GUARD_TAP = "com.stridecoach.running.widget.GUARD_TAP"
        private const val ACT_PAUSE = "com.stridecoach.running.widget.PAUSE"
        private const val ACT_NEXT = "com.stridecoach.running.widget.NEXT"
        private const val ACT_STOP_CONFIRM = "com.stridecoach.running.widget.STOP_CONFIRM"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, LiveWorkoutWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_live_workout)
            val st = Bus.state.value
            bindTelemetry(ctx, rv, st)
            bindButtons(ctx, rv, st)
            return rv
        }

        private fun bindTelemetry(ctx: Context, rv: RemoteViews, st: LiveTrackingState) {
            val sn = st.snapshot
            val pace = sn?.currentPaceSecPerKm ?: 0
            val spd = if (st.currentSpeedKmh > 0.2f) "%.1f km/h".format(st.currentSpeedKmh) else formatSpeedKmh(pace)
            rv.setTextViewText(R.id.widget_title, st.workout?.title ?: "StrideCoach Live")
            rv.setTextViewText(R.id.widget_status, if (!st.isActive) "IDLE" else if (st.isPaused) "PAUSED" else st.activityKind.singularTag.uppercase())
            rv.setTextViewText(R.id.widget_dist, "%.2f km".format((sn?.totalDistanceMeters ?: 0.0) / 1000.0))
            rv.setTextViewText(R.id.widget_time, formatDuration(sn?.totalElapsedMillis ?: 0L))
            rv.setTextViewText(R.id.widget_pace, "${formatPace(pace)} /km")
            rv.setTextViewText(R.id.widget_speed, spd)
            val pi = PendingIntent.getActivity(ctx, 701, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.widget_title, pi)
        }

        private fun bindButtons(ctx: Context, rv: RemoteViews, st: LiveTrackingState) {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val unlocked = !(st.isActive && Bus.widgetTapGuardEnabled) || now < sp.getLong(KEY_UNLOCK_UNTIL, 0L)
            if (!unlocked) {
                val armed = now - sp.getLong(KEY_ARMED_AT, 0L) <= 2_800L
                setVis(rv, guard = View.VISIBLE, ctrl = View.GONE, stop = View.GONE)
                rv.setTextViewText(R.id.widget_btn_guard, if (armed) "Tap Again to Unlock Controls" else "Locked • Tap 2x to Control")
            } else {
                val stopArmed = now - sp.getLong(KEY_STOP_ARMED, 0L) <= 3_500L
                setVis(rv, guard = View.GONE, ctrl = if (st.isActive) View.VISIBLE else View.GONE, stop = View.VISIBLE)
                rv.setTextViewText(R.id.widget_btn_pause, if (st.isPaused) "Resume" else "Pause")
                rv.setTextViewText(R.id.widget_btn_stop, if (!st.isActive) "Start Run" else if (stopArmed) "Confirm?" else "Finish")
            }
            rv.setOnClickPendingIntent(R.id.widget_btn_guard, actionPi(ctx, ACT_GUARD_TAP, 710))
            rv.setOnClickPendingIntent(R.id.widget_btn_pause, actionPi(ctx, ACT_PAUSE, 711))
            rv.setOnClickPendingIntent(R.id.widget_btn_next, actionPi(ctx, ACT_NEXT, 712))
            rv.setOnClickPendingIntent(R.id.widget_btn_stop, actionPi(ctx, ACT_STOP_CONFIRM, 713))
        }

        private fun setVis(rv: RemoteViews, guard: Int, ctrl: Int, stop: Int) {
            rv.setViewVisibility(R.id.widget_btn_guard, guard)
            rv.setViewVisibility(R.id.widget_btn_pause, ctrl)
            rv.setViewVisibility(R.id.widget_btn_next, ctrl)
            rv.setViewVisibility(R.id.widget_btn_stop, stop)
        }

        private fun actionPi(ctx: Context, act: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(ctx, code, Intent(ctx, LiveWorkoutWidgetProvider::class.java).setAction(act), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

package com.stridecoach.running.core.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.stridecoach.running.core.domain.engine.CoachingEvent
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.WorkoutStateMachine
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import java.util.UUID
import kotlin.math.roundToInt

class RunTrackingService : Service(), LocationListener {
    private var locMgr: LocationManager? = null
    private var coach: VoiceCoachEngine? = null
    private var wake: PowerManager.WakeLock? = null
    private var gps = GpsSignalProcessor()
    private var sm: WorkoutStateMachine? = null

    private var startMs = 0L
    private var paused = false
    private val splits = mutableListOf<RunSplit>()

    override fun onCreate() {
        super.onCreate()
        initChannel()
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        coach = VoiceCoachEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_NEXT_STEP -> nextStep()
            ACTION_STOP -> finish()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun begin() {
        val w = TrackingSessionBus.pendingWorkout
        val dayId = TrackingSessionBus.pendingPlanDayId
        startMs = System.currentTimeMillis()
        paused = false
        splits.clear()
        gps = GpsSignalProcessor()
        sm = WorkoutStateMachine(w)

        lockCpu()
        TrackingSessionBus.update { LiveTrackingState(true, false, workout = w, planDayId = dayId) }
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif("GPS ready", "0.00 km • 00:00"), fgsType)
        locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 2f, this)

        sm?.currentStep()?.let { first ->
            coach?.handleEvent(
                CoachingEvent.StepStarted(first),
                TrackingSessionBus.voiceEnabled,
                TrackingSessionBus.hapticEnabled
            )
        }
    }

    private fun togglePause() {
        paused = !paused
        TrackingSessionBus.update { it.copy(isPaused = paused) }
        pushNotif(if (paused) "Paused" else "Running", summary())
    }

    private fun nextStep() {
        val m = sm ?: return
        val ev = m.advanceManual(gps.totalDistanceMeters(), gps.activeElapsedMillis())
        emitEvents(ev)
        pushNotif(m.currentStep()?.displayName() ?: "Free Run", summary())
    }

    private fun finish() {
        locMgr?.removeUpdates(this)
        buildRun()?.let(TrackingSessionBus::emitCompletedRun)
        TrackingSessionBus.update { LiveTrackingState(false) }
        unlockCpu()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(loc: Location) {
        val fix = RawGpsFix(
            loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            loc.latitude,
            loc.longitude,
            if (loc.hasAltitude()) loc.altitude else null,
            loc.accuracy,
            if (loc.hasSpeed()) loc.speed else null
        )
        val idx = sm?.currentStep()?.index
        val (_, mv) = gps.process(fix, idx, paused, TrackingSessionBus.autoPauseEnabled)
        val pace = gps.rollingPaceSecPerKm()
        val res = sm?.onTick(gps.totalDistanceMeters(), gps.activeElapsedMillis(), pace)
        val snap = res?.first
        emitEvents(res?.second.orEmpty())

        TrackingSessionBus.update { it.copy(movementState = mv, snapshot = snap, trackPoints = gps.points()) }
        val title = snap?.activeStep?.displayName() ?: TrackingSessionBus.state.value.workout?.title ?: "Live Run"
        pushNotif(title, summary())
    }

    private fun emitEvents(list: List<CoachingEvent>) {
        for (ev in list) {
            if (ev is CoachingEvent.SplitCompleted) {
                splits.add(RunSplit(ev.kmNumber, 1_000, ev.splitDurationMillis))
            }
            coach?.handleEvent(ev, TrackingSessionBus.voiceEnabled, TrackingSessionBus.hapticEnabled)
        }
    }

    private fun buildRun(): RunRecord? {
        val pts = gps.points()
        val dist = gps.totalDistanceMeters().roundToInt()
        val dur = gps.activeElapsedMillis()
        if (dist < 20 && dur < 5_000L) return null

        val pace = if (dist > 0) ((dur / 1000.0) / (dist / 1000.0)).roundToInt() else 0
        val cals = ((dist / 1000.0) * 62.0).roundToInt()
        val poly = EncodedPolylineCodec.encode(pts.map { LatLon(it.latitude, it.longitude) })
        val st = TrackingSessionBus.state.value

        return RunRecord(
            id = "run_${UUID.randomUUID()}",
            startTimestampMillis = startMs,
            distanceMeters = dist,
            durationMillis = dur,
            paceSecondsPerKm = pace,
            caloriesBurned = cals,
            name = st.workout?.title ?: "Guided Run",
            description = "",
            encodedPolyline = poly,
            splits = splits.toList(),
            trackPoints = pts,
            workoutId = st.workout?.id,
            planDayId = st.planDayId,
            source = RunSource.RECORDED
        )
    }

    private fun summary(): String {
        val km = gps.totalDistanceMeters() / 1000.0
        val p = formatPace(gps.rollingPaceSecPerKm())
        val d = formatDuration(gps.activeElapsedMillis())
        return "%.2f km • %s • %s/km".format(km, d, p)
    }

    private fun notif(title: String, text: String): Notification {
        val lbl = if (paused) "Resume" else "Pause"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, lbl, actionPi(ACTION_PAUSE_RESUME, 1))
            .addAction(0, "Next", actionPi(ACTION_NEXT_STEP, 2))
            .addAction(0, "Stop", actionPi(ACTION_STOP, 3))
            .build()
    }

    private fun pushNotif(title: String, text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, notif(title, text))
    }

    private fun actionPi(act: String, code: Int): PendingIntent {
        val i = Intent(this, RunTrackingService::class.java).setAction(act)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun initChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "Active Run Coaching", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(ch)
    }

    private fun lockCpu() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StrideCoach::Gps").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun unlockCpu() {
        if (wake?.isHeld == true) wake?.release()
        wake = null
    }

    override fun onDestroy() {
        locMgr?.removeUpdates(this)
        coach?.shutdown()
        unlockCpu()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "stridecoach_tracking"
        const val NOTIF_ID = 4201
        const val ACTION_START = "com.stridecoach.running.action.START"
        const val ACTION_PAUSE_RESUME = "com.stridecoach.running.action.PAUSE_RESUME"
        const val ACTION_NEXT_STEP = "com.stridecoach.running.action.NEXT_STEP"
        const val ACTION_STOP = "com.stridecoach.running.action.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RunTrackingService::class.java).setAction(ACTION_START))
        }

        fun sendAction(ctx: Context, act: String) {
            ctx.startService(Intent(ctx, RunTrackingService::class.java).setAction(act))
        }
    }
}

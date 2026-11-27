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
import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.CoachingEvent
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.ImuKinematicsAnalyzer
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.TopographyPaceEngine
import com.stridecoach.running.core.domain.engine.WorkoutStateMachine
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

class RunTrackingService : Service(), LocationListener {
    private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    @Volatile
    private var lastMovingFixWallMs = 0L

    private var locMgr: LocationManager? = null
    private var coach: VoiceCoachEngine? = null
    private var steps: StepCounterTracker? = null
    private var imu: ImuMotionTracker? = null
    private var wake: PowerManager.WakeLock? = null
    private var gps = GpsSignalProcessor()
    private var sm: WorkoutStateMachine? = null

    private var startMs = 0L
    private var paused = false
    private var sessionKind = ActivityKind.RUNNING
    private val splits = mutableListOf<RunSplit>()
    private var notifGuardArmedAtMs = 0L
    private var notifUnlockedUntilMs = 0L
    private var notifStopArmedAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        initChannel()
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        coach = VoiceCoachEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_START -> begin(TrackingSessionBus.pendingActivityKind)
            ACTION_START_AUTO -> {
                val rawKind = intent.getStringExtra(EXTRA_ACTIVITY_KIND)
                val kind = runCatching { ActivityKind.valueOf(rawKind ?: "") }.getOrDefault(ActivityKind.RUNNING)
                begin(kind)
            }
            ACTION_NOTIF_UNLOCK_TAP -> {
                if (now - notifGuardArmedAtMs <= 3_000L) {
                    notifUnlockedUntilMs = now + 7_000L
                    notifGuardArmedAtMs = 0L
                    notifStopArmedAtMs = 0L
                } else {
                    notifGuardArmedAtMs = now
                }
                pushNotif(currentNotifTitle(), summary())
            }
            ACTION_PAUSE_RESUME -> {
                if (now < notifUnlockedUntilMs) notifUnlockedUntilMs = now + 7_000L
                togglePause()
            }
            ACTION_NEXT_STEP -> {
                if (now < notifUnlockedUntilMs) notifUnlockedUntilMs = now + 7_000L
                nextStep()
            }
            ACTION_NOTIF_STOP_CONFIRM -> {
                val guardOff = !TrackingSessionBus.widgetTapGuardEnabled || now < notifUnlockedUntilMs
                if (guardOff) {
                    if (now - notifStopArmedAtMs <= 3_500L) {
                        notifStopArmedAtMs = 0L
                        notifUnlockedUntilMs = 0L
                        finish()
                    } else {
                        notifStopArmedAtMs = now
                        notifUnlockedUntilMs = now + 7_000L
                        pushNotif(currentNotifTitle(), summary())
                    }
                }
            }
            ACTION_STOP -> finish()
        }
        return START_STICKY
    }

    private fun currentNotifTitle(): String = when {
        paused -> "Paused"
        else -> sm?.currentStep()?.displayName()
            ?: TrackingSessionBus.state.value.workout?.title
            ?: defaultTitle(sessionKind)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun begin(kind: ActivityKind) {
        val w = TrackingSessionBus.pendingWorkout
        val dayId = TrackingSessionBus.pendingPlanDayId
        sessionKind = kind
        startMs = System.currentTimeMillis()
        paused = false
        notifGuardArmedAtMs = 0L
        notifUnlockedUntilMs = 0L
        notifStopArmedAtMs = 0L
        splits.clear()
        gps = GpsSignalProcessor()
        sm = WorkoutStateMachine(w)
        steps = StepCounterTracker(this).also { it.start() }
        val useGyro = TrackingSessionBus.gyroEnabled
        val imuStarted = if (useGyro) ImuMotionTracker(this).also { imu = it }.start() else false

        lockCpu()
        TrackingSessionBus.update {
            LiveTrackingState(
                isActive = true,
                isPaused = false,
                workout = w,
                planDayId = dayId,
                activityKind = sessionKind,
                gyroActive = imuStarted
            )
        }
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif("GPS ready", "0.00 km • 00:00"), fgsType)
        locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 2f, this)
        TrackingSessionBus.widgetUpdater?.invoke(this)
        startTickerLoop()

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
        TrackingSessionBus.widgetUpdater?.invoke(this)
    }

    private fun nextStep() {
        val m = sm ?: return
        val ev = m.advanceManual(gps.totalDistanceMeters(), gps.activeElapsedMillis())
        emitEvents(ev)
        pushNotif(m.currentStep()?.displayName() ?: "Open Run", summary())
        TrackingSessionBus.widgetUpdater?.invoke(this)
    }

    private fun finish() {
        tickerJob?.cancel()
        tickerJob = null
        locMgr?.removeUpdates(this)
        steps?.stop()
        imu?.stop()
        buildRun()?.let(TrackingSessionBus::emitRun)
        TrackingSessionBus.update { LiveTrackingState(false) }
        TrackingSessionBus.widgetUpdater?.invoke(this)
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
        val stepCad = steps?.cadenceSpm?.value ?: 0
        val cad = if (stepCad > 0) stepCad else (imu?.imuCadenceSpm?.value ?: 0)
        val (_, mv) = gps.process(fix, idx, paused, TrackingSessionBus.autoPauseEnabled, cad.takeIf { it > 0 })
        val pts = gps.points()
        val gradePct = TopographyPaceEngine.computeRollingGradePct(pts)
        val pace = gps.rollingPaceSecPerKm()
        val res = sm?.onTick(
            gps.totalDistanceMeters(),
            gps.activeElapsedMillis(),
            pace,
            slopeGradePct = gradePct,
            slopeAdjustEnabled = TrackingSessionBus.slopeAdjustedPaceEnabled
        )
        val snap = res?.first
        emitEvents(res?.second.orEmpty())

        val lastPt = pts.lastOrNull()
        val fusedHeading = if (imu != null) {
            ImuKinematicsAnalyzer.fuseHeading(
                imu?.headingDeg?.value ?: 0f,
                lastPt?.bearingDegrees,
                lastPt?.speedMps ?: 0f
            )
        } else {
            lastPt?.bearingDegrees ?: 0f
        }
        val kind = ActivitySegmentClassifier.classifyInstant(lastPt?.speedMps, cad, paused || mv != MovementState.MOVING)
        val gain = ActivitySegmentClassifier.computeElevationGain(pts)
        val stability = imu?.strideStabilityPct?.value ?: 0
        val spdKmh = ((lastPt?.speedMps ?: 0f) * 3.6f).coerceAtLeast(0f)
        if ((lastPt?.speedMps ?: 0f) > 0.35f) lastMovingFixWallMs = System.currentTimeMillis()

        TrackingSessionBus.update {
            it.copy(
                movementState = mv,
                snapshot = snap,
                trackPoints = pts,
                steps = steps?.sessionSteps?.value ?: 0,
                cadenceSpm = cad,
                actType = kind,
                gpsAccM = fix.accuracyMeters,
                elevM = gain,
                headingDeg = fusedHeading,
                strideStabilityPct = stability,
                currentSpeedKmh = spdKmh,
                currentGradePct = gradePct.toFloat()
            )
        }
        val title = snap?.activeStep?.displayName() ?: TrackingSessionBus.state.value.workout?.title ?: defaultTitle(sessionKind)
        pushNotif(title, summary())
        TrackingSessionBus.widgetUpdater?.invoke(this)
    }

    private fun emitEvents(list: List<CoachingEvent>) {
        val cad = steps?.cadenceSpm?.value ?: 0
        for (ev in list) {
            if (ev is CoachingEvent.SplitCompleted) {
                splits.add(RunSplit(ev.kmNumber, 1_000, ev.splitDurationMillis, avgCadenceSpm = cad))
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
        val calFactor = if (sessionKind == ActivityKind.CYCLING) 32.0 else 62.0
        val cals = ((dist / 1000.0) * calFactor).roundToInt()
        val poly = EncodedPolylineCodec.encode(pts.map { LatLon(it.latitude, it.longitude) })
        val st = TrackingSessionBus.state.value
        val segs = ActivitySegmentClassifier.classify(pts)
        val topo = TopographyPaceEngine.analyzeTrack(pts, pace.takeIf { it > 0 } ?: 330)
        val gain = topo?.gainMeters ?: ActivitySegmentClassifier.computeElevationGain(pts)
        val totSteps = steps?.sessionSteps?.value ?: 0
        val avgCad = if (dur > 0L && totSteps > 0) ((totSteps / (dur / 60_000.0))).roundToInt() else 0

        return RunRecord(
            id = "run_${UUID.randomUUID()}",
            startTimestampMillis = startMs,
            distanceMeters = dist,
            durationMillis = dur,
            paceSecondsPerKm = pace,
            caloriesBurned = cals,
            name = st.workout?.title ?: defaultTitle(sessionKind),
            description = "",
            encodedPolyline = poly,
            splits = splits.toList(),
            trackPoints = pts,
            workoutId = st.workout?.id,
            planDayId = st.planDayId,
            source = RunSource.RECORDED,
            totalSteps = totSteps,
            avgCadenceSpm = avgCad,
            elevGainM = gain,
            segs = segs,
            activityKind = sessionKind,
            rawEncodedPolyline = poly,
            strideStabilityPct = imu?.strideStabilityPct?.value ?: 0,
            elevLossM = topo?.lossMeters ?: 0,
            minAltM = topo?.minAltMeters,
            maxAltM = topo?.maxAltMeters,
            gradeAdjustedPaceSec = topo?.gradeAdjustedPaceSec ?: 0
        )
    }

    private fun defaultTitle(k: ActivityKind): String = when (k) {
        ActivityKind.RUNNING -> "Guided Run"
        ActivityKind.WALKING -> "Outdoor Walk"
        ActivityKind.CYCLING -> "Outdoor Ride"
        ActivityKind.HIKING -> "Outdoor Hike"
        ActivityKind.OTHER -> "Outdoor Workout"
    }

    private fun summary(): String {
        val km = gps.totalDistanceMeters() / 1000.0
        val paceSec = gps.rollingPaceSecPerKm()
        val p = formatPace(paceSec)
        val d = formatDuration(gps.activeElapsedMillis())
        val spd = TrackingSessionBus.state.value.currentSpeedKmh
        val spdText = if (spd > 0.2f) "%.1f km/h".format(spd) else formatSpeedKmh(paceSec)
        return "%.2f km • %s • %s/km • %s".format(km, d, p, spdText)
    }

    private fun notif(title: String, text: String): Notification {
        val lbl = if (paused) "Resume" else "Pause"
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val openPi = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val now = System.currentTimeMillis()
        val guardActive = TrackingSessionBus.widgetTapGuardEnabled && now >= notifUnlockedUntilMs
        if (guardActive) {
            val armed = now - notifGuardArmedAtMs <= 3_000L
            builder.addAction(0, if (armed) "Tap Again to Unlock" else "Locked • Tap 2x", actionPi(ACTION_NOTIF_UNLOCK_TAP, 4))
        } else {
            val stopArmed = now - notifStopArmedAtMs <= 3_500L
            builder.addAction(0, lbl, actionPi(ACTION_PAUSE_RESUME, 1))
            builder.addAction(0, "Next", actionPi(ACTION_NEXT_STEP, 2))
            builder.addAction(0, if (stopArmed) "Confirm Stop?" else "Stop", actionPi(ACTION_NOTIF_STOP_CONFIRM, 3))
        }
        return builder.build()
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
        tickerJob?.cancel()
        svcScope.cancel()
        locMgr?.removeUpdates(this)
        steps?.stop()
        imu?.stop()
        coach?.shutdown()
        unlockCpu()
        super.onDestroy()
    }

    private fun startTickerLoop() {
        tickerJob?.cancel()
        tickerJob = svcScope.launch {
            while (isActive && TrackingSessionBus.state.value.isActive) {
                delay(1_000L)
                if (!paused) refreshLiveTick()
            }
        }
    }

    private fun refreshLiveTick() {
        val pace = gps.rollingPaceSecPerKm()
        val pts = gps.points()
        val gradePct = TopographyPaceEngine.computeRollingGradePct(pts)
        val res = sm?.onTick(
            gps.totalDistanceMeters(),
            gps.activeElapsedMillis(),
            pace,
            slopeGradePct = gradePct,
            slopeAdjustEnabled = TrackingSessionBus.slopeAdjustedPaceEnabled
        )
        val snap = res?.first ?: return
        val staleMoving = System.currentTimeMillis() - lastMovingFixWallMs > 4_500L
        val stepCad = steps?.cadenceSpm?.value ?: 0
        val cad = if (stepCad > 0) stepCad else (imu?.imuCadenceSpm?.value ?: 0)
        TrackingSessionBus.update { st ->
            st.copy(
                snapshot = snap,
                steps = steps?.sessionSteps?.value ?: st.steps,
                cadenceSpm = cad,
                currentSpeedKmh = if (staleMoving) 0f else st.currentSpeedKmh
            )
        }
        val title = snap.activeStep?.displayName()
            ?: TrackingSessionBus.state.value.workout?.title
            ?: defaultTitle(sessionKind)
        pushNotif(title, summary())
        TrackingSessionBus.widgetUpdater?.invoke(this)
    }

    companion object {
        const val CHANNEL = "stridecoach_tracking"
        const val NOTIF_ID = 4201
        const val ACTION_START = "com.stridecoach.running.action.START"
        const val ACTION_START_AUTO = "com.stridecoach.running.action.START_AUTO"
        const val ACTION_PAUSE_RESUME = "com.stridecoach.running.action.PAUSE_RESUME"
        const val ACTION_NEXT_STEP = "com.stridecoach.running.action.NEXT_STEP"
        const val ACTION_STOP = "com.stridecoach.running.action.STOP"
        const val ACTION_NOTIF_UNLOCK_TAP = "com.stridecoach.running.action.NOTIF_UNLOCK_TAP"
        const val ACTION_NOTIF_STOP_CONFIRM = "com.stridecoach.running.action.NOTIF_STOP_CONFIRM"
        const val EXTRA_ACTIVITY_KIND = "activity_kind"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RunTrackingService::class.java).setAction(ACTION_START))
        }

        fun startAuto(ctx: Context, kind: ActivityKind) {
            val i = Intent(ctx, RunTrackingService::class.java)
                .setAction(ACTION_START_AUTO)
                .putExtra(EXTRA_ACTIVITY_KIND, kind.name)
            ctx.startForegroundService(i)
        }

        fun sendAction(ctx: Context, act: String) {
            ctx.startService(Intent(ctx, RunTrackingService::class.java).setAction(act))
        }
    }
}

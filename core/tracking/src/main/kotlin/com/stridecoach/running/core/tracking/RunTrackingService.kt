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
import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier as SegClassifier
import com.stridecoach.running.core.domain.engine.CoachingEvent
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec as Codec
import com.stridecoach.running.core.domain.engine.ImuKinematicsAnalyzer as ImuMath
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.TopographyPaceEngine as TopoEngine
import com.stridecoach.running.core.domain.engine.WorkoutStateMachine as StateMachine
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import com.stridecoach.running.core.tracking.TrackingSessionBus as Bus
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    @Volatile private var lastMoveMs = 0L

    private var locMgr: LocationManager? = null
    private var coach: VoiceCoachEngine? = null
    private var steps: StepCounterTracker? = null
    private var imu: ImuMotionTracker? = null
    private var wake: PowerManager.WakeLock? = null
    private var gps = GpsSignalProcessor()
    private var sm: StateMachine? = null

    private var startMs = 0L
    private var paused = false
    private var kind = ActivityKind.RUNNING
    private val splits = mutableListOf<RunSplit>()
    private var armMs = 0L
    private var unlockMs = 0L
    private var stopArmMs = 0L

    override fun onCreate() {
        super.onCreate()
        initChannel()
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        coach = VoiceCoachEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_START -> begin(Bus.pendingActivityKind)
            ACTION_START_AUTO -> beginAuto(intent)
            ACTION_NOTIF_UNLOCK_TAP -> handleUnlockTap(now)
            ACTION_PAUSE_RESUME -> { if (now < unlockMs) unlockMs = now + 7_000L; togglePause() }
            ACTION_NEXT_STEP -> { if (now < unlockMs) unlockMs = now + 7_000L; nextStep() }
            ACTION_NOTIF_STOP_CONFIRM -> handleStopTap(now)
            ACTION_STOP -> finish()
        }
        return START_STICKY
    }

    private fun beginAuto(i: Intent) {
        val raw = i.getStringExtra(EXTRA_ACTIVITY_KIND)
        begin(runCatching { ActivityKind.valueOf(raw ?: "") }.getOrDefault(ActivityKind.RUNNING))
    }

    private fun handleUnlockTap(now: Long) {
        if (now - armMs <= 3_000L) { unlockMs = now + 7_000L; armMs = 0L; stopArmMs = 0L } else armMs = now
        pushNotif(notifTitle(), summary())
    }

    private fun handleStopTap(now: Long) {
        if (Bus.widgetTapGuardEnabled && now >= unlockMs) return
        if (now - stopArmMs <= 3_500L) { stopArmMs = 0L; unlockMs = 0L; finish(); return }
        stopArmMs = now
        unlockMs = now + 7_000L
        pushNotif(notifTitle(), summary())
    }

    private fun notifTitle(): String = if (paused) "Paused" else sm?.currentStep()?.displayName() ?: Bus.state.value.workout?.title ?: titleFor(kind)

    override fun onBind(i: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun begin(actKind: ActivityKind) {
        val w = Bus.pendingWorkout
        kind = actKind; startMs = System.currentTimeMillis(); paused = false
        armMs = 0L; unlockMs = 0L; stopArmMs = 0L
        splits.clear(); gps = GpsSignalProcessor(); sm = StateMachine(w)
        steps = StepCounterTracker(this).also { it.start() }
        val imuOn = if (Bus.gyroEnabled) ImuMotionTracker(this).also { imu = it }.start() else false

        lockCpu()
        Bus.update { LiveTrackingState(isActive = true, workout = w, planDayId = Bus.pendingPlanDayId, activityKind = kind, gyroActive = imuOn) }
        val fgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif("GPS ready", "0.00 km • 00:00"), fgs)
        locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 2f, this)
        Bus.widgetUpdater?.invoke(this)
        startTicker()
        sm?.currentStep()?.let { s -> coach?.handleEvent(CoachingEvent.StepStarted(s), Bus.voiceEnabled, Bus.hapticEnabled) }
    }

    private fun togglePause() {
        paused = !paused
        Bus.update { it.copy(isPaused = paused) }
        pushNotif(if (paused) "Paused" else "Running", summary())
        Bus.widgetUpdater?.invoke(this)
    }

    private fun nextStep() {
        val m = sm ?: return
        emitEvents(m.advanceManual(gps.totalDistanceMeters(), gps.activeElapsedMillis()))
        pushNotif(m.currentStep()?.displayName() ?: "Open Run", summary())
        Bus.widgetUpdater?.invoke(this)
    }

    private fun finish() {
        tickJob?.cancel(); tickJob = null
        locMgr?.removeUpdates(this); steps?.stop(); imu?.stop()
        buildRun()?.let(Bus::emitRun)
        Bus.update { LiveTrackingState(false) }
        Bus.widgetUpdater?.invoke(this)
        unlockCpu(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onLocationChanged(loc: Location) {
        val fix = RawGpsFix(
            loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(), loc.latitude, loc.longitude,
            if (loc.hasAltitude()) loc.altitude else null, loc.accuracy, if (loc.hasSpeed()) loc.speed else null
        )
        val cad = liveCadence()
        val (_, mv) = gps.process(fix, sm?.currentStep()?.index, paused, Bus.autoPauseEnabled, cad.takeIf { it > 0 })
        val pts = gps.points()
        val grade = TopoEngine.computeRollingGradePct(pts)
        val res = sm?.onTick(gps.totalDistanceMeters(), gps.activeElapsedMillis(), gps.rollingPaceSecPerKm(), grade, Bus.slopeAdjustedPaceEnabled)
        emitEvents(res?.second.orEmpty())
        publishLocation(fix, pts, mv, res?.first, grade, cad)
    }

    private fun publishLocation(
        fix: RawGpsFix,
        pts: List<TrackPoint>,
        mv: MovementState,
        snap: com.stridecoach.running.core.domain.engine.LiveWorkoutSnapshot?,
        grade: Double,
        cad: Int
    ) {
        val last = pts.lastOrNull()
        val brg = if (imu != null) ImuMath.fuseHeading(imu?.headingDeg?.value ?: 0f, last?.bearingDegrees, last?.speedMps ?: 0f) else (last?.bearingDegrees ?: 0f)
        val act = SegClassifier.classifyInstant(last?.speedMps, cad, paused || mv != MovementState.MOVING)
        val spd = ((last?.speedMps ?: 0f) * 3.6f).coerceAtLeast(0f)
        if ((last?.speedMps ?: 0f) > 0.35f) lastMoveMs = System.currentTimeMillis()
        Bus.update {
            it.copy(
                movementState = mv, snapshot = snap, trackPoints = pts, steps = steps?.sessionSteps?.value ?: 0,
                cadenceSpm = cad, actType = act, gpsAccM = fix.accuracyMeters, elevM = SegClassifier.computeElevationGain(pts),
                headingDeg = brg, strideStabilityPct = imu?.strideStabilityPct?.value ?: 0, currentSpeedKmh = spd, currentGradePct = grade.toFloat()
            )
        }
        pushNotif(snap?.activeStep?.displayName() ?: Bus.state.value.workout?.title ?: titleFor(kind), summary())
        Bus.widgetUpdater?.invoke(this)
    }

    private fun liveCadence(): Int = (steps?.cadenceSpm?.value ?: 0).takeIf { it > 0 } ?: (imu?.imuCadenceSpm?.value ?: 0)

    private fun emitEvents(list: List<CoachingEvent>) {
        val cad = steps?.cadenceSpm?.value ?: 0
        for (ev in list) {
            if (ev is CoachingEvent.SplitCompleted) splits.add(RunSplit(ev.kmNumber, 1_000, ev.splitDurationMillis, avgCadenceSpm = cad))
            coach?.handleEvent(ev, Bus.voiceEnabled, Bus.hapticEnabled)
        }
    }

    private fun buildRun(): RunRecord? {
        val pts = gps.points()
        val dist = gps.totalDistanceMeters().roundToInt()
        val dur = gps.activeElapsedMillis()
        if (dist < 20 && dur < 5_000L) return null
        val pace = if (dist > 0) ((dur / 1000.0) / (dist / 1000.0)).roundToInt() else 0
        val kcal = ((dist / 1000.0) * if (kind == ActivityKind.CYCLING) 32.0 else 62.0).roundToInt()
        val poly = Codec.encode(pts.map { LatLon(it.latitude, it.longitude) })
        val st = Bus.state.value
        val topo = TopoEngine.analyzeTrack(pts, pace.takeIf { it > 0 } ?: 330)
        val totSteps = steps?.sessionSteps?.value ?: 0
        val avgCad = if (dur > 0L && totSteps > 0) (totSteps / (dur / 60_000.0)).roundToInt() else 0
        return RunRecord(
            id = "run_${UUID.randomUUID()}", startTimestampMillis = startMs, distanceMeters = dist, durationMillis = dur,
            paceSecondsPerKm = pace, caloriesBurned = kcal, name = st.workout?.title ?: titleFor(kind), description = "",
            encodedPolyline = poly, splits = splits.toList(), trackPoints = pts, workoutId = st.workout?.id,
            planDayId = st.planDayId, source = RunSource.RECORDED, totalSteps = totSteps, avgCadenceSpm = avgCad,
            elevGainM = topo?.gainMeters ?: SegClassifier.computeElevationGain(pts), segs = SegClassifier.classify(pts),
            activityKind = kind, rawEncodedPolyline = poly, strideStabilityPct = imu?.strideStabilityPct?.value ?: 0,
            elevLossM = topo?.lossMeters ?: 0, minAltM = topo?.minAltMeters, maxAltM = topo?.maxAltMeters, gradeAdjustedPaceSec = topo?.gradeAdjustedPaceSec ?: 0
        )
    }

    private fun titleFor(k: ActivityKind): String = when (k) {
        ActivityKind.RUNNING -> "Guided Run"
        ActivityKind.WALKING -> "Outdoor Walk"
        ActivityKind.CYCLING -> "Outdoor Ride"
        ActivityKind.HIKING -> "Outdoor Hike"
        ActivityKind.OTHER -> "Outdoor Workout"
    }

    private fun summary(): String {
        val pace = gps.rollingPaceSecPerKm()
        val spd = Bus.state.value.currentSpeedKmh
        val spdTxt = if (spd > 0.2f) "%.1f km/h".format(spd) else formatSpeedKmh(pace)
        return "%.2f km • %s • %s/km • %s".format(gps.totalDistanceMeters() / 1000.0, formatDuration(gps.activeElapsedMillis()), formatPace(pace), spdTxt)
    }

    private fun notif(title: String, text: String): Notification {
        val pi = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(pi).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        addNotifActions(b)
        return b.build()
    }

    private fun addNotifActions(b: NotificationCompat.Builder) {
        val now = System.currentTimeMillis()
        if (Bus.widgetTapGuardEnabled && now >= unlockMs) {
            val armed = now - armMs <= 3_000L
            b.addAction(0, if (armed) "Tap Again to Unlock" else "Locked • Tap 2x", actionPi(ACTION_NOTIF_UNLOCK_TAP, 4))
            return
        }
        val stopArmed = now - stopArmMs <= 3_500L
        b.addAction(0, if (paused) "Resume" else "Pause", actionPi(ACTION_PAUSE_RESUME, 1))
        b.addAction(0, "Next", actionPi(ACTION_NEXT_STEP, 2))
        b.addAction(0, if (stopArmed) "Confirm Stop?" else "Stop", actionPi(ACTION_NOTIF_STOP_CONFIRM, 3))
    }

    private fun pushNotif(title: String, text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif(title, text))
    }

    private fun actionPi(act: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, RunTrackingService::class.java).setAction(act), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun initChannel() {
        val ch = NotificationChannel(CHANNEL, "Active Run Coaching", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun lockCpu() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StrideCoach::Gps").apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun unlockCpu() { if (wake?.isHeld == true) wake?.release(); wake = null }

    override fun onDestroy() {
        tickJob?.cancel(); scope.cancel(); locMgr?.removeUpdates(this)
        steps?.stop(); imu?.stop(); coach?.shutdown(); unlockCpu()
        super.onDestroy()
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && Bus.state.value.isActive) { delay(1_000L); if (!paused) refreshLiveTick() }
        }
    }

    private fun refreshLiveTick() {
        val pts = gps.points()
        val grade = TopoEngine.computeRollingGradePct(pts)
        val snap = sm?.onTick(gps.totalDistanceMeters(), gps.activeElapsedMillis(), gps.rollingPaceSecPerKm(), grade, Bus.slopeAdjustedPaceEnabled)?.first ?: return
        val stale = System.currentTimeMillis() - lastMoveMs > 4_500L
        val cad = liveCadence()
        Bus.update { st ->
            st.copy(snapshot = snap, steps = steps?.sessionSteps?.value ?: st.steps, cadenceSpm = cad, currentSpeedKmh = if (stale) 0f else st.currentSpeedKmh)
        }
        pushNotif(snap.activeStep?.displayName() ?: Bus.state.value.workout?.title ?: titleFor(kind), summary())
        Bus.widgetUpdater?.invoke(this)
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

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, RunTrackingService::class.java).setAction(ACTION_START)) }

        fun startAuto(ctx: Context, kind: ActivityKind) {
            ctx.startForegroundService(Intent(ctx, RunTrackingService::class.java).setAction(ACTION_START_AUTO).putExtra(EXTRA_ACTIVITY_KIND, kind.name))
        }

        fun sendAction(ctx: Context, act: String) { ctx.startService(Intent(ctx, RunTrackingService::class.java).setAction(act)) }
    }
}

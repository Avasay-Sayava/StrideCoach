package com.stridecoach.running.core.tracking

import android.content.Context
import com.stridecoach.running.core.domain.engine.LiveWorkoutSnapshot
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrackPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveTrackingState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val movementState: MovementState = MovementState.MOVING,
    val workout: StructuredWorkout? = null,
    val planDayId: String? = null,
    val snapshot: LiveWorkoutSnapshot? = null,
    val trackPoints: List<TrackPoint> = emptyList(),
    val lastCueMessage: String? = null,
    val steps: Int = 0,
    val cadenceSpm: Int = 0,
    val actType: ActivitySegmentType = ActivitySegmentType.RUNNING,
    val gpsAccM: Float? = null,
    val elevM: Int = 0,
    val activityKind: ActivityKind = ActivityKind.RUNNING,
    val headingDeg: Float = 0f,
    val strideStabilityPct: Int = 0,
    val gyroActive: Boolean = false,
    val currentSpeedKmh: Float = 0f,
    val currentGradePct: Float = 0f
) {
    val currentPaceStatus: PaceStatus
        get() = snapshot?.paceStatus ?: PaceStatus.NO_SIGNAL
}

object TrackingSessionBus {
    private val st = MutableStateFlow(LiveTrackingState())
    val state: StateFlow<LiveTrackingState> = st.asStateFlow()

    private val done = MutableSharedFlow<RunRecord>(extraBufferCapacity = 4)
    val completedRuns: SharedFlow<RunRecord> = done.asSharedFlow()

    @Volatile
    var pendingWorkout: StructuredWorkout? = null
    @Volatile
    var pendingPlanDayId: String? = null
    @Volatile
    var pendingActivityKind: ActivityKind = ActivityKind.RUNNING
    @Volatile
    var voiceEnabled: Boolean = true
    @Volatile
    var hapticEnabled: Boolean = true
    @Volatile
    var autoPauseEnabled: Boolean = true
    @Volatile
    var gyroEnabled: Boolean = true
    @Volatile
    var slopeAdjustedPaceEnabled: Boolean = true
    @Volatile
    var widgetTapGuardEnabled: Boolean = true
    @Volatile
    var widgetUpdater: ((Context) -> Unit)? = null

    internal fun update(fn: (LiveTrackingState) -> LiveTrackingState) {
        val c = st.value
        val n = fn(c)
        if (n != c) st.value = n
    }

    internal fun emitRun(r: RunRecord) {
        done.tryEmit(r)
    }

    fun isLive(): Boolean = st.value.isActive
    fun isPaused(): Boolean = st.value.isPaused
    fun pts(): List<TrackPoint> = st.value.trackPoints
    fun reset() {
        st.value = LiveTrackingState()
    }
}

package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.ExecutableStep
import com.stridecoach.running.core.domain.model.PaceBounds
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.RacePhase
import com.stridecoach.running.core.domain.model.SegmentTarget
import com.stridecoach.running.core.domain.model.StructuredWorkout

sealed interface CoachingEvent {
    data class StepStarted(val step: ExecutableStep) : CoachingEvent
    data class PaceAlert(val status: PaceStatus, val currentPaceSec: Int, val bounds: PaceBounds) : CoachingEvent
    data class SplitCompleted(val kmNumber: Int, val splitDurationMillis: Long) : CoachingEvent
    data object WorkoutCompleted : CoachingEvent
}

data class LiveWorkoutSnapshot(
    val activeStepIndex: Int,
    val activeStep: ExecutableStep?,
    val stepRemainingMeters: Int?,
    val stepRemainingSeconds: Int?,
    val totalDistanceMeters: Double,
    val totalElapsedMillis: Long,
    val currentPaceSecPerKm: Int,
    val paceStatus: PaceStatus,
    val activeRacePhase: RacePhase?,
    val projectedFinishMillis: Long?,
    val isFinished: Boolean
)

class WorkoutStateMachine(
    workout: StructuredWorkout?,
    private val alertCooldownMs: Long = 20_000L
) {
    private val steps: List<ExecutableStep> = workout?.flattenSteps().orEmpty()
    private val phases: List<RacePhase> = workout?.racePhases.orEmpty()

    private var idx = 0
    private var stepDist0 = 0.0
    private var stepTime0 = 0L
    private var splitKm = 0
    private var splitMs = 0L
    private var lastAlertMs = -alertCooldownMs
    private var done = false

    fun currentStep(): ExecutableStep? = steps.getOrNull(idx)

    fun advanceManual(dist: Double, ms: Long): List<CoachingEvent> {
        if (done || steps.isEmpty()) return emptyList()
        return nextStep(dist, ms)
    }

    fun onTick(
        totalDistanceMeters: Double,
        totalElapsedMillis: Long,
        rollingPaceSecPerKm: Int,
        slopeGradePct: Double = 0.0,
        slopeAdjustEnabled: Boolean = false
    ): Pair<LiveWorkoutSnapshot, List<CoachingEvent>> {
        val d = totalDistanceMeters
        val t = totalElapsedMillis
        val p = rollingPaceSecPerKm
        val ev = mutableListOf<CoachingEvent>()
        checkSplit(d, t, ev)

        val cur = currentStep()
        if (cur != null && !done && isDone(cur, d, t)) {
            ev.addAll(nextStep(d, t))
        }

        val act = currentStep()
        val ph = findPhase(d)
        val rawBounds = ph?.paceBounds ?: act?.step?.paceBounds
        val bnd = if (rawBounds != null && slopeAdjustEnabled && kotlin.math.abs(slopeGradePct) >= 1.0) {
            PaceBounds(
                TopographyPaceEngine.adjustTargetPaceForGrade(rawBounds.fastSecPerKm, slopeGradePct),
                TopographyPaceEngine.adjustTargetPaceForGrade(rawBounds.slowSecPerKm, slopeGradePct)
            )
        } else {
            rawBounds
        }
        val st = bnd?.evaluate(p) ?: PaceStatus.NO_SIGNAL

        checkPaceAlert(st, p, bnd, t, ev)
        return Pair(snapshot(act, d, t, p, st, ph), ev)
    }

    private fun checkSplit(d: Double, t: Long, ev: MutableList<CoachingEvent>) {
        val km = (d / 1000.0).toInt()
        if (km > splitKm) {
            val dur = t - splitMs
            splitKm = km
            splitMs = t
            ev.add(CoachingEvent.SplitCompleted(km, dur))
        }
    }

    private fun isDone(s: ExecutableStep, d: Double, t: Long): Boolean {
        return when (val tgt = s.step.target) {
            is SegmentTarget.Distance -> (d - stepDist0) >= tgt.meters
            is SegmentTarget.Duration -> (t - stepTime0) >= tgt.seconds * 1000L
            SegmentTarget.Open -> false
        }
    }

    private fun nextStep(d: Double, t: Long): List<CoachingEvent> {
        idx++
        stepDist0 = d
        stepTime0 = t
        val next = currentStep()
        if (next == null) {
            done = true
            return listOf(CoachingEvent.WorkoutCompleted)
        }
        return listOf(CoachingEvent.StepStarted(next))
    }

    private fun checkPaceAlert(
        st: PaceStatus,
        p: Int,
        bnd: PaceBounds?,
        t: Long,
        ev: MutableList<CoachingEvent>
    ) {
        if (bnd == null) return
        if (st != PaceStatus.TOO_FAST && st != PaceStatus.TOO_SLOW) return
        if ((t - lastAlertMs) < alertCooldownMs) return
        lastAlertMs = t
        ev.add(CoachingEvent.PaceAlert(st, p, bnd))
    }

    private fun findPhase(d: Double): RacePhase? {
        if (phases.isEmpty()) return null
        val m = d.toInt()
        return phases.firstOrNull { m in it.startMeters until it.endMeters } ?: phases.last()
    }

    private fun snapshot(
        act: ExecutableStep?,
        d: Double,
        t: Long,
        p: Int,
        st: PaceStatus,
        ph: RacePhase?
    ): LiveWorkoutSnapshot {
        val remM = if (act != null && !done) remMeters(act, d) else null
        val remS = if (act != null && !done) remSecs(act, t) else null
        val proj = projectFinish(d, t, p)
        return LiveWorkoutSnapshot(idx, act, remM, remS, d, t, p, st, ph, proj, done)
    }

    private fun remMeters(act: ExecutableStep, d: Double): Int? {
        val tgt = act.step.target as? SegmentTarget.Distance ?: return null
        return (tgt.meters - (d - stepDist0)).toInt().coerceAtLeast(0)
    }

    private fun remSecs(act: ExecutableStep, t: Long): Int? {
        val tgt = act.step.target as? SegmentTarget.Duration ?: return null
        val el = ((t - stepTime0) / 1000L).toInt()
        return (tgt.seconds - el).coerceAtLeast(0)
    }

    private fun projectFinish(d: Double, t: Long, p: Int): Long? {
        if (phases.isEmpty() || p <= 0) return null
        val end = phases.last().endMeters.toDouble()
        val remKm = ((end - d).coerceAtLeast(0.0)) / 1000.0
        return t + (remKm * p * 1000.0).toLong()
    }
}

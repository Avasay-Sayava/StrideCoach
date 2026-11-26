package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.PlanDayStatus
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.SegmentTarget
import com.stridecoach.running.core.domain.model.StepKind
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkingLoadRule
import com.stridecoach.running.core.domain.model.WorkoutSegment

data class WalkingLoadAlert(
    val triggerWalkMeters: Int,
    val triggerDayLabel: String,
    val downgradedWorkout: StructuredWorkout,
    val canSwapWithTomorrow: Boolean
)

class WalkingLoadRegulator {
    fun evaluate(
        today: PlannedDay,
        tomorrow: PlannedDay?,
        todayWalk: WalkLog?,
        yestWalk: WalkLog?,
        rule: WalkingLoadRule
    ): WalkingLoadAlert? {
        val w = today.workout ?: return null
        val fast = w.fastestTargetPaceSec() ?: return null
        if (!rule.isFastPace(fast)) return null

        val d0 = todayWalk?.distanceMeters ?: 0
        val d1 = yestWalk?.distanceMeters ?: 0
        val peak = maxOf(d0, d1)
        if (!rule.isHeavyWalk(peak)) return null

        val day = if (d0 >= d1) "today" else "yesterday"
        val km = rule.downgradeDistanceMeters / 1000
        val easy = StructuredWorkout(
            id = "${w.id}_easy",
            title = "Easy Recovery Run ($km km)",
            description = "Auto-regulated due to heavy walking load.",
            segments = listOf(
                WorkoutSegment.AtomicStep(
                    label = "Easy Aerobic Run",
                    kind = StepKind.RUN,
                    target = SegmentTarget.Distance(rule.downgradeDistanceMeters),
                    paceBounds = rule.downgradePaceBounds
                )
            )
        )
        val swap = tomorrow != null &&
            (tomorrow.dayRole == DayRole.FULL_REST || tomorrow.dayRole == DayRole.WALK_ONLY)

        return WalkingLoadAlert(peak, day, easy, swap)
    }

    fun applyDowngrade(today: PlannedDay, alert: WalkingLoadAlert): PlannedDay = today.copy(
        dayRole = DayRole.EASY_RUN,
        workout = alert.downgradedWorkout,
        status = PlanDayStatus.ADJUSTED
    )

    fun applySwapWithTomorrow(today: PlannedDay, next: PlannedDay): Pair<PlannedDay, PlannedDay> {
        val a = today.copy(dayRole = next.dayRole, workout = next.workout, status = PlanDayStatus.ADJUSTED)
        val b = next.copy(dayRole = today.dayRole, workout = today.workout, status = PlanDayStatus.ADJUSTED)
        return Pair(a, b)
    }
}

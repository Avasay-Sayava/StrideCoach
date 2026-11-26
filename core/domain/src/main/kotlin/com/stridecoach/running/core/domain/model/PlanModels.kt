package com.stridecoach.running.core.domain.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek

@Serializable
enum class GoalDistance(val label: String, val meters: Int) {
    FIVE_K("5K", 5_000),
    TEN_K("10K", 10_000),
    HALF_MARATHON("Half Marathon", 21_097),
    MARATHON("Marathon", 42_195),
    BASE_MAINTENANCE("Base Building", 0)
}

@Serializable
enum class DayRole(val label: String) {
    LONG_RUN("Long Run"),
    FAST_WORKOUT("Speed / Tempo"),
    EASY_RUN("Easy Run"),
    FULL_REST("Full Rest"),
    WALK_ONLY("Walk-Only Recovery"),
    CROSS_TRAIN("Cross-Train"),
    RACE_DAY("Race Day")
}

@Serializable
enum class PlanDayStatus {
    PLANNED,
    COMPLETED,
    ADJUSTED,
    SKIPPED
}

@Serializable
data class WalkingLoadRule(
    val walkThresholdMeters: Int = 7_000,
    val fastPaceThresholdSecPerKm: Int = 320,
    val downgradeDistanceMeters: Int = 5_000,
    val downgradePaceBounds: PaceBounds = PaceBounds(360, 380)
) {
    init {
        require(walkThresholdMeters > 0) { "cap > 0" }
        require(downgradeDistanceMeters > 0) { "dist > 0" }
    }

    fun isHeavyWalk(m: Int): Boolean = m > walkThresholdMeters
    fun isFastPace(s: Int): Boolean = s < fastPaceThresholdSecPerKm
}

@Serializable
data class TrainingPlanConfig(
    val id: String,
    val name: String,
    val goalDistance: GoalDistance,
    val startEpochDay: Long,
    val raceEpochDay: Long,
    val targetPaceSecPerKm: Int,
    val easyPaceBounds: PaceBounds,
    val startLongRunMeters: Int,
    val peakLongRunMeters: Int,
    val cutbackEveryWeeks: Int = 4,
    val taperWeeks: Int = 2,
    val benchmarkWeekNumber: Int? = 7,
    val weeklyTemplate: Map<DayOfWeek, DayRole>,
    val walkingLoadRule: WalkingLoadRule = WalkingLoadRule(),
    val racePhases: List<RacePhase> = emptyList()
) {
    init {
        val d0 = startEpochDay
        val d1 = raceEpochDay
        val s0 = startLongRunMeters
        val s1 = peakLongRunMeters
        require(d1 >= d0) { "race >= start" }
        require(targetPaceSecPerKm > 0) { "pace > 0" }
        require(s1 >= s0) { "peak >= start" }
    }

    fun totalDays(): Int = (raceEpochDay - startEpochDay).toInt() + 1
    fun weeks(): Int = ((totalDays() - 1) / 7) + 1
}

@Serializable
data class PlannedDay(
    val id: String,
    val planId: String,
    val dateEpochDay: Long,
    val weekNumber: Int,
    val dayRole: DayRole,
    val isCutbackWeek: Boolean,
    val isTaperWeek: Boolean,
    val isBenchmarkDay: Boolean,
    val workout: StructuredWorkout?,
    val status: PlanDayStatus = PlanDayStatus.PLANNED,
    val linkedRunId: String? = null
)

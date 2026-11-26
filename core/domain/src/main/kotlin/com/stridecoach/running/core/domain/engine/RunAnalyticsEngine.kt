package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.WalkLog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

enum class SplitDisciplineFlag {
    EARLY_SURGE,
    ON_TARGET,
    LATE_FADE
}

data class SplitEvaluation(
    val split: RunSplit,
    val paceSecPerKm: Int,
    val deltaFromTargetSec: Int,
    val flag: SplitDisciplineFlag
)

data class WeeklyLoadPoint(
    val weekLabel: String,
    val runMeters: Int,
    val walkMeters: Int
) {
    val totalMeters: Int get() = runMeters + walkMeters
}

data class PersonalRecord(
    val label: String,
    val targetMeters: Int,
    val bestDurationMillis: Long,
    val runId: String
)

class RunAnalyticsEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    fun evaluateSplitDiscipline(
        splits: List<RunSplit>,
        targetPaceSecPerKm: Int,
        toleranceSec: Int = 8
    ): List<SplitEvaluation> {
        if (splits.isEmpty() || targetPaceSecPerKm <= 0) return emptyList()
        return splits.map { split ->
            val pace = split.paceSecPerKm()
            val delta = pace - targetPaceSecPerKm
            val flag = when {
                delta < -toleranceSec -> SplitDisciplineFlag.EARLY_SURGE
                delta > toleranceSec -> SplitDisciplineFlag.LATE_FADE
                else -> SplitDisciplineFlag.ON_TARGET
            }
            SplitEvaluation(split, pace, delta, flag)
        }
    }

    fun computeWeeklyCombinedLoad(
        runs: List<RunRecord>,
        walks: List<WalkLog>,
        maxWeeks: Int = 12
    ): List<WeeklyLoadPoint> {
        val runByWeek = mutableMapOf<String, Int>()
        val walkByWeek = mutableMapOf<String, Int>()

        for (run in runs) {
            val date = Instant.ofEpochMilli(run.startTimestampMillis).atZone(zoneId).toLocalDate()
            val key = weekKey(date)
            runByWeek[key] = (runByWeek[key] ?: 0) + run.distanceMeters
        }
        for (walk in walks) {
            val key = weekKey(LocalDate.ofEpochDay(walk.dateEpochDay))
            walkByWeek[key] = (walkByWeek[key] ?: 0) + walk.distanceMeters
        }

        val keys = (runByWeek.keys + walkByWeek.keys).sorted().takeLast(maxWeeks)
        return keys.map { key ->
            WeeklyLoadPoint(
                weekLabel = key,
                runMeters = runByWeek[key] ?: 0,
                walkMeters = walkByWeek[key] ?: 0
            )
        }
    }

    fun computeWeekdayWalkingHeatmap(walks: List<WalkLog>): Map<DayOfWeek, Int> {
        val sums = mutableMapOf<DayOfWeek, MutableList<Int>>()
        for (walk in walks) {
            val dow = LocalDate.ofEpochDay(walk.dateEpochDay).dayOfWeek
            sums.getOrPut(dow) { mutableListOf() }.add(walk.distanceMeters)
        }
        return DayOfWeek.entries.associateWith { dow ->
            val values = sums[dow]
            if (values.isNullOrEmpty()) 0 else values.average().toInt()
        }
    }

    fun computePersonalRecords(runs: List<RunRecord>): List<PersonalRecord> {
        val benchmarks = listOf(
            "1K" to 1_000,
            "2K" to 2_000,
            "5K" to 5_000,
            "10K" to 10_000,
            "Half Marathon" to 21_097
        )
        return benchmarks.mapNotNull { (label, meters) ->
            val eligible = runs.filter { it.distanceMeters >= (meters * 0.98) && it.durationMillis > 0L }
            val best = eligible.minByOrNull { run ->
                (run.durationMillis.toDouble() / run.distanceMeters) * meters
            } ?: return@mapNotNull null
            val scaledMillis = ((best.durationMillis.toDouble() / best.distanceMeters) * meters).toLong()
            PersonalRecord(label, meters, scaledMillis, best.id)
        }
    }

    private fun weekKey(date: LocalDate): String {
        val wf = WeekFields.ISO
        val week = date.get(wf.weekOfWeekBasedYear())
        val year = date.get(wf.weekBasedYear())
        return "%d-W%02d".format(year, week)
    }
}

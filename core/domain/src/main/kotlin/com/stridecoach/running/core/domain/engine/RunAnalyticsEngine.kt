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

data class DailyLoadBar(
    val dayLabel: String,
    val runMeters: Int,
    val walkMeters: Int
)

data class PaceZoneBucket(
    val zone: Int,
    val label: String,
    val percent: Int,
    val durationMillis: Long
)

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
        val runMap = mutableMapOf<String, Int>()
        val walkMap = mutableMapOf<String, Int>()
        for (run in runs) {
            val d = Instant.ofEpochMilli(run.startTimestampMillis).atZone(zoneId).toLocalDate()
            val k = weekKey(d)
            runMap[k] = (runMap[k] ?: 0) + run.distanceMeters
        }
        for (walk in walks) {
            val k = weekKey(LocalDate.ofEpochDay(walk.dateEpochDay))
            walkMap[k] = (walkMap[k] ?: 0) + walk.distanceMeters
        }
        val keys = (runMap.keys + walkMap.keys).sorted().takeLast(maxWeeks)
        return keys.map { k -> WeeklyLoadPoint(k, runMap[k] ?: 0, walkMap[k] ?: 0) }
    }

    fun computeLast7DaysLoad(
        runs: List<RunRecord>,
        walks: List<WalkLog>,
        todayEpoch: Long = LocalDate.now(zoneId).toEpochDay()
    ): List<DailyLoadBar> {
        val runByDay = runs.groupBy {
            Instant.ofEpochMilli(it.startTimestampMillis).atZone(zoneId).toLocalDate().toEpochDay()
        }.mapValues { (_, list) -> list.sumOf { it.distanceMeters } }
        val walkByDay = walks.associate { it.dateEpochDay to it.distanceMeters }

        val startDay = todayEpoch - 6L
        return (0..6).map { offset ->
            val day = startDay + offset
            val lbl = LocalDate.ofEpochDay(day).dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            DailyLoadBar(lbl, runByDay[day] ?: 0, walkByDay[day] ?: 0)
        }
    }

    fun computePaceZoneDistribution(
        runs: List<RunRecord>,
        refPaceSec: Int
    ): List<PaceZoneBucket> {
        val ref = refPaceSec.coerceIn(180, 540)
        val sums = LongArray(5)
        for (run in runs) addRunToZones(run, ref, sums)
        val total = sums.sum().coerceAtLeast(1L)
        val labels = listOf("Easy", "Aerobic", "Tempo", "Threshold", "Peak")
        return (0..4).map { idx ->
            val pct = ((sums[idx] * 100.0) / total).toInt()
            PaceZoneBucket(idx + 1, labels[idx], pct, sums[idx])
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
        val marks = listOf(
            "1K" to 1_000,
            "2K" to 2_000,
            "5K" to 5_000,
            "10K" to 10_000,
            "Half Marathon" to 21_097
        )
        return marks.mapNotNull { (label, meters) ->
            val eligible = runs.filter { it.distanceMeters >= (meters * 0.98) && it.durationMillis > 0L }
            val best = eligible.minByOrNull { (it.durationMillis.toDouble() / it.distanceMeters) * meters }
                ?: return@mapNotNull null
            val ms = ((best.durationMillis.toDouble() / best.distanceMeters) * meters).toLong()
            PersonalRecord(label, meters, ms, best.id)
        }
    }

    private fun addRunToZones(run: RunRecord, ref: Int, sums: LongArray) {
        if (run.splits.isNotEmpty()) {
            for (sp in run.splits) {
                val p = sp.paceSecPerKm()
                if (p > 0) sums[zoneIdx(p - ref)] += sp.durationMillis.coerceAtLeast(0L)
            }
            return
        }
        if (run.paceSecondsPerKm > 0) {
            sums[zoneIdx(run.paceSecondsPerKm - ref)] += run.durationMillis.coerceAtLeast(0L)
        }
    }

    private fun zoneIdx(delta: Int): Int = when {
        delta > 45 -> 0
        delta in 16..45 -> 1
        delta in -5..15 -> 2
        delta in -20..-6 -> 3
        else -> 4
    }

    private fun weekKey(date: LocalDate): String {
        val wf = WeekFields.ISO
        val wk = date.get(wf.weekOfWeekBasedYear())
        val yr = date.get(wf.weekBasedYear())
        return "%d-W%02d".format(yr, wk)
    }
}

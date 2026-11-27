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
    val flag: SplitDisciplineFlag,
    val zone: PaceZone = PaceZone.Z3_CRUISE
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

data class PrProgressionEntry(
    val runId: String,
    val runName: String,
    val timestampMillis: Long,
    val durationMillis: Long,
    val paceSecPerKm: Int,
    val improvementMillis: Long
)

data class PersonalRecordHistory(
    val label: String,
    val targetMeters: Int,
    val currentBest: PrProgressionEntry,
    val history: List<PrProgressionEntry>
)

data class HistoricalLoadBucket(
    val label: String,
    val runMeters: Int,
    val walkMeters: Int,
    val otherMeters: Int
)

data class HistoricalWindowStats(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val totalRunMeters: Int,
    val totalWalkMeters: Int,
    val totalDurationMillis: Long,
    val totalElevGainM: Int,
    val sessionCount: Int,
    val bars: List<HistoricalLoadBucket>,
    val matchingRuns: List<RunRecord>
)

class RunAnalyticsEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    fun evaluateSplitDiscipline(
        splits: List<RunSplit>,
        refPaceSecPerKm: Int,
        toleranceSec: Int = 8
    ): List<SplitEvaluation> {
        if (splits.isEmpty() || refPaceSecPerKm <= 0) return emptyList()
        return splits.map { split ->
            val pace = split.paceSecPerKm()
            val delta = pace - refPaceSecPerKm
            val flag = when {
                delta < -toleranceSec -> SplitDisciplineFlag.EARLY_SURGE
                delta > toleranceSec -> SplitDisciplineFlag.LATE_FADE
                else -> SplitDisciplineFlag.ON_TARGET
            }
            val zone = PaceZoneClassifier.classifyPace(pace, refPaceSecPerKm)
            SplitEvaluation(split, pace, delta, flag, zone)
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

    fun computeHistoricalWindow(
        runs: List<RunRecord>,
        walks: List<WalkLog>,
        endEpochDay: Long,
        spanDays: Int,
        bucketDays: Int
    ): HistoricalWindowStats {
        val span = spanDays.coerceAtLeast(1)
        val bucket = bucketDays.coerceAtLeast(1)
        val startEpochDay = endEpochDay - span + 1L

        val inWindowRuns = runs.filter { r ->
            val d = Instant.ofEpochMilli(r.startTimestampMillis).atZone(zoneId).toLocalDate().toEpochDay()
            d in startEpochDay..endEpochDay
        }.sortedByDescending { it.startTimestampMillis }
        val inWindowWalks = walks.filter { it.dateEpochDay in startEpochDay..endEpochDay }

        val runByDay = mutableMapOf<Long, Int>()
        for (r in inWindowRuns) {
            val d = Instant.ofEpochMilli(r.startTimestampMillis).atZone(zoneId).toLocalDate().toEpochDay()
            runByDay[d] = (runByDay[d] ?: 0) + r.distanceMeters
        }
        val walkByDay = inWindowWalks.associate { it.dateEpochDay to it.distanceMeters }

        val bars = mutableListOf<HistoricalLoadBucket>()
        var cur = startEpochDay
        while (cur <= endEpochDay) {
            val bEnd = (cur + bucket - 1L).coerceAtMost(endEpochDay)
            var rm = 0
            var wm = 0
            for (d in cur..bEnd) {
                rm += runByDay[d] ?: 0
                wm += walkByDay[d] ?: 0
            }
            val dt = LocalDate.ofEpochDay(cur)
            val lbl = when {
                bucket == 1 -> dt.dayOfWeek.name.take(2).lowercase().replaceFirstChar { it.uppercase() }
                bucket >= 25 -> dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                else -> "${dt.monthValue}/${dt.dayOfMonth}"
            }
            bars.add(HistoricalLoadBucket(lbl, rm, wm, 0))
            cur += bucket
        }

        return HistoricalWindowStats(
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            totalRunMeters = inWindowRuns.sumOf { it.distanceMeters },
            totalWalkMeters = inWindowWalks.sumOf { it.distanceMeters },
            totalDurationMillis = inWindowRuns.sumOf { it.durationMillis },
            totalElevGainM = inWindowRuns.sumOf { it.elevGainM },
            sessionCount = inWindowRuns.size,
            bars = bars,
            matchingRuns = inWindowRuns
        )
    }

    fun computePaceZoneDistribution(
        runs: List<RunRecord>,
        refPaceSec: Int
    ): List<PaceZoneBucket> {
        val ref = refPaceSec.coerceIn(150, 720)
        val sums = LongArray(PaceZone.entries.size)
        for (run in runs) addRunToZones(run, ref, sums)
        val total = sums.sum().coerceAtLeast(1L)
        return PaceZone.entries.map { z ->
            val idx = z.zoneNum - 1
            val pct = ((sums[idx] * 100.0) / total).toInt()
            PaceZoneBucket(z.zoneNum, z.label, pct, sums[idx])
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

    fun computePersonalRecords(runs: List<RunRecord>): List<PersonalRecord> =
        computePersonalRecordHistories(runs).map { h ->
            PersonalRecord(h.label, h.targetMeters, h.currentBest.durationMillis, h.currentBest.runId)
        }

    fun computePersonalRecordHistories(runs: List<RunRecord>): List<PersonalRecordHistory> {
        val marks = listOf(
            "1K" to 1_000,
            "2K" to 2_000,
            "5K" to 5_000,
            "10K" to 10_000,
            "Half Marathon" to 21_097
        )
        val chronological = runs.sortedBy { it.startTimestampMillis }
        return marks.mapNotNull { (label, meters) ->
            var bestMs = Long.MAX_VALUE
            val breaks = mutableListOf<PrProgressionEntry>()
            for (r in chronological) {
                if (r.distanceMeters < (meters * 0.98) || r.durationMillis <= 0L) continue
                val projMs = ((r.durationMillis.toDouble() / r.distanceMeters) * meters).toLong()
                if (projMs < bestMs) {
                    val gain = if (bestMs == Long.MAX_VALUE) 0L else bestMs - projMs
                    bestMs = projMs
                    val pace = ((projMs / 1000.0) / (meters / 1000.0)).toInt()
                    breaks.add(
                        PrProgressionEntry(
                            runId = r.id,
                            runName = r.name,
                            timestampMillis = r.startTimestampMillis,
                            durationMillis = projMs,
                            paceSecPerKm = pace,
                            improvementMillis = gain
                        )
                    )
                }
            }
            val current = breaks.lastOrNull() ?: return@mapNotNull null
            PersonalRecordHistory(label, meters, current, breaks.reversed())
        }
    }

    private fun addRunToZones(run: RunRecord, ref: Int, sums: LongArray) {
        if (run.splits.isNotEmpty()) {
            for (sp in run.splits) {
                val p = sp.paceSecPerKm()
                if (p > 0) {
                    val idx = PaceZoneClassifier.classifyPace(p, ref).zoneNum - 1
                    sums[idx] += sp.durationMillis.coerceAtLeast(0L)
                }
            }
            return
        }
        if (run.paceSecondsPerKm > 0) {
            val idx = PaceZoneClassifier.classifyPace(run.paceSecondsPerKm, ref).zoneNum - 1
            sums[idx] += run.durationMillis.coerceAtLeast(0L)
        }
    }

    private fun weekKey(date: LocalDate): String {
        val wf = WeekFields.ISO
        val wk = date.get(wf.weekOfWeekBasedYear())
        val yr = date.get(wf.weekBasedYear())
        return "%d-W%02d".format(yr, wk)
    }
}

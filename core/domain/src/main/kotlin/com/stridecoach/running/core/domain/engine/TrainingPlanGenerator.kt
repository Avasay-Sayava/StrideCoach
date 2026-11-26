package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.GoalDistance
import com.stridecoach.running.core.domain.model.PaceBounds
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.RacePhase
import com.stridecoach.running.core.domain.model.SegmentTarget
import com.stridecoach.running.core.domain.model.StepKind
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.WalkingLoadRule
import com.stridecoach.running.core.domain.model.WorkoutSegment
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

fun interface DayWorkoutBuilder {
    fun build(ctx: DayBuildContext): StructuredWorkout?
}

data class DayBuildContext(
    val config: TrainingPlanConfig,
    val date: LocalDate,
    val weekNumber: Int,
    val totalWeeks: Int,
    val isCutback: Boolean,
    val isTaper: Boolean,
    val isBenchmark: Boolean
)

class TrainingPlanGenerator(
    private val builders: Map<DayRole, DayWorkoutBuilder> = defaultBuilders()
) {
    fun generate(config: TrainingPlanConfig): List<PlannedDay> {
        val totalDays = (config.raceEpochDay - config.startEpochDay).toInt() + 1
        val totalWeeks = ((totalDays - 1) / 7) + 1
        val result = mutableListOf<PlannedDay>()

        for (offset in 0 until totalDays) {
            val epochDay = config.startEpochDay + offset
            val date = LocalDate.ofEpochDay(epochDay)
            val weekNum = (offset / 7) + 1
            val isRaceDay = epochDay == config.raceEpochDay
            val isTaper = weekNum > (totalWeeks - config.taperWeeks)
            val isCutback = !isTaper && config.cutbackEveryWeeks > 0 && (weekNum % config.cutbackEveryWeeks == 0)

            val baseRole = if (isRaceDay) {
                DayRole.RACE_DAY
            } else {
                config.weeklyTemplate[date.dayOfWeek] ?: DayRole.FULL_REST
            }
            val isBenchmark = config.benchmarkWeekNumber == weekNum && baseRole == DayRole.LONG_RUN && !isRaceDay
            val ctx = DayBuildContext(config, date, weekNum, totalWeeks, isCutback, isTaper, isBenchmark)
            val workout = builders[baseRole]?.build(ctx)

            result.add(
                PlannedDay(
                    id = "${config.id}_$epochDay",
                    planId = config.id,
                    dateEpochDay = epochDay,
                    weekNumber = weekNum,
                    dayRole = baseRole,
                    isCutbackWeek = isCutback,
                    isTaperWeek = isTaper,
                    isBenchmarkDay = isBenchmark,
                    workout = workout
                )
            )
        }
        return result
    }

    fun recalibrateFrom5kBenchmark(
        config: TrainingPlanConfig,
        fiveKDurationMillis: Long
    ): TrainingPlanConfig {
        require(fiveKDurationMillis > 600_000L) { "5K benchmark duration is unrealistically short" }
        val fiveKPaceSec = (fiveKDurationMillis / 1000.0) / 5.0
        val projected10kPaceSec = (fiveKPaceSec * 1.045).roundToInt().coerceIn(210, 480)
        val easyFast = projected10kPaceSec + 60
        val easySlow = projected10kPaceSec + 85
        val phases = buildDefaultRacePhases(config.goalDistance.meters.coerceAtLeast(10_000), projected10kPaceSec)
        return config.copy(
            targetPaceSecPerKm = projected10kPaceSec,
            easyPaceBounds = PaceBounds(easyFast, easySlow),
            racePhases = phases
        )
    }

    companion object {
        fun defaultBuilders(): Map<DayRole, DayWorkoutBuilder> = mapOf(
            DayRole.LONG_RUN to DayWorkoutBuilder(::buildLongOrBenchmark),
            DayRole.FAST_WORKOUT to DayWorkoutBuilder(::buildFastWorkout),
            DayRole.EASY_RUN to DayWorkoutBuilder(::buildEasyWorkout),
            DayRole.RACE_DAY to DayWorkoutBuilder(::buildRaceDayWorkout),
            DayRole.WALK_ONLY to DayWorkoutBuilder(::buildWalkRecovery),
            DayRole.CROSS_TRAIN to DayWorkoutBuilder(::buildCrossTrain),
            DayRole.FULL_REST to DayWorkoutBuilder { null }
        )

        private fun buildLongOrBenchmark(ctx: DayBuildContext): StructuredWorkout {
            if (ctx.isBenchmark) {
                val racePace = ctx.config.targetPaceSecPerKm
                val benchBounds = PaceBounds((racePace - 15).coerceAtLeast(180), racePace - 5)
                return StructuredWorkout(
                    id = "bench_5k_w${ctx.weekNumber}",
                    title = "5K Benchmark Time Trial",
                    description = "All-out controlled 5K to recalibrate your training pace zones.",
                    segments = listOf(
                        WorkoutSegment.AtomicStep("Warm-Up Jog", StepKind.WARM_UP, SegmentTarget.Distance(2_000), ctx.config.easyPaceBounds),
                        WorkoutSegment.AtomicStep("5K Time Trial", StepKind.RUN, SegmentTarget.Distance(5_000), benchBounds),
                        WorkoutSegment.AtomicStep("Cool-Down Walk", StepKind.COOL_DOWN, SegmentTarget.Duration(300))
                    )
                )
            }
            val distMeters = computeLongRunMeters(ctx)
            val easy = ctx.config.easyPaceBounds
            return StructuredWorkout(
                id = "long_w${ctx.weekNumber}_${ctx.date}",
                title = "Long Run (${distMeters / 1000} km)",
                description = "Steady aerobic endurance run. Hold back early and finish strong.",
                segments = listOf(
                    WorkoutSegment.AtomicStep("Brisk Walk Warm-Up", StepKind.WARM_UP, SegmentTarget.Duration(180)),
                    WorkoutSegment.AtomicStep("Aerobic Long Run", StepKind.RUN, SegmentTarget.Distance(distMeters), easy),
                    WorkoutSegment.AtomicStep("Cool-Down Walk", StepKind.COOL_DOWN, SegmentTarget.Duration(240))
                )
            )
        }

        private fun computeLongRunMeters(ctx: DayBuildContext): Int {
            val buildWeeks = (ctx.totalWeeks - ctx.config.taperWeeks).coerceAtLeast(1)
            val progress = ((ctx.weekNumber - 1).toDouble() / (buildWeeks - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val raw = ctx.config.startLongRunMeters + (progress * (ctx.config.peakLongRunMeters - ctx.config.startLongRunMeters))
            val adjusted = when {
                ctx.isTaper -> raw * 0.55
                ctx.isCutback -> raw * 0.75
                else -> raw
            }
            return ((adjusted / 1000.0).roundToInt().coerceAtLeast(5)) * 1000
        }

        private fun buildFastWorkout(ctx: DayBuildContext): StructuredWorkout {
            val target = ctx.config.targetPaceSecPerKm
            val easy = ctx.config.easyPaceBounds
            val cycle = ctx.weekNumber % 3
            val mainBlock: WorkoutSegment = when {
                ctx.isTaper -> WorkoutSegment.RepeatBlock(
                    label = "Race-Pace Tune-Up",
                    repetitions = 3,
                    steps = listOf(
                        WorkoutSegment.AtomicStep("Race Pace", StepKind.RUN, SegmentTarget.Distance(1_000), PaceBounds(target - 3, target + 4)),
                        WorkoutSegment.AtomicStep("Easy Recovery", StepKind.WALK, SegmentTarget.Duration(120))
                    )
                )
                cycle == 1 -> WorkoutSegment.RepeatBlock(
                    label = "1K Threshold Intervals",
                    repetitions = if (ctx.isCutback) 4 else 5,
                    steps = listOf(
                        WorkoutSegment.AtomicStep("Fast 1K", StepKind.RUN, SegmentTarget.Distance(1_000), PaceBounds(target - 12, target - 4)),
                        WorkoutSegment.AtomicStep("Recovery Walk/Jog", StepKind.REST, SegmentTarget.Duration(120))
                    )
                )
                cycle == 2 -> WorkoutSegment.AtomicStep(
                    label = "Continuous Tempo",
                    kind = StepKind.RUN,
                    target = SegmentTarget.Distance(if (ctx.isCutback) 4_000 else 5_000),
                    paceBounds = PaceBounds(target + 2, target + 12)
                )
                else -> WorkoutSegment.RepeatBlock(
                    label = "2K Goal-Pace Repeats",
                    repetitions = if (ctx.isCutback) 2 else 3,
                    steps = listOf(
                        WorkoutSegment.AtomicStep("Goal Pace 2K", StepKind.RUN, SegmentTarget.Distance(2_000), PaceBounds(target - 4, target + 4)),
                        WorkoutSegment.AtomicStep("Recovery Jog", StepKind.REST, SegmentTarget.Duration(150))
                    )
                )
            }
            return StructuredWorkout(
                id = "fast_w${ctx.weekNumber}_${ctx.date}",
                title = "Speed & Tempo Session",
                description = "Controlled quality work with full warm-up and cool-down.",
                segments = listOf(
                    WorkoutSegment.AtomicStep("Easy Warm-Up", StepKind.WARM_UP, SegmentTarget.Distance(1_500), easy),
                    mainBlock,
                    WorkoutSegment.AtomicStep("Easy Cool-Down", StepKind.COOL_DOWN, SegmentTarget.Distance(1_000), easy)
                )
            )
        }

        private fun buildEasyWorkout(ctx: DayBuildContext): StructuredWorkout {
            val dist = when {
                ctx.isTaper -> 4_000
                ctx.isCutback -> 5_000
                ctx.date.dayOfWeek == DayOfWeek.WEDNESDAY -> 6_000
                else -> 5_000
            }
            val segments = mutableListOf<WorkoutSegment>(
                WorkoutSegment.AtomicStep("Easy Aerobic Run", StepKind.RUN, SegmentTarget.Distance(dist), ctx.config.easyPaceBounds)
            )
            val includeStrides = !ctx.isTaper && ctx.date.dayOfWeek == DayOfWeek.WEDNESDAY
            if (includeStrides) {
                val strideFast = (ctx.config.targetPaceSecPerKm - 25).coerceAtLeast(190)
                segments.add(
                    WorkoutSegment.RepeatBlock(
                        label = "Relaxed Strides",
                        repetitions = 4,
                        steps = listOf(
                            WorkoutSegment.AtomicStep("Fast Relaxed 100m", StepKind.RUN, SegmentTarget.Distance(100), PaceBounds(strideFast, strideFast + 20)),
                            WorkoutSegment.AtomicStep("Walk Back Recovery", StepKind.WALK, SegmentTarget.Duration(45))
                        )
                    )
                )
            }
            return StructuredWorkout(
                id = "easy_w${ctx.weekNumber}_${ctx.date}",
                title = if (includeStrides) "Easy Run (${dist / 1000} km) + Strides" else "Easy Run (${dist / 1000} km)",
                description = "Keep effort conversational and avoid surging early.",
                segments = segments
            )
        }

        private fun buildRaceDayWorkout(ctx: DayBuildContext): StructuredWorkout {
            val totalMeters = ctx.config.goalDistance.meters.coerceAtLeast(5_000)
            val phases = ctx.config.racePhases.ifEmpty {
                buildDefaultRacePhases(totalMeters, ctx.config.targetPaceSecPerKm)
            }
            val steps = phases.map { phase ->
                WorkoutSegment.AtomicStep(
                    label = phase.name,
                    kind = StepKind.RUN,
                    target = SegmentTarget.Distance(phase.endMeters - phase.startMeters),
                    paceBounds = phase.paceBounds
                )
            }
            return StructuredWorkout(
                id = "race_${ctx.config.id}",
                title = "${ctx.config.name} — Race Day",
                description = "Execute negative-split pacing phases cleanly.",
                segments = steps,
                racePhases = phases
            )
        }

        private fun buildWalkRecovery(ctx: DayBuildContext): StructuredWorkout = StructuredWorkout(
            id = "walk_w${ctx.weekNumber}_${ctx.date}",
            title = "Walk-Only Recovery",
            description = "No running today. Normal urban walking keeps legs loose.",
            segments = listOf(
                WorkoutSegment.AtomicStep("Easy Recovery Walk", StepKind.WALK, SegmentTarget.Open)
            )
        )

        private fun buildCrossTrain(ctx: DayBuildContext): StructuredWorkout = StructuredWorkout(
            id = "xt_w${ctx.weekNumber}_${ctx.date}",
            title = "Low-Impact Cross-Training",
            description = "30–40 min easy cycling, swimming, or mobility.",
            segments = listOf(
                WorkoutSegment.AtomicStep("Easy Cross-Train", StepKind.WALK, SegmentTarget.Duration(2_100))
            )
        )

        fun buildDefaultRacePhases(totalMeters: Int, targetPaceSec: Int): List<RacePhase> {
            val p1End = (totalMeters * 0.3).roundToInt()
            val p2End = (totalMeters * 0.7).roundToInt()
            return listOf(
                RacePhase("Phase 1: Controlled Start", 0, p1End, PaceBounds(targetPaceSec + 3, targetPaceSec + 7)),
                RacePhase("Phase 2: Cruise Rhythm", p1End, p2End, PaceBounds(targetPaceSec - 3, targetPaceSec + 1)),
                RacePhase("Phase 3: Strong Finish", p2End, totalMeters, PaceBounds(targetPaceSec - 15, targetPaceSec - 7))
            )
        }
    }
}

object PlanPresets {
    fun telAviv10kPreset(): TrainingPlanConfig {
        val start = LocalDate.of(2026, 11, 27).toEpochDay()
        val race = LocalDate.of(2027, 2, 26).toEpochDay()
        val template = mapOf(
            DayOfWeek.SATURDAY to DayRole.LONG_RUN,
            DayOfWeek.SUNDAY to DayRole.FULL_REST,
            DayOfWeek.MONDAY to DayRole.FAST_WORKOUT,
            DayOfWeek.TUESDAY to DayRole.FULL_REST,
            DayOfWeek.WEDNESDAY to DayRole.EASY_RUN,
            DayOfWeek.THURSDAY to DayRole.EASY_RUN,
            DayOfWeek.FRIDAY to DayRole.WALK_ONLY
        )
        val phases = listOf(
            RacePhase("K1–K3: Hold Back", 0, 3_000, PaceBounds(298, 302)),
            RacePhase("K4–K7: Cruise Pace", 3_000, 7_000, PaceBounds(292, 296)),
            RacePhase("K8–K10: Fast Finish", 7_000, 10_000, PaceBounds(280, 288))
        )
        return TrainingPlanConfig(
            id = "preset_tlv_10k_2027",
            name = "Tel Aviv 10K (92-Day Build)",
            goalDistance = GoalDistance.TEN_K,
            startEpochDay = start,
            raceEpochDay = race,
            targetPaceSecPerKm = 295,
            easyPaceBounds = PaceBounds(355, 380),
            startLongRunMeters = 8_000,
            peakLongRunMeters = 15_000,
            cutbackEveryWeeks = 4,
            taperWeeks = 2,
            benchmarkWeekNumber = 7,
            weeklyTemplate = template,
            walkingLoadRule = WalkingLoadRule(
                walkThresholdMeters = 7_000,
                fastPaceThresholdSecPerKm = 320,
                downgradeDistanceMeters = 5_000,
                downgradePaceBounds = PaceBounds(360, 380)
            ),
            racePhases = phases
        )
    }
}

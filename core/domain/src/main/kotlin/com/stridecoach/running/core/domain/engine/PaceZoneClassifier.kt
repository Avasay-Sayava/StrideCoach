package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.formatPace
import com.stridecoach.running.core.domain.model.formatSpeedKmh
import kotlin.math.roundToInt

enum class PaceBaselineMode(val label: String) {
    CURRENT_FITNESS("Current Fitness"),
    PLAN_TARGET("Plan Goal"),
    RUN_AVERAGE("Run Average")
}

enum class PaceZone(val zoneNum: Int, val label: String, val hex: String) {
    Z1_EASY(1, "Easy", "#3DAEE9"),
    Z2_BASE(2, "Base", "#4ADE80"),
    Z3_CRUISE(3, "Cruise", "#926EE4"),
    Z4_TEMPO(4, "Tempo", "#FBBF24"),
    Z5_PEAK(5, "Peak", "#F87171")
}

data class ZoneExplanation(
    val zone: PaceZone,
    val paceRangeText: String,
    val speedRangeText: String,
    val summary: String,
    val coachingTip: String
)

object PaceZoneClassifier {
    private const val MIN_VALID_PACE_SEC = 120
    private const val MAX_VALID_PACE_SEC = 900

    /**
     * Computes current baseline pace from recent completed sessions of the same activity kind,
     * reflecting the athlete's current fitness rather than a distant future race target.
     */
    fun computeCurrentBaselinePace(
        runs: List<RunRecord>,
        kind: ActivityKind = ActivityKind.RUNNING,
        fallbackPaceSec: Int = 330
    ): Int {
        val recentPaces = runs
            .asSequence()
            .filter { it.activityKind == kind && it.distanceMeters >= 1_000 }
            .filter { it.paceSecondsPerKm in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC }
            .sortedByDescending { it.startTimestampMillis }
            .take(10)
            .map { it.paceSecondsPerKm }
            .toList()

        val pool = if (recentPaces.isNotEmpty()) {
            recentPaces
        } else {
            runs.asSequence()
                .filter { it.paceSecondsPerKm in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC }
                .sortedByDescending { it.startTimestampMillis }
                .take(10)
                .map { it.paceSecondsPerKm }
                .toList()
        }
        if (pool.isEmpty()) return fallbackPaceSec.coerceIn(180, 600)
        val sorted = pool.sorted()
        return sorted[sorted.size / 2]
    }

    fun classifyPace(paceSecPerKm: Int, refPaceSec: Int): PaceZone {
        val base = refPaceSec.coerceIn(150, 720).toDouble()
        val p = paceSecPerKm.coerceAtLeast(120).toDouble()
        val ratio = p / base
        return when {
            ratio > 1.12 -> PaceZone.Z1_EASY
            ratio > 1.03 -> PaceZone.Z2_BASE
            ratio >= 0.96 -> PaceZone.Z3_CRUISE
            ratio >= 0.89 -> PaceZone.Z4_TEMPO
            else -> PaceZone.Z5_PEAK
        }
    }

    fun classifySpeed(speedMps: Float?, refPaceSec: Int): PaceZone? {
        val v = speedMps ?: return PaceZone.Z3_CRUISE
        if (v < 0.5f) return null
        val paceSec = (1000.0 / v).toInt()
        return classifyPace(paceSec, refPaceSec)
    }

    /**
     * Continuously interpolates color across Z1..Z5 for smooth sub-kilometer pace gradients on vector maps.
     */
    fun smoothPaceHex(speedMps: Float?, refPaceSec: Int): String {
        val v = speedMps ?: return PaceZone.Z3_CRUISE.hex
        if (v < 0.5f) return "#9BA3AC"
        val base = refPaceSec.coerceIn(150, 720).toDouble()
        val pace = (1000.0 / v).coerceIn(120.0, 960.0)
        val ratio = pace / base

        val stops = listOf(
            0.85 to intArrayOf(0xF8, 0x71, 0x71),
            0.925 to intArrayOf(0xFB, 0xBF, 0x24),
            1.00 to intArrayOf(0x92, 0x6E, 0xE4),
            1.075 to intArrayOf(0x4A, 0xDE, 0x80),
            1.18 to intArrayOf(0x3D, 0xAE, 0xE9)
        )
        if (ratio <= stops.first().first) return PaceZone.Z5_PEAK.hex
        if (ratio >= stops.last().first) return PaceZone.Z1_EASY.hex
        for (i in 0 until stops.lastIndex) {
            val (r0, c0) = stops[i]
            val (r1, c1) = stops[i + 1]
            if (ratio in r0..r1) {
                val t = ((ratio - r0) / (r1 - r0)).coerceIn(0.0, 1.0)
                val r = (c0[0] + (c1[0] - c0[0]) * t).roundToInt().coerceIn(0, 255)
                val g = (c0[1] + (c1[1] - c0[1]) * t).roundToInt().coerceIn(0, 255)
                val b = (c0[2] + (c1[2] - c0[2]) * t).roundToInt().coerceIn(0, 255)
                return "#%02X%02X%02X".format(r, g, b)
            }
        }
        return PaceZone.Z3_CRUISE.hex
    }

    fun explainZone(zone: PaceZone, refPaceSec: Int): ZoneExplanation {
        val base = refPaceSec.coerceIn(150, 720).toDouble()
        return when (zone) {
            PaceZone.Z1_EASY -> {
                val fast = (base * 1.12).roundToInt()
                ZoneExplanation(
                    zone = zone,
                    paceRangeText = "> ${formatPace(fast)}/km",
                    speedRangeText = "< ${formatSpeedKmh(fast)}",
                    summary = "Active recovery, warm-ups & gentle shakeout movement.",
                    coachingTip = "Builds capillary density with low joint strain."
                )
            }
            PaceZone.Z2_BASE -> {
                val fast = (base * 1.03).roundToInt()
                val slow = (base * 1.12).roundToInt()
                ZoneExplanation(
                    zone = zone,
                    paceRangeText = "${formatPace(fast)}–${formatPace(slow)}/km",
                    speedRangeText = "${formatSpeedKmh(slow)} – ${formatSpeedKmh(fast)}",
                    summary = "Conversational aerobic foundation (ideal long-run pace).",
                    coachingTip = "Target ~65-75% of weekly training here for durable stamina."
                )
            }
            PaceZone.Z3_CRUISE -> {
                val fast = (base * 0.96).roundToInt()
                val slow = (base * 1.03).roundToInt()
                ZoneExplanation(
                    zone = zone,
                    paceRangeText = "${formatPace(fast)}–${formatPace(slow)}/km",
                    speedRangeText = "${formatSpeedKmh(slow)} – ${formatSpeedKmh(fast)}",
                    summary = "Steady rhythm near your current everyday cruise baseline.",
                    coachingTip = "Bridges easy mileage and tempo work; watch leg freshness."
                )
            }
            PaceZone.Z4_TEMPO -> {
                val fast = (base * 0.89).roundToInt()
                val slow = (base * 0.96).roundToInt()
                ZoneExplanation(
                    zone = zone,
                    paceRangeText = "${formatPace(fast)}–${formatPace(slow)}/km",
                    speedRangeText = "${formatSpeedKmh(slow)} – ${formatSpeedKmh(fast)}",
                    summary = "Lactate threshold & sustained cruise-interval pressure.",
                    coachingTip = "Raises sustainable race pace without redlining."
                )
            }
            PaceZone.Z5_PEAK -> {
                val slow = (base * 0.89).roundToInt()
                ZoneExplanation(
                    zone = zone,
                    paceRangeText = "< ${formatPace(slow)}/km",
                    speedRangeText = "> ${formatSpeedKmh(slow)}",
                    summary = "VO2max, finishing kick & short speed intervals.",
                    coachingTip = "Keep bouts crisp (100m-1K) and protect recovery days."
                )
            }
        }
    }
}

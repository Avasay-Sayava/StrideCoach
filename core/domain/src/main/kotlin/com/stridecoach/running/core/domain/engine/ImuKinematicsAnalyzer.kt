package com.stridecoach.running.core.domain.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ImuKinematicsAnalyzer {
    /**
     * Complementary filter blending rotation-vector/gyro heading with GPS course-over-ground
     * once moving reliably (> 1.5 m/s) to eliminate pocket wobble & magnetic interference.
     */
    fun fuseHeading(compassDeg: Float, gpsBearingDeg: Float?, speedMps: Float): Float {
        val cNorm = normalizeDeg(compassDeg)
        val g = gpsBearingDeg ?: return cNorm
        val weightGps = if (speedMps > 1.5f) 0.72f else 0.15f
        val diff = ((normalizeDeg(g) - cNorm + 540f) % 360f) - 180f
        return normalizeDeg(cNorm + diff * weightGps)
    }

    /**
     * Scores gait cadence consistency (0..100%) from recent stride interval durations in milliseconds.
     */
    fun computeStrideStabilityPct(peakIntervalsMs: List<Long>): Int {
        val valid = peakIntervalsMs.filter { it in 220L..1_100L }
        if (valid.size < 4) return 0
        val mean = valid.average()
        if (mean <= 1.0) return 0
        val variance = valid.sumOf { (it - mean) * (it - mean) } / valid.size
        val cv = sqrt(variance) / mean
        return ((1.0 - (cv / 0.25)) * 100.0).roundToInt().coerceIn(0, 100)
    }

    fun isImuStationary(accelVariance: Float, gyroRmsRadSec: Float): Boolean =
        accelVariance < 0.22f && gyroRmsRadSec < 0.18f

    fun detectHoldingCircle(cumulativeAbsYawDeg: Float, netDisplacementM: Double): Boolean =
        abs(cumulativeAbsYawDeg) >= 270f && netDisplacementM <= 28.0

    private fun normalizeDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f
}

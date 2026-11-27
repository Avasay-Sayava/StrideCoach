package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.TrackPoint
import kotlin.math.abs
import kotlin.math.roundToInt

data class ElevationSample(
    val distKm: Float,
    val altitudeM: Float,
    val gradePct: Float
)

data class TopographySummary(
    val gainMeters: Int,
    val lossMeters: Int,
    val minAltMeters: Int,
    val maxAltMeters: Int,
    val maxGradePct: Double,
    val avgAbsGradePct: Double,
    val gradeAdjustedPaceSec: Int,
    val profilePoints: List<ElevationSample>
)

object TopographyPaceEngine {
    fun computeGradePct(deltaAltM: Double, distM: Double): Double {
        if (distM < 3.0) return 0.0
        return ((deltaAltM / distM) * 100.0).coerceIn(-25.0, 25.0)
    }

    /**
     * Adjusts a flat target pace (sec/km) for slope grade: climbs allow slower pace,
     * gentle descents speed target slightly, and steep descents cushion knee braking.
     */
    fun adjustTargetPaceForGrade(flatTargetPaceSec: Int, gradePct: Double): Int {
        if (flatTargetPaceSec <= 0) return flatTargetPaceSec
        val offset = when {
            gradePct > 0.5 -> (gradePct * 4.5).roundToInt()
            gradePct in -6.5..-0.5 -> -(abs(gradePct) * 2.2).roundToInt()
            gradePct < -6.5 -> ((abs(gradePct) - 6.5) * 2.5 - 12.0).roundToInt()
            else -> 0
        }
        return (flatTargetPaceSec + offset).coerceIn(150, 900)
    }

    /**
     * Converts actual pace on a given slope grade back to flat-equivalent effort (Grade-Adjusted Pace).
     */
    fun computeGradeAdjustedPace(actualPaceSec: Int, gradePct: Double): Int {
        if (actualPaceSec <= 0) return actualPaceSec
        val offset = adjustTargetPaceForGrade(actualPaceSec, gradePct) - actualPaceSec
        return (actualPaceSec - offset).coerceIn(150, 900)
    }

    fun computeRollingGradePct(pts: List<TrackPoint>, windowMeters: Double = 40.0): Double {
        if (pts.size < 2) return 0.0
        val endPt = pts.last()
        val endAlt = endPt.altitudeMeters ?: return 0.0
        for (i in pts.lastIndex - 1 downTo 0) {
            val dist = endPt.cumulativeDistanceMeters - pts[i].cumulativeDistanceMeters
            val startAlt = pts[i].altitudeMeters ?: continue
            if (dist >= windowMeters || i == 0) {
                return computeGradePct(endAlt - startAlt, dist)
            }
        }
        return 0.0
    }

    fun analyzeTrack(pts: List<TrackPoint>, fallbackPaceSec: Int = 330): TopographySummary? {
        val withAlt = pts.filter { it.altitudeMeters != null }
        if (withAlt.size < 2) return null

        val rawAlts = withAlt.map { it.altitudeMeters!! }
        val smoothed = smoothAltitudes(rawAlts)
        var gain = 0.0
        var loss = 0.0
        var maxGrade = 0.0
        var gradeSum = 0.0
        var gradeCnt = 0
        var weightedEffortSec = 0.0
        val samples = mutableListOf<ElevationSample>()

        val minAlt = smoothed.min().roundToInt()
        val maxAlt = smoothed.max().roundToInt()
        samples.add(ElevationSample((withAlt.first().cumulativeDistanceMeters / 1000.0).toFloat(), smoothed.first().toFloat(), 0f))

        for (i in 1 until withAlt.size) {
            val prev = withAlt[i - 1]
            val cur = withAlt[i]
            val dAlt = smoothed[i] - smoothed[i - 1]
            if (dAlt > 0.4) gain += dAlt else if (dAlt < -0.4) loss += abs(dAlt)

            val segDist = (cur.cumulativeDistanceMeters - prev.cumulativeDistanceMeters).coerceAtLeast(1.0)
            val grade = computeGradePct(dAlt, segDist)
            if (abs(grade) > abs(maxGrade)) maxGrade = grade
            gradeSum += abs(grade)
            gradeCnt++

            val rawSpd = cur.speedMps?.takeIf { it > 0.5f } ?: (1000f / fallbackPaceSec.coerceAtLeast(180))
            val segPace = (1000.0 / rawSpd).roundToInt()
            val gap = computeGradeAdjustedPace(segPace, grade)
            weightedEffortSec += (segDist / 1000.0) * gap

            if (i == withAlt.lastIndex || i % maxOf(1, withAlt.size / 60) == 0) {
                samples.add(
                    ElevationSample(
                        distKm = (cur.cumulativeDistanceMeters / 1000.0).toFloat(),
                        altitudeM = smoothed[i].toFloat(),
                        gradePct = grade.toFloat()
                    )
                )
            }
        }

        val totalDistKm = ((withAlt.last().cumulativeDistanceMeters - withAlt.first().cumulativeDistanceMeters) / 1000.0)
            .coerceAtLeast(0.05)
        val overallGap = (weightedEffortSec / totalDistKm).roundToInt().coerceIn(150, 900)
        return TopographySummary(
            gainMeters = gain.roundToInt(),
            lossMeters = loss.roundToInt(),
            minAltMeters = minAlt,
            maxAltMeters = maxAlt,
            maxGradePct = maxGrade,
            avgAbsGradePct = if (gradeCnt > 0) gradeSum / gradeCnt else 0.0,
            gradeAdjustedPaceSec = overallGap,
            profilePoints = samples
        )
    }

    private fun smoothAltitudes(alts: List<Double>): List<Double> {
        if (alts.size < 3) return alts
        return alts.indices.map { i ->
            when (i) {
                0, alts.lastIndex -> alts[i]
                else -> (alts[i - 1] + 2.0 * alts[i] + alts[i + 1]) / 4.0
            }
        }
    }
}

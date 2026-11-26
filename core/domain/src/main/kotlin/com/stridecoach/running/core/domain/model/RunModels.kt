package com.stridecoach.running.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RunSource {
    RECORDED,
    BACKUP_IMPORT
}

@Serializable
data class TrackPoint(
    val seqIndex: Int,
    val timestampMillis: Long,
    val elapsedMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val cumulativeDistanceMeters: Double = 0.0,
    val segmentIndex: Int? = null,
    val isPaused: Boolean = false
)

@Serializable
data class RunSplit(
    val splitNumber: Int,
    val distanceMeters: Int,
    val durationMillis: Long,
    val targetPaceMinSec: Int? = null,
    val targetPaceMaxSec: Int? = null
) {
    fun paceSecPerKm(): Int {
        val d = distanceMeters
        val t = durationMillis
        if (d <= 0 || t <= 0L) return 0
        return ((t / 1000.0) / (d / 1000.0)).toInt()
    }
}

@Serializable
data class RunRecord(
    val id: String,
    val startTimestampMillis: Long,
    val distanceMeters: Int,
    val durationMillis: Long,
    val paceSecondsPerKm: Int,
    val caloriesBurned: Int,
    val name: String,
    val description: String,
    val encodedPolyline: String,
    val splits: List<RunSplit>,
    val trackPoints: List<TrackPoint> = emptyList(),
    val workoutId: String? = null,
    val planDayId: String? = null,
    val source: RunSource = RunSource.RECORDED
) {
    fun km(): Double = distanceMeters / 1000.0
    fun hasTrack(): Boolean = trackPoints.size >= 2 || encodedPolyline.isNotBlank()
}

@Serializable
enum class WalkSource {
    STEP_SENSOR,
    TIMELINE_IMPORT,
    MANUAL
}

@Serializable
data class WalkLog(
    val dateEpochDay: Long,
    val distanceMeters: Int,
    val source: WalkSource,
    val stepCount: Int = 0,
    val updatedAtMillis: Long = 0L
) {
    fun km(): Double = distanceMeters / 1000.0
}

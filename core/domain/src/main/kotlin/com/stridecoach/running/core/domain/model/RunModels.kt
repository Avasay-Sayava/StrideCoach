package com.stridecoach.running.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RunSource {
    RECORDED,
    BACKUP_IMPORT,
    HEALTH_CONNECT
}

@Serializable
enum class ActivitySegmentType {
    RUNNING,
    WALKING,
    STRETCH_REST
}

@Serializable
data class DetectedSegment(
    val type: ActivitySegmentType,
    val startMs: Long,
    val endMs: Long,
    val distM: Int,
    val steps: Int,
    val paceSec: Int
)

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
    val isPaused: Boolean = false,
    val cadenceSpm: Int? = null
)

@Serializable
data class RunSplit(
    val splitNumber: Int,
    val distanceMeters: Int,
    val durationMillis: Long,
    val targetPaceMinSec: Int? = null,
    val targetPaceMaxSec: Int? = null,
    val avgCadenceSpm: Int = 0,
    val elevDeltaM: Int = 0
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
    val source: RunSource = RunSource.RECORDED,
    val totalSteps: Int = 0,
    val avgCadenceSpm: Int = 0,
    val elevGainM: Int = 0,
    val segs: List<DetectedSegment> = emptyList()
) {
    fun km(): Double = distanceMeters / 1000.0
    fun hasTrack(): Boolean = trackPoints.size >= 2 || encodedPolyline.isNotBlank()
}

@Serializable
enum class WalkSource {
    STEP_SENSOR,
    TIMELINE_IMPORT,
    MANUAL,
    HEALTH_CONNECT
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

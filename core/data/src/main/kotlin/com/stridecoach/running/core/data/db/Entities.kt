package com.stridecoach.running.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.DetectedSegment
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.TrackPoint

@Entity(tableName = "runs", indices = [Index("startTimestampMillis")])
data class RunEntity(
    @PrimaryKey val id: String,
    val startTimestampMillis: Long,
    val distanceMeters: Int,
    val durationMillis: Long,
    val paceSecondsPerKm: Int,
    val caloriesBurned: Int,
    val name: String,
    val description: String,
    val encodedPolyline: String,
    val workoutId: String?,
    val planDayId: String?,
    val source: String,
    val totalSteps: Int = 0,
    val avgCadenceSpm: Int = 0,
    val elevationGainMeters: Int = 0,
    val activityKind: String = ActivityKind.RUNNING.name,
    val rawEncodedPolyline: String = "",
    val snappedEncodedPolyline: String = "",
    val isSnapped: Boolean = false,
    val snapStreetPct: Int = 0,
    val snapGreeneryPct: Int = 0,
    val snapLoopsKept: Int = 0,
    val strideStabilityPct: Int = 0,
    val elevationLossMeters: Int = 0,
    val minAltitudeMeters: Int? = null,
    val maxAltitudeMeters: Int? = null,
    val gradeAdjustedPaceSec: Int = 0,
    val deletedAtMillis: Long? = null
) {
    fun toDomain(
        sp: List<RunSplit>,
        pts: List<TrackPoint>,
        segs: List<DetectedSegment> = emptyList()
    ): RunRecord = RunRecord(
        id, startTimestampMillis, distanceMeters, durationMillis, paceSecondsPerKm,
        caloriesBurned, name, description, encodedPolyline, sp, pts,
        workoutId, planDayId, RunSource.valueOf(source),
        totalSteps, avgCadenceSpm, elevationGainMeters, segs,
        runCatching { ActivityKind.valueOf(activityKind) }.getOrDefault(ActivityKind.RUNNING),
        rawEncodedPolyline, snappedEncodedPolyline, isSnapped,
        snapStreetPct, snapGreeneryPct, snapLoopsKept, strideStabilityPct,
        elevationLossMeters, minAltitudeMeters, maxAltitudeMeters, gradeAdjustedPaceSec, deletedAtMillis
    )
}

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["runId", "seqIndex"], unique = true)]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val runId: String,
    val seqIndex: Int,
    val timestampMillis: Long,
    val elapsedMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val cumulativeDistanceMeters: Double,
    val segmentIndex: Int?,
    val isPaused: Boolean,
    val cadenceSpm: Int? = null
) {
    fun toDomain(): TrackPoint = TrackPoint(
        seqIndex, timestampMillis, elapsedMillis, latitude, longitude,
        altitudeMeters, accuracyMeters, speedMps, bearingDegrees,
        cumulativeDistanceMeters, segmentIndex, isPaused, cadenceSpm
    )
}

@Entity(
    tableName = "run_splits",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["runId", "splitNumber"])]
)
data class RunSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val runId: String,
    val splitNumber: Int,
    val distanceMeters: Int,
    val durationMillis: Long,
    val targetPaceMinSec: Int?,
    val targetPaceMaxSec: Int?,
    val avgCadenceSpm: Int = 0,
    val elevationDeltaMeters: Int = 0
) {
    fun toDomain(): RunSplit = RunSplit(
        splitNumber, distanceMeters, durationMillis,
        targetPaceMinSec, targetPaceMaxSec, avgCadenceSpm, elevationDeltaMeters
    )
}

@Entity(
    tableName = "detected_segments",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["runId", "startMs"])]
)
data class DetectedSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val runId: String,
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val distM: Int,
    val steps: Int,
    val paceSec: Int
) {
    fun toDomain(): DetectedSegment = DetectedSegment(
        ActivitySegmentType.valueOf(type),
        startMs,
        endMs,
        distM,
        steps,
        paceSec
    )
}

@Entity(tableName = "walking_logs")
data class WalkLogEntity(
    @PrimaryKey val dateEpochDay: Long,
    val distanceMeters: Int,
    val source: String,
    val stepCount: Int,
    val updatedAtMillis: Long
)

@Entity(tableName = "training_plans")
data class TrainingPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val configJson: String,
    val isActive: Boolean
)

@Entity(
    tableName = "planned_days",
    foreignKeys = [
        ForeignKey(
            entity = TrainingPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["planId", "dateEpochDay"])]
)
data class PlannedDayEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val dateEpochDay: Long,
    val weekNumber: Int,
    val dayRole: String,
    val isCutbackWeek: Boolean,
    val isTaperWeek: Boolean,
    val isBenchmarkDay: Boolean,
    val workoutJson: String?,
    val status: String,
    val linkedRunId: String?
)

@Entity(tableName = "workout_templates")
data class WorkoutTemplateEntity(
    @PrimaryKey val id: String,
    val title: String,
    val workoutJson: String,
    val deletedAtMillis: Long? = null
)

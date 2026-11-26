package com.stridecoach.running.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
    val source: String
) {
    fun toDomain(sp: List<RunSplit>, pts: List<TrackPoint>): RunRecord = RunRecord(
        id, startTimestampMillis, distanceMeters, durationMillis, paceSecondsPerKm,
        caloriesBurned, name, description, encodedPolyline, sp, pts,
        workoutId, planDayId, RunSource.valueOf(source)
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
    val isPaused: Boolean
) {
    fun toDomain(): TrackPoint = TrackPoint(
        seqIndex, timestampMillis, elapsedMillis, latitude, longitude,
        altitudeMeters, accuracyMeters, speedMps, bearingDegrees,
        cumulativeDistanceMeters, segmentIndex, isPaused
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
    val targetPaceMaxSec: Int?
) {
    fun toDomain(): RunSplit = RunSplit(splitNumber, distanceMeters, durationMillis, targetPaceMinSec, targetPaceMaxSec)
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
    val workoutJson: String
)

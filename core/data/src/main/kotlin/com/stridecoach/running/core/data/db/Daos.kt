package com.stridecoach.running.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class RunWithSplits(
    @Embedded val run: RunEntity,
    @Relation(parentColumn = "id", entityColumn = "runId")
    val splits: List<RunSplitEntity>
)

data class FullRunBundle(
    val run: RunEntity,
    val splits: List<RunSplitEntity>,
    val points: List<TrackPointEntity>,
    val segments: List<DetectedSegmentEntity> = emptyList()
)

@Dao
interface RunDao {
    @Transaction
    @Query("SELECT * FROM runs ORDER BY startTimestampMillis DESC")
    fun observeRunsWithSplits(): Flow<List<RunWithSplits>>

    @Transaction
    @Query("SELECT * FROM runs ORDER BY startTimestampMillis DESC")
    suspend fun getAllRunsWithSplits(): List<RunWithSplits>

    @Query("SELECT * FROM runs WHERE id = :runId LIMIT 1")
    suspend fun getRun(runId: String): RunEntity?

    @Query("SELECT * FROM run_splits WHERE runId = :runId ORDER BY splitNumber ASC")
    suspend fun getSplits(runId: String): List<RunSplitEntity>

    @Query("SELECT * FROM track_points WHERE runId = :runId ORDER BY seqIndex ASC")
    suspend fun getTrackPoints(runId: String): List<TrackPointEntity>

    @Query("SELECT * FROM detected_segments WHERE runId = :runId ORDER BY startMs ASC")
    suspend fun getDetectedSegments(runId: String): List<DetectedSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: RunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<RunSplitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoints(points: List<TrackPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<DetectedSegmentEntity>)

    @Query("DELETE FROM run_splits WHERE runId = :runId")
    suspend fun deleteSplits(runId: String)

    @Query("DELETE FROM track_points WHERE runId = :runId")
    suspend fun deleteTrackPoints(runId: String)

    @Query("DELETE FROM detected_segments WHERE runId = :runId")
    suspend fun deleteSegments(runId: String)

    @Query("DELETE FROM runs WHERE id = :runId")
    suspend fun deleteRun(runId: String)

    @Transaction
    suspend fun replaceRunFull(bundle: FullRunBundle) {
        val run = bundle.run
        upsertRun(run)
        deleteSplits(run.id)
        deleteTrackPoints(run.id)
        deleteSegments(run.id)
        if (bundle.splits.isNotEmpty()) insertSplits(bundle.splits)
        if (bundle.points.isNotEmpty()) insertTrackPoints(bundle.points)
        if (bundle.segments.isNotEmpty()) insertSegments(bundle.segments)
    }

    @Transaction
    suspend fun replaceRunsBatch(items: List<FullRunBundle>) {
        for (item in items) replaceRunFull(item)
    }
}

@Dao
interface WalkDao {
    @Query("SELECT * FROM walking_logs ORDER BY dateEpochDay DESC")
    fun observeWalkLogs(): Flow<List<WalkLogEntity>>

    @Query("SELECT * FROM walking_logs")
    suspend fun getAll(): List<WalkLogEntity>

    @Query("SELECT * FROM walking_logs WHERE dateEpochDay = :epochDay LIMIT 1")
    suspend fun getByDay(epochDay: Long): WalkLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<WalkLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: WalkLogEntity)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM training_plans ORDER BY isActive DESC")
    fun observePlans(): Flow<List<TrainingPlanEntity>>

    @Query("SELECT * FROM training_plans WHERE isActive = 1 LIMIT 1")
    fun observeActivePlan(): Flow<TrainingPlanEntity?>

    @Query("SELECT * FROM planned_days WHERE planId = :planId ORDER BY dateEpochDay ASC")
    fun observeDays(planId: String): Flow<List<PlannedDayEntity>>

    @Query("UPDATE training_plans SET isActive = 0")
    suspend fun clearActiveFlags()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(plan: TrainingPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDays(days: List<PlannedDayEntity>)

    @Query("DELETE FROM planned_days WHERE planId = :planId")
    suspend fun deleteDays(planId: String)

    @Transaction
    suspend fun activatePlanWithDays(plan: TrainingPlanEntity, days: List<PlannedDayEntity>) {
        clearActiveFlags()
        upsertPlan(plan.copy(isActive = true))
        deleteDays(plan.id)
        upsertDays(days)
    }
}

@Dao
interface WorkoutTemplateDao {
    @Query("SELECT * FROM workout_templates ORDER BY title ASC")
    fun observeTemplates(): Flow<List<WorkoutTemplateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: WorkoutTemplateEntity)

    @Query("DELETE FROM workout_templates WHERE id = :id")
    suspend fun delete(id: String)
}

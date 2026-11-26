package com.stridecoach.running.core.data.repository

import com.stridecoach.running.core.data.db.FullRunBundle
import com.stridecoach.running.core.data.db.PlannedDayEntity
import com.stridecoach.running.core.data.db.RunEntity
import com.stridecoach.running.core.data.db.RunSplitEntity
import com.stridecoach.running.core.data.db.RunningDatabase
import com.stridecoach.running.core.data.db.TrackPointEntity
import com.stridecoach.running.core.data.db.TrainingPlanEntity
import com.stridecoach.running.core.data.db.WalkLogEntity
import com.stridecoach.running.core.data.db.WorkoutTemplateEntity
import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.PlanDayStatus
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StrideCoachRepository(
    private val db: RunningDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    fun observeRuns(): Flow<List<RunRecord>> = db.runDao().observeRuns().map { list ->
        list.map { it.toDomain(emptyList(), emptyList()) }
    }

    suspend fun getRunDetail(id: String): RunRecord? {
        val run = db.runDao().getRun(id) ?: return null
        val sp = db.runDao().getSplits(id).map { it.toDomain() }
        val pts = db.runDao().getTrackPoints(id).map { it.toDomain() }
        return run.toDomain(sp, pts)
    }

    suspend fun getAllRunsWithSplits(): List<RunRecord> = db.runDao().getAllRuns().map { r ->
        val sp = db.runDao().getSplits(r.id).map { it.toDomain() }
        r.toDomain(sp, emptyList())
    }

    suspend fun saveRun(run: RunRecord) {
        val bundle = run.toBundle()
        db.runDao().replaceRunFull(bundle.run, bundle.splits, bundle.points)
    }

    suspend fun saveRuns(runs: List<RunRecord>) {
        if (runs.isEmpty()) return
        db.runDao().replaceRunsBatch(runs.map { it.toBundle() })
    }

    suspend fun deleteRun(id: String) = db.runDao().deleteRun(id)

    fun observeWalkLogs(): Flow<List<WalkLog>> = db.walkDao().observeWalkLogs().map { list ->
        list.map { WalkLog(it.dateEpochDay, it.distanceMeters, WalkSource.valueOf(it.source), it.stepCount, it.updatedAtMillis) }
    }

    suspend fun saveWalkLogs(logs: List<WalkLog>) {
        if (logs.isEmpty()) return
        val list = logs.map {
            WalkLogEntity(it.dateEpochDay, it.distanceMeters, it.source.name, it.stepCount, it.updatedAtMillis)
        }
        db.walkDao().upsertAll(list)
    }

    fun observeActivePlan(): Flow<TrainingPlanConfig?> = db.planDao().observeActivePlan().map { row ->
        row?.let { json.decodeFromString<TrainingPlanConfig>(it.configJson) }
    }

    fun observePlanDays(planId: String): Flow<List<PlannedDay>> = db.planDao().observeDays(planId).map { list ->
        list.map { it.toDomain(json) }
    }

    suspend fun activatePlan(cfg: TrainingPlanConfig, days: List<PlannedDay>) {
        val planRow = TrainingPlanEntity(cfg.id, cfg.name, json.encodeToString(cfg), true)
        db.planDao().activatePlanWithDays(planRow, days.map { it.toEntity(json) })
    }

    suspend fun updatePlannedDays(days: List<PlannedDay>) {
        db.planDao().upsertDays(days.map { it.toEntity(json) })
    }

    fun observeWorkoutTemplates(): Flow<List<StructuredWorkout>> = db.workoutTemplateDao().observeTemplates().map { list ->
        list.map { json.decodeFromString<StructuredWorkout>(it.workoutJson) }
    }

    suspend fun saveWorkoutTemplate(w: StructuredWorkout) {
        db.workoutTemplateDao().upsert(WorkoutTemplateEntity(w.id, w.title, json.encodeToString(w)))
    }

    private fun RunRecord.toBundle(): FullRunBundle {
        val r = RunEntity(
            id, startTimestampMillis, distanceMeters, durationMillis, paceSecondsPerKm,
            caloriesBurned, name, description, encodedPolyline, workoutId, planDayId, source.name
        )
        val sp = splits.map {
            RunSplitEntity(0L, id, it.splitNumber, it.distanceMeters, it.durationMillis, it.targetPaceMinSec, it.targetPaceMaxSec)
        }
        val pts = trackPoints.map { pt ->
            TrackPointEntity(
                0L, id, pt.seqIndex, pt.timestampMillis, pt.elapsedMillis, pt.latitude, pt.longitude,
                pt.altitudeMeters, pt.accuracyMeters, pt.speedMps, pt.bearingDegrees,
                pt.cumulativeDistanceMeters, pt.segmentIndex, pt.isPaused
            )
        }
        return FullRunBundle(r, sp, pts)
    }

    private fun PlannedDayEntity.toDomain(codec: Json) = PlannedDay(
        id, planId, dateEpochDay, weekNumber, DayRole.valueOf(dayRole),
        isCutbackWeek, isTaperWeek, isBenchmarkDay,
        workoutJson?.let { codec.decodeFromString<StructuredWorkout>(it) },
        PlanDayStatus.valueOf(status), linkedRunId
    )

    private fun PlannedDay.toEntity(codec: Json) = PlannedDayEntity(
        id, planId, dateEpochDay, weekNumber, dayRole.name,
        isCutbackWeek, isTaperWeek, isBenchmarkDay,
        workout?.let { codec.encodeToString(it) }, status.name, linkedRunId
    )
}

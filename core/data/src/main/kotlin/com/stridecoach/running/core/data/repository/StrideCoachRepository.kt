package com.stridecoach.running.core.data.repository

import com.stridecoach.running.core.data.db.DetectedSegmentEntity
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
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class StrideCoachRepository(
    private val db: RunningDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private val plannedDayCache = ConcurrentHashMap<String, PlannedDay>()
    private val templateCache = ConcurrentHashMap<String, StructuredWorkout>()

    fun observeRuns(): Flow<List<RunRecord>> = db.runDao().observeRunsWithSplits().map { list ->
        list.map { row ->
            val sp = row.splits.sortedBy { it.splitNumber }.map { it.toDomain() }
            row.run.toDomain(sp, emptyList())
        }
    }.flowOn(Dispatchers.Default)

    fun observeTrashedRuns(): Flow<List<RunRecord>> = db.runDao().observeTrashedRunsWithSplits().map { list ->
        list.map { row ->
            val sp = row.splits.sortedBy { it.splitNumber }.map { it.toDomain() }
            row.run.toDomain(sp, emptyList())
        }
    }

    suspend fun getRunDetail(id: String): RunRecord? {
        val run = db.runDao().getRun(id) ?: return null
        val sp = db.runDao().getSplits(id).map { it.toDomain() }
        val pts = db.runDao().getTrackPoints(id).map { it.toDomain() }
        val segs = db.runDao().getDetectedSegments(id).map { it.toDomain() }
        return run.toDomain(sp, pts, segs)
    }

    suspend fun getAllRunsWithSplits(): List<RunRecord> = db.runDao().getAllRunsWithSplits().map { row ->
        val sp = row.splits.sortedBy { it.splitNumber }.map { it.toDomain() }
        row.run.toDomain(sp, emptyList())
    }

    suspend fun getAllRunsForVault(): List<RunRecord> = db.runDao().getAllRunsIncludingTrashed().map { row ->
        val sp = row.splits.sortedBy { it.splitNumber }.map { it.toDomain() }
        val pts = db.runDao().getTrackPoints(row.run.id).map { it.toDomain() }
        row.run.toDomain(sp, pts)
    }

    suspend fun saveRun(run: RunRecord) {
        db.runDao().replaceRunFull(run.toBundle())
    }

    suspend fun saveRuns(runs: List<RunRecord>) {
        if (runs.isEmpty()) return
        db.runDao().replaceRunsBatch(runs.map { it.toBundle() })
    }

    suspend fun saveRunsDeduplicated(incoming: List<RunRecord>): Int {
        if (incoming.isEmpty()) return 0
        val existing = db.runDao().getAllRunsIncludingTrashed()
        val windowMs = 5 * 60_000L
        val fresh = mutableListOf<RunRecord>()
        var upgraded = 0
        for (rec in incoming) {
            val match = existing.firstOrNull { row ->
                abs(row.run.startTimestampMillis - rec.startTimestampMillis) <= windowMs
            }
            if (match == null) {
                fresh.add(rec)
            } else if (match.run.source == RunSource.TIMELINE_IMPORT.name && rec.trackPoints.size > 2) {
                val curPts = db.runDao().getTrackPoints(match.run.id)
                if (curPts.size <= 2) {
                    val domain = match.run.toDomain(match.splits.map { it.toDomain() }, curPts.map { it.toDomain() })
                    saveRun(
                        domain.copy(
                            encodedPolyline = rec.encodedPolyline,
                            rawEncodedPolyline = rec.rawEncodedPolyline,
                            trackPoints = rec.trackPoints
                        )
                    )
                    upgraded++
                }
            }
        }
        if (fresh.isNotEmpty()) saveRuns(fresh)
        return fresh.size + upgraded
    }

    suspend fun moveRunToTrash(id: String) = db.runDao().setRunDeleted(id, System.currentTimeMillis())
    suspend fun renameRun(id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) db.runDao().updateRunName(id, clean)
    }
    suspend fun restoreRun(id: String) = db.runDao().setRunDeleted(id, null)
    suspend fun deleteRunForever(id: String) = db.runDao().deleteRun(id)

    fun observeWalkLogs(): Flow<List<WalkLog>> = db.walkDao().observeWalkLogs().map { list ->
        list.map { WalkLog(it.dateEpochDay, it.distanceMeters, WalkSource.valueOf(it.source), it.stepCount, it.updatedAtMillis) }
    }.flowOn(Dispatchers.Default)

    suspend fun getAllWalkLogs(): List<WalkLog> = db.walkDao().getAll().map {
        WalkLog(it.dateEpochDay, it.distanceMeters, WalkSource.valueOf(it.source), it.stepCount, it.updatedAtMillis)
    }

    suspend fun saveWalkLogs(logs: List<WalkLog>) {
        if (logs.isEmpty()) return
        val list = logs.map {
            WalkLogEntity(it.dateEpochDay, it.distanceMeters, it.source.name, it.stepCount, it.updatedAtMillis)
        }
        db.walkDao().upsertAll(list)
    }

    suspend fun mergeWalkLogs(incoming: List<WalkLog>) {
        if (incoming.isEmpty()) return
        val curMap = db.walkDao().getAll().associateBy { it.dateEpochDay }
        val merged = incoming.map { w ->
            val prev = curMap[w.dateEpochDay]
            val dist = if (prev != null) maxOf(prev.distanceMeters, w.distanceMeters) else w.distanceMeters
            val steps = if (prev != null) maxOf(prev.stepCount, w.stepCount) else w.stepCount
            WalkLogEntity(w.dateEpochDay, dist, w.source.name, steps, w.updatedAtMillis)
        }
        db.walkDao().upsertAll(merged)
    }

    fun observeActivePlan(): Flow<TrainingPlanConfig?> = db.planDao().observeActivePlan().map { row ->
        row?.let { json.decodeFromString<TrainingPlanConfig>(it.configJson) }
    }

    suspend fun getActivePlan(): TrainingPlanConfig? = db.planDao().getActivePlan()?.let {
        json.decodeFromString<TrainingPlanConfig>(it.configJson)
    }

    fun observePlanDays(planId: String): Flow<List<PlannedDay>> = db.planDao().observeDays(planId).map { list ->
        list.map { row ->
            val ck = "${row.id}_${row.status}_${row.linkedRunId}_${row.workoutJson.hashCode()}"
            plannedDayCache[ck] ?: row.toDomain(json).also {
                if (plannedDayCache.size > 400) plannedDayCache.clear()
                plannedDayCache[ck] = it
            }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun activatePlan(cfg: TrainingPlanConfig, days: List<PlannedDay>) {
        val planRow = TrainingPlanEntity(cfg.id, cfg.name, json.encodeToString(cfg), true)
        db.planDao().activatePlanWithDays(planRow, days.map { it.toEntity(json) })
    }

    suspend fun updatePlannedDays(days: List<PlannedDay>) {
        db.planDao().upsertDays(days.map { it.toEntity(json) })
    }

    fun observeWorkoutTemplates(): Flow<List<StructuredWorkout>> = db.workoutTemplateDao().observeTemplates().map { list ->
        list.map { row ->
            val ck = "${row.id}_${row.deletedAtMillis}_${row.workoutJson.hashCode()}"
            templateCache[ck] ?: json.decodeFromString<StructuredWorkout>(row.workoutJson)
                .copy(deletedAtMillis = row.deletedAtMillis)
                .also {
                    if (templateCache.size > 128) templateCache.clear()
                    templateCache[ck] = it
                }
        }
    }.flowOn(Dispatchers.Default)

    fun observeTrashedTemplates(): Flow<List<StructuredWorkout>> = db.workoutTemplateDao().observeTrashedTemplates().map { list ->
        list.map { row -> json.decodeFromString<StructuredWorkout>(row.workoutJson).copy(deletedAtMillis = row.deletedAtMillis) }
    }

    suspend fun getAllTemplatesForVault(): List<StructuredWorkout> =
        db.workoutTemplateDao().getAllIncludingTrashed().map { row ->
            json.decodeFromString<StructuredWorkout>(row.workoutJson).copy(deletedAtMillis = row.deletedAtMillis)
        }

    suspend fun saveWorkoutTemplate(w: StructuredWorkout) {
        db.workoutTemplateDao().upsert(WorkoutTemplateEntity(w.id, w.title, json.encodeToString(w), w.deletedAtMillis))
    }

    suspend fun moveTemplateToTrash(id: String) = db.workoutTemplateDao().setDeleted(id, System.currentTimeMillis())
    suspend fun restoreTemplate(id: String) = db.workoutTemplateDao().setDeleted(id, null)
    suspend fun deleteTemplateForever(id: String) = db.workoutTemplateDao().delete(id)

    suspend fun restoreAllFromTrash() {
        db.runDao().restoreAllTrashedRuns()
        db.workoutTemplateDao().restoreAllTrashed()
    }

    suspend fun emptyTrash() {
        db.runDao().emptyTrashedRuns()
        db.workoutTemplateDao().emptyTrashed()
    }

    private fun RunRecord.toBundle(): FullRunBundle {
        val r = RunEntity(
            id, startTimestampMillis, distanceMeters, durationMillis, paceSecondsPerKm,
            caloriesBurned, name, description, encodedPolyline, workoutId, planDayId,
            source.name, totalSteps, avgCadenceSpm, elevGainM,
            activityKind.name, rawEncodedPolyline, snappedEncodedPolyline, isSnapped,
            snapStreetPct, snapGreeneryPct, snapLoopsKept, strideStabilityPct,
            elevLossM, minAltM, maxAltM, gradeAdjustedPaceSec, deletedAtMillis
        )
        val sp = splits.map {
            RunSplitEntity(0L, id, it.splitNumber, it.distanceMeters, it.durationMillis, it.targetPaceMinSec, it.targetPaceMaxSec, it.avgCadenceSpm, it.elevDeltaM)
        }
        val pts = trackPoints.map { pt ->
            TrackPointEntity(
                0L, id, pt.seqIndex, pt.timestampMillis, pt.elapsedMillis, pt.latitude, pt.longitude,
                pt.altitudeMeters, pt.accuracyMeters, pt.speedMps, pt.bearingDegrees,
                pt.cumulativeDistanceMeters, pt.segmentIndex, pt.isPaused, pt.cadenceSpm
            )
        }
        val segList = segs.map { sg ->
            DetectedSegmentEntity(0L, id, sg.type.name, sg.startMs, sg.endMs, sg.distM, sg.steps, sg.paceSec)
        }
        return FullRunBundle(r, sp, pts, segList)
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

package com.stridecoach.running.core.data.repository

import com.stridecoach.running.core.data.db.DetectedSegmentEntity
import com.stridecoach.running.core.data.db.FullRunBundle
import com.stridecoach.running.core.data.db.PlannedDayEntity
import com.stridecoach.running.core.data.db.RunEntity
import com.stridecoach.running.core.data.db.RunSplitEntity
import com.stridecoach.running.core.data.db.RunWithSplits
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
    private val dayCache = ConcurrentHashMap<String, PlannedDay>()
    private val tplCache = ConcurrentHashMap<String, StructuredWorkout>()

    fun observeRuns(): Flow<List<RunRecord>> =
        db.runDao().observeRunsWithSplits().map { rows -> rows.map(::mapSummaryRow) }.flowOn(Dispatchers.Default)

    fun observeTrashedRuns(): Flow<List<RunRecord>> =
        db.runDao().observeTrashedRunsWithSplits().map { rows -> rows.map(::mapSummaryRow) }

    suspend fun getRunDetail(id: String): RunRecord? {
        val run = db.runDao().getRun(id) ?: return null
        val sp = db.runDao().getSplits(id).map { it.toDomain() }
        val pts = db.runDao().getTrackPoints(id).map { it.toDomain() }
        val segs = db.runDao().getDetectedSegments(id).map { it.toDomain() }
        return run.toDomain(sp, pts, segs)
    }

    suspend fun getAllRunsWithSplits(): List<RunRecord> =
        db.runDao().getAllRunsWithSplits().map(::mapSummaryRow)

    suspend fun getAllRunsForVault(): List<RunRecord> =
        db.runDao().getAllRunsIncludingTrashed().map { mapFullRow(it) }

    suspend fun saveRun(run: RunRecord) { db.runDao().replaceRunFull(run.toBundle()) }

    suspend fun saveRuns(runs: List<RunRecord>) {
        if (runs.isEmpty()) return
        db.runDao().replaceRunsBatch(runs.map { it.toBundle() })
    }

    suspend fun saveRunsDeduplicated(incoming: List<RunRecord>): Int {
        if (incoming.isEmpty()) return 0
        val existing = db.runDao().getAllRunsIncludingTrashed()
        val fresh = mutableListOf<RunRecord>()
        var up = 0
        for (rec in incoming) {
            val match = existing.firstOrNull { abs(it.run.startTimestampMillis - rec.startTimestampMillis) <= 300_000L }
            if (match == null) fresh.add(rec) else if (tryUpgradeTimeline(match, rec)) up++
        }
        if (fresh.isNotEmpty()) saveRuns(fresh)
        return fresh.size + up
    }

    private suspend fun tryUpgradeTimeline(m: RunWithSplits, rec: RunRecord): Boolean {
        if (m.run.source != RunSource.TIMELINE_IMPORT.name || rec.trackPoints.size <= 2) return false
        val pts = db.runDao().getTrackPoints(m.run.id)
        if (pts.size > 2) return false
        val base = m.run.toDomain(m.splits.map { it.toDomain() }, pts.map { it.toDomain() })
        saveRun(base.copy(encodedPolyline = rec.encodedPolyline, rawEncodedPolyline = rec.rawEncodedPolyline, trackPoints = rec.trackPoints))
        return true
    }

    suspend fun moveRunToTrash(id: String) = db.runDao().setRunDeleted(id, System.currentTimeMillis())

    suspend fun renameRun(id: String, name: String) {
        name.trim().takeIf { it.isNotEmpty() }?.let { db.runDao().updateRunName(id, it) }
    }

    suspend fun restoreRun(id: String) = db.runDao().setRunDeleted(id, null)
    suspend fun deleteRunForever(id: String) = db.runDao().deleteRun(id)

    fun observeWalkLogs(): Flow<List<WalkLog>> =
        db.walkDao().observeWalkLogs().map { it.map(::toWalkDomain) }.flowOn(Dispatchers.Default)

    suspend fun getAllWalkLogs(): List<WalkLog> = db.walkDao().getAll().map(::toWalkDomain)

    suspend fun saveWalkLogs(logs: List<WalkLog>) {
        if (logs.isEmpty()) return
        db.walkDao().upsertAll(logs.map { WalkLogEntity(it.dateEpochDay, it.distanceMeters, it.source.name, it.stepCount, it.updatedAtMillis) })
    }

    suspend fun mergeWalkLogs(incoming: List<WalkLog>) {
        if (incoming.isEmpty()) return
        val cur = db.walkDao().getAll().associateBy { it.dateEpochDay }
        val merged = incoming.map { w ->
            val p = cur[w.dateEpochDay]
            WalkLogEntity(w.dateEpochDay, maxOf(p?.distanceMeters ?: 0, w.distanceMeters), w.source.name, maxOf(p?.stepCount ?: 0, w.stepCount), w.updatedAtMillis)
        }
        db.walkDao().upsertAll(merged)
    }

    fun observeActivePlan(): Flow<TrainingPlanConfig?> =
        db.planDao().observeActivePlan().map { row -> row?.let { json.decodeFromString<TrainingPlanConfig>(it.configJson) } }

    suspend fun getActivePlan(): TrainingPlanConfig? =
        db.planDao().getActivePlan()?.let { json.decodeFromString<TrainingPlanConfig>(it.configJson) }

    fun observePlanDays(planId: String): Flow<List<PlannedDay>> =
        db.planDao().observeDays(planId).map { rows -> rows.map(::cachedDay) }.flowOn(Dispatchers.Default)

    suspend fun activatePlan(cfg: TrainingPlanConfig, days: List<PlannedDay>) {
        db.planDao().activatePlanWithDays(TrainingPlanEntity(cfg.id, cfg.name, json.encodeToString(cfg), true), days.map { it.toEntity(json) })
    }

    suspend fun updatePlannedDays(days: List<PlannedDay>) = db.planDao().upsertDays(days.map { it.toEntity(json) })

    fun observeWorkoutTemplates(): Flow<List<StructuredWorkout>> =
        db.workoutTemplateDao().observeTemplates().map { rows -> rows.map(::cachedTpl) }.flowOn(Dispatchers.Default)

    fun observeTrashedTemplates(): Flow<List<StructuredWorkout>> =
        db.workoutTemplateDao().observeTrashedTemplates().map { rows -> rows.map(::toTplDomain) }

    suspend fun getAllTemplatesForVault(): List<StructuredWorkout> =
        db.workoutTemplateDao().getAllIncludingTrashed().map(::toTplDomain)

    suspend fun saveWorkoutTemplate(w: StructuredWorkout) =
        db.workoutTemplateDao().upsert(WorkoutTemplateEntity(w.id, w.title, json.encodeToString(w), w.deletedAtMillis))

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

    private fun mapSummaryRow(r: RunWithSplits): RunRecord =
        r.run.toDomain(r.splits.sortedBy { it.splitNumber }.map { it.toDomain() }, emptyList())

    private suspend fun mapFullRow(r: RunWithSplits): RunRecord {
        val sp = r.splits.sortedBy { it.splitNumber }.map { it.toDomain() }
        val pts = db.runDao().getTrackPoints(r.run.id).map { it.toDomain() }
        return r.run.toDomain(sp, pts)
    }

    private fun cachedDay(row: PlannedDayEntity): PlannedDay {
        val k = "${row.id}_${row.status}_${row.linkedRunId}_${row.workoutJson.hashCode()}"
        return dayCache[k] ?: row.toDomain(json).also {
            if (dayCache.size > 400) dayCache.clear()
            dayCache[k] = it
        }
    }

    private fun cachedTpl(row: WorkoutTemplateEntity): StructuredWorkout {
        val k = "${row.id}_${row.deletedAtMillis}_${row.workoutJson.hashCode()}"
        return tplCache[k] ?: toTplDomain(row).also {
            if (tplCache.size > 128) tplCache.clear()
            tplCache[k] = it
        }
    }

    private fun toTplDomain(row: WorkoutTemplateEntity): StructuredWorkout =
        json.decodeFromString<StructuredWorkout>(row.workoutJson).copy(deletedAtMillis = row.deletedAtMillis)

    private fun toWalkDomain(e: WalkLogEntity) =
        WalkLog(e.dateEpochDay, e.distanceMeters, WalkSource.valueOf(e.source), e.stepCount, e.updatedAtMillis)

    private fun RunRecord.toBundle(): FullRunBundle {
        val r = RunEntity(
            id, startTimestampMillis, distanceMeters, durationMillis, paceSecondsPerKm, caloriesBurned, name,
            description, encodedPolyline, workoutId, planDayId, source.name, totalSteps, avgCadenceSpm, elevGainM,
            activityKind.name, rawEncodedPolyline, snappedEncodedPolyline, isSnapped, snapStreetPct, snapGreeneryPct,
            snapLoopsKept, strideStabilityPct, elevLossM, minAltM, maxAltM, gradeAdjustedPaceSec, deletedAtMillis
        )
        val sp = splits.map { RunSplitEntity(0L, id, it.splitNumber, it.distanceMeters, it.durationMillis, it.targetPaceMinSec, it.targetPaceMaxSec, it.avgCadenceSpm, it.elevDeltaM) }
        val pts = trackPoints.map { TrackPointEntity(0L, id, it.seqIndex, it.timestampMillis, it.elapsedMillis, it.latitude, it.longitude, it.altitudeMeters, it.accuracyMeters, it.speedMps, it.bearingDegrees, it.cumulativeDistanceMeters, it.segmentIndex, it.isPaused, it.cadenceSpm) }
        val sg = segs.map { DetectedSegmentEntity(0L, id, it.type.name, it.startMs, it.endMs, it.distM, it.steps, it.paceSec) }
        return FullRunBundle(r, sp, pts, sg)
    }

    private fun PlannedDayEntity.toDomain(c: Json) = PlannedDay(
        id, planId, dateEpochDay, weekNumber, DayRole.valueOf(dayRole), isCutbackWeek, isTaperWeek,
        isBenchmarkDay, workoutJson?.let { c.decodeFromString<StructuredWorkout>(it) }, PlanDayStatus.valueOf(status), linkedRunId
    )

    private fun PlannedDay.toEntity(c: Json) = PlannedDayEntity(
        id, planId, dateEpochDay, weekNumber, dayRole.name, isCutbackWeek, isTaperWeek,
        isBenchmarkDay, workout?.let { c.encodeToString(it) }, status.name, linkedRunId
    )
}

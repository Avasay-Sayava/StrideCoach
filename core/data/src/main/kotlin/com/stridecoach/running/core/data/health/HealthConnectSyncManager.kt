package com.stridecoach.running.core.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.GeoMath
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class HealthSyncResult(
    val syncedWalkDays: Int,
    val addedRuns: Int,
    val addedWalks: Int = 0,
    val addedCycles: Int = 0,
    val addedOther: Int = 0
)

object HealthConnectSyncManager {
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    fun isAvailable(ctx: Context): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
    }.getOrDefault(false)

    suspend fun hasPermissions(ctx: Context): Boolean {
        if (!isAvailable(ctx)) return false
        return runCatching {
            val client = HealthConnectClient.getOrCreate(ctx)
            client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
        }.getOrDefault(false)
    }

    suspend fun syncRecentDays(
        ctx: Context,
        repo: StrideCoachRepository,
        days: Long = 30L
    ): HealthSyncResult {
        if (!isAvailable(ctx)) return HealthSyncResult(0, 0)
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = runCatching {
            client.permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)

        val walks = readWalkLogs(client, granted, start, end)
        if (walks.isNotEmpty()) repo.mergeWalkLogs(walks)

        val activities = readActivities(client, granted, start, end)
        val addedTotal = if (activities.isNotEmpty()) repo.saveRunsDeduplicated(activities) else 0
        val runsCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.RUNNING } else 0
        val walkCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.WALKING } else 0
        val cycCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.CYCLING } else 0
        val othCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.HIKING || it.activityKind == ActivityKind.OTHER } else 0
        return HealthSyncResult(walks.size, addedTotal, walkCnt, cycCnt, othCnt)
    }

    private suspend fun readWalkLogs(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant
    ): List<WalkLog> {
        val zone = ZoneId.systemDefault()
        val stepsByDay = mutableMapOf<Long, Int>()
        val distByDay = mutableMapOf<Long, Int>()
        val stepPerm = HealthPermission.getReadPermission(StepsRecord::class)
        val distPerm = HealthPermission.getReadPermission(DistanceRecord::class)

        if (stepPerm in granted) {
            val recs = runCatching {
                client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))).records
            }.getOrDefault(emptyList())
            for (r in recs) {
                val day = r.startTime.atZone(zone).toLocalDate().toEpochDay()
                stepsByDay[day] = (stepsByDay[day] ?: 0) + r.count.toInt()
            }
        }
        if (distPerm in granted) {
            val recs = runCatching {
                client.readRecords(ReadRecordsRequest(DistanceRecord::class, TimeRangeFilter.between(start, end))).records
            }.getOrDefault(emptyList())
            for (r in recs) {
                val day = r.startTime.atZone(zone).toLocalDate().toEpochDay()
                distByDay[day] = (distByDay[day] ?: 0) + r.distance.inMeters.roundToInt()
            }
        }

        val allDays = stepsByDay.keys + distByDay.keys
        val now = System.currentTimeMillis()
        return allDays.mapNotNull { day ->
            val st = stepsByDay[day] ?: 0
            val m = distByDay[day] ?: (st * 0.74).roundToInt()
            if (m <= 0 && st <= 0) null else WalkLog(day, m, WalkSource.HEALTH_CONNECT, st, now)
        }
    }

    private suspend fun readActivities(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant
    ): List<RunRecord> {
        val perm = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        if (perm !in granted) return emptyList()
        val sessions = runCatching {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end))).records
        }.getOrDefault(emptyList())

        return sessions.mapNotNull { s -> mapKind(s.exerciseType)?.let { k -> toRunRecord(s, k) } }
    }

    private fun mapKind(exerciseType: Int): ActivityKind? = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ActivityKind.RUNNING
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ActivityKind.WALKING
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> ActivityKind.CYCLING
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> ActivityKind.HIKING
        else -> null
    }

    private fun toRunRecord(s: ExerciseSessionRecord, kind: ActivityKind): RunRecord? {
        val durMs = (s.endTime.toEpochMilli() - s.startTime.toEpochMilli()).coerceAtLeast(1_000L)
        if (durMs < 60_000L) return null
        val routeRes = s.exerciseRouteResult
        val routePts = if (routeRes is ExerciseRouteResult.Data) {
            toTrackPoints(routeRes.exerciseRoute.route, s.startTime.toEpochMilli())
        } else {
            emptyList()
        }
        val dist = routePts.lastOrNull()?.cumulativeDistanceMeters?.roundToInt() ?: 0
        val poly = EncodedPolylineCodec.encode(routePts.map { LatLon(it.latitude, it.longitude) })
        val pace = if (dist >= 100) ((durMs / 1000.0) / (dist / 1000.0)).roundToInt() else 0
        val segs = ActivitySegmentClassifier.classify(routePts)
        val elev = ActivitySegmentClassifier.computeElevationGain(routePts)
        val defaultTitle = when (kind) {
            ActivityKind.RUNNING -> "Synced Run"
            ActivityKind.WALKING -> "Synced Walk"
            ActivityKind.CYCLING -> "Synced Ride"
            ActivityKind.HIKING -> "Synced Hike"
            ActivityKind.OTHER -> "Synced Workout"
        }

        return RunRecord(
            id = "hc_${kind.name.lowercase()}_${s.startTime.toEpochMilli()}",
            startTimestampMillis = s.startTime.toEpochMilli(),
            distanceMeters = dist,
            durationMillis = durMs,
            paceSecondsPerKm = pace,
            caloriesBurned = ((dist / 1000.0) * 62.0).roundToInt(),
            name = s.title?.takeIf { it.isNotBlank() } ?: defaultTitle,
            description = s.notes.orEmpty(),
            encodedPolyline = poly,
            rawEncodedPolyline = poly,
            splits = emptyList(),
            trackPoints = routePts,
            source = RunSource.HEALTH_CONNECT,
            elevGainM = elev,
            segs = segs,
            activityKind = kind
        )
    }

    private fun toTrackPoints(
        raw: List<androidx.health.connect.client.records.ExerciseRoute.Location>,
        startMs: Long
    ): List<TrackPoint> {
        var distAcc = 0.0
        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevMs = startMs
        return raw.mapIndexed { idx, loc ->
            val lat = loc.latitude
            val lon = loc.longitude
            val ts = loc.time.toEpochMilli()
            val pLat = prevLat
            val pLon = prevLon
            val step = if (pLat != null && pLon != null) GeoMath.distanceMeters(pLat, pLon, lat, lon) else 0.0
            distAcc += step
            val dt = ((ts - prevMs) / 1000.0).coerceAtLeast(0.5)
            val spd = if (idx > 0) (step / dt).toFloat() else null
            val brg = if (pLat != null && pLon != null) GeoMath.bearingDegrees(pLat, pLon, lat, lon) else 0f
            prevLat = lat
            prevLon = lon
            prevMs = ts
            TrackPoint(
                seqIndex = idx,
                timestampMillis = ts,
                elapsedMillis = (ts - startMs).coerceAtLeast(0L),
                latitude = lat,
                longitude = lon,
                altitudeMeters = loc.altitude?.inMeters,
                speedMps = spd,
                bearingDegrees = brg,
                cumulativeDistanceMeters = distAcc
            )
        }
    }
}

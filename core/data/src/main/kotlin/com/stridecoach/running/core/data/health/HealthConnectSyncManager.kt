package com.stridecoach.running.core.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.GeoMath
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
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
import kotlin.reflect.KClass

data class HealthSyncResult(
    val syncedWalkDays: Int,
    val addedRuns: Int,
    val addedWalks: Int = 0,
    val addedCycles: Int = 0,
    val addedOther: Int = 0,
    val routesLoaded: Int = 0,
    val noRouteSessions: Int = 0,
    val pendingRouteSessionIds: List<String> = emptyList()
)

private data class ActivityBatch(
    val records: List<RunRecord>,
    val pendingRouteIds: List<String>,
    val noRouteCount: Int
)

internal data class DistanceSlice(
    val startMs: Long,
    val endMs: Long,
    val meters: Double
)

internal data class RouteLocationSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null
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

        val distSlices = readDistanceSlices(client, granted, start, end)
        val walks = readWalkLogs(client, granted, distSlices, start, end)
        if (walks.isNotEmpty()) repo.mergeWalkLogs(walks)

        val actBatch = readActivities(client, granted, distSlices, start, end)
        val activities = actBatch.records
        val pendingIds = actBatch.pendingRouteIds
        val routesLoaded = activities.count { it.trackPoints.size >= 2 }
        val addedTotal = if (activities.isNotEmpty()) repo.saveRunsDeduplicated(activities) else 0
        val runsCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.RUNNING } else 0
        val walkCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.WALKING } else 0
        val cycCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.CYCLING } else 0
        val othCnt = if (addedTotal > 0) activities.count { it.activityKind == ActivityKind.HIKING || it.activityKind == ActivityKind.OTHER } else 0
        return HealthSyncResult(
            syncedWalkDays = walks.size,
            addedRuns = addedTotal,
            addedWalks = walkCnt,
            addedCycles = cycCnt,
            addedOther = othCnt,
            routesLoaded = routesLoaded,
            noRouteSessions = actBatch.noRouteCount,
            pendingRouteSessionIds = pendingIds
        )
    }

    suspend fun applyConsentedRoute(
        ctx: Context,
        repo: StrideCoachRepository,
        sessionId: String,
        route: ExerciseRoute
    ): Boolean {
        if (!isAvailable(ctx)) return false
        val client = HealthConnectClient.getOrCreate(ctx)
        val session = runCatching {
            client.readRecord(ExerciseSessionRecord::class, sessionId).record
        }.getOrNull() ?: return false
        val kind = mapKind(session.exerciseType) ?: return false
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        val aggDist = readSessionDistanceMeters(client, granted, session)
        val rec = toRunRecord(session, kind, aggDist, emptyList(), route) ?: return false
        return repo.saveRunsDeduplicated(listOf(rec)) > 0
    }

    private suspend fun readSessionDistanceMeters(
        client: HealthConnectClient,
        granted: Set<String>,
        s: ExerciseSessionRecord
    ): Int? {
        if (HealthPermission.getReadPermission(DistanceRecord::class) !in granted) return null
        val range = TimeRangeFilter.between(s.startTime, s.endTime)
        val byOrigin = runCatching {
            client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), range, setOf(s.metadata.dataOrigin)))
                .get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.roundToInt()
        }.getOrNull()
        if (byOrigin != null && byOrigin > 0) return byOrigin
        return runCatching {
            client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), range))
                .get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.roundToInt()
        }.getOrNull()?.takeIf { it > 0 }
    }

    private suspend fun <T : Record> readAllRecords(
        client: HealthConnectClient,
        recordType: KClass<T>,
        start: Instant,
        end: Instant
    ): List<T> {
        val out = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(recordType, TimeRangeFilter.between(start, end), pageToken = pageToken)
            )
            out.addAll(resp.records)
            pageToken = resp.pageToken
        } while (!pageToken.isNullOrBlank())
        return out
    }

    private suspend fun readDistanceSlices(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant
    ): List<DistanceSlice> {
        val distPerm = HealthPermission.getReadPermission(DistanceRecord::class)
        if (distPerm !in granted) return emptyList()
        val recs = runCatching {
            readAllRecords(client, DistanceRecord::class, start, end)
        }.getOrDefault(emptyList())
        return recs.map { r ->
            DistanceSlice(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.distance.inMeters)
        }
    }

    private suspend fun readWalkLogs(
        client: HealthConnectClient,
        granted: Set<String>,
        distSlices: List<DistanceSlice>,
        start: Instant,
        end: Instant
    ): List<WalkLog> {
        val zone = ZoneId.systemDefault()
        val stepsByDay = mutableMapOf<Long, Int>()
        val distByDay = mutableMapOf<Long, Int>()
        val stepPerm = HealthPermission.getReadPermission(StepsRecord::class)

        if (stepPerm in granted) {
            val recs = runCatching {
                readAllRecords(client, StepsRecord::class, start, end)
            }.getOrDefault(emptyList())
            for (r in recs) {
                val day = r.startTime.atZone(zone).toLocalDate().toEpochDay()
                stepsByDay[day] = (stepsByDay[day] ?: 0) + r.count.toInt()
            }
        }
        for (slice in distSlices) {
            val day = Instant.ofEpochMilli(slice.startMs).atZone(zone).toLocalDate().toEpochDay()
            distByDay[day] = (distByDay[day] ?: 0) + slice.meters.roundToInt()
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
        distSlices: List<DistanceSlice>,
        start: Instant,
        end: Instant
    ): ActivityBatch {
        val perm = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        if (perm !in granted) return ActivityBatch(emptyList(), emptyList(), 0)
        val sessions = runCatching {
            readAllRecords(client, ExerciseSessionRecord::class, start, end)
        }.getOrDefault(emptyList()).sortedByDescending { it.startTime }

        val out = mutableListOf<RunRecord>()
        val pendingRouteIds = mutableListOf<String>()
        var noRouteCount = 0
        for (s in sessions) {
            val kind = mapKind(s.exerciseType) ?: continue
            when (s.exerciseRouteResult) {
                is ExerciseRouteResult.ConsentRequired -> if (s.metadata.id.isNotBlank()) pendingRouteIds.add(s.metadata.id)
                is ExerciseRouteResult.NoData -> noRouteCount++
                is ExerciseRouteResult.Data -> Unit
            }
            val routeOverride = (s.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute
            val aggDist = readSessionDistanceMeters(client, granted, s)
            toRunRecord(s, kind, aggDist, distSlices, routeOverride)?.let(out::add)
        }
        return ActivityBatch(out, pendingRouteIds, noRouteCount)
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

    private fun toRunRecord(
        s: ExerciseSessionRecord,
        kind: ActivityKind,
        aggregatedDistMeters: Int?,
        distSlices: List<DistanceSlice>,
        routeOverride: ExerciseRoute?
    ): RunRecord? {
        val startMs = s.startTime.toEpochMilli()
        val endMs = s.endTime.toEpochMilli()
        val durMs = (endMs - startMs).coerceAtLeast(1_000L)
        if (durMs < 60_000L) return null

        val routePts = routeOverride?.route?.let { locs ->
            val samples = locs.map {
                RouteLocationSample(it.time.toEpochMilli(), it.latitude, it.longitude, it.altitude?.inMeters)
            }
            buildTrackPointsFromLocations(samples, startMs)
        } ?: emptyList()

        val routeDist = routePts.lastOrNull()?.cumulativeDistanceMeters?.roundToInt() ?: 0
        val fallbackDist = aggregatedDistMeters ?: overlapDistanceMeters(distSlices, startMs, endMs).roundToInt()
        val dist = if (routeDist >= 50) routeDist else fallbackDist
        val poly = EncodedPolylineCodec.encode(routePts.map { LatLon(it.latitude, it.longitude) })
        val pace = if (dist >= 100) ((durMs / 1000.0) / (dist / 1000.0)).roundToInt() else 0
        val segs = ActivitySegmentClassifier.classify(routePts)
        val elev = ActivitySegmentClassifier.computeElevationGain(routePts)
        val splits = RunTailTrimmer.buildKmSplits(routePts)
        val defaultTitle = when (kind) {
            ActivityKind.RUNNING -> "Synced Run"
            ActivityKind.WALKING -> "Synced Walk"
            ActivityKind.CYCLING -> "Synced Ride"
            ActivityKind.HIKING -> "Synced Hike"
            ActivityKind.OTHER -> "Synced Workout"
        }

        return RunRecord(
            id = "hc_${kind.name.lowercase()}_$startMs",
            startTimestampMillis = startMs,
            distanceMeters = dist,
            durationMillis = durMs,
            paceSecondsPerKm = pace,
            caloriesBurned = ((dist / 1000.0) * 62.0).roundToInt(),
            name = s.title?.takeIf { it.isNotBlank() } ?: defaultTitle,
            description = s.notes.orEmpty(),
            encodedPolyline = poly,
            rawEncodedPolyline = poly,
            splits = splits,
            trackPoints = routePts,
            source = RunSource.HEALTH_CONNECT,
            elevGainM = elev,
            segs = segs,
            activityKind = kind
        )
    }

    internal fun overlapDistanceMeters(
        slices: List<DistanceSlice>,
        winStartMs: Long,
        winEndMs: Long
    ): Double {
        if (winEndMs <= winStartMs || slices.isEmpty()) return 0.0
        var total = 0.0
        for (slice in slices) {
            if (slice.endMs <= winStartMs || slice.startMs >= winEndMs) continue
            val sliceDur = (slice.endMs - slice.startMs).coerceAtLeast(1L)
            val oStart = maxOf(slice.startMs, winStartMs)
            val oEnd = minOf(slice.endMs, winEndMs)
            if (oEnd > oStart) {
                total += slice.meters * ((oEnd - oStart).toDouble() / sliceDur.toDouble())
            }
        }
        return total
    }

    internal fun buildTrackPointsFromLocations(
        raw: List<RouteLocationSample>,
        startMs: Long
    ): List<TrackPoint> {
        val validSorted = raw
            .filter { loc ->
                loc.latitude.isFinite() && loc.longitude.isFinite() &&
                    loc.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0 &&
                    !(loc.latitude == 0.0 && loc.longitude == 0.0)
            }
            .sortedBy { it.timestampMs }
        if (validSorted.isEmpty()) return emptyList()

        var distAcc = 0.0
        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevMs = validSorted.first().timestampMs
        return validSorted.mapIndexed { idx, loc ->
            val lat = loc.latitude
            val lon = loc.longitude
            val ts = loc.timestampMs
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
                altitudeMeters = loc.altitudeMeters,
                speedMps = spd,
                bearingDegrees = brg,
                cumulativeDistanceMeters = distAcc
            )
        }
    }
}

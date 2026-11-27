package com.stridecoach.running.core.data.adapter

import android.util.JsonReader
import android.util.JsonToken
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.GeoMath
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

@Serializable
private data class TimelineRoot(
    val semanticSegments: List<SegDto> = emptyList(),
    val rawSignals: List<RawSignalDto> = emptyList()
)

@Serializable
private data class RawSignalDto(val position: RawPositionDto? = null)

@Serializable
private data class RawPositionDto(
    @kotlinx.serialization.SerialName("LatLng")
    val latLngUpper: String? = null,
    val latLng: String? = null,
    val point: String? = null,
    val timestamp: String? = null
)

@Serializable
private data class SegDto(
    val startTime: String? = null,
    val endTime: String? = null,
    val activity: ActDto? = null,
    val timelinePath: List<PathPtDto> = emptyList()
)

@Serializable
private data class PathPtDto(val point: String? = null, val time: String? = null)

@Serializable
private data class LatLngDto(val latLng: String? = null)

@Serializable
private data class ActDto(
    val distanceMeters: Double? = null,
    val topCandidate: CandDto? = null,
    val start: LatLngDto? = null,
    val end: LatLngDto? = null
)

@Serializable
private data class CandDto(val type: String? = null)

data class TimelineImportSummary(
    val walkLogs: List<WalkLog>,
    val filteredGlitches: Int,
    val deduplicatedOverlaps: Int,
    val totalWalkMeters: Int = walkLogs.sumOf { it.distanceMeters },
    val activityRecords: List<RunRecord> = emptyList(),
    val walkCount: Int = 0,
    val runCount: Int = 0,
    val cyclingCount: Int = 0,
    val otherCount: Int = 0
)

private data class ParsedPathSample(val ms: Long?, val ll: LatLon)

private data class ParsedSeg(
    val startMs: Long?,
    val endMs: Long?,
    val distM: Double?,
    val actType: String?,
    val startLl: LatLon?,
    val endLl: LatLon?,
    val pathPts: List<ParsedPathSample>
)

private class MultiActivityCollector(
    private val maxWalkKmh: Double,
    private val minGlitchM: Double,
    private val zone: ZoneId,
    private val runs: List<RunRecord>
) {
    private val byDay = mutableMapOf<Long, Double>()
    private val records = mutableListOf<RunRecord>()
    private val pathPoints = mutableListOf<ParsedPathSample>()
    var glitches = 0
        private set
    var overlaps = 0
        private set
    var walkCount = 0
        private set
    var runCount = 0
        private set
    var cyclingCount = 0
        private set
    var otherCount = 0
        private set

    fun collectPath(pts: List<ParsedPathSample>) {
        if (pts.isNotEmpty()) pathPoints.addAll(pts)
    }

    fun accept(seg: ParsedSeg) {
        val t0 = seg.startMs ?: return
        val t1 = seg.endMs ?: return
        val d = seg.distM ?: return
        val kindStr = seg.actType?.uppercase() ?: return
        if (d <= 20.0 || t1 <= t0) return

        val hrs = ((t1 - t0) / 1000.0).coerceAtLeast(1.0) / 3600.0
        val kmh = (d / 1000.0) / hrs
        val actKind = mapKind(kindStr) ?: return
        val maxSpeed = maxSpeedFor(actKind)
        if (kmh > maxSpeed && d > minGlitchM) {
            glitches++
            return
        }
        if (overlapsRun(t0, t1)) {
            overlaps++
            return
        }
        when (actKind) {
            ActivityKind.WALKING -> {
                walkCount++
                val day = Instant.ofEpochMilli(t0).atZone(zone).toLocalDate().toEpochDay()
                byDay[day] = (byDay[day] ?: 0.0) + d
                if (d >= 800.0) records.add(toRecord(seg, actKind, t0, t1, d))
            }
            ActivityKind.RUNNING -> {
                runCount++
                if (d >= 400.0) records.add(toRecord(seg, actKind, t0, t1, d))
            }
            ActivityKind.CYCLING -> {
                cyclingCount++
                if (d >= 1000.0) records.add(toRecord(seg, actKind, t0, t1, d))
            }
            ActivityKind.HIKING, ActivityKind.OTHER -> {
                otherCount++
                if (d >= 600.0) records.add(toRecord(seg, actKind, t0, t1, d))
            }
        }
    }

    fun build(nowMs: Long): TimelineImportSummary {
        val logs = byDay.entries.sortedBy { it.key }.map { (day, m) ->
            WalkLog(day, m.toInt(), WalkSource.TIMELINE_IMPORT, 0, nowMs)
        }
        val sortedPath = pathPoints
            .filter { it.ms != null }
            .sortedBy { it.ms!! }
        val enriched = records.map { r -> attachPathIfAvailable(r, sortedPath) }
        return TimelineImportSummary(
            walkLogs = logs,
            filteredGlitches = glitches,
            deduplicatedOverlaps = overlaps,
            totalWalkMeters = logs.sumOf { it.distanceMeters },
            activityRecords = enriched,
            walkCount = walkCount,
            runCount = runCount,
            cyclingCount = cyclingCount,
            otherCount = otherCount
        )
    }

    private fun attachPathIfAvailable(rec: RunRecord, sortedPath: List<ParsedPathSample>): RunRecord {
        if (rec.trackPoints.size > 2 || sortedPath.isEmpty()) return rec
        val t0 = rec.startTimestampMillis
        val t1 = t0 + rec.durationMillis
        var matched = sliceTimeRange(sortedPath, t0, t1)
        if (matched.size < 2) {
            matched = sliceTimeRange(sortedPath, t0 - 120_000L, t1 + 120_000L)
        }
        if (matched.isEmpty()) return rec

        val rawSamples = mutableListOf<ParsedPathSample>()
        val startPt = rec.trackPoints.firstOrNull()?.let { LatLon(it.latitude, it.longitude) }
        val endPt = rec.trackPoints.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
        if (startPt != null && GeoMath.distanceMeters(startPt.latitude, startPt.longitude, matched.first().ll.latitude, matched.first().ll.longitude) > 15.0) {
            rawSamples.add(ParsedPathSample(t0, startPt))
        }
        for (sample in matched) {
            val prev = rawSamples.lastOrNull()?.ll
            if (prev == null || GeoMath.distanceMeters(prev.latitude, prev.longitude, sample.ll.latitude, sample.ll.longitude) >= 2.0) {
                rawSamples.add(sample)
            }
        }
        if (endPt != null && rawSamples.isNotEmpty() && GeoMath.distanceMeters(rawSamples.last().ll.latitude, rawSamples.last().ll.longitude, endPt.latitude, endPt.longitude) > 15.0) {
            rawSamples.add(ParsedPathSample(t1, endPt))
        }

        if (rawSamples.size < 2 || (rawSamples.size <= 2 && rec.encodedPolyline.isNotBlank())) return rec

        val avgSpeedMps = if (rec.durationMillis > 0L) (rec.distanceMeters / (rec.durationMillis / 1000.0)).toFloat().coerceIn(0.5f, 18f) else 2.8f
        val pts = ArrayList<TrackPoint>(rawSamples.size)
        var cumDist = 0.0
        for (idx in rawSamples.indices) {
            val cur = rawSamples[idx]
            val prev = if (idx > 0) rawSamples[idx - 1] else null
            val stepM = if (prev != null) {
                GeoMath.distanceMeters(prev.ll.latitude, prev.ll.longitude, cur.ll.latitude, cur.ll.longitude)
            } else 0.0
            cumDist += stepM
            val curMs = (cur.ms ?: t0).coerceAtLeast(t0)
            val prevMs = (prev?.ms ?: t0).coerceAtLeast(t0)
            val dtSec = ((curMs - prevMs) / 1000.0)
            val segSpd = if (prev != null && dtSec >= 0.5) {
                (stepM / dtSec).toFloat().coerceIn(0f, 18f)
            } else avgSpeedMps
            pts.add(
                TrackPoint(
                    seqIndex = idx,
                    timestampMillis = curMs,
                    elapsedMillis = (curMs - t0).coerceAtLeast(0L),
                    latitude = cur.ll.latitude,
                    longitude = cur.ll.longitude,
                    cumulativeDistanceMeters = cumDist,
                    speedMps = segSpd
                )
            )
        }
        val poly = EncodedPolylineCodec.encode(rawSamples.map { it.ll })
        return rec.copy(encodedPolyline = poly, rawEncodedPolyline = poly, trackPoints = pts)
    }

    private fun sliceTimeRange(sorted: List<ParsedPathSample>, loMs: Long, hiMs: Long): List<ParsedPathSample> {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if ((sorted[mid].ms ?: 0L) < loMs) lo = mid + 1 else hi = mid
        }
        val out = mutableListOf<ParsedPathSample>()
        var i = lo
        while (i < sorted.size) {
            val s = sorted[i]
            val t = s.ms ?: break
            if (t > hiMs) break
            out.add(s)
            i++
        }
        return out
    }

    private fun toRecord(seg: ParsedSeg, kind: ActivityKind, t0: Long, t1: Long, d: Double): RunRecord {
        val durMs = (t1 - t0).coerceAtLeast(1_000L)
        val distM = d.roundToInt().coerceAtLeast(1)
        val pace = ((durMs / 1000.0) / (distM / 1000.0)).roundToInt()
        val coords = listOfNotNull(seg.startLl, seg.endLl)
        val poly = if (coords.size >= 2) EncodedPolylineCodec.encode(coords) else ""
        val pts = if (coords.size >= 2) {
            val dStep = GeoMath.distanceMeters(coords[0].latitude, coords[0].longitude, coords[1].latitude, coords[1].longitude)
            listOf(
                TrackPoint(0, t0, 0L, coords[0].latitude, coords[0].longitude, speedMps = (distM / (durMs / 1000.0)).toFloat()),
                TrackPoint(1, t1, durMs, coords[1].latitude, coords[1].longitude, cumulativeDistanceMeters = dStep, speedMps = (distM / (durMs / 1000.0)).toFloat())
            )
        } else emptyList()
        val title = when (kind) {
            ActivityKind.RUNNING -> "Timeline Run"
            ActivityKind.WALKING -> "Timeline Walk"
            ActivityKind.CYCLING -> "Timeline Ride"
            ActivityKind.HIKING -> "Timeline Hike"
            ActivityKind.OTHER -> "Timeline Activity"
        }
        return RunRecord(
            id = "tl_${kind.name.lowercase()}_$t0",
            startTimestampMillis = t0,
            distanceMeters = distM,
            durationMillis = durMs,
            paceSecondsPerKm = pace,
            caloriesBurned = ((distM / 1000.0) * if (kind == ActivityKind.CYCLING) 28.0 else 58.0).roundToInt(),
            name = title,
            description = "Imported from Google Timeline",
            encodedPolyline = poly,
            rawEncodedPolyline = poly,
            splits = emptyList(),
            trackPoints = pts,
            source = RunSource.TIMELINE_IMPORT,
            activityKind = kind
        )
    }

    private fun mapKind(s: String): ActivityKind? = when {
        "WALK" in s -> ActivityKind.WALKING
        "RUN" in s -> ActivityKind.RUNNING
        "CYCL" in s || "BIK" in s -> ActivityKind.CYCLING
        "HIK" in s -> ActivityKind.HIKING
        else -> null
    }

    private fun maxSpeedFor(k: ActivityKind): Double = when (k) {
        ActivityKind.WALKING -> maxWalkKmh
        ActivityKind.RUNNING -> 24.0
        ActivityKind.CYCLING -> 55.0
        ActivityKind.HIKING, ActivityKind.OTHER -> 12.0
    }

    private fun overlapsRun(t0: Long, t1: Long): Boolean = runs.any { r ->
        val r0 = r.startTimestampMillis
        val r1 = r0 + r.durationMillis
        t0 < r1 && t1 > r0
    }
}

class TimelineJsonImporter(
    private val maxKmh: Double = 7.5,
    private val minGlitchM: Double = 500.0,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun importTimeline(
        raw: String,
        runs: List<RunRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): TimelineImportSummary {
        require(raw.isNotBlank()) { "Timeline JSON is empty" }
        val root = json.decodeFromString<TimelineRoot>(raw)
        val col = MultiActivityCollector(maxKmh, minGlitchM, zone, runs)
        for (seg in root.semanticSegments) {
            val path = seg.timelinePath.mapNotNull { p -> parseLatLon(p.point)?.let { ParsedPathSample(parseMs(p.time), it) } }
            col.collectPath(path)
            val act = seg.activity ?: continue
            col.accept(
                ParsedSeg(
                    parseMs(seg.startTime), parseMs(seg.endTime), act.distanceMeters,
                    act.topCandidate?.type, parseLatLon(act.start?.latLng), parseLatLon(act.end?.latLng), path
                )
            )
        }
        val rawPts = root.rawSignals.mapNotNull { sig ->
            val pos = sig.position ?: return@mapNotNull null
            val ll = parseLatLon(pos.latLngUpper ?: pos.latLng ?: pos.point) ?: return@mapNotNull null
            val ms = parseMs(pos.timestamp) ?: return@mapNotNull null
            ParsedPathSample(ms, ll)
        }
        col.collectPath(rawPts)
        return col.build(nowMs)
    }

    fun importTimelineStream(
        stream: InputStream,
        runs: List<RunRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): TimelineImportSummary {
        val col = MultiActivityCollector(maxKmh, minGlitchM, zone, runs)
        val br = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        JsonReader(br).use { jr -> readRoot(jr, col) }
        return col.build(nowMs)
    }

    private fun readRoot(jr: JsonReader, col: MultiActivityCollector) {
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "semanticSegments" -> if (jr.peek() == JsonToken.BEGIN_ARRAY) readSegments(jr, col) else jr.skipValue()
                "rawSignals" -> if (jr.peek() == JsonToken.BEGIN_ARRAY) readRawSignals(jr, col) else jr.skipValue()
                else -> jr.skipValue()
            }
        }
        jr.endObject()
    }

    private fun readRawSignals(jr: JsonReader, col: MultiActivityCollector) {
        val batch = mutableListOf<ParsedPathSample>()
        jr.beginArray()
        while (jr.hasNext()) {
            if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
            var sample: ParsedPathSample? = null
            jr.beginObject()
            while (jr.hasNext()) {
                if (jr.nextName() == "position" && jr.peek() == JsonToken.BEGIN_OBJECT) {
                    sample = readRawPosition(jr)
                } else {
                    jr.skipValue()
                }
            }
            jr.endObject()
            if (sample != null) batch.add(sample)
        }
        jr.endArray()
        col.collectPath(batch)
    }

    private fun readRawPosition(jr: JsonReader): ParsedPathSample? {
        var llStr: String? = null
        var tsStr: String? = null
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "LatLng", "latLng", "point" -> llStr = nextStr(jr)
                "timestamp", "time" -> tsStr = nextStr(jr)
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        val ll = parseLatLon(llStr) ?: return null
        val ms = parseMs(tsStr) ?: return null
        return ParsedPathSample(ms, ll)
    }

    private fun readSegments(jr: JsonReader, col: MultiActivityCollector) {
        jr.beginArray()
        while (jr.hasNext()) {
            if (jr.peek() == JsonToken.BEGIN_OBJECT) {
                val seg = readOneSeg(jr)
                col.collectPath(seg.pathPts)
                col.accept(seg)
            } else {
                jr.skipValue()
            }
        }
        jr.endArray()
    }

    private fun readOneSeg(jr: JsonReader): ParsedSeg {
        var t0: Long? = null
        var t1: Long? = null
        var dist: Double? = null
        var kind: String? = null
        var sLl: LatLon? = null
        var eLl: LatLon? = null
        val pts = mutableListOf<ParsedPathSample>()
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "startTime" -> t0 = nextStr(jr)?.let(::parseMs)
                "endTime" -> t1 = nextStr(jr)?.let(::parseMs)
                "timelinePath" -> if (jr.peek() == JsonToken.BEGIN_ARRAY) pts.addAll(readPathArr(jr)) else jr.skipValue()
                "activity" -> if (jr.peek() == JsonToken.BEGIN_OBJECT) {
                    val info = readActivity(jr)
                    dist = info.first
                    kind = info.second
                    sLl = info.third
                    eLl = info.fourth
                } else jr.skipValue()
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        return ParsedSeg(t0, t1, dist, kind, sLl, eLl, pts)
    }

    private fun readPathArr(jr: JsonReader): List<ParsedPathSample> {
        val list = mutableListOf<ParsedPathSample>()
        jr.beginArray()
        while (jr.hasNext()) {
            if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
            var ptStr: String? = null
            var tmStr: String? = null
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "point" -> ptStr = nextStr(jr)
                    "time" -> tmStr = nextStr(jr)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            parseLatLon(ptStr)?.let { list.add(ParsedPathSample(parseMs(tmStr), it)) }
        }
        jr.endArray()
        return list
    }

    private data class ActQuad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun readActivity(jr: JsonReader): ActQuad<Double?, String?, LatLon?, LatLon?> {
        var dist: Double? = null
        var kind: String? = null
        var sLl: LatLon? = null
        var eLl: LatLon? = null
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "distanceMeters" -> dist = nextNum(jr)
                "topCandidate" -> kind = if (jr.peek() == JsonToken.BEGIN_OBJECT) readTopKind(jr) else { jr.skipValue(); null }
                "start" -> sLl = if (jr.peek() == JsonToken.BEGIN_OBJECT) readLatLngObj(jr) else { jr.skipValue(); null }
                "end" -> eLl = if (jr.peek() == JsonToken.BEGIN_OBJECT) readLatLngObj(jr) else { jr.skipValue(); null }
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        return ActQuad(dist, kind, sLl, eLl)
    }

    private fun readLatLngObj(jr: JsonReader): LatLon? {
        var s: String? = null
        jr.beginObject()
        while (jr.hasNext()) {
            if (jr.nextName() == "latLng") s = nextStr(jr) else jr.skipValue()
        }
        jr.endObject()
        return parseLatLon(s)
    }

    private fun readTopKind(jr: JsonReader): String? {
        var kind: String? = null
        jr.beginObject()
        while (jr.hasNext()) {
            if (jr.nextName() == "type") kind = nextStr(jr) else jr.skipValue()
        }
        jr.endObject()
        return kind
    }

    private fun parseLatLon(raw: String?): LatLon? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("°", "").replace("geo:", "").trim()
        val parts = cleaned.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return if (lat in -89.9..89.9 && lon in -179.9..179.9) LatLon(lat, lon) else null
    }

    private fun nextStr(jr: JsonReader): String? {
        if (jr.peek() == JsonToken.NULL) { jr.nextNull(); return null }
        return jr.nextString()
    }

    private fun nextNum(jr: JsonReader): Double? {
        if (jr.peek() == JsonToken.NULL) { jr.nextNull(); return null }
        return jr.nextDouble()
    }

    private fun parseMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    }
}

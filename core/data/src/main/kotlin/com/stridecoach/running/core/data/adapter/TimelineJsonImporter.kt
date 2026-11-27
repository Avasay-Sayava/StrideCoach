package com.stridecoach.running.core.data.adapter

import android.util.JsonReader
import android.util.JsonToken
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec as Codec
import com.stridecoach.running.core.domain.engine.GeoMath
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
import kotlinx.serialization.SerialName
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
    @SerialName("LatLng") val latLngUpper: String? = null,
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

private data class Sample(val ms: Long?, val ll: LatLon)

private data class Seg(
    val t0: Long?,
    val t1: Long?,
    val dist: Double?,
    val kind: String?,
    val sLl: LatLon?,
    val eLl: LatLon?,
    val pts: List<Sample>
)

private data class ActInfo(val dist: Double?, val kind: String?, val sLl: LatLon?, val eLl: LatLon?)

private class TlState(
    val maxWalkKmh: Double,
    val minGlitchM: Double,
    val zone: ZoneId,
    val runs: List<RunRecord>
) {
    val byDay = mutableMapOf<Long, Double>()
    val recs = mutableListOf<RunRecord>()
    val path = mutableListOf<Sample>()
    var glitches = 0
    var overlaps = 0
    var walks = 0
    var runCnt = 0
    var bikes = 0
    var other = 0
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
        val st = TlState(maxKmh, minGlitchM, zone, runs)
        for (seg in root.semanticSegments) {
            val pts = seg.timelinePath.mapNotNull { p -> parseLl(p.point)?.let { Sample(parseMs(p.time), it) } }
            addPath(st, pts)
            val act = seg.activity ?: continue
            val parsed = Seg(
                parseMs(seg.startTime), parseMs(seg.endTime), act.distanceMeters,
                act.topCandidate?.type, parseLl(act.start?.latLng), parseLl(act.end?.latLng), pts
            )
            acceptSeg(st, parsed)
        }
        addPath(st, root.rawSignals.mapNotNull(::toRawSample))
        return buildSummary(st, nowMs)
    }

    fun importTimelineStream(
        stream: InputStream,
        runs: List<RunRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): TimelineImportSummary {
        val st = TlState(maxKmh, minGlitchM, zone, runs)
        val br = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        JsonReader(br).use { jr -> readRoot(jr, st) }
        return buildSummary(st, nowMs)
    }
}

private fun toRawSample(sig: RawSignalDto): Sample? {
    val pos = sig.position ?: return null
    val ll = parseLl(pos.latLngUpper ?: pos.latLng ?: pos.point) ?: return null
    val ms = parseMs(pos.timestamp) ?: return null
    return Sample(ms, ll)
}

private fun addPath(st: TlState, pts: List<Sample>) {
    if (pts.isNotEmpty()) st.path.addAll(pts)
}

private fun acceptSeg(st: TlState, seg: Seg) {
    val t0 = seg.t0 ?: return
    val t1 = seg.t1 ?: return
    val d = seg.dist ?: return
    val raw = seg.kind?.uppercase() ?: return
    if (d <= 20.0 || t1 <= t0) return

    val kind = mapKind(raw) ?: return
    val kmh = (d / 1000.0) / (((t1 - t0) / 1000.0).coerceAtLeast(1.0) / 3600.0)
    if (kmh > maxKmhFor(kind, st.maxWalkKmh) && d > st.minGlitchM) { st.glitches++; return }
    if (overlapsAny(st.runs, t0, t1)) { st.overlaps++; return }
    recordActivity(st, seg, kind, t0, t1, d)
}

private fun recordActivity(st: TlState, seg: Seg, kind: ActivityKind, t0: Long, t1: Long, d: Double) {
    when (kind) {
        ActivityKind.WALKING -> {
            st.walks++
            val day = Instant.ofEpochMilli(t0).atZone(st.zone).toLocalDate().toEpochDay()
            st.byDay[day] = (st.byDay[day] ?: 0.0) + d
            if (d >= 800.0) st.recs.add(makeRecord(seg, kind, t0, t1, d))
        }
        ActivityKind.RUNNING -> { st.runCnt++; if (d >= 400.0) st.recs.add(makeRecord(seg, kind, t0, t1, d)) }
        ActivityKind.CYCLING -> { st.bikes++; if (d >= 1000.0) st.recs.add(makeRecord(seg, kind, t0, t1, d)) }
        ActivityKind.HIKING, ActivityKind.OTHER -> { st.other++; if (d >= 600.0) st.recs.add(makeRecord(seg, kind, t0, t1, d)) }
    }
}

private fun buildSummary(st: TlState, nowMs: Long): TimelineImportSummary {
    val logs = st.byDay.entries.sortedBy { it.key }.map { (day, m) ->
        WalkLog(day, m.toInt(), WalkSource.TIMELINE_IMPORT, 0, nowMs)
    }
    val sorted = st.path.filter { it.ms != null }.sortedBy { it.ms!! }
    val recs = st.recs.map { attachPath(it, sorted) }
    return TimelineImportSummary(
        walkLogs = logs,
        filteredGlitches = st.glitches,
        deduplicatedOverlaps = st.overlaps,
        totalWalkMeters = logs.sumOf { it.distanceMeters },
        activityRecords = recs,
        walkCount = st.walks,
        runCount = st.runCnt,
        cyclingCount = st.bikes,
        otherCount = st.other
    )
}

private fun attachPath(rec: RunRecord, sorted: List<Sample>): RunRecord {
    if (rec.trackPoints.size > 2 || sorted.isEmpty()) return rec
    val t0 = rec.startTimestampMillis
    val t1 = t0 + rec.durationMillis
    val raw = matchedSamples(rec, sorted, t0, t1)
    if (raw.size < 2 || (raw.size <= 2 && rec.encodedPolyline.isNotBlank())) return rec
    val avg = if (rec.durationMillis > 0L) (rec.distanceMeters / (rec.durationMillis / 1000.0)).toFloat().coerceIn(0.5f, 18f) else 2.8f
    val pts = buildTrackPts(raw, t0, avg)
    val poly = Codec.encode(raw.map { it.ll })
    return rec.copy(encodedPolyline = poly, rawEncodedPolyline = poly, trackPoints = pts)
}

private fun matchedSamples(rec: RunRecord, sorted: List<Sample>, t0: Long, t1: Long): List<Sample> {
    var slice = sliceTime(sorted, t0, t1)
    if (slice.size < 2) slice = sliceTime(sorted, t0 - 120_000L, t1 + 120_000L)
    if (slice.isEmpty()) return emptyList()
    val out = mutableListOf<Sample>()
    val sPt = rec.trackPoints.firstOrNull()?.let { LatLon(it.latitude, it.longitude) }
    val ePt = rec.trackPoints.lastOrNull()?.let { LatLon(it.latitude, it.longitude) }
    if (sPt != null && distM(sPt, slice.first().ll) > 15.0) out.add(Sample(t0, sPt))
    for (item in slice) {
        val prev = out.lastOrNull()?.ll
        if (prev == null || distM(prev, item.ll) >= 2.0) out.add(item)
    }
    if (ePt != null && out.isNotEmpty() && distM(out.last().ll, ePt) > 15.0) out.add(Sample(t1, ePt))
    return out
}

private fun distM(a: LatLon, b: LatLon) = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

private fun buildTrackPts(raw: List<Sample>, t0: Long, avgSpd: Float): List<TrackPoint> {
    val pts = ArrayList<TrackPoint>(raw.size)
    var cum = 0.0
    for (idx in raw.indices) {
        val cur = raw[idx]
        val prev = raw.getOrNull(idx - 1)
        val step = if (prev != null) distM(prev.ll, cur.ll) else 0.0
        cum += step
        val curMs = (cur.ms ?: t0).coerceAtLeast(t0)
        val prevMs = (prev?.ms ?: t0).coerceAtLeast(t0)
        val dt = (curMs - prevMs) / 1000.0
        val spd = if (prev != null && dt >= 0.5) (step / dt).toFloat().coerceIn(0f, 18f) else avgSpd
        pts.add(TrackPoint(idx, curMs, (curMs - t0).coerceAtLeast(0L), cur.ll.latitude, cur.ll.longitude, cumulativeDistanceMeters = cum, speedMps = spd))
    }
    return pts
}

private fun sliceTime(sorted: List<Sample>, loMs: Long, hiMs: Long): List<Sample> {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if ((sorted[mid].ms ?: 0L) < loMs) lo = mid + 1 else hi = mid
    }
    val out = mutableListOf<Sample>()
    for (i in lo until sorted.size) {
        val s = sorted[i]
        val t = s.ms ?: break
        if (t > hiMs) break
        out.add(s)
    }
    return out
}

private fun makeRecord(seg: Seg, kind: ActivityKind, t0: Long, t1: Long, d: Double): RunRecord {
    val dur = (t1 - t0).coerceAtLeast(1_000L)
    val dist = d.roundToInt().coerceAtLeast(1)
    val pace = ((dur / 1000.0) / (dist / 1000.0)).roundToInt()
    val coords = listOfNotNull(seg.sLl, seg.eLl)
    val poly = if (coords.size >= 2) Codec.encode(coords) else ""
    val spd = (dist / (dur / 1000.0)).toFloat()
    val pts = if (coords.size >= 2) listOf(
        TrackPoint(0, t0, 0L, coords[0].latitude, coords[0].longitude, speedMps = spd),
        TrackPoint(1, t1, dur, coords[1].latitude, coords[1].longitude, cumulativeDistanceMeters = distM(coords[0], coords[1]), speedMps = spd)
    ) else emptyList()
    val kcal = ((dist / 1000.0) * if (kind == ActivityKind.CYCLING) 28.0 else 58.0).roundToInt()
    return RunRecord(
        id = "tl_${kind.name.lowercase()}_$t0", startTimestampMillis = t0, distanceMeters = dist,
        durationMillis = dur, paceSecondsPerKm = pace, caloriesBurned = kcal, name = titleFor(kind),
        description = "Imported from Google Timeline", encodedPolyline = poly, rawEncodedPolyline = poly,
        splits = emptyList(), trackPoints = pts, source = RunSource.TIMELINE_IMPORT, activityKind = kind
    )
}

private fun titleFor(k: ActivityKind): String = when (k) {
    ActivityKind.RUNNING -> "Timeline Run"
    ActivityKind.WALKING -> "Timeline Walk"
    ActivityKind.CYCLING -> "Timeline Ride"
    ActivityKind.HIKING -> "Timeline Hike"
    ActivityKind.OTHER -> "Timeline Activity"
}

private fun mapKind(s: String): ActivityKind? = when {
    "WALK" in s -> ActivityKind.WALKING
    "RUN" in s -> ActivityKind.RUNNING
    "CYCL" in s || "BIK" in s -> ActivityKind.CYCLING
    "HIK" in s -> ActivityKind.HIKING
    else -> null
}

private fun maxKmhFor(k: ActivityKind, walk: Double): Double = when (k) {
    ActivityKind.WALKING -> walk
    ActivityKind.RUNNING -> 24.0
    ActivityKind.CYCLING -> 55.0
    ActivityKind.HIKING, ActivityKind.OTHER -> 12.0
}

private fun overlapsAny(runs: List<RunRecord>, t0: Long, t1: Long): Boolean = runs.any { r ->
    val r0 = r.startTimestampMillis
    t0 < r0 + r.durationMillis && t1 > r0
}

private fun readRoot(jr: JsonReader, st: TlState) {
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "semanticSegments" -> if (jr.peek() == JsonToken.BEGIN_ARRAY) readSegments(jr, st) else jr.skipValue()
            "rawSignals" -> if (jr.peek() == JsonToken.BEGIN_ARRAY) readRawSignals(jr, st) else jr.skipValue()
            else -> jr.skipValue()
        }
    }
    jr.endObject()
}

private fun readRawSignals(jr: JsonReader, st: TlState) {
    val batch = mutableListOf<Sample>()
    jr.beginArray()
    while (jr.hasNext()) {
        if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
        readSignalObj(jr)?.let(batch::add)
    }
    jr.endArray()
    addPath(st, batch)
}

private fun readSignalObj(jr: JsonReader): Sample? {
    var out: Sample? = null
    jr.beginObject()
    while (jr.hasNext()) {
        if (jr.nextName() == "position" && jr.peek() == JsonToken.BEGIN_OBJECT) out = readRawPos(jr)
        else jr.skipValue()
    }
    jr.endObject()
    return out
}

private fun readRawPos(jr: JsonReader): Sample? {
    var ll: String? = null
    var ts: String? = null
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "LatLng", "latLng", "point" -> ll = nextStr(jr)
            "timestamp", "time" -> ts = nextStr(jr)
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    val pt = parseLl(ll) ?: return null
    val ms = parseMs(ts) ?: return null
    return Sample(ms, pt)
}

private fun readSegments(jr: JsonReader, st: TlState) {
    jr.beginArray()
    while (jr.hasNext()) {
        if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
        val seg = readOneSeg(jr)
        addPath(st, seg.pts)
        acceptSeg(st, seg)
    }
    jr.endArray()
}

private fun readOneSeg(jr: JsonReader): Seg {
    var t0: Long? = null; var t1: Long? = null; var act = ActInfo(null, null, null, null)
    val pts = mutableListOf<Sample>()
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "startTime" -> t0 = nextStr(jr)?.let(::parseMs)
            "endTime" -> t1 = nextStr(jr)?.let(::parseMs)
            "timelinePath" -> if (jr.peek() == JsonToken.BEGIN_ARRAY) readPathInto(jr, pts) else jr.skipValue()
            "activity" -> if (jr.peek() == JsonToken.BEGIN_OBJECT) act = readAct(jr) else jr.skipValue()
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return Seg(t0, t1, act.dist, act.kind, act.sLl, act.eLl, pts)
}

private fun readPathInto(jr: JsonReader, out: MutableList<Sample>) {
    jr.beginArray()
    while (jr.hasNext()) {
        if (jr.peek() == JsonToken.BEGIN_OBJECT) readPathPt(jr)?.let(out::add) else jr.skipValue()
    }
    jr.endArray()
}

private fun readPathPt(jr: JsonReader): Sample? {
    var pt: String? = null; var tm: String? = null
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "point" -> pt = nextStr(jr)
            "time" -> tm = nextStr(jr)
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return parseLl(pt)?.let { Sample(parseMs(tm), it) }
}

private fun readAct(jr: JsonReader): ActInfo {
    var d: Double? = null; var k: String? = null; var s: LatLon? = null; var e: LatLon? = null
    jr.beginObject()
    while (jr.hasNext()) {
        when (jr.nextName()) {
            "distanceMeters" -> d = nextNum(jr)
            "topCandidate" -> k = if (jr.peek() == JsonToken.BEGIN_OBJECT) readFieldStr(jr, "type") else { jr.skipValue(); null }
            "start" -> s = if (jr.peek() == JsonToken.BEGIN_OBJECT) readFieldStr(jr, "latLng")?.let(::parseLl) else { jr.skipValue(); null }
            "end" -> e = if (jr.peek() == JsonToken.BEGIN_OBJECT) readFieldStr(jr, "latLng")?.let(::parseLl) else { jr.skipValue(); null }
            else -> jr.skipValue()
        }
    }
    jr.endObject()
    return ActInfo(d, k, s, e)
}

private fun readFieldStr(jr: JsonReader, key: String): String? {
    var v: String? = null
    jr.beginObject()
    while (jr.hasNext()) { if (jr.nextName() == key) v = nextStr(jr) else jr.skipValue() }
    jr.endObject()
    return v
}

private fun parseLl(raw: String?): LatLon? {
    if (raw.isNullOrBlank()) return null
    val p = raw.replace("°", "").replace("geo:", "").trim().split(',')
    if (p.size < 2) return null
    val lat = p[0].trim().toDoubleOrNull() ?: return null
    val lon = p[1].trim().toDoubleOrNull() ?: return null
    return if (lat in -89.9..89.9 && lon in -179.9..179.9) LatLon(lat, lon) else null
}

private fun nextStr(jr: JsonReader): String? =
    if (jr.peek() == JsonToken.NULL) { jr.nextNull(); null } else jr.nextString()

private fun nextNum(jr: JsonReader): Double? =
    if (jr.peek() == JsonToken.NULL) { jr.nextNull(); null } else jr.nextDouble()

private fun parseMs(raw: String?): Long? =
    if (raw.isNullOrBlank()) null else runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()

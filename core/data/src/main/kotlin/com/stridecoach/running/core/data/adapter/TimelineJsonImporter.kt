package com.stridecoach.running.core.data.adapter

import android.util.JsonReader
import android.util.JsonToken
import com.stridecoach.running.core.domain.model.RunRecord
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

@Serializable
private data class TimelineRoot(val semanticSegments: List<SegDto> = emptyList())

@Serializable
private data class SegDto(
    val startTime: String? = null,
    val endTime: String? = null,
    val activity: ActDto? = null
)

@Serializable
private data class ActDto(
    val distanceMeters: Double? = null,
    val topCandidate: CandDto? = null
)

@Serializable
private data class CandDto(val type: String? = null)

data class TimelineImportSummary(
    val walkLogs: List<WalkLog>,
    val filteredGlitches: Int,
    val deduplicatedOverlaps: Int,
    val totalWalkMeters: Int = walkLogs.sumOf { it.distanceMeters }
)

private data class ParsedSeg(
    val startMs: Long?,
    val endMs: Long?,
    val distM: Double?,
    val actType: String?
)

private class WalkAggregator(
    private val maxKmh: Double,
    private val minGlitchM: Double,
    private val zone: ZoneId,
    private val runs: List<RunRecord>
) {
    private val byDay = mutableMapOf<Long, Double>()
    var glitches = 0
        private set
    var overlaps = 0
        private set

    fun accept(t0: Long?, t1: Long?, d: Double?, kind: String?) {
        if (!"WALKING".equals(kind, ignoreCase = true)) return
        if (t0 == null || t1 == null || d == null || d <= 0.0) return
        val hrs = ((t1 - t0) / 1000.0).coerceAtLeast(1.0) / 3600.0
        val kmh = (d / 1000.0) / hrs
        if (kmh > maxKmh && d > minGlitchM) {
            glitches++
            return
        }
        if (overlapsRun(t0, t1)) {
            overlaps++
            return
        }
        val day = Instant.ofEpochMilli(t0).atZone(zone).toLocalDate().toEpochDay()
        byDay[day] = (byDay[day] ?: 0.0) + d
    }

    fun build(nowMs: Long): TimelineImportSummary {
        val logs = byDay.entries.sortedBy { it.key }.map { (day, m) ->
            WalkLog(day, m.toInt(), WalkSource.TIMELINE_IMPORT, 0, nowMs)
        }
        val sum = logs.sumOf { it.distanceMeters }
        return TimelineImportSummary(logs, glitches, overlaps, sum)
    }

    private fun overlapsRun(t0: Long, t1: Long): Boolean {
        return runs.any { r ->
            val r0 = r.startTimestampMillis
            val r1 = r0 + r.durationMillis
            t0 < r1 && t1 > r0
        }
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
        val agg = WalkAggregator(maxKmh, minGlitchM, zone, runs)
        for (seg in root.semanticSegments) {
            val act = seg.activity ?: continue
            agg.accept(parseMs(seg.startTime), parseMs(seg.endTime), act.distanceMeters, act.topCandidate?.type)
        }
        return agg.build(nowMs)
    }

    fun importTimelineStream(
        stream: InputStream,
        runs: List<RunRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): TimelineImportSummary {
        val agg = WalkAggregator(maxKmh, minGlitchM, zone, runs)
        val br = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        JsonReader(br).use { jr -> readRoot(jr, agg) }
        return agg.build(nowMs)
    }

    private fun readRoot(jr: JsonReader, agg: WalkAggregator) {
        jr.beginObject()
        while (jr.hasNext()) {
            if (jr.nextName() == "semanticSegments" && jr.peek() == JsonToken.BEGIN_ARRAY) {
                readSegments(jr, agg)
            } else {
                jr.skipValue()
            }
        }
        jr.endObject()
    }

    private fun readSegments(jr: JsonReader, agg: WalkAggregator) {
        jr.beginArray()
        while (jr.hasNext()) {
            if (jr.peek() == JsonToken.BEGIN_OBJECT) {
                val seg = readOneSeg(jr)
                agg.accept(seg.startMs, seg.endMs, seg.distM, seg.actType)
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
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "startTime" -> t0 = nextStr(jr)?.let(::parseMs)
                "endTime" -> t1 = nextStr(jr)?.let(::parseMs)
                "activity" -> if (jr.peek() == JsonToken.BEGIN_OBJECT) {
                    val pair = readActivity(jr)
                    dist = pair.first
                    kind = pair.second
                } else jr.skipValue()
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        return ParsedSeg(t0, t1, dist, kind)
    }

    private fun readActivity(jr: JsonReader): Pair<Double?, String?> {
        var dist: Double? = null
        var kind: String? = null
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "distanceMeters" -> dist = nextNum(jr)
                "topCandidate" -> kind = if (jr.peek() == JsonToken.BEGIN_OBJECT) readTopKind(jr) else {
                    jr.skipValue(); null
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        return Pair(dist, kind)
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

    private fun nextStr(jr: JsonReader): String? {
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            return null
        }
        return jr.nextString()
    }

    private fun nextNum(jr: JsonReader): Double? {
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            return null
        }
        return jr.nextDouble()
    }

    private fun parseMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    }
}

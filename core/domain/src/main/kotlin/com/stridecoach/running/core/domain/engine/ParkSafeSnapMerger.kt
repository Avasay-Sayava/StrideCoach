package com.stridecoach.running.core.domain.engine

import com.stridecoach.running.core.domain.model.TrackPoint
import kotlin.math.roundToInt

enum class UnsnappedReason {
    GREENERY_OFFROAD,
    STOP_LOOP
}

data class UnsnappedSection(
    val id: Int,
    val startIdx: Int,
    val endIdx: Int,
    val distMeters: Int,
    val avgDriftMeters: Int,
    val reason: UnsnappedReason,
    val canSnapToStreet: Boolean
)

data class MatchedPointSample(
    val matched: Boolean,
    val lat: Double,
    val lon: Double,
    val distM: Double
)

data class SnapMergeResult(
    val points: List<TrackPoint>,
    val snappedPct: Int,
    val greeneryPct: Int,
    val circlesKept: Int,
    val rawPoints: List<TrackPoint> = emptyList(),
    val samples: List<MatchedPointSample> = emptyList(),
    val unsnappedSections: List<UnsnappedSection> = emptyList(),
    val forcedSnapSectionIds: Set<Int> = emptySet()
)

object ParkSafeSnapMerger {
    fun detectHoldingLoops(raw: List<TrackPoint>): Pair<BooleanArray, Int> {
        val n = raw.size
        val marks = BooleanArray(n)
        if (n < 5) return marks to 0
        for (i in 0 until n - 4) markLoopWindow(raw, i, marks)
        return marks to countClusters(marks)
    }

    private fun markLoopWindow(raw: List<TrackPoint>, s: Int, marks: BooleanArray) {
        var pathM = 0.0
        var minLat = raw[s].latitude
        var maxLat = minLat
        var minLon = raw[s].longitude
        var maxLon = minLon
        for (j in s + 1 until raw.size) {
            if (raw[j].elapsedMillis - raw[s].elapsedMillis > 90_000L) break
            pathM += GeoMath.distanceMeters(raw[j - 1].latitude, raw[j - 1].longitude, raw[j].latitude, raw[j].longitude)
            minLat = minOf(minLat, raw[j].latitude)
            maxLat = maxOf(maxLat, raw[j].latitude)
            minLon = minOf(minLon, raw[j].longitude)
            maxLon = maxOf(maxLon, raw[j].longitude)
            if (j - s < 4 || pathM < 38.0) continue
            val diam = GeoMath.distanceMeters(minLat, minLon, maxLat, maxLon)
            val net = GeoMath.distanceMeters(raw[s].latitude, raw[s].longitude, raw[j].latitude, raw[j].longitude).coerceAtLeast(1.0)
            if (diam <= 34.0 && pathM / net >= 2.5) {
                for (k in s..j) marks[k] = true
            }
        }
    }

    fun mergePointMatches(
        raw: List<TrackPoint>,
        samples: List<MatchedPointSample>,
        maxSnapM: Double = 18.0,
        forcedSnapSectionIds: Set<Int> = emptySet()
    ): SnapMergeResult {
        if (raw.size < 2 || samples.size != raw.size) {
            return SnapMergeResult(raw, 0, 100, 0, raw, samples)
        }
        val (loops, loopCount) = detectHoldingLoops(raw)
        val sections = buildUnsnappedSections(raw, samples, loops, maxSnapM)
        val forcedIndices = HashSet<Int>()
        for (sec in sections) {
            if (sec.id in forcedSnapSectionIds) {
                for (k in sec.startIdx..sec.endIdx) forcedIndices.add(k)
            }
        }

        var snapCount = 0
        val out = raw.mapIndexed { i, p ->
            val sp = samples[i]
            val naturalSnap = !loops[i] && sp.matched && sp.distM <= maxSnapM
            val forcedSnap = (i in forcedIndices) && sp.matched
            if (naturalSnap || forcedSnap) {
                snapCount++
                p.copy(latitude = sp.lat, longitude = sp.lon)
            } else {
                smoothSinglePoint(raw, i, p)
            }
        }
        val rebuilt = rebuildDistAndBearing(out)
        val pct = ((snapCount * 100.0) / raw.size).roundToInt().coerceIn(0, 100)
        return SnapMergeResult(
            points = rebuilt,
            snappedPct = pct,
            greeneryPct = (100 - pct).coerceIn(0, 100),
            circlesKept = loopCount,
            rawPoints = raw,
            samples = samples,
            unsnappedSections = sections,
            forcedSnapSectionIds = forcedSnapSectionIds
        )
    }

    fun recomputeWithOverrides(
        prev: SnapMergeResult,
        forcedIds: Set<Int>,
        maxSnapM: Double = 18.0
    ): SnapMergeResult {
        if (prev.rawPoints.size < 2 || prev.samples.size != prev.rawPoints.size) return prev
        return mergePointMatches(prev.rawPoints, prev.samples, maxSnapM, forcedIds)
    }

    private fun buildUnsnappedSections(
        raw: List<TrackPoint>,
        samples: List<MatchedPointSample>,
        loops: BooleanArray,
        maxSnapM: Double
    ): List<UnsnappedSection> {
        val keepRaw = BooleanArray(raw.size) { i ->
            val sp = samples[i]
            loops[i] || !sp.matched || sp.distM > maxSnapM
        }
        val spans = collectSpans(keepRaw)
        val out = mutableListOf<UnsnappedSection>()
        var nextId = 1
        for ((s, e) in spans) {
            val isLoop = (s..e).any { loops[it] }
            val dist = spanDistMeters(raw, s, e)
            if (!isLoop && (e - s < 2 || dist < 18)) continue
            val matchedCnt = (s..e).count { samples[it].matched }
            val driftSum = (s..e).sumOf { if (samples[it].matched) samples[it].distM.coerceAtMost(99.0) else 35.0 }
            val avgDrift = (driftSum / (e - s + 1)).roundToInt()
            val reason = if (isLoop) UnsnappedReason.STOP_LOOP else UnsnappedReason.GREENERY_OFFROAD
            out.add(
                UnsnappedSection(
                    id = nextId++,
                    startIdx = s,
                    endIdx = e,
                    distMeters = dist,
                    avgDriftMeters = avgDrift,
                    reason = reason,
                    canSnapToStreet = matchedCnt * 2 >= (e - s + 1)
                )
            )
        }
        return out
    }

    private fun collectSpans(marks: BooleanArray): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < marks.size) {
            if (!marks[i]) {
                i++
                continue
            }
            val start = i
            while (i < marks.size) {
                if (marks[i]) {
                    i++
                } else if (i + 1 < marks.size && marks[i + 1]) {
                    i += 2
                } else if (i + 2 < marks.size && marks[i + 2]) {
                    i += 3
                } else {
                    break
                }
            }
            spans.add(start to (i - 1))
        }
        return spans
    }

    private fun spanDistMeters(pts: List<TrackPoint>, s: Int, e: Int): Int {
        var acc = 0.0
        for (i in (s + 1)..e) {
            acc += GeoMath.distanceMeters(pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude)
        }
        return acc.roundToInt()
    }

    private fun countClusters(marks: BooleanArray): Int {
        var cnt = 0
        var open = false
        for (m in marks) {
            if (m && !open) {
                cnt++
                open = true
            } else if (!m) {
                open = false
            }
        }
        return cnt
    }

    private fun smoothSinglePoint(raw: List<TrackPoint>, i: Int, p: TrackPoint): TrackPoint {
        if (i == 0 || i == raw.lastIndex) return p
        val a = raw[i - 1]
        val b = raw[i + 1]
        val lat = (a.latitude + p.latitude * 2.0 + b.latitude) / 4.0
        val lon = (a.longitude + p.longitude * 2.0 + b.longitude) / 4.0
        return p.copy(latitude = lat, longitude = lon)
    }

    private fun rebuildDistAndBearing(pts: List<TrackPoint>): List<TrackPoint> {
        if (pts.isEmpty()) return pts
        var acc = 0.0
        return pts.mapIndexed { i, p ->
            if (i == 0) {
                p.copy(seqIndex = 0, cumulativeDistanceMeters = 0.0)
            } else {
                val prev = pts[i - 1]
                acc += GeoMath.distanceMeters(prev.latitude, prev.longitude, p.latitude, p.longitude)
                val brg = GeoMath.bearingDegrees(prev.latitude, prev.longitude, p.latitude, p.longitude)
                p.copy(seqIndex = i, cumulativeDistanceMeters = acc, bearingDegrees = brg)
            }
        }
    }
}

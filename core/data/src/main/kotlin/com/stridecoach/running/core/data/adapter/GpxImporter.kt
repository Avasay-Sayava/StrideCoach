package com.stridecoach.running.core.data.adapter

import com.stridecoach.running.core.data.health.HealthConnectSyncManager
import com.stridecoach.running.core.data.health.RouteLocationSample
import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

object GpxImporter {
    fun isGpxContent(prefix: String): Boolean =
        prefix.trimStart('\uFEFF', ' ', '\t', '\n', '\r').let {
            it.startsWith("<gpx", ignoreCase = true) ||
                (it.startsWith("<?xml", ignoreCase = true) && it.contains("<gpx", ignoreCase = true))
        }

    fun parseGpx(xml: String): RunRecord? {
        if (xml.isBlank()) return null
        val doc = runCatching {
            val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            dbf.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null

        val name = firstTagText(doc.documentElement, "name")?.takeIf { it.isNotBlank() } ?: "GPX Run"
        val nodes = doc.getElementsByTagName("trkpt")
        val fallbackStart = Instant.now().toEpochMilli()
        val samples = (0 until nodes.length).mapNotNull { i ->
            val el = nodes.item(i) as? Element ?: return@mapNotNull null
            parseTrkpt(el, fallbackStart + i * 2_000L)
        }
        if (samples.size < 2) return null

        val startMs = samples.minOf { it.timestampMs }
        val pts = HealthConnectSyncManager.buildTrackPointsFromLocations(samples, startMs)
        if (pts.size < 2) return null

        val dist = pts.last().cumulativeDistanceMeters.roundToInt().coerceAtLeast(1)
        val durMs = pts.last().elapsedMillis.coerceAtLeast(pts.size * 2_000L)
        val poly = EncodedPolylineCodec.encode(pts.map { LatLon(it.latitude, it.longitude) })
        val pace = if (dist >= 100) ((durMs / 1000.0) / (dist / 1000.0)).roundToInt() else 0
        val elev = ActivitySegmentClassifier.computeElevationGain(pts)
        val splits = RunTailTrimmer.buildKmSplits(pts)
        val segs = ActivitySegmentClassifier.classify(pts)

        return RunRecord(
            id = "gpx_$startMs",
            startTimestampMillis = startMs,
            distanceMeters = dist,
            durationMillis = durMs,
            paceSecondsPerKm = pace,
            caloriesBurned = ((dist / 1000.0) * 62.0).roundToInt(),
            name = name,
            description = "Imported GPX track",
            encodedPolyline = poly,
            rawEncodedPolyline = poly,
            splits = splits,
            trackPoints = pts,
            source = RunSource.HEALTH_CONNECT,
            elevGainM = elev,
            segs = segs,
            activityKind = ActivityKind.RUNNING
        )
    }

    private fun parseTrkpt(el: Element, fallbackMs: Long): RouteLocationSample? {
        val lat = el.getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = el.getAttribute("lon").toDoubleOrNull() ?: return null
        val ele = firstTagText(el, "ele")?.toDoubleOrNull()
        val ts = firstTagText(el, "time")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: fallbackMs
        return RouteLocationSample(ts, lat, lon, ele)
    }

    private fun firstTagText(parent: Element, tag: String): String? {
        val list = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        return list.item(0)?.textContent?.trim()
    }
}

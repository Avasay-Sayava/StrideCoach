package com.stridecoach.running.core.data

import com.stridecoach.running.core.data.adapter.GpxExporter
import com.stridecoach.running.core.data.adapter.ImportFileKind
import com.stridecoach.running.core.data.adapter.RunningTrackerBackupAdapter
import com.stridecoach.running.core.data.adapter.TimelineJsonImporter
import com.stridecoach.running.core.domain.model.RunRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneOffset

class DataAdaptersTest {
    @Test
    fun backupRoundTrip() {
        val raw = """
            {
              "version": "4.1.0",
              "createdAt": "1795714644919",
              "runs": [
                {
                  "caloriesBurned": 561,
                  "distanceMeters": 10168,
                  "durationMillis": 3371917,
                  "path": "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                  "name": "Evening Run",
                  "paceSecondsKM": 331,
                  "splits": [{ "splitNumber": 1, "distanceMeters": 1008, "durationMillis": 296072 }],
                  "startTimestamp": 1795631402499
                },
                {
                  "caloriesBurned": 120,
                  "distanceMeters": 2000,
                  "durationMillis": 580000,
                  "path": "_p~iF~ps|U_ulLnnqC",
                  "name": "Timer Locked Late",
                  "paceSecondsKM": 0,
                  "splits": [],
                  "startTimestamp": 1795640000000
                }
              ]
            }
        """.trimIndent()

        val ad = RunningTrackerBackupAdapter()
        val list = ad.importBackupStream(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(2, list.size)
        assertEquals("", list[0].description)
        assertEquals(290, list[1].paceSecondsPerKm)
        assertTrue(list[0].trackPoints.isNotEmpty())

        val out = ad.exportBackup(list, 1795714644919L)
        val back = ad.importBackup(out)
        assertEquals(2, back.size)
        assertEquals(10168, back.first().distanceMeters)
    }

    @Test
    fun timelineFilters() {
        val raw = """
            {
              "semanticSegments": [
                {
                  "startTime": "2026-11-25T08:00:00.000+02:00",
                  "endTime": "2026-11-25T08:30:00.000+02:00",
                  "activity": { "distanceMeters": 2400.5, "topCandidate": { "type": "WALKING", "probability": 0.9 } }
                },
                {
                  "startTime": "2026-11-25T09:00:00.000+02:00",
                  "endTime": "2026-11-25T09:05:00.000+02:00",
                  "activity": { "distanceMeters": 1500.0, "topCandidate": { "type": "WALKING" } }
                },
                {
                  "startTime": "2026-11-25T18:00:00.000+02:00",
                  "endTime": "2026-11-25T18:40:00.000+02:00",
                  "activity": { "distanceMeters": 4000.0, "topCandidate": { "type": "WALKING" } }
                },
                {
                  "startTime": "2026-11-25T20:00:00.000+02:00",
                  "endTime": "2026-11-25T20:30:00.000+02:00",
                  "activity": { "distanceMeters": 5000.0, "topCandidate": { "type": "RUNNING" } }
                }
              ],
              "rawSignals": [{ "position": { "LatLng": "32.0, 34.8" } }],
              "userLocationProfile": {}
            }
        """.trimIndent()

        val run = RunRecord(
            id = "r1",
            startTimestampMillis = 1795622400000L,
            distanceMeters = 5000,
            durationMillis = 2_400_000L,
            paceSecondsPerKm = 360,
            caloriesBurned = 300,
            name = "Run",
            description = "",
            encodedPolyline = "",
            splits = emptyList()
        )

        val imp = TimelineJsonImporter(zone = ZoneOffset.ofHours(2))
        val res = imp.importTimeline(raw, listOf(run))

        assertEquals(1, res.filteredGlitches)
        assertEquals(1, res.deduplicatedOverlaps)
        assertEquals(1, res.walkLogs.size)
        assertEquals(2400, res.totalWalkMeters)
    }

    @Test
    fun detectsFileKind() {
        val ad = RunningTrackerBackupAdapter()
        val k1 = ad.detectImportKind("""{"version":"4.1.0","runs":[{"startTimestamp":1}]}""")
        val k2 = ad.detectImportKind("""{"semanticSegments":[]}""")
        val k3 = ad.detectImportKind("""{"hello":"world"}""")
        assertEquals(ImportFileKind.RUNNING_TRACKER_BACKUP, k1)
        assertEquals(ImportFileKind.GOOGLE_TIMELINE, k2)
        assertEquals(ImportFileKind.UNKNOWN, k3)
    }

    @Test
    fun gpxExportsXml() {
        val s = """{"version":"4.1.0","createdAt":"1","runs":[{"startTimestamp":1795631402499,"distanceMeters":1000,"durationMillis":300000,"paceSecondsKM":300,"caloriesBurned":60,"name":"A & B","path":"_p~iF~ps|U_ulLnnqC","splits":[]}]}"""
        val run = RunningTrackerBackupAdapter().importBackup(s).first()
        val xml = GpxExporter().exportRun(run)
        assertTrue(xml.contains("<gpx version=\"1.1\""))
        assertTrue(xml.contains("A &amp; B"))
        assertTrue(xml.contains("<trkpt"))
    }
}

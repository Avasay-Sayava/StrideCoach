package com.stridecoach.running.core.data.adapter

import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineJsonImporterTest {
    @Test
    fun `imports full timelinePath breadcrumbs instead of start-end straight line`() {
        val sampleJson = """
            {
              "semanticSegments": [
                {
                  "startTime": "2026-10-01T18:00:00.000+03:00",
                  "endTime": "2026-10-01T18:20:00.000+03:00",
                  "timelinePath": [
                    { "point": "32.110000°, 34.800000°", "time": "2026-10-01T18:01:00.000+03:00" },
                    { "point": "32.111800°, 34.801200°", "time": "2026-10-01T18:05:00.000+03:00" },
                    { "point": "32.113600°, 34.802500°", "time": "2026-10-01T18:10:00.000+03:00" },
                    { "point": "32.115400°, 34.803800°", "time": "2026-10-01T18:15:00.000+03:00" }
                  ]
                },
                {
                  "startTime": "2026-10-01T18:00:00.000+03:00",
                  "endTime": "2026-10-01T18:20:00.000+03:00",
                  "activity": {
                    "distanceMeters": 3200.0,
                    "topCandidate": { "type": "RUNNING", "probability": 0.96 },
                    "start": { "latLng": "32.109500°, 34.799500°" },
                    "end": { "latLng": "32.116000°, 34.804200°" }
                  }
                }
              ],
              "rawSignals": [
                { "position": { "LatLng": "32.114500°, 34.803200°", "timestamp": "2026-10-01T18:12:30.000+03:00" } }
              ]
            }
        """.trimIndent()

        val importer = TimelineJsonImporter()
        val res = importer.importTimeline(sampleJson, emptyList(), 1_000L)

        assertTrue("Expected at least 1 run record", res.activityRecords.size == 1)
        val rec = res.activityRecords.first()
        val decoded = EncodedPolylineCodec.decode(rec.encodedPolyline)
        assertTrue("Expected full path (>= 6 points), got ${rec.trackPoints.size}", rec.trackPoints.size >= 6)
        assertTrue("Expected decoded polyline >= 6 points, got ${decoded.size}", decoded.size >= 6)
        assertTrue("Expected increasing cumulative distance", rec.trackPoints.last().cumulativeDistanceMeters > 500.0)
        assertTrue("Expected positive speed samples", rec.trackPoints.all { (it.speedMps ?: 0f) > 0f })
    }
}

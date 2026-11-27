package com.stridecoach.running.core.data.health

import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectSyncTest {
    @Test
    fun routeLocationsSortFilterAndBuildSplits() {
        val startMs = 1_700_000_000_000L
        val samples = listOf(
            RouteLocationSample(startMs + 420_000L, 32.1260, 34.8044, 24.0),
            RouteLocationSample(startMs + 10_000L, 0.0, 0.0, 0.0),
            RouteLocationSample(startMs + 20_000L, Double.NaN, 34.8044, null),
            RouteLocationSample(startMs, 32.1133, 34.8044, 15.0),
            RouteLocationSample(startMs + 210_000L, 32.1195, 34.8044, 20.0)
        )
        val pts = HealthConnectSyncManager.buildTrackPointsFromLocations(samples, startMs)
        assertEquals(3, pts.size)
        assertEquals(0L, pts[0].elapsedMillis)
        assertEquals(210_000L, pts[1].elapsedMillis)
        assertEquals(420_000L, pts[2].elapsedMillis)
        val totalMeters = pts.last().cumulativeDistanceMeters
        assertTrue("Expected ~1.4 km route, got $totalMeters", totalMeters in 1350.0..1460.0)

        val splits = RunTailTrimmer.buildKmSplits(pts)
        assertEquals(1, splits.size)
        assertTrue(splits[0].distanceMeters >= 1_000)
    }

    @Test
    fun overlappingDistanceFallbackHandlesPartialWindows() {
        val winStart = 10_000L
        val winEnd = 20_000L
        val slices = listOf(
            DistanceSlice(0L, 8_000L, 500.0),
            DistanceSlice(5_000L, 15_000L, 1000.0),
            DistanceSlice(15_000L, 25_000L, 1200.0),
            DistanceSlice(22_000L, 30_000L, 900.0)
        )
        val overlap = HealthConnectSyncManager.overlapDistanceMeters(slices, winStart, winEnd)
        assertEquals(1100.0, overlap, 0.01)
    }
}

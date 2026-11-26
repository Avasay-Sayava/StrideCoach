package com.stridecoach.running.core.domain

import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.CoachingEvent
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.MatchedPointSample
import com.stridecoach.running.core.domain.engine.ParkSafeSnapMerger
import com.stridecoach.running.core.domain.engine.PlanPresets
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import com.stridecoach.running.core.domain.engine.TrackInterpolator
import com.stridecoach.running.core.domain.engine.TrainingPlanGenerator
import com.stridecoach.running.core.domain.engine.WalkingLoadRegulator
import com.stridecoach.running.core.domain.engine.WorkoutStateMachine
import com.stridecoach.running.core.domain.model.ActivitySegmentType
import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSplit
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEnginesTest {
    @Test
    fun presetBuilds92Days() {
        val gen = TrainingPlanGenerator()
        val cfg = PlanPresets.telAviv10kPreset()
        val days = gen.generate(cfg)

        assertEquals(92, days.size)
        assertEquals(DayRole.RACE_DAY, days.last().dayRole)
        assertTrue(days.any { it.isBenchmarkDay })
        assertTrue(days.any { it.isCutbackWeek })
        assertTrue(days.any { it.isTaperWeek })
    }

    @Test
    fun heavyWalkTriggersAlert() {
        val gen = TrainingPlanGenerator()
        val cfg = PlanPresets.telAviv10kPreset()
        val days = gen.generate(cfg)
        val i = days.indexOfFirst { it.dayRole == DayRole.FAST_WORKOUT }
        val day = days[i]
        val next = days[i + 1]

        val reg = WalkingLoadRegulator()
        val walk = WalkLog(day.dateEpochDay, 8_200, WalkSource.MANUAL)
        val alert = reg.evaluate(day, next, walk, null, cfg.walkingLoadRule)

        assertNotNull(alert)
        assertTrue(alert!!.canSwapWithTomorrow)
        assertEquals(5_000, alert.downgradedWorkout.estimatedDistanceMeters())
    }

    @Test
    fun racePacerFlagsEarlySurge() {
        val gen = TrainingPlanGenerator()
        val days = gen.generate(PlanPresets.telAviv10kPreset())
        val w = days.last().workout!!
        val sm = WorkoutStateMachine(w, 10_000L)

        val (snap, ev) = sm.onTick(1_200.0, 340_000L, 285)
        assertEquals(PaceStatus.TOO_FAST, snap.paceStatus)
        assertNotNull(snap.projectedFinishMillis)
        assertTrue(ev.any { it is CoachingEvent.PaceAlert })
    }

    @Test
    fun tailTrimmerReducesDistance() {
        val pts = listOf(
            LatLon(32.0853, 34.7818),
            LatLon(32.0898, 34.7818),
            LatLon(32.0943, 34.7818),
            LatLon(32.0988, 34.7818)
        )
        val poly = EncodedPolylineCodec.encode(pts)
        val sp = listOf(RunSplit(1, 1_500, 600_000L))
        val trk = EncodedPolylineCodec.synthesizeReplayTrack(poly, 1_795_631_400_000L, 600_000L, sp)
        val run = RunRecord(
            id = "r1",
            startTimestampMillis = 1_795_631_400_000L,
            distanceMeters = trk.last().cumulativeDistanceMeters.toInt(),
            durationMillis = 600_000L,
            paceSecondsPerKm = 400,
            caloriesBurned = 120,
            name = "Test",
            description = "",
            encodedPolyline = poly,
            splits = sp,
            trackPoints = trk
        )

        val out = RunTailTrimmer().trimToElapsedMillis(run, 350_000L)
        assertTrue(out.durationMillis <= 350_000L)
        assertTrue(out.distanceMeters < run.distanceMeters)
    }

    @Test
    fun segClassifierSplit() {
        val pts = listOf(
            TrackPoint(0, 0L, 0L, 32.0, 34.8, speedMps = 3.2f, cumulativeDistanceMeters = 0.0, cadenceSpm = 170),
            TrackPoint(1, 30_000L, 30_000L, 32.001, 34.8, speedMps = 3.2f, cumulativeDistanceMeters = 96.0, cadenceSpm = 170),
            TrackPoint(2, 60_000L, 60_000L, 32.0015, 34.8, speedMps = 1.3f, cumulativeDistanceMeters = 135.0, cadenceSpm = 105),
            TrackPoint(3, 95_000L, 95_000L, 32.0018, 34.8, speedMps = 0.2f, cumulativeDistanceMeters = 140.0, cadenceSpm = 0),
            TrackPoint(4, 125_000L, 125_000L, 32.0018, 34.8, speedMps = 0.1f, cumulativeDistanceMeters = 142.0, cadenceSpm = 0)
        )
        val segs = ActivitySegmentClassifier.classify(pts)
        assertTrue(segs.any { it.type == ActivitySegmentType.RUNNING })
        assertTrue(segs.any { it.type == ActivitySegmentType.STRETCH_REST })
    }

    @Test
    fun zonesAndBars() {
        val r = RunRecord(
            id = "z1",
            startTimestampMillis = 1_795_631_402_499L,
            distanceMeters = 3_000,
            durationMillis = 900_000L,
            paceSecondsPerKm = 300,
            caloriesBurned = 180,
            name = "Tempo",
            description = "",
            encodedPolyline = "",
            splits = listOf(RunSplit(1, 1_000, 355_000L), RunSplit(2, 1_000, 295_000L), RunSplit(3, 1_000, 270_000L))
        )
        val eng = RunAnalyticsEngine()
        val zs = eng.computePaceZoneDistribution(listOf(r), 295)
        assertEquals(5, zs.size)
        assertTrue(zs.sumOf { it.durationMillis } == 920_000L)
        val bs = eng.computeLast7DaysLoad(listOf(r), emptyList(), 20_780L)
        assertEquals(7, bs.size)
    }

    @Test
    fun interpMidpoint() {
        val pts = listOf(
            TrackPoint(0, 0L, 0L, 32.0800, 34.7800, speedMps = 3.0f, cumulativeDistanceMeters = 0.0),
            TrackPoint(1, 10_000L, 10_000L, 32.0810, 34.7810, speedMps = 4.0f, cumulativeDistanceMeters = 140.0)
        )
        val h = TrackInterpolator.sampleAt(pts, 5_000L)
        assertNotNull(h)
        assertEquals(32.0805, h!!.lat, 1e-6)
        assertEquals(34.7805, h.lon, 1e-6)
        assertEquals(70.0, h.distM, 0.5)
    }

    @Test
    fun snapKeepsParkAndLoops() {
        val pts = listOf(
            TrackPoint(0, 0L, 0L, 32.0800, 34.7800, speedMps = 3.0f),
            TrackPoint(1, 5_000L, 5_000L, 32.0802, 34.7800, speedMps = 3.0f),
            TrackPoint(2, 10_000L, 10_000L, 32.0802, 34.78015, speedMps = 3.0f),
            TrackPoint(3, 15_000L, 15_000L, 32.0800, 34.78015, speedMps = 3.0f),
            TrackPoint(4, 20_000L, 20_000L, 32.0800, 34.7800, speedMps = 3.0f),
            TrackPoint(5, 40_000L, 40_000L, 32.0810, 34.7800, speedMps = 3.0f),
            TrackPoint(6, 60_000L, 60_000L, 32.0820, 34.7800, speedMps = 3.0f)
        )
        val smp = pts.mapIndexed { i, p ->
            if (i == 6) MatchedPointSample(true, 32.0820, 34.7810, 45.0)
            else MatchedPointSample(true, p.latitude, 34.78005, 4.0)
        }
        val res = ParkSafeSnapMerger.mergePointMatches(pts, smp, maxSnapM = 18.0)
        assertTrue(res.circlesKept >= 1)
        assertTrue(res.points[6].longitude < 34.7805)
        assertTrue(res.snappedPct in 1..60)
    }
}

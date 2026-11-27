package com.stridecoach.running.core.domain

import com.stridecoach.running.core.domain.engine.ActivitySegmentClassifier
import com.stridecoach.running.core.domain.engine.CoachingEvent
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.ImuKinematicsAnalyzer
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.MatchedPointSample
import com.stridecoach.running.core.domain.engine.PaceZone
import com.stridecoach.running.core.domain.engine.PaceZoneClassifier
import com.stridecoach.running.core.domain.engine.ParkSafeSnapMerger
import com.stridecoach.running.core.domain.engine.PlanPresets
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import com.stridecoach.running.core.domain.engine.TopographyPaceEngine
import com.stridecoach.running.core.domain.engine.TrackInterpolator
import com.stridecoach.running.core.domain.engine.TrainingPlanGenerator
import com.stridecoach.running.core.domain.engine.WalkingLoadRegulator
import com.stridecoach.running.core.domain.engine.WorkoutStateMachine
import com.stridecoach.running.core.domain.model.ActivityKind
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

    @Test
    fun paceZoneMatchesCurrentFitnessAndMapParity() {
        val runs = listOf(
            RunRecord("a", 1_000L, 5_000, 1_650_000L, 330, 300, "Run 1", "", "", emptyList()),
            RunRecord("b", 2_000L, 5_000, 1_550_000L, 310, 300, "Run 2", "", "", emptyList()),
            RunRecord("c", 3_000L, 5_000, 1_600_000L, 320, 300, "Run 3", "", "", emptyList())
        )
        val base = PaceZoneClassifier.computeCurrentBaselinePace(runs, ActivityKind.RUNNING, 240)
        assertEquals(320, base)
        assertEquals(PaceZone.Z3_CRUISE, PaceZoneClassifier.classifyPace(320, base))
        assertEquals(PaceZone.Z1_EASY, PaceZoneClassifier.classifyPace(380, base))
        assertEquals(PaceZone.Z5_PEAK, PaceZoneClassifier.classifyPace(270, base))
        val speedCruise = PaceZoneClassifier.classifySpeed(1000f / 320f, base)
        assertEquals(PaceZone.Z3_CRUISE.hex, speedCruise?.hex)
    }

    @Test
    fun selectiveGreeneryOverrideSnapsCleanlyWithoutDoubleSnap() {
        val pts = (0..10).map { i ->
            TrackPoint(i, i * 10_000L, i * 10_000L, 32.0800 + i * 0.0005, 34.7800, speedMps = 3.1f)
        }
        val smp = pts.mapIndexed { i, p ->
            if (i in 4..8) MatchedPointSample(true, p.latitude, 34.78035, 32.0)
            else MatchedPointSample(true, p.latitude, 34.78002, 2.0)
        }
        val initial = ParkSafeSnapMerger.mergePointMatches(pts, smp, maxSnapM = 18.0)
        assertTrue(initial.unsnappedSections.isNotEmpty())
        val secId = initial.unsnappedSections.first().id
        val overridden = ParkSafeSnapMerger.recomputeWithOverrides(initial, setOf(secId))
        assertTrue(overridden.snappedPct > initial.snappedPct)
        val restored = ParkSafeSnapMerger.recomputeWithOverrides(overridden, emptySet())
        assertEquals(initial.snappedPct, restored.snappedPct)
    }

    @Test
    fun bothSideTrimAndAutoTrimSuggestion() {
        val pts = listOf(
            TrackPoint(0, 0L, 0L, 32.0800, 34.7800, speedMps = 0.2f),
            TrackPoint(1, 20_000L, 20_000L, 32.0800, 34.7800, speedMps = 0.3f),
            TrackPoint(2, 40_000L, 40_000L, 32.0810, 34.7800, speedMps = 3.2f),
            TrackPoint(3, 60_000L, 60_000L, 32.0820, 34.7800, speedMps = 3.2f),
            TrackPoint(4, 80_000L, 80_000L, 32.0830, 34.7800, speedMps = 3.2f),
            TrackPoint(5, 100_000L, 100_000L, 32.0840, 34.7800, speedMps = 3.2f),
            TrackPoint(6, 120_000L, 120_000L, 32.0840, 34.7800, speedMps = 0.2f),
            TrackPoint(7, 140_000L, 140_000L, 32.0840, 34.7800, speedMps = 0.2f)
        )
        val sug = RunTailTrimmer().suggestAutoTrim(pts, ActivityKind.RUNNING)
        assertNotNull(sug)
        assertTrue(sug!!.trimmedStartMs >= 15_000L)
        assertTrue(sug.trimmedEndMs >= 15_000L)

        val poly = EncodedPolylineCodec.encode(pts.map { LatLon(it.latitude, it.longitude) })
        val run = RunRecord("tr1", 1_000L, 450, 140_000L, 311, 40, "R", "", poly, emptyList(), pts)
        val trimmed = RunTailTrimmer().trimRange(run, sug.startMs, sug.endMs)
        assertTrue(trimmed.durationMillis < run.durationMillis)
        assertEquals(0L, trimmed.trackPoints.first().elapsedMillis)
    }

    @Test
    fun imuKinematicsAndBearingWraparound() {
        val pts = listOf(
            TrackPoint(0, 0L, 0L, 32.08, 34.78, bearingDegrees = 350f, speedMps = 3.0f),
            TrackPoint(1, 10_000L, 10_000L, 32.09, 34.78, bearingDegrees = 10f, speedMps = 3.0f)
        )
        val head = TrackInterpolator.sampleAt(pts, 5_000L)!!
        assertTrue(head.bearingDeg >= 359f || head.bearingDeg <= 1f)
        val stability = ImuKinematicsAnalyzer.computeStrideStabilityPct(listOf(350L, 355L, 348L, 352L, 351L))
        assertTrue(stability >= 90)
        assertTrue(ImuKinematicsAnalyzer.detectHoldingCircle(360f, 12.0))
        val fused = ImuKinematicsAnalyzer.fuseHeading(20f, 350f, 3.0f)
        assertTrue(fused > 300f || fused < 20f)
    }

    @Test
    fun topographyAdjustsPaceAndComputesGap() {
        val uphillTarget = TopographyPaceEngine.adjustTargetPaceForGrade(300, 6.0)
        val downhillTarget = TopographyPaceEngine.adjustTargetPaceForGrade(300, -4.0)
        assertTrue(uphillTarget > 300)
        assertTrue(downhillTarget < 300)
        val gap = TopographyPaceEngine.computeGradeAdjustedPace(330, 5.0)
        assertTrue(gap < 330)
        val pts = (0..5).map { i ->
            TrackPoint(i, i * 15_000L, i * 15_000L, 32.08 + i * 0.0005, 34.78, altitudeMeters = 20.0 + i * 4.0, speedMps = 3.0f, cumulativeDistanceMeters = i * 50.0)
        }
        val topo = TopographyPaceEngine.analyzeTrack(pts, 320)
        assertNotNull(topo)
        assertTrue(topo!!.gainMeters >= 15)
        assertTrue(topo.profilePoints.isNotEmpty())
    }

    @Test
    fun smoothGradientPrHistoryAndSingularTags() {
        assertEquals("Walk", ActivityKind.WALKING.singularTag)
        assertEquals("Run", ActivityKind.RUNNING.singularTag)
        val hexFast = PaceZoneClassifier.smoothPaceHex(4.0f, 330)
        val hexSlow = PaceZoneClassifier.smoothPaceHex(2.2f, 330)
        assertTrue(hexFast.startsWith("#") && hexSlow.startsWith("#") && hexFast != hexSlow)
        assertTrue(PaceZoneClassifier.explainZone(PaceZone.Z2_BASE, 320).paceRangeText.contains("/km"))

        val r1 = RunRecord("r1", 10_000L, 5_000, 1_500_000L, 300, 300, "First 5K", "", "", emptyList())
        val r2 = RunRecord("r2", 90_000_000L, 5_000, 1_420_000L, 284, 300, "PR 5K", "", "", emptyList())
        val eng = RunAnalyticsEngine()
        val histories = eng.computePersonalRecordHistories(listOf(r2, r1))
        val h5k = histories.first { it.label == "5K" }
        assertEquals(2, h5k.history.size)
        assertEquals(80_000L, h5k.currentBest.improvementMillis)
    }

    @Test
    fun midReplaySamplingOnSnappedTrackReportsLiveSpeedAndCoords() {
        val raw = (0..10).map { i ->
            TrackPoint(i, i * 10_000L, i * 10_000L, 32.10 + i * 0.0008, 34.79, speedMps = 3.2f, cumulativeDistanceMeters = i * 95.0)
        }
        val samples = raw.map { p -> MatchedPointSample(true, p.latitude + 0.0001, p.longitude + 0.0001, 12.0) }
        val snapped = ParkSafeSnapMerger.mergePointMatches(raw, samples)
        val head = TrackInterpolator.sampleAt(snapped.points, 40_000L)
        assertNotNull(head)
        assertTrue(head!!.speedKmh > 5f)
        assertTrue(head.currentKm >= 1)
        assertEquals(raw[4].latitude + 0.0001, head.lat, 0.00005)
    }
}

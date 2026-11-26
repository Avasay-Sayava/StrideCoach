package com.stridecoach.running.core.domain

import com.stridecoach.running.core.domain.engine.CoachingEvent
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.PlanPresets
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import com.stridecoach.running.core.domain.engine.TrainingPlanGenerator
import com.stridecoach.running.core.domain.engine.WalkingLoadRegulator
import com.stridecoach.running.core.domain.engine.WorkoutStateMachine
import com.stridecoach.running.core.domain.model.DayRole
import com.stridecoach.running.core.domain.model.PaceStatus
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSplit
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
}

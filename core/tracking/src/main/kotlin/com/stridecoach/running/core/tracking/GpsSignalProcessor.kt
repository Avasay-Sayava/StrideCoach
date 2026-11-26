package com.stridecoach.running.core.tracking

import com.stridecoach.running.core.domain.engine.GeoMath
import com.stridecoach.running.core.domain.model.TrackPoint

data class RawGpsFix(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMps: Float?
)

enum class MovementState {
    MOVING,
    AUTO_PAUSED,
    AUTO_FINISH_SUGGESTED
}

class GpsSignalProcessor(
    private val maxAccM: Float = 25f,
    private val maxMps: Double = 11.5,
    private val winM: Double = 200.0,
    private val pauseMps: Double = 0.65,
    private val pauseMs: Long = 6_000L,
    private val stopMs: Long = 180_000L
) {
    private val pts = mutableListOf<TrackPoint>()
    private var lastMs = 0L
    private var stillMs: Long? = null
    private var distM = 0.0
    private var elMs = 0L

    fun points(): List<TrackPoint> = pts.toList()
    fun totalDistanceMeters(): Double = distM
    fun activeElapsedMillis(): Long = elMs

    fun process(
        fix: RawGpsFix,
        segIdx: Int?,
        paused: Boolean,
        autoPause: Boolean
    ): Pair<TrackPoint?, MovementState> {
        if (fix.accuracyMeters > maxAccM) {
            return Pair(null, stateAt(fix.timestampMillis, autoPause))
        }
        val prev = pts.lastOrNull()
        if (prev == null) {
            val pt = TrackPoint(
                0, fix.timestampMillis, 0L, fix.latitude, fix.longitude,
                fix.altitudeMeters, fix.accuracyMeters, fix.speedMps, 0f, 0.0, segIdx, paused
            )
            pts.add(pt)
            lastMs = fix.timestampMillis
            return Pair(pt, MovementState.MOVING)
        }

        val dt = (fix.timestampMillis - lastMs).coerceAtLeast(1L)
        val step = GeoMath.distanceMeters(prev.latitude, prev.longitude, fix.latitude, fix.longitude)
        val spd = step / (dt / 1000.0)
        if (spd > maxMps && step > 15.0) {
            return Pair(null, stateAt(fix.timestampMillis, autoPause))
        }

        lastMs = fix.timestampMillis
        val v = fix.speedMps?.toDouble() ?: spd
        val mv = updateMove(v, fix.timestampMillis, autoPause)
        val stop = paused || mv != MovementState.MOVING

        if (!stop) {
            distM += step
            elMs += dt
        }
        val brg = GeoMath.bearingDegrees(prev.latitude, prev.longitude, fix.latitude, fix.longitude)
        val pt = TrackPoint(
            pts.size, fix.timestampMillis, elMs, fix.latitude, fix.longitude,
            fix.altitudeMeters, fix.accuracyMeters, v.toFloat(), brg, distM, segIdx, stop
        )
        pts.add(pt)
        return Pair(pt, mv)
    }

    fun rollingPaceSecPerKm(): Int {
        if (pts.size < 2) return 0
        val tail = pts.last()
        val cut = (tail.cumulativeDistanceMeters - winM).coerceAtLeast(0.0)
        var base = pts.first()
        for (i in pts.lastIndex - 1 downTo 0) {
            if (pts[i].cumulativeDistanceMeters <= cut) {
                base = pts[i]
                break
            }
        }
        val d = tail.cumulativeDistanceMeters - base.cumulativeDistanceMeters
        val s = (tail.elapsedMillis - base.elapsedMillis) / 1000.0
        if (d < 20.0 || s <= 1.0) return 0
        return (s / (d / 1000.0)).toInt().coerceIn(120, 1200)
    }

    private fun updateMove(v: Double, now: Long, on: Boolean): MovementState {
        if (!on || v >= pauseMps) {
            stillMs = null
            return MovementState.MOVING
        }
        val s0 = stillMs ?: now.also { stillMs = it }
        return classifyStop(now - s0)
    }

    private fun stateAt(now: Long, on: Boolean): MovementState {
        if (!on) return MovementState.MOVING
        val s0 = stillMs ?: return MovementState.MOVING
        return classifyStop(now - s0)
    }

    private fun classifyStop(dur: Long): MovementState = when {
        dur >= stopMs -> MovementState.AUTO_FINISH_SUGGESTED
        dur >= pauseMs -> MovementState.AUTO_PAUSED
        else -> MovementState.MOVING
    }
}

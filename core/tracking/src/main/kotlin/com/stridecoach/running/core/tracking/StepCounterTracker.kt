package com.stridecoach.running.core.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class StepCounterTracker(
    ctx: Context,
    private val strideM: Double = 0.74
) : SensorEventListener {
    private val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector: Sensor? = mgr.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val counter: Sensor? = mgr.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var base: Float? = null
    private var detectedCount = 0
    private val recentMs = ArrayDeque<Long>()

    private val stepsFlow = MutableStateFlow(0)
    val sessionSteps: StateFlow<Int> = stepsFlow.asStateFlow()

    private val cadFlow = MutableStateFlow(0)
    val cadenceSpm: StateFlow<Int> = cadFlow.asStateFlow()

    fun start(): Boolean {
        val detOk = detector?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } ?: false
        val cntOk = counter?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false
        return detOk || cntOk
    }

    fun stop() {
        mgr.unregisterListener(this)
        recentMs.clear()
        cadFlow.value = 0
    }

    fun estimatedMeters(): Int = (stepsFlow.value * strideM).toInt()

    override fun onSensorChanged(ev: SensorEvent) {
        val now = System.currentTimeMillis()
        if (ev.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            detectedCount += ev.values.size.coerceAtLeast(1)
            if (detectedCount > stepsFlow.value) stepsFlow.value = detectedCount
            recentMs.addLast(now)
            trimWindow(now)
            return
        }
        val v = ev.values.firstOrNull() ?: return
        val b = base ?: v.also { base = it }
        val total = (v - b).toInt().coerceAtLeast(0)
        if (total > stepsFlow.value) {
            val delta = total - stepsFlow.value
            stepsFlow.value = total
            repeat(delta.coerceAtMost(20)) { recentMs.addLast(now) }
            trimWindow(now)
        }
    }

    private fun trimWindow(now: Long) {
        val cutoff = now - 15_000L
        while (recentMs.isNotEmpty() && recentMs.first() < cutoff) {
            recentMs.removeFirst()
        }
        if (recentMs.size < 4) {
            cadFlow.value = 0
            return
        }
        val spanSec = ((now - recentMs.first()).coerceAtLeast(3_000L)) / 1000.0
        cadFlow.value = ((recentMs.size / spanSec) * 60.0).roundToInt().coerceIn(40, 240)
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit
}

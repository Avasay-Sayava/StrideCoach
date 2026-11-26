package com.stridecoach.running.core.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StepCounterTracker(
    ctx: Context,
    private val strideM: Double = 0.74
) : SensorEventListener {
    private val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = mgr.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var base: Float? = null

    private val stepsFlow = MutableStateFlow(0)
    val sessionSteps: StateFlow<Int> = stepsFlow.asStateFlow()

    fun start(): Boolean {
        val s = sensor ?: return false
        return mgr.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        mgr.unregisterListener(this)
    }

    fun estimatedMeters(): Int = (stepsFlow.value * strideM).toInt()

    override fun onSensorChanged(ev: SensorEvent) {
        val v = ev.values.firstOrNull() ?: return
        val b = base ?: v.also { base = it }
        stepsFlow.value = (v - b).toInt().coerceAtLeast(0)
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit
}

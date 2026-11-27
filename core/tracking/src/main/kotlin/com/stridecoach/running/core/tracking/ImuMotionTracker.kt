package com.stridecoach.running.core.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.stridecoach.running.core.domain.engine.ImuKinematicsAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Combines hardware gyroscope, linear acceleration, and rotation vector sensors with FIFO batching
 * (500ms latency) to compute heading, stride rhythm stability, backup IMU cadence, and stationary state.
 */
class ImuMotionTracker(ctx: Context) : SensorEventListener {
    private val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = mgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: mgr.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linAccelSensor: Sensor? = mgr.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val rotMat = FloatArray(9)
    private val ori = FloatArray(3)
    private val peakTimestamps = ArrayDeque<Long>()
    private val strideIntervals = ArrayDeque<Long>()
    private val accelWindow = ArrayDeque<Float>()
    private val gyroWindow = ArrayDeque<Float>()

    private var lastPeakMs = 0L
    private var prevAccel = 0f
    private var prevRising = false

    val hasGyroHardware: Boolean = gyroSensor != null || rotSensor != null

    private val headingFlow = MutableStateFlow(0f)
    val headingDeg: StateFlow<Float> = headingFlow.asStateFlow()

    private val imuCadenceFlow = MutableStateFlow(0)
    val imuCadenceSpm: StateFlow<Int> = imuCadenceFlow.asStateFlow()

    private val stabilityFlow = MutableStateFlow(0)
    val strideStabilityPct: StateFlow<Int> = stabilityFlow.asStateFlow()

    private val stationaryFlow = MutableStateFlow(false)
    val isStationary: StateFlow<Boolean> = stationaryFlow.asStateFlow()

    private val turnRateFlow = MutableStateFlow(0f)
    val gyroTurnRateDegSec: StateFlow<Float> = turnRateFlow.asStateFlow()

    fun start(): Boolean {
        val batchUs = 500_000
        val rOk = rotSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, batchUs) } ?: false
        val gOk = gyroSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, batchUs) } ?: false
        val aOk = linAccelSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, batchUs) } ?: false
        return rOk || gOk || aOk
    }

    fun stop() {
        mgr.unregisterListener(this)
        peakTimestamps.clear()
        strideIntervals.clear()
        accelWindow.clear()
        gyroWindow.clear()
        imuCadenceFlow.value = 0
        stabilityFlow.value = 0
        stationaryFlow.value = false
    }

    override fun onSensorChanged(ev: SensorEvent) {
        val now = System.currentTimeMillis()
        when (ev.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> handleRotation(ev.values)
            Sensor.TYPE_GYROSCOPE -> handleGyro(ev.values)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleAccel(ev.values, now)
        }
    }

    private fun handleRotation(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotMat, values)
        SensorManager.getOrientation(rotMat, ori)
        val azimuthDeg = ((Math.toDegrees(ori[0].toDouble()).toFloat() % 360f) + 360f) % 360f
        headingFlow.value = azimuthDeg
    }

    private fun handleGyro(values: FloatArray) {
        val rms = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
        turnRateFlow.value = Math.toDegrees(abs(values[2]).toDouble()).toFloat()
        gyroWindow.addLast(rms)
        while (gyroWindow.size > 20) gyroWindow.removeFirst()
        refreshStationary()
    }

    private fun handleAccel(values: FloatArray, now: Long) {
        val mag = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
        accelWindow.addLast(mag)
        while (accelWindow.size > 24) accelWindow.removeFirst()
        refreshStationary()

        val rising = mag > prevAccel
        if (prevRising && !rising && prevAccel > 2.4f && now - lastPeakMs > 230L) {
            if (lastPeakMs > 0L) {
                strideIntervals.addLast(now - lastPeakMs)
                while (strideIntervals.size > 16) strideIntervals.removeFirst()
                stabilityFlow.value = ImuKinematicsAnalyzer.computeStrideStabilityPct(strideIntervals.toList())
            }
            lastPeakMs = now
            peakTimestamps.addLast(now)
            trimPeaks(now)
        }
        prevRising = rising
        prevAccel = mag
    }

    private fun refreshStationary() {
        if (accelWindow.size < 6) return
        val meanA = accelWindow.average().toFloat()
        val varA = accelWindow.sumOf { ((it - meanA) * (it - meanA)).toDouble() }.toFloat() / accelWindow.size
        val rmsG = if (gyroWindow.isEmpty()) 0f else gyroWindow.average().toFloat()
        stationaryFlow.value = ImuKinematicsAnalyzer.isImuStationary(varA, rmsG)
    }

    private fun trimPeaks(now: Long) {
        val cutoff = now - 12_000L
        while (peakTimestamps.isNotEmpty() && peakTimestamps.first() < cutoff) {
            peakTimestamps.removeFirst()
        }
        if (peakTimestamps.size < 4) {
            imuCadenceFlow.value = 0
            return
        }
        val spanSec = ((now - peakTimestamps.first()).coerceAtLeast(3_000L)) / 1000.0
        imuCadenceFlow.value = ((peakTimestamps.size / spanSec) * 60.0).roundToInt().coerceIn(50, 230)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

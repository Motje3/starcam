package com.wm.starcam.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Gyroscope watcher (PLAN.md §3.3): tripod-stillness gating before a session starts, and
 * per-exposure bump detection so knocked frames get rejected instead of poisoning the stack.
 */
class MotionMonitor(context: Context) : SensorEventListener {

    /** rad/s below which the phone counts as still; must hold for [STILL_HOLD_MS]. */
    var stillThreshold = 0.006f
    /** rad/s peak during an exposure window that marks the frame as bumped. */
    var bumpThreshold = 0.02f

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _isStill = MutableStateFlow(false)
    val isStill: StateFlow<Boolean> = _isStill

    private val _magnitude = MutableStateFlow(0f)
    val magnitude: StateFlow<Float> = _magnitude

    /** Device roll in degrees (0 = level in portrait); drives the HUD spirit level. */
    private val _rollDeg = MutableStateFlow(0f)
    val rollDeg: StateFlow<Float> = _rollDeg

    val hasGyro: Boolean get() = gyro != null

    private var lastLoudMs = 0L

    // Exposure window accumulation.
    private var windowActive = false
    private var windowPeak = 0f
    private var windowSumSq = 0.0
    private var windowCount = 0

    fun start() {
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (gyro == null) _isStill.value = true  // no gyro: never block capture on stillness
        lastLoudMs = System.currentTimeMillis()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Begins accumulating for one exposure. */
    @Synchronized
    fun startWindow() {
        windowActive = true
        windowPeak = 0f
        windowSumSq = 0.0
        windowCount = 0
    }

    /**
     * One exposure's motion. [samples] is how many gyro readings it saw: none means "unknown",
     * not "still" - [bumped] is then false only because there is nothing to judge by.
     */
    data class Window(val peak: Float, val rms: Float, val bumped: Boolean, val samples: Int)

    /** Ends the exposure window. */
    @Synchronized
    fun endWindow(): Window {
        windowActive = false
        val rms = if (windowCount > 0) sqrt(windowSumSq / windowCount).toFloat() else 0f
        return Window(windowPeak, rms, windowPeak > bumpThreshold, windowCount)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Roll around the screen axis: 0 deg when the long edge is plumb in portrait.
            val ax = event.values[0]
            val ay = event.values[1]
            _rollDeg.value = Math.toDegrees(kotlin.math.atan2(ax, ay).toDouble()).toFloat()
            return
        }
        val m = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        _magnitude.value = m
        val now = System.currentTimeMillis()
        if (m > stillThreshold) lastLoudMs = now
        _isStill.value = now - lastLoudMs >= STILL_HOLD_MS
        synchronized(this) {
            if (windowActive) {
                if (m > windowPeak) windowPeak = m
                windowSumSq += m.toDouble() * m
                windowCount++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val STILL_HOLD_MS = 1500L
    }
}

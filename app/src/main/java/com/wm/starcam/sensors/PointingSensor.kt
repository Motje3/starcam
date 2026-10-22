package com.wm.starcam.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where the back camera points: azimuth from true north and altitude above the horizon, from the
 * rotation-vector sensor (gyro, accelerometer and compass fused). The compass gives magnetic
 * north; [setPlace] adds the local declination (the phone's built-in world magnetic model, no
 * network) so the azimuth compares with the sky's.
 */
class PointingSensor(context: Context) : SensorEventListener {

    data class Aim(val azDeg: Double, val altDeg: Double)

    private val sm = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = rotation != null

    private val _aim = MutableStateFlow<Aim?>(null)
    val aim: StateFlow<Aim?> = _aim

    private var declinationDeg = 0f
    private val r = FloatArray(9)
    private var smooth: FloatArray? = null

    fun setPlace(latDeg: Double, lonDeg: Double) {
        declinationDeg = GeomagneticField(latDeg.toFloat(), lonDeg.toFloat(), 0f, System.currentTimeMillis())
            .declination
    }

    fun start() {
        rotation?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm?.unregisterListener(this)
        smooth = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // The camera looks along the device's -Z axis; R takes device vectors into the world
        // frame (x east, y magnetic north, z up), so that direction is minus R's third column.
        val v = floatArrayOf(-r[2], -r[5], -r[8])
        // A light low-pass keeps the needle steady without making it lag a turn of the wrist.
        val s = smooth?.also { for (i in 0..2) it[i] += 0.2f * (v[i] - it[i]) } ?: v.copyOf().also { smooth = it }
        val n = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]).coerceAtLeast(1e-6f)
        val az = (Math.toDegrees(atan2(s[0], s[1]).toDouble()) + declinationDeg + 720.0) % 360.0
        val alt = Math.toDegrees(asin((s[2] / n).coerceIn(-1f, 1f).toDouble()))
        _aim.value = Aim(az, alt)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

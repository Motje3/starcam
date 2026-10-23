package com.wm.starcam.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.sqrt

/**
 * The phone's full orientation for the sky view: the rotation-vector sensor (gyro, accelerometer
 * and compass fused) as a device-to-world rotation matrix, world x east, y magnetic north, z up.
 * Lightly smoothed (on the quaternion, so it stays a rotation), and published as Compose state
 * so a sky drawing redraws on every reading without recomposing the screen around it.
 */
class SkyOrientation(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = rotation != null

    /** Row-major 3x3 rotation matrix; null until the first reading. */
    val matrix = mutableStateOf<FloatArray?>(null)

    /** The sensor's own estimate of its heading error (deg); NaN when it gives none. */
    val headingErrorDeg = mutableFloatStateOf(Float.NaN)

    private var q: FloatArray? = null

    fun start() {
        rotation?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm?.unregisterListener(this)
        q = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val x = v[0]
        val y = v[1]
        val z = v[2]
        val w = if (v.size >= 4) v[3] else sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
        val prev = q
        val cur = if (prev == null) {
            floatArrayOf(x, y, z, w)
        } else {
            // q and -q are the same turn: follow the one nearer the last reading, then blend.
            val sign = if (prev[0] * x + prev[1] * y + prev[2] * z + prev[3] * w < 0f) -1f else 1f
            val k = SMOOTHING
            floatArrayOf(
                prev[0] + k * (sign * x - prev[0]),
                prev[1] + k * (sign * y - prev[1]),
                prev[2] + k * (sign * z - prev[2]),
                prev[3] + k * (sign * w - prev[3]),
            ).also { n ->
                val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2] + n[3] * n[3]).coerceAtLeast(1e-6f)
                for (i in 0..3) n[i] /= len
            }
        }
        q = cur
        val r = FloatArray(9)
        // getRotationMatrixFromVector wants x, y, z, w with w >= 0 or it recomputes w.
        val s = if (cur[3] < 0f) -1f else 1f
        SensorManager.getRotationMatrixFromVector(r, floatArrayOf(s * cur[0], s * cur[1], s * cur[2], s * cur[3]))
        matrix.value = r
        if (v.size >= 5 && v[4] >= 0f) headingErrorDeg.floatValue = Math.toDegrees(v[4].toDouble()).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Share of each new reading: steady at rest, still quick to follow a turn. */
        private const val SMOOTHING = 0.25f
    }
}

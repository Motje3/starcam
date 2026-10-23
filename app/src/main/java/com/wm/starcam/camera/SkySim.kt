package com.wm.starcam.camera

import com.wm.starcam.engine.NativeEngine
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * A synthetic night sky for the emulator, whose camera shows no stars - debug builds only, armed
 * from adb for the next session:
 *
 *   adb shell am start -n com.wm.starcam/.MainActivity --ez session true
 *       --es sim "frames=12;exp=300;clouds=5-8;drift=12:0.06;dawn=20;darks=auto"
 *
 * Every frame that session takes - the probe, the star focus, the lights, the darks - is replaced
 * by a rendered star field (cpp/sim.cpp) before the engine sees it, so the capture loop and the
 * autopilot run as they would under a real sky: the stars drift between frames, cloud passes over
 * the light attempts in [Scenario.clouds], the lens's best focus moves by [Scenario.driftDpt]
 * from light attempt [Scenario.driftAt] on (the blur grows with the distance between the focus
 * asked for and the true one), and the sky brightens 60% a frame from [Scenario.dawnAt]. Light
 * attempts count every light exposure, the autopilot's cloud checks included.
 */
class SkySim(val scenario: Scenario, private val cam: CamInfo, width: Int, height: Int) {

    data class Scenario(
        /** Light frames planned, instead of the preset's. */
        val frames: Int? = null,
        /** Exposure per light frame, instead of the decision from the probe. */
        val exposureMs: Long? = null,
        val clouds: List<IntRange> = emptyList(),
        val driftAt: Int? = null,
        val driftDpt: Float = 0f,
        val dawnAt: Int? = null,
        /** Answers the "cover the lens" question itself (true), or skips the darks (false). */
        val autoDarks: Boolean = true,
    )

    enum class Kind { PROBE, FOCUS, LIGHT, DARK }

    private val width = width
    private val height = height
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
    private var captures = 0

    /** Light exposures so far, cloud checks included. */
    var lightAttempts = 0
        private set

    /**
     * The frame the camera would have delivered for a capture of [kind] at [focusDpt]. Lights
     * advance the scenario; the buffer is reused, so use the pixels before the next call.
     */
    fun render(kind: Kind, focusDpt: Float): RawPixels {
        val attempt = if (kind == Kind.LIGHT) lightAttempts++ else lightAttempts
        captures++
        val drifted = scenario.driftAt?.let { attempt >= it } == true && kind != Kind.PROBE
        val trueFocus = TRUE_FOCUS_DPT + if (drifted) scenario.driftDpt else 0f
        val cloudy = kind == Kind.LIGHT && scenario.clouds.any { attempt in it }
        val dawnSteps = scenario.dawnAt?.let { attempt - it + 1 }?.takeIf { it > 0 && kind == Kind.LIGHT } ?: 0
        val params = JSONObject().apply {
            put("cfa", cam.cfa)
            put("black", cam.blackLevelRaster.average())
            put("white", cam.whiteLevel)
            put("drift", captures)
            put("seed", 1000 + captures)
            put("stars", 700)
            put("sky", SKY * 1.6.pow(dawnSteps))
            put("cloud", if (cloudy) CLOUD else 0.0)
            put("blurPx", (abs(focusDpt - trueFocus) * BLUR_PX_PER_DPT).toDouble())
            put("dark", kind == Kind.DARK)
        }.toString()
        buffer.clear()
        NativeEngine.simulateSky(buffer, width, height, width * 2, params)
        return RawPixels(buffer, width, height, width * 2)
    }

    companion object {
        /** Where the simulated lens is sharpest before any drift (the sweep runs 0..0.30 dpt). */
        const val TRUE_FOCUS_DPT = 0.10f
        /** Blur (PSF sigma, RAW px) per diopter of focus error. */
        const val BLUR_PX_PER_DPT = 45f
        private const val SKY = 0.035
        /** Cloud thick enough to hide most of the stars. */
        private const val CLOUD = 0.93

        /** Armed by the adb hook, taken by the next session. */
        @Volatile var armed: Scenario? = null

        /** "frames=12;exp=300;clouds=5-8,14;drift=12:0.06;dawn=20;darks=auto" (all optional). */
        fun parse(spec: String): Scenario {
            var s = Scenario()
            for (part in spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val key = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                s = when (key) {
                    "frames" -> s.copy(frames = value.toIntOrNull()?.coerceIn(2, 200))
                    "exp" -> s.copy(exposureMs = value.toLongOrNull()?.coerceIn(1L, 60_000L))
                    "clouds" -> s.copy(clouds = value.split(',').mapNotNull { r ->
                        val a = r.substringBefore('-').trim().toIntOrNull() ?: return@mapNotNull null
                        val b = r.substringAfter('-', a.toString()).trim().toIntOrNull() ?: a
                        a..b
                    })
                    "drift" -> s.copy(
                        driftAt = value.substringBefore(':').toIntOrNull(),
                        driftDpt = value.substringAfter(':', "0").toFloatOrNull() ?: 0f,
                    )
                    "dawn" -> s.copy(dawnAt = value.toIntOrNull())
                    "darks" -> s.copy(autoDarks = value != "skip")
                    else -> s
                }
            }
            return s
        }
    }
}

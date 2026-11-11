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
 *   adb shell am start -n com.wm.stelli/com.wm.starcam.MainActivity --ez session true
 *       --es sim "frames=12;exp=300;clouds=5-8;drift=12:0.06;dawn=20;darks=auto"
 *
 * Every frame that session takes - the probe, the star focus, the lights, the darks - is replaced
 * by a rendered star field (cpp/sim.cpp) before the engine sees it, so the capture loop and the
 * autopilot run as they would under a real sky: the stars drift between frames, cloud passes over
 * the light attempts in [Scenario.clouds], the lens's best focus moves by [Scenario.driftDpt]
 * from light attempt [Scenario.driftAt] on (the blur grows with the distance between the focus
 * asked for and the true one), and the sky brightens 60% a frame from [Scenario.dawnAt]. Light
 * attempts count every light exposure, the autopilot's cloud checks and the pilot frame included.
 *
 * The sky and the stars are as bright as the frame's exposure x ISO makes them, up to white: the
 * scenarios written before that ran at the emulator's 0.3 s x ISO 1600 ([REF_EXPOSURE_ISO]),
 * where nothing changed. [Scenario.skyPct] sets the sky instead, [Scenario.pedestalDn] adds an
 * offset like the one the phone's 1 s ISO 3200 probe showed, and [Scenario.probeMs] shortens the
 * probe (the emulator's longest exposure is 0.3 s: without it the probe and the light frames are
 * the same exposure, and an offset in the probe is no different from sky).
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
        /**
         * The "cover the lens" question answered at once: yes (true) or skip the darks (false);
         * null leaves it to the person, as under a real sky.
         */
        val autoDarks: Boolean? = true,
        /** The sky in percent of white at 1 s x ISO 3200, before any pedestal; null: the old sky. */
        val skyPct: Double? = null,
        /** DN added to every frame at ISO 3200, in proportion to the ISO (no light in it). */
        val pedestalDn: Double = 0.0,
        /** The probe frame's exposure, instead of the app's 1 s (capped by the camera). */
        val probeMs: Long? = null,
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
     * The frame the camera would have delivered for a capture of [kind] at [focusDpt], exposed
     * [exposureNs] at [iso]. Lights advance the scenario; the buffer is reused, so use the pixels
     * before the next call.
     */
    fun render(kind: Kind, focusDpt: Float, exposureNs: Long, iso: Int): RawPixels {
        val attempt = if (kind == Kind.LIGHT) lightAttempts++ else lightAttempts
        captures++
        // What the camera would really have exposed: it clamps a request to its range.
        val exp = exposureNs.coerceIn(cam.exposureRangeNs.lower, cam.exposureRangeNs.upper)
        val sens = iso.coerceIn(cam.isoRange.lower, cam.isoRange.upper)
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
            put("sky", skyLevel(scenario, exp, sens) * 1.6.pow(dawnSteps))
            put("starGain", starGain(exp, sens))
            put("pedestal", pedestal(scenario, sens))
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
        /** The sky's share of the range at [REF_EXPOSURE_ISO], unless the scenario sets one. */
        private const val SKY = 0.035
        /** Cloud thick enough to hide most of the stars. */
        private const val CLOUD = 0.93

        /**
         * Exposure (s) x ISO at which the sky is [SKY] and the stars as bright as they always
         * were: the emulator's longest exposure at its highest ISO, which every frame of the
         * scenarios written before the sky followed the settings was taken at.
         */
        const val REF_EXPOSURE_ISO = 0.3 * 1600
        /** Exposure (s) x ISO that [Scenario.skyPct] and [Scenario.pedestalDn] are given at. */
        const val PROBE_EXPOSURE_ISO = 1.0 * 3200

        /** The sky's share of the range in a frame exposed [exposureNs] at [iso], before dawn and cloud. */
        fun skyLevel(scenario: Scenario, exposureNs: Long, iso: Int): Double {
            val light = exposureNs / 1e9 * iso
            return scenario.skyPct?.let { it / 100.0 * light / PROBE_EXPOSURE_ISO } ?: SKY * light / REF_EXPOSURE_ISO
        }

        /** How bright the stars are against the old scenarios' frames. */
        fun starGain(exposureNs: Long, iso: Int): Double = exposureNs / 1e9 * iso / REF_EXPOSURE_ISO

        /** DN of pedestal in a frame at [iso]. */
        fun pedestal(scenario: Scenario, iso: Int): Double = scenario.pedestalDn * iso / 3200.0

        /** Armed by the adb hook, taken by the next session. */
        @Volatile var armed: Scenario? = null

        /**
         * "frames=12;exp=300;clouds=5-8,14;drift=12:0.06;dawn=20;darks=auto|skip|ask;sky=0.8;ped=6;
         * probe=100" (all optional).
         */
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
                    "darks" -> s.copy(autoDarks = when (value) { "skip" -> false; "ask" -> null; else -> true })
                    "sky" -> s.copy(skyPct = value.toDoubleOrNull()?.takeIf { it >= 0.0 }?.coerceAtMost(100.0))
                    "ped" -> s.copy(pedestalDn = value.toDoubleOrNull()?.coerceIn(0.0, 10_000.0) ?: 0.0)
                    "probe" -> s.copy(probeMs = value.toLongOrNull()?.coerceIn(1L, 60_000L))
                    else -> s
                }
            }
            return s
        }
    }
}

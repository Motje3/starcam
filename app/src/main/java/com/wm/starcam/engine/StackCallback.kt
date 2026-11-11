package com.wm.starcam.engine

import com.wm.starcam.session.SettingsStore
import com.wm.starcam.sky.PlateSolution
import com.wm.starcam.sky.SkyRecord
import com.wm.starcam.sky.SkyStars
import com.wm.starcam.sky.StackSky
import com.wm.starcam.sky.StarCatalog
import com.wm.starcam.sky.StarColour
import com.wm.starcam.util.AppLog
import com.wm.starcam.util.WhatsInPhoto
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What finalizeStack is given by both of its callers, a session and a re-stack: the stages for
 * the progress bar, and [onStars], which the engine calls by name (jni_bridge.cpp) between the
 * stack and the look with the stack's own stars (out/stars.json). There the photo is solved from
 * the stars that followed the sky, the white balance is taken from the colours of the catalogue's
 * stars in it (the engine develops the photo with it) and the sky's brightness from their light,
 * all kept in meta/sky.json; once the JPEG is saved, [writeWhatsIn] gives "What's in this photo"
 * its answer from the same solution.
 *
 * [focalRawPx] and [rawLong]: the session header's focal length at its RAW size; [cameraR],
 * [cameraB]: the camera's white balance the frames were shot with (red and blue over green), with
 * [cameraId] for the balance the camera's earlier sessions measured; [starColour]: whether the
 * photo takes the stars' balance at all (off: the stars are still solved, for the rest); [learn]:
 * whether this session teaches the camera's balance (a real sky, shot now).
 */
class StackCallback(
    private val sessionDir: File,
    private val catalog: StarCatalog?,
    private val focalRawPx: Double?,
    private val rawLong: Int,
    private val cameraR: Double,
    private val cameraB: Double,
    private val cameraId: String?,
    private val starColour: Boolean,
    private val settings: SettingsStore?,
    private val learn: Boolean,
    private val progress: (stage: String, percent: Int) -> Unit,
) : NativeEngine.ProgressCallback {

    override fun onProgress(stage: String, percent: Int) = progress(stage, percent)

    /** The stack's solution and its picture size, once [onStars] has found one; null otherwise. */
    @Volatile private var solved: Triple<PlateSolution, SkyStars, Pair<Int, Long>>? = null

    /**
     * The engine's question, on its own thread: out/stars.json's text in, the stars' white balance
     * out as a flat {"wbR":..,"wbB":..} - or "" for no change (no answer, Star colours off,
     * anything gone wrong). Never throws and never takes much more than [BUDGET_MS]: the photo
     * waits for it.
     */
    fun onStars(starsJson: String): String {
        val t0 = System.currentTimeMillis()
        return try {
            answer(starsJson, t0)
        } catch (t: Throwable) {
            AppLog.e(TAG, "the stars' answer failed after ${System.currentTimeMillis() - t0} ms: nothing changes", t)
            ""
        }
    }

    private fun answer(starsJson: String, t0: Long): String {
        // What an earlier stack of this session found belongs to that one.
        val recordFile = File(sessionDir, SkyRecord.FILE)
        recordFile.delete()
        solved = null
        val sky = SkyStars.parse(starsJson)
        if (sky == null) {
            AppLog.w(TAG, "out/stars.json could not be read: nothing changes")
            return ""
        }
        val points = sky.skyPoints().size / 3
        // The solve, here on the engine's thread: the solver stops by itself at the deadline (a
        // field with no sky in it would cost every pair of stars in full), so the photo never
        // waits much longer than the budget and nothing is left running beside the look.
        var measured: StackSky.Measured? = null
        var why = ""
        if (catalog == null) {
            why = "no catalogue"
        } else {
            try {
                // The search itself gets four fifths of the budget: what it had found by then is
                // still checked, and that took 0.7 s on the emulator's debug build.
                measured = StackSky.measure(sky, catalog, focalRawPx, rawLong, giveUpMs = BUDGET_MS / 2,
                    deadlineMs = t0 + BUDGET_MS * 4 / 5)
            } catch (e: Exception) {
                AppLog.e(TAG, "the solve failed", e)
                why = "the solve failed"
            }
        }
        val m = measured
        val sol = m?.solution
        if (m != null && sol == null) why = (if (m.outOfTime) "out of time" else "not recognised") + " (tried${m.tried})"
        if (m != null && sol != null) solved = Triple(sol, sky, m.solving to m.solveMs)

        // The balance: the stars' own when they gave one, else what this camera's earlier sessions
        // measured - and none when too few stars mark the sky for the look (StackSky.balance).
        val fit = m?.colour
        val remembered = cameraId?.let { settings?.starBalance(it) }
        // What the stars said of the sensor is kept whether or not this photo takes it.
        if (fit != null && learn && cameraId != null && settings != null && cameraR > 0 && cameraB > 0) {
            val now = StarColour.absolute(cameraR, cameraB, fit)
            val stored = StarColour.learn(remembered, now)
            settings.saveStarBalance(cameraId, stored)
            AppLog.i(TAG, String.format(Locale.US, "camera %s star balance R %.3f B %.3f by this session, now R %.3f B %.3f",
                cameraId, now.first, now.second, stored.first, stored.second))
        }
        val colour = StackSky.balance(fit, remembered, cameraR, cameraB, points)
        val applied = colour != null && starColour
        val record = if (m != null) StackSky.record(m, colour.takeIf { applied })
        else SkyRecord(width = sky.width, height = sky.height, colour = colour.takeIf { applied })
        if (record.solution != null || record.colour != null) {
            runCatching {
                recordFile.parentFile?.mkdirs()
                recordFile.writeText(record.toJson())
            }.onFailure { AppLog.e(TAG, "could not keep ${SkyRecord.FILE}", it) }
        }
        val ms = System.currentTimeMillis() - t0
        AppLog.i(TAG, buildString {
            append("${m?.solving ?: sky.forSolving().size} of ${sky.stars.size} stars to solve, $points sky points: ")
            append(sol?.let {
                String.format(Locale.US, "ra %.2f dec %.2f, %d matched, rms %.2f px, %.0f px focal, %d catalogue stars on them",
                    it.raDeg, it.decDeg, it.matched, it.rmsPx, it.scale, m?.matched?.size ?: 0)
            } ?: why)
            fit?.let {
                append(String.format(Locale.US, " · colour wbR %.3f wbB %.3f from %d stars of snr %.0f+ (%s slopes %+.2f %+.2f, " +
                    "scatter %.3f %.3f, known to %.3f %.3f)",
                    it.wbR, it.wbB, it.stars, it.snrCut, if (it.freeSlopes) "free" else "fixed", it.slopeR, it.slopeB,
                    it.scatterR, it.scatterB, it.errorR, it.errorB))
            } ?: append(if (colour != null) String.format(Locale.US, " · colour remembered: wbR %.3f wbB %.3f",
                colour.wbR, colour.wbB) else " · no colour")
            m?.photometry?.let {
                append(String.format(Locale.US, " · sky %.2f mag/arcsec2 (Bortle %d, zp %.2f from %d stars)",
                    it.magArcsec2, it.bortle, it.zp, it.stars))
            }
            append(when {
                applied -> " · applied"
                colour != null -> " · not applied (Star colours off)"
                fit != null -> " · not applied ($points sky points, the look needs ${StackSky.MIN_SKY_POINTS})"
                else -> ""
            })
            append(" · $ms ms")
        })
        if (colour == null || !applied) return ""
        return JSONObject().put("wbR", colour.wbR).put("wbB", colour.wbB).toString()
    }

    /**
     * "What's in this photo" for [photo], the JPEG just saved from this stack, from the solution
     * [onStars] found: the session page then has it at once. Nothing when it found none.
     */
    fun writeWhatsIn(photo: File) {
        val (sol, sky, info) = solved ?: return
        runCatching {
            WhatsInPhoto.keep(sessionDir, photo, sky.width, sky.height, sol, info.first, info.second, WhatsInPhoto.SOURCE_STACK)
        }.onFailure { AppLog.e(TAG, "could not keep the stack's answer for ${photo.name}", it) }
    }

    companion object {
        private const val TAG = "stars"

        /** How long the photo may wait for the stars' answer. */
        const val BUDGET_MS = 4000L

        /**
         * The camera's white balance as red and blue over green, from its gains in 2x2 raster order
         * ([com.wm.starcam.camera.CamMeta.rasterWbGains], an sraw header) for the sensor's [cfa].
         */
        fun cameraBalance(rasterWb: FloatArray, cfa: Int): Pair<Double, Double> {
            if (rasterWb.size < 4) return 1.0 to 1.0
            val colours = when (cfa) {
                0 -> intArrayOf(0, 1, 1, 2)  // RGGB
                1 -> intArrayOf(1, 0, 2, 1)  // GRBG
                2 -> intArrayOf(1, 2, 0, 1)  // GBRG
                else -> intArrayOf(2, 1, 1, 0)  // BGGR
            }
            var r = 0.0
            var b = 0.0
            var g = 0.0
            for (i in 0..3) when (colours[i]) {
                0 -> r = rasterWb[i].toDouble()
                2 -> b = rasterWb[i].toDouble()
                else -> g += rasterWb[i] / 2.0
            }
            if (!(g > 0) || !(r > 0) || !(b > 0)) return 1.0 to 1.0
            return r / g to b / g
        }
    }
}

package com.wm.starcam.sky

import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What the stack's stars said about its photo, kept as meta/sky.json: where it points (the
 * solution, at stars.json's size), the white balance its stars gave and whether the photo was
 * developed with it, and the sky's brightness. The studio develops the master again with the same
 * balance, and the session page shows the numbers. Every part is optional: a photo that did not
 * solve may still have the camera's remembered balance, and one too poor in stars no sky numbers.
 */
class SkyRecord(
    val solution: PlateSolution? = null,
    /** The solution's picture size (stars.json's). */
    val width: Int = 0,
    val height: Int = 0,
    /** Stars given to the solver, and catalogue stars matched on them. */
    val stars: Int = 0,
    val matched: Int = 0,
    val colour: Colour? = null,
    val sky: Sky? = null,
) {
    /** The gains the photo was developed with; [stars] 0 and [source] "remembered" for a remembered balance. */
    class Colour(
        val wbR: Double,
        val wbB: Double,
        val stars: Int,
        val scatterR: Double?,
        val scatterB: Double?,
        val source: String,
    )

    class Sky(val zp: Double, val magArcsec2: Double, val bortle: Int, val stars: Int)

    fun toJson(): String = JSONObject().apply {
        if (solution != null) put("solution", solutionJson(solution).put("width", width).put("height", height))
        put("stars", stars)
        put("matched", matched)
        colour?.let { c ->
            put("colour", JSONObject().apply {
                put("wbR", c.wbR)
                put("wbB", c.wbB)
                put("stars", c.stars)
                c.scatterR?.let { put("scatterR", it) }
                c.scatterB?.let { put("scatterB", it) }
                put("source", c.source)
            })
        }
        sky?.let { s ->
            put("sky", JSONObject().apply {
                put("zp", s.zp)
                put("magArcsec2", s.magArcsec2)
                put("bortle", s.bortle)
                put("stars", s.stars)
            })
        }
    }.toString()

    /**
     * The session page's Details lines, label and value, for what is known: how the star colours
     * were set and the sky's brightness where the photo looks (a Bortle class is the zenith's:
     * "like", for a direction).
     */
    fun detailRows(): List<Pair<String, String>> = buildList {
        colour?.let { c ->
            add("Star colours" to if (c.source == SOURCE_REMEMBERED) "From this camera's earlier photos"
            else "Set by ${c.stars} stars")
        }
        sky?.let { s ->
            add("Sky brightness" to String.format(Locale.US, "%.1f mag/arcsec², like Bortle %d, in this direction",
                s.magArcsec2, s.bortle))
        }
    }

    companion object {
        const val FILE = "meta/sky.json"
        const val SOURCE_STARS = "stars"
        const val SOURCE_REMEMBERED = "remembered"

        fun read(sessionDir: File): SkyRecord? = runCatching {
            val f = File(sessionDir, FILE)
            if (f.isFile) parse(f.readText()) else null
        }.getOrNull()

        fun parse(json: String): SkyRecord? = runCatching {
            val o = JSONObject(json)
            val s = o.optJSONObject("solution")
            val c = o.optJSONObject("colour")
            val k = o.optJSONObject("sky")
            SkyRecord(
                solution = s?.let { solutionFrom(it) },
                width = s?.optInt("width") ?: 0,
                height = s?.optInt("height") ?: 0,
                stars = o.optInt("stars"),
                matched = o.optInt("matched"),
                colour = c?.let {
                    Colour(it.getDouble("wbR"), it.getDouble("wbB"), it.optInt("stars"),
                        it.optDouble("scatterR").takeIf { v -> v.isFinite() },
                        it.optDouble("scatterB").takeIf { v -> v.isFinite() },
                        it.optString("source", SOURCE_STARS))
                },
                sky = k?.let {
                    Sky(it.getDouble("zp"), it.getDouble("magArcsec2"), it.optInt("bortle"), it.optInt("stars"))
                },
            )
        }.getOrNull()

        /** A solution as whats_in.json and sky.json keep it. */
        fun solutionJson(sol: PlateSolution): JSONObject = JSONObject().apply {
            put("ra", sol.raDeg)
            put("dec", sol.decDeg)
            put("scale", sol.scale)
            put("angle", sol.angle)
            put("flipped", sol.flipped)
            put("cx", sol.cx)
            put("cy", sol.cy)
            put("matched", sol.matched)
            put("rms", sol.rmsPx)
            put("k", sol.k)
            if (sol.kReach2 < Double.MAX_VALUE) put("kReach2", sol.kReach2)
        }

        fun solutionFrom(s: JSONObject): PlateSolution = PlateSolution(
            s.getDouble("ra"), s.getDouble("dec"), s.getDouble("scale"), s.getDouble("angle"),
            s.getBoolean("flipped"), s.getDouble("cx"), s.getDouble("cy"), s.getInt("matched"), s.getDouble("rms"),
            s.optDouble("k", 0.0), s.optDouble("kReach2", Double.MAX_VALUE),
        )
    }
}

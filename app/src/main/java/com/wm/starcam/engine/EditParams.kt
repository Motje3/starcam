package com.wm.starcam.engine

import org.json.JSONObject

/**
 * The editing studio's settings for one photo, as the user sees them. Saved beside the photo
 * (out/edit.json) so reopening the studio continues where it was left; [toOptionsJson] turns
 * them into the engine's post options (post.h, version 2).
 */
data class EditParams(
    /** Display brightness of the sky background. */
    val sky: Float = 0.16f,
    /** 0 soft .. 1 punchy: how far above black the sky background sits. */
    val contrast: Float = 0.6f,
    /** Display brightness of the landscape. */
    val ground: Float = 0.07f,
    val saturation: Float = 1.25f,
    /** Extra colour in stars. */
    val starColor: Float = 0.4f,
    /** -1 blue .. 1 amber. */
    val warmth: Float = 0f,
    /** -1 green .. 1 magenta. */
    val tint: Float = 0f,
    /** Share of the light-pollution gradient removed. */
    val gradient: Float = 1f,
    /** Background noise reduction. */
    val noise: Float = 0.6f,
    val sharpen: Boolean = false,
    /** 0..1: the Milky Way's structure, its dust lanes and star clouds, made stronger. */
    val detail: Float = 0f,
    /** 0..1: stars made smaller and fainter, so the Milky Way stands out. */
    val smallerStars: Float = 0f,
    /** 0..1: the corners darkened. */
    val vignette: Float = 0f,
    /** 0 natural, 1 nightfall. */
    val style: Int = 0,
    /** Clockwise turn the photo is shown and saved with, relative to the master as stored. */
    val rotation: Int = 0,
) {
    /**
     * [masterUpright]: the master was written upright (sessions that recorded the phone's
     * attitude), so the ground is at its bottom unless the user turned it.
     */
    fun toOptionsJson(masterUpright: Boolean): String = JSONObject().apply {
        put("version", 2)
        put("skyLevel", sky.toDouble())
        put("contrastGap", (0.5f - 0.42f * contrast).toDouble())
        put("groundLevel", ground.toDouble())
        put("saturation", saturation.toDouble())
        put("starColor", starColor.toDouble())
        put("warmth", warmth.toDouble())
        put("tint", tint.toDouble())
        put("gradientRemoval", gradient > 0f)
        put("gradientStrength", gradient.toDouble())
        put("noiseReduction", noise.toDouble())
        put("chromaDenoise", true)
        put("starSharpen", sharpen)
        put("detail", detail.toDouble())
        put("starReduction", smallerStars.toDouble())
        put("vignette", vignette.toDouble())
        put("style", style)
        put("rotation", rotation)
        // Where the ground is in the master: known when it is upright or the user has turned it
        // upright; otherwise the engine looks along every edge.
        put("downEdge", if (masterUpright || rotation != 0) downEdgeFor(rotation) else -1)
    }.toString()

    fun toJson(): String = JSONObject().apply {
        put("sky", sky.toDouble())
        put("contrast", contrast.toDouble())
        put("ground", ground.toDouble())
        put("saturation", saturation.toDouble())
        put("starColor", starColor.toDouble())
        put("warmth", warmth.toDouble())
        put("tint", tint.toDouble())
        put("gradient", gradient.toDouble())
        put("noise", noise.toDouble())
        put("sharpen", sharpen)
        put("detail", detail.toDouble())
        put("smallerStars", smallerStars.toDouble())
        put("vignette", vignette.toDouble())
        put("style", style)
        put("rotation", rotation)
    }.toString()

    companion object {
        fun fromJson(o: JSONObject): EditParams {
            val d = EditParams()
            fun f(k: String, v: Float) = o.optDouble(k, v.toDouble()).toFloat()
            return EditParams(
                sky = f("sky", d.sky), contrast = f("contrast", d.contrast), ground = f("ground", d.ground),
                saturation = f("saturation", d.saturation), starColor = f("starColor", d.starColor),
                warmth = f("warmth", d.warmth), tint = f("tint", d.tint),
                gradient = f("gradient", d.gradient), noise = f("noise", d.noise),
                sharpen = o.optBoolean("sharpen", d.sharpen), detail = f("detail", d.detail),
                smallerStars = f("smallerStars", d.smallerStars), vignette = f("vignette", d.vignette),
                style = o.optInt("style", d.style),
                rotation = quarterTurn(o.optInt("rotation", d.rotation)),
            )
        }

        /**
         * [r] as the nearest of 0, 90, 180 and 270, as the engine reads it: any other value
         * would turn the engine's picture but not the preview bitmap it has to fit.
         */
        fun quarterTurn(r: Int): Int = (((r % 360) + 360) % 360 + 45) / 90 * 90 % 360

        /** The master's edge that faces the ground after a clockwise turn (engine's downEdge). */
        fun downEdgeFor(rotation: Int): Int = when (rotation) {
            90 -> 3
            180 -> 2
            270 -> 1
            else -> 0
        }
    }
}

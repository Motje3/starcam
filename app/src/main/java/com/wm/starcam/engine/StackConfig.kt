package com.wm.starcam.engine

import org.json.JSONObject

/**
 * Options for NativeEngine.finalizeStack. Flat JSON only — the native parser does not support
 * nesting. Keys must stay in sync with engine.cpp's option reading.
 */
data class StackConfig(
    val sigmaFirst: Float = 3.5f,
    val sigmaFinal: Float = 2.8f,
    val hotPixelFix: Boolean = true,
    /**
     * Divide out the lens's vignetting using the map the camera reports, before stacking.
     * Only does anything when a usable map was captured; with it off (or absent) the pipeline
     * behaves exactly as it did before the feature existed.
     */
    val lensShading: Boolean = true,
    val gradientRemoval: Boolean = true,
    val stretchStrength: Int = 1,          // 0 subtle, 1 normal, 2 strong
    val saturation: Float = 1.25f,
    val chromaDenoise: Boolean = true,
    val starSharpen: Boolean = false,
    val foregroundComposite: Boolean = false,
    val featherPx: Float = 40f,
    /** 0 = natural (default), 1 = "nightfall" purple-blue grade. Opt-in, never default. */
    val style: Int = 0,
    /** Processing generation: 2 = current, 1 = the 0.3 "Classic" look (Settings › Look). */
    val version: Int = 2,
    /**
     * Clockwise turn that makes the photo upright, from the phone's attitude when the burst
     * started; -1 unknown (sessions from before it was recorded): written as the sensor
     * delivers them, and the processing looks for the landscape along every edge.
     */
    val rotation: Int = -1,
    /**
     * The phone pointed too near the zenith to tell which way up it was: [rotation] is the way
     * it was held before, and the processing does not take the ground's edge from it.
     */
    val rotationGuessed: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("sigmaFirst", sigmaFirst)
        put("sigmaFinal", sigmaFinal)
        put("hotPixelFix", hotPixelFix)
        put("lensShading", lensShading)
        put("gradientRemoval", gradientRemoval)
        put("stretchStrength", stretchStrength)
        put("saturation", saturation)
        put("chromaDenoise", chromaDenoise)
        put("starSharpen", starSharpen)
        put("foregroundComposite", foregroundComposite)
        put("featherPx", featherPx)
        put("style", style)
        put("version", version)
        if (rotation >= 0) put("rotation", rotation)
        if (rotationGuessed) put("rotationGuessed", true)
    }.toString()

    companion object {
        /** Reads what [toJson] wrote (a session header's "stackConfig"); missing keys keep defaults. */
        fun fromJson(o: JSONObject): StackConfig {
            val d = StackConfig()
            return StackConfig(
                sigmaFirst = o.optDouble("sigmaFirst", d.sigmaFirst.toDouble()).toFloat(),
                sigmaFinal = o.optDouble("sigmaFinal", d.sigmaFinal.toDouble()).toFloat(),
                hotPixelFix = o.optBoolean("hotPixelFix", d.hotPixelFix),
                lensShading = o.optBoolean("lensShading", d.lensShading),
                gradientRemoval = o.optBoolean("gradientRemoval", d.gradientRemoval),
                stretchStrength = o.optInt("stretchStrength", d.stretchStrength),
                saturation = o.optDouble("saturation", d.saturation.toDouble()).toFloat(),
                chromaDenoise = o.optBoolean("chromaDenoise", d.chromaDenoise),
                starSharpen = o.optBoolean("starSharpen", d.starSharpen),
                foregroundComposite = o.optBoolean("foregroundComposite", d.foregroundComposite),
                featherPx = o.optDouble("featherPx", d.featherPx.toDouble()).toFloat(),
                style = o.optInt("style", d.style),
                // A header from before the look existed was stacked classic.
                version = o.optInt("version", 1),
                rotation = o.optInt("rotation", -1),
                rotationGuessed = o.optBoolean("rotationGuessed", false),
            )
        }
    }
}

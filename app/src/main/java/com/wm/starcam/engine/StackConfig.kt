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
    }.toString()
}

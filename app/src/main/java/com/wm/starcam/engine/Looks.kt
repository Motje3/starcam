package com.wm.starcam.engine

/**
 * The editing studio's looks: one tap tones the photo - brightness, contrast, colour, the Milky
 * Way's detail, the vignette - and leaves the clean-up (light pollution, noise, star size,
 * sharpening), the landscape's brightness and the turn as they are.
 */
enum class Look(val label: String, private val tone: EditParams) {
    /** The look the photo was made with. */
    NATURAL("Natural", EditParams()),
    VIVID("Vivid", EditParams(sky = 0.17f, contrast = 0.72f, saturation = 1.65f, starColor = 0.75f, detail = 0.5f)),
    /** Darker and punchier: a black sky, the Milky Way carved out. */
    DEEP("Deep", EditParams(sky = 0.12f, contrast = 0.85f, saturation = 1.35f, starColor = 0.5f, warmth = -0.08f,
        detail = 0.6f, vignette = 0.35f)),
    NIGHTFALL("Nightfall", EditParams(contrast = 0.65f, saturation = 1.3f, starColor = 0.5f, style = 1, detail = 0.3f)),
    WARM("Warm", EditParams(saturation = 1.35f, starColor = 0.5f, warmth = 0.35f, tint = 0.05f, detail = 0.2f)),
    COOL("Cool", EditParams(sky = 0.15f, contrast = 0.65f, saturation = 1.2f, starColor = 0.45f, warmth = -0.35f,
        tint = 0.06f, detail = 0.2f)),
    MONO("Mono", EditParams(sky = 0.15f, contrast = 0.8f, saturation = 0f, starColor = 0f, detail = 0.5f,
        vignette = 0.25f));

    /** [p] toned as this look. */
    fun applyTo(p: EditParams): EditParams = p.copy(
        sky = tone.sky, contrast = tone.contrast, saturation = tone.saturation, starColor = tone.starColor,
        warmth = tone.warmth, tint = tone.tint, style = tone.style, detail = tone.detail,
        vignette = tone.vignette,
    )

    /** Whether [p] is toned exactly as this look (no slider moved since it was picked). */
    fun matches(p: EditParams): Boolean = applyTo(p) == p
}

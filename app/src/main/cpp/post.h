// Astro post-processing pipeline. See PLAN.md §6.7. Operates in place.
#pragma once

#include "common.h"

namespace starcam {

struct PostParams {
    bool gradientRemoval = true;
    int stretchStrength = 1;      // 0 subtle, 1 normal, 2 strong (target sky bg 0.06/0.09/0.13)
    float saturation = 1.25f;
    bool chromaDenoise = true;
    bool starSharpen = false;
    // Color grade applied after saturation: 0 = natural (honest color, default),
    // 1 = "nightfall" (the cool purple-blue social-media astro look; opt-in, never default).
    int style = 0;
    // Processing generation. 1 = the 0.3 look, kept exactly as it was ("Classic" in Settings);
    // 2 = the current pipeline (post2.cpp).
    int version = 1;

    // ---- version 2 only (classic ignores these) ----
    // Which edge of the image faces the ground when the photo is upright: 0 bottom, 1 left,
    // 2 top, 3 right; -1 unknown (the landscape is then looked for along every edge).
    int downEdge = -1;
    float skyLevel = 0.16f;        // display brightness the sky background lands at
    float groundLevel = 0.07f;     // display brightness of the landscape's median
    float gradientStrength = 0.9f; // share of the fitted light-pollution gradient that is removed
    float noiseReduction = 0.6f;   // 0..1, background luma noise reduction
    float starColor = 0.4f;        // extra chroma in stars, on top of saturation
    // How far above black the sky background sits, as a fraction of its brightness: the
    // contrast control. Smaller is punchier and shows more noise.
    float contrastGap = 0.25f;
    int gradientOrder = 0;         // sky gradient model: 2 quadratic, 3 cubic, 0 auto
    float warmth = 0.0f;           // -1..1 white balance towards blue / amber (editing studio)
    float tint = 0.0f;             // -1..1 white balance towards green / magenta (editing studio)
};

// The v2 pipeline behind postprocess() for version >= 2. Same contract.
void postprocess_v2(ImageF& rgb, const PostParams& p, const ProgressFn& progress);

// Input: linear sRGB, roughly [0,1]. Output (in place): display-referred, sRGB-gamma-encoded [0,1].
// Stages (each skippable per params): 2nd-order polynomial gradient removal (star-masked samples),
// background gray-world WB trim (gains clamped 0.9..1.1), per-channel black point at 0.25th
// percentile minus 0.5*sigma, asinh stretch with k auto-searched so the sky background lands at the
// strength target, saturation boost in YCbCr (highlight-protected), chroma-only box denoise at 1/4
// res, optional star-masked unsharp, final sRGB gamma encode.
void postprocess(ImageF& rgb, const PostParams& p, const ProgressFn& progress);

}  // namespace starcam

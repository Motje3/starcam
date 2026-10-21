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
};

// Input: linear sRGB, roughly [0,1]. Output (in place): display-referred, sRGB-gamma-encoded [0,1].
// Stages (each skippable per params): 2nd-order polynomial gradient removal (star-masked samples),
// background gray-world WB trim (gains clamped 0.9..1.1), per-channel black point at 0.25th
// percentile minus 0.5*sigma, asinh stretch with k auto-searched so the sky background lands at the
// strength target, saturation boost in YCbCr (highlight-protected), chroma-only box denoise at 1/4
// res, optional star-masked unsharp, final sRGB gamma encode.
void postprocess(ImageF& rgb, const PostParams& p, const ProgressFn& progress);

}  // namespace starcam

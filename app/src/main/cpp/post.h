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
    float gradientStrength = 1.0f; // share of the fitted light-pollution gradient that is removed
    float noiseReduction = 0.6f;   // 0..1, background luma noise reduction
    float starColor = 0.4f;        // extra chroma in stars, on top of saturation
    // How far above black the sky background sits, as a fraction of its brightness: the
    // contrast control. Smaller is punchier and shows more noise.
    float contrastGap = 0.25f;
    float warmth = 0.0f;           // -1..1 white balance towards blue / amber (editing studio)
    float tint = 0.0f;             // -1..1 white balance towards green / magenta (editing studio)
    float detail = 0.0f;           // 0..1, the sky's structure (dust lanes, star clouds) made stronger
    float starReduction = 0.0f;    // 0..1, stars made smaller and fainter
    float vignette = 0.0f;         // 0..1, the corners darkened
    // White-balance gains for red and blue (green 1) from the stars' own colour once the photo is
    // solved; 1 = the camera's balance. [0.25, 4]. They act on the stars' own light - what stands
    // out of the sky beside it - and not on the sky's glow or a lit landscape: see sky_lit_mask
    // and postprocess_v2's stretch.
    float wbR = 1.0f;
    float wbB = 1.0f;
    // Where the sky is, from its own stars: triples x, y, bg - a sky-verified star's position in the
    // coordinates of the picture being developed (pixel centres at integers) and the luma of its
    // surroundings, (r + 2g + b) / 4 in the picture's linear units (stars.json's "bg"). finalizeStack
    // fills it from the stack's stars; the options' "skyStars" carry it for the studio. With fewer
    // than 10 points the gains are not applied at all.
    std::vector<float> skyPts;
    // How many pixels of the full-size picture one pixel of this one stands for (the studio's
    // preview and thumbnail; 1 otherwise): what is measured in pixels around the sky points
    // shrinks with it, so a preview decides as its export will.
    int skyReduce = 1;
};

// The v2 pipeline behind postprocess() for version >= 2. Same contract.
void postprocess_v2(ImageF& rgb, const PostParams& p, const ProgressFn& progress);

// Where the stars' white balance may not act: how much of each pixel is bright and starless - a lit
// landscape, which the camera's own balance was right for - judged against the sky's local level
// from its own stars (skyPts, see PostParams). 0 sky .. 1 lit, at half resolution
// (max(1, w/2) x max(1, h/2)), from the linear picture before the look changes it. False (and
// litHalf empty) with fewer than 10 sky points in the picture. reduce: PostParams::skyReduce.
bool sky_lit_mask(const ImageF& rgb, const std::vector<float>& skyPts, std::vector<float>* litHalf,
                  int reduce = 1);

// The gains on a linear picture, pixel by pixel: gain_c = 1 + wSky * wHi * (g_c - 1) with
// g = (wbR, 1, wbB), wSky = 1 - sky_lit_mask and wHi taking clipped highlights out (they stay
// white, their unclipped skirts get the colour). The look does not call this: it applies the
// same weights at its stretch, to what stands out of the sky beside it (postprocess_v2). Kept
// for the self-test, which checks the weights through it. Nothing happens when both gains are 1
// or the mask cannot be made; litHalf, when given, receives the mask (empty when nothing happened).
void apply_sky_white_balance(ImageF& rgb, float wbR, float wbB, const std::vector<float>& skyPts,
                             std::vector<float>* litHalf = nullptr);

// The sky points for a picture box-averaged down by a whole factor (the studio's preview and
// thumbnail): pixel x of the reduced picture is centred on x * factor + (factor - 1) / 2.
std::vector<float> scale_sky_points(const std::vector<float>& skyPts, int factor);

// Input: linear sRGB, roughly [0,1]. Output (in place): display-referred, sRGB-gamma-encoded [0,1].
// Stages (each skippable per params): 2nd-order polynomial gradient removal (star-masked samples),
// background gray-world WB trim (gains clamped 0.9..1.1), per-channel black point at 0.25th
// percentile minus 0.5*sigma, asinh stretch with k auto-searched so the sky background lands at the
// strength target, saturation boost in YCbCr (highlight-protected), chroma-only box denoise at 1/4
// res, optional star-masked unsharp, final sRGB gamma encode.
void postprocess(ImageF& rgb, const PostParams& p, const ProgressFn& progress);

}  // namespace starcam

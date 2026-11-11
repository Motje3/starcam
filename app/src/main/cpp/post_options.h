// PostParams from the flat options JSON the app sends (finalizeStack and the editing studio).
#pragma once

#include "json_mini.h"
#include "post.h"

namespace starcam {

// A white-balance gain read from JSON: clamped to [0.25, 4]; anything not a finite number gives
// `fallback` (finalizeStack reads the stars' answer with it too).
inline float wb_gain_from(double v, float fallback) {
    return std::isfinite(v) ? clampf((float)v, 0.25f, 4.0f) : fallback;
}

// Keys missing from the JSON keep the engine's defaults; "version" defaults to 1 (classic), so
// options written before newer generations existed keep their look.
inline PostParams read_post_params(const jsonmini::Obj& opt) {
    PostParams pp;
    pp.gradientRemoval = opt.boolean("gradientRemoval", true);
    pp.stretchStrength = clampi(opt.numi("stretchStrength", 1), 0, 2);
    pp.saturation = (float)opt.num("saturation", 1.25);
    pp.chromaDenoise = opt.boolean("chromaDenoise", true);
    pp.starSharpen = opt.boolean("starSharpen", false);
    pp.style = clampi(opt.numi("style", 0), 0, 1);
    pp.version = clampi(opt.numi("version", 1), 1, 2);
    static const float kSkyLevels[3] = {0.13f, 0.16f, 0.20f};  // by stretchStrength
    pp.skyLevel = (float)opt.num("skyLevel", kSkyLevels[pp.stretchStrength]);
    pp.groundLevel = (float)opt.num("groundLevel", pp.groundLevel);
    pp.gradientStrength = (float)opt.num("gradientStrength", pp.gradientStrength);
    pp.noiseReduction = (float)opt.num("noiseReduction", pp.noiseReduction);
    pp.starColor = (float)opt.num("starColor", pp.starColor);
    pp.contrastGap = (float)opt.num("contrastGap", pp.contrastGap);
    pp.warmth = (float)opt.num("warmth", 0.0);
    pp.tint = (float)opt.num("tint", 0.0);
    pp.detail = (float)opt.num("detail", pp.detail);
    pp.starReduction = (float)opt.num("starReduction", pp.starReduction);
    pp.vignette = (float)opt.num("vignette", pp.vignette);
    pp.downEdge = clampi(opt.numi("downEdge", -1), -1, 3);
    // White-balance gains (v2 only): anything not a finite number keeps the camera's balance.
    pp.wbR = wb_gain_from(opt.num("wbR", 1.0), 1.0f);
    pp.wbB = wb_gain_from(opt.num("wbB", 1.0), 1.0f);
    // The sky's own stars, x, y, bg, x, y, bg, ... in the full-size picture as stored (the upright
    // picture stars.json describes); a triple with anything not finite in it is left out.
    const std::vector<double> sky = opt.arr("skyStars");
    for (size_t i = 0; i + 2 < sky.size(); i += 3) {
        if (!std::isfinite(sky[i]) || !std::isfinite(sky[i + 1]) || !std::isfinite(sky[i + 2])) continue;
        pp.skyPts.push_back((float)sky[i]);
        pp.skyPts.push_back((float)sky[i + 1]);
        pp.skyPts.push_back((float)sky[i + 2]);
    }
    return pp;
}

}  // namespace starcam

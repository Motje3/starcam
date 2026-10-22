// PostParams from the flat options JSON the app sends (finalizeStack and the editing studio).
#pragma once

#include "json_mini.h"
#include "post.h"

namespace starcam {

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
    pp.downEdge = clampi(opt.numi("downEdge", -1), -1, 3);
    return pp;
}

}  // namespace starcam

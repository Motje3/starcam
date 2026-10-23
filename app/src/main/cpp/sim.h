// sim.h — a synthetic night sky for testing the capture loop where there is none (debug builds).
#pragma once

#include <cstdint>

namespace starcam {

struct SimParams {
    int cfa = 0;             // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR (unused: the sky is neutral)
    float black = 64.0f;
    float white = 1023.0f;
    int drift = 0;           // the sky turns and slides a little with every step
    uint32_t seed = 1;       // noise, one per capture
    int stars = 700;
    float sky = 0.04f;       // background, share of the range above black
    float cloud = 0.0f;      // 0 clear .. 1 overcast: starlight lost, sky lifted by the clouds
    float blurPx = 0.0f;     // extra PSF sigma in RAW px (focus error)
    bool dark = false;       // lens covered: no light, only dark current and hot pixels
};

// Renders one RAW16 frame into [out] (strideElems uint16 per row). False on bad arguments.
bool simulate_sky(uint16_t* out, int w, int h, int strideElems, const SimParams& p);

}  // namespace starcam

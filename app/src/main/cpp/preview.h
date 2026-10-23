// The live-stack preview: what the capture screen shows while frames come in, and what the replay
// video is made of.
#pragma once

#include <cstdint>

#include "common.h"

namespace starcam {

// Renders [sum] (the running sum of the aligned frames, linear RGB; divided by [cnt] per pixel
// when given, as is when null) into [argbOut] (outW x outH, 0xAARRGGBB): area-averaged down,
// turned by [rotation] (clockwise, like the final photo) and fitted with black bars. The sky
// background is made neutral and set to a fixed display brightness a few noise sigmas above
// black, with an asinh stretch on the luminance that keeps colours. False when there is nothing
// to show yet.
bool render_preview(const ImageF& sum, const ImageF* cnt, int rotation, uint32_t* argbOut, int outW,
                    int outH);

}  // namespace starcam

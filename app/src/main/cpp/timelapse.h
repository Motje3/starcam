// timelapse.h — one kept RAW frame (cache/frame_NNNN.sraw) developed for the timelapse video: the
// master dark's hot pixels and glow taken off, half-res colour from 2x2 superpixels, the live
// preview's look (neutral sky at a fixed brightness, asinh stretch), turned upright like the photo.
#pragma once

#include <cstdint>
#include <string>

namespace starcam {

// Develops [srawPath] into [argbOut] (outW x outH, 0xAARRGGBB). [darkPath] (may be empty): the
// session's master dark, read once and kept for the frames that follow. False when the frame
// cannot be read.
bool develop_frame(const std::string& srawPath, const std::string& darkPath, int rotation,
                   uint32_t* argbOut, int outW, int outH);

// Lets the kept master dark go (the video is done).
void develop_frame_release();

}  // namespace starcam

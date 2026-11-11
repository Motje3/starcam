// Hot pixels found from the light frames themselves, for the sessions that shot no darks (every one
// in the field so far: the dark list alone left them all unrepaired).
#pragma once

#include "common.h"

namespace starcam {

struct HotLightsReport {
    int frames = 0;      // light frames looked at
    int candidates = 0;  // sites well above their same-colour neighbours in the frames' mean
    int persistent = 0;  // of those, above them in (nearly) every frame on its own
    int shared = 0;      // of the persistent ones, those whose neighbours share their light (the lens's)
    int hot = 0;         // persistent and not shared, after the cap: the list
    float threshold[4] = {0, 0, 0, 0};  // per CFA position (raster order), DN over the neighbours
};

// A pixel is hot when, in the mean over the frames (up to 32, spread over the session) of its value
// over its frame's own black, it is more than max(5 sigma, 1 DN) above the median of its 8 same-colour
// neighbours 2 px away (sigma: the robust spread of that excess per CFA position) and above all of
// them but one, and then both
//   - persistent: above them in essentially every frame (the 25th percentile of its per-frame excess
//     at least half the median one) - a star that moves with the sky is on it for some frames only -
//     and steadily (the median excess at least the neighbours' own frame-to-frame change, which in
//     a lit scene sliding under the pixels is large);
//   - not shared: its neighbours are not lifted by its light (median of the 8, over the median of the
//     16 same-colour sites 4 px out, below 0.15 of the site's own lift; and likewise the median of
//     the 8 adjacent pixels, each over its own colour 4 px further out) - everything the lens draws,
//     a star that stands still or a lamp, spreads over several pixels; a defect of the sensor does not.
// Uses the frames that are not rejected (their cache files, which must match si's size). Capped at
// 0.1 % of the pixels, the strongest (median per-frame excess) first, like find_hot_pixels. Returned
// sorted ascending (indices y * width + x). Sites within 5 px of the edge are not looked at. Works a
// band of rows at a time (266 rows of floats: a few MB at any sensor size). Empty when there are
// fewer than 2 usable frames or memory runs out; never throws.
std::vector<uint32_t> find_hot_pixels_lights(const std::vector<FrameRecord>& frames,
                                             const SensorInfo& si, HotLightsReport* report,
                                             const std::atomic<bool>* abortFlag = nullptr);

// The union of two index lists, sorted ascending, each index once.
std::vector<uint32_t> merge_hot_lists(const std::vector<uint32_t>& a, const std::vector<uint32_t>& b);

}  // namespace starcam

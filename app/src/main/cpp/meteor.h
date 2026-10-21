// Transient streak detection & classification (beta). See PLAN.md §6.6.
#pragma once

#include <deque>

#include "common.h"

namespace starcam {

struct Streak {
    int frameIndex = -1;
    float x0 = 0, y0 = 0, x1 = 0, y1 = 0;  // luma coords, endpoints of fitted line segment
    float length = 0;
    float brightness = 0;                  // peak diff amplitude above background
    int cls = 0;                           // 0 = meteor candidate, 1 = satellite, 2 = plane
};

class MeteorDetector {
public:
    // Feed half-res luma frames in capture order. Detection = current frame minus the pixelwise
    // median of the last 5 frames; components above bg+6*sigma with length>=25 px, aspect>=4 and
    // line-solidity>=0.7 become streaks (initially cls=0). Returns streaks found in THIS frame.
    std::vector<Streak> addFrame(const ImageF& luma, int frameIndex);

    // Cross-frame classification: colinear presence in >=3 consecutive frames -> satellite (1);
    // alternating blink pattern along a track -> plane (2); otherwise stays meteor candidate (0).
    // Returns all streaks with final classes.
    std::vector<Streak> finalizeClassification();

private:
    std::deque<ImageF> recent_;   // last <=5 luma frames
    std::vector<Streak> all_;
};

}  // namespace starcam

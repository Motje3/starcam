// Star-trail (lighten / per-pixel max) compositing.
#pragma once

#include "common.h"

namespace starcam {

class TrailsAccumulator {
public:
    // First add() defines the geometry; later frames must match or are ignored (logged).
    void add(const ImageF& rgb);
    const ImageF& result() const { return max_; }
    bool empty() const { return max_.empty(); }
    void reset() { max_ = ImageF(); }

private:
    ImageF max_;
};

}  // namespace starcam

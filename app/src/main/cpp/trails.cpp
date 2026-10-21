// Star-trail (lighten / per-pixel max) compositing — implementation.
#include "trails.h"

namespace starcam {

// File-local worker so the public add() can be a no-throw wrapper (the header declares
// only add/result/empty/reset, so this must not be a member function).
static void addImpl(ImageF& max_, const ImageF& rgb) {
    // Ignore degenerate frames outright: an empty first frame must not define the geometry.
    if (rgb.empty() || rgb.w <= 0 || rgb.h <= 0 || rgb.ch <= 0) {
        SLOGE("TrailsAccumulator::add: empty/invalid frame ignored (w=%d h=%d ch=%d)",
              rgb.w, rgb.h, rgb.ch);
        return;
    }
    // Defensive: dimensions must describe the buffer exactly.
    if (rgb.d.size() != (size_t)rgb.w * (size_t)rgb.h * (size_t)rgb.ch) {
        SLOGE("TrailsAccumulator::add: buffer size %zu != w*h*ch (%d*%d*%d), frame ignored",
              rgb.d.size(), rgb.w, rgb.h, rgb.ch);
        return;
    }

    if (max_.empty()) {
        // First frame defines the geometry; copy it wholesale.
        max_ = rgb;
        return;
    }

    if (rgb.w != max_.w || rgb.h != max_.h || rgb.ch != max_.ch) {
        SLOGE("TrailsAccumulator::add: geometry mismatch (got %dx%dx%d, expected %dx%dx%d), frame ignored",
              rgb.w, rgb.h, rgb.ch, max_.w, max_.h, max_.ch);
        return;
    }

    // Per-element max (lighten blend). Parallelize over rows; the lambda cannot throw.
    const int h = max_.h;
    const size_t rowElems = (size_t)max_.w * (size_t)max_.ch;
    float* dst = max_.d.data();
    const float* src = rgb.d.data();
    parallel_for(0, h, [dst, src, rowElems](int y) {
        float* drow = dst + (size_t)y * rowElems;
        const float* srow = src + (size_t)y * rowElems;
        for (size_t i = 0; i < rowElems; ++i) {
            if (srow[i] > drow[i]) drow[i] = srow[i];
        }
    });
}

void TrailsAccumulator::add(const ImageF& rgb) {
    try {
        addImpl(max_, rgb);
    } catch (...) {
        // No exception may escape a public function (out-of-memory on the first-frame
        // copy is the only realistic path here).
        SLOGE("TrailsAccumulator::add: exception caught, frame dropped");
    }
}

}  // namespace starcam

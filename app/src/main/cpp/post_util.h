// Helpers shared by the post-processing generations (post.cpp = classic, post2.cpp = v2).
#pragma once

#include <cstdint>
#include <vector>

#include "common.h"

namespace starcam {
namespace postutil {

constexpr float kTiny = 1e-6f;

// BT.601 luma — used consistently for masks, stretch scaling and YCbCr stages.
inline float lumaOf(const float* q) {
    return 0.299f * q[0] + 0.587f * q[1] + 0.114f * q[2];
}

inline float smoothstepf(float e0, float e1, float x) {
    float t = clampf((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Binary dilation by Chebyshev radius r (separable max filter). Returns a new mask.
inline std::vector<uint8_t> dilatedMask(const std::vector<uint8_t>& src, int w, int h, int r) {
    std::vector<uint8_t> tmp((size_t)w * h, 0);
    std::vector<uint8_t> out((size_t)w * h, 0);
    if (w <= 0 || h <= 0 || r <= 0) { out = src; return out; }
    parallel_for(0, h, [&](int y) {
        const uint8_t* srow = &src[(size_t)y * w];
        uint8_t* trow = &tmp[(size_t)y * w];
        for (int x = 0; x < w; ++x) {
            int x0 = std::max(0, x - r), x1 = std::min(w - 1, x + r);
            uint8_t v = 0;
            for (int xx = x0; xx <= x1; ++xx) {
                if (srow[xx]) { v = 1; break; }
            }
            trow[x] = v;
        }
    });
    parallel_for(0, h, [&](int y) {
        int y0 = std::max(0, y - r), y1 = std::min(h - 1, y + r);
        uint8_t* orow = &out[(size_t)y * w];
        for (int x = 0; x < w; ++x) {
            uint8_t v = 0;
            for (int yy = y0; yy <= y1; ++yy) {
                if (tmp[(size_t)yy * w + x]) { v = 1; break; }
            }
            orow[x] = v;
        }
    });
    return out;
}

// Solve the 6x6 normal equations A*c = b for 3 right-hand sides (one per channel)
// by Gaussian elimination with partial pivoting. A and B are destroyed.
inline bool solve6x6(double A[6][6], double B[6][3], double out[3][6]) {
    for (int col = 0; col < 6; ++col) {
        int piv = col;
        double best = std::fabs(A[col][col]);
        for (int r = col + 1; r < 6; ++r) {
            double v = std::fabs(A[r][col]);
            if (v > best) { best = v; piv = r; }
        }
        if (best < 1e-12) return false;
        if (piv != col) {
            for (int k = 0; k < 6; ++k) std::swap(A[col][k], A[piv][k]);
            for (int k = 0; k < 3; ++k) std::swap(B[col][k], B[piv][k]);
        }
        double inv = 1.0 / A[col][col];
        for (int r = col + 1; r < 6; ++r) {
            double f = A[r][col] * inv;
            if (f == 0.0) continue;
            for (int k = col; k < 6; ++k) A[r][k] -= f * A[col][k];
            for (int k = 0; k < 3; ++k) B[r][k] -= f * B[col][k];
        }
    }
    for (int c = 0; c < 3; ++c) {
        for (int r = 5; r >= 0; --r) {
            double s = B[r][c];
            for (int k = r + 1; k < 6; ++k) s -= A[r][k] * out[c][k];
            out[c][r] = s / A[r][r];
        }
    }
    return true;
}

}  // namespace postutil
}  // namespace starcam

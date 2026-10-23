// preview.cpp - the live-stack preview (preview.h).
//
// The first preview stretched each channel on its own with a fixed asinh from the frame's 10th
// percentile: on a nightscape that is the ground, so the sky came out grey and tinted, and it
// sampled the stack nearest-neighbour, which showed all of its noise. This one is the finished
// photo's sky stretch (post2.cpp) in miniature, so the live stack already looks like the photo
// it is becoming, and the replay video dissolves into the photo without a jump.

#include "preview.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace starcam {
namespace {

constexpr float kSkyTarget = 0.16f;  // display level of the sky background, as the photo's
constexpr float kSaturation = 1.4f;  // the live stack has no colour matrix: livelier colours

// asinh strength k that puts a level [gap] above black at display value [t] (1.0 stays 1.0). A
// sky already brighter than that (twilight, the Moon) gets the gentlest curve, nearly linear.
float preview_k(float gap, float t) {
    gap = std::max(gap, 1e-6f);
    auto at = [gap](float k) { return std::asinh(k * gap) / std::asinh(k); };
    float lo = 0.05f, hi = 50000.0f;
    if (at(lo) >= t) return lo;
    if (at(hi) <= t) return hi;
    for (int i = 0; i < 40; ++i) {
        const float mid = std::sqrt(lo * hi);
        if (at(mid) >= t) hi = mid; else lo = mid;
    }
    return std::sqrt(lo * hi);
}

}  // namespace

bool render_preview(const ImageF& sum, const ImageF* cnt, int rotation, uint32_t* argbOut, int outW,
                    int outH) {
    try {
        const int sw = sum.w, sh = sum.h;
        if (!argbOut || outW <= 0 || outH <= 0 || sw <= 0 || sh <= 0 || sum.ch != 3) return false;
        if (sum.d.size() < (size_t)sw * sh * 3) return false;
        if (cnt && (cnt->w != sw || cnt->h != sh || cnt->d.size() < (size_t)sw * sh)) return false;
        const int rot = normalize_rotation(rotation);
        int rw, rh;
        rotated_size(rot, sw, sh, &rw, &rh);
        const double scale = std::min((double)outW / rw, (double)outH / rh);
        const int drawW = std::clamp((int)std::lround(rw * scale), 1, outW);
        const int drawH = std::clamp((int)std::lround(rh * scale), 1, outH);
        const int ox = (outW - drawW) / 2, oy = (outH - drawH) / 2;

        // 1) The stack's mean, averaged over each screen pixel's footprint (NaN: not covered yet).
        const float nan = std::numeric_limits<float>::quiet_NaN();
        std::vector<float> lin((size_t)drawW * drawH * 3, nan);
        parallel_for(0, drawH, [&](int yy) {
            const int ry0 = (int)((int64_t)yy * rh / drawH);
            const int ry1 = std::min(rh, std::max(ry0 + 1, (int)((int64_t)(yy + 1) * rh / drawH)));
            for (int xx = 0; xx < drawW; ++xx) {
                const int rx0 = (int)((int64_t)xx * rw / drawW);
                const int rx1 = std::min(rw, std::max(rx0 + 1, (int)((int64_t)(xx + 1) * rw / drawW)));
                double a[3] = {0, 0, 0};
                int n = 0;
                for (int ry = ry0; ry < ry1; ++ry) {
                    for (int rx = rx0; rx < rx1; ++rx) {
                        int sx, sy;
                        rotated_source(rot, sw, sh, rx, ry, &sx, &sy);
                        float c = 1.0f;
                        if (cnt) {
                            c = cnt->d[(size_t)sy * sw + sx];
                            if (!(c > 0.0f)) continue;
                        }
                        const float* p = sum.px(sx, sy);
                        for (int k = 0; k < 3; ++k) a[k] += p[k] / c;
                        ++n;
                    }
                }
                if (n == 0) continue;
                float* o = &lin[((size_t)yy * drawW + xx) * 3];
                for (int k = 0; k < 3; ++k) o[k] = (float)(a[k] / n);
            }
        });

        // 2) Sky background per channel (medians) and noise (neighbour differences, which the
        //    landscape and the Milky Way hardly touch).
        std::vector<float> ch[3], diff;
        const size_t total = (size_t)drawW * drawH;
        const size_t step = std::max<size_t>(1, total / 40000);
        for (size_t i = 0; i + 1 < total; i += step) {
            const float* q = &lin[i * 3];
            if (std::isnan(q[0])) continue;
            for (int k = 0; k < 3; ++k) ch[k].push_back(q[k]);
            if ((i + 1) % (size_t)drawW != 0 && !std::isnan(q[3]))
                diff.push_back(std::fabs((q[0] + q[1] + q[2]) - (q[3] + q[4] + q[5])) / 3.0f);
        }
        if (ch[0].size() < 16) return false;
        float bg[3];
        for (int k = 0; k < 3; ++k) bg[k] = median_of(ch[k]);
        const float bgL = (bg[0] + bg[1] + bg[2]) / 3.0f;
        const float sigma = diff.size() > 16 ? 1.4826f * median_of(diff) / std::sqrt(2.0f) : 0.0f;

        // 3) Black point a few sigmas (or a quarter of the background) under the sky, which the
        //    asinh then puts at the photo's sky level.
        float gap = std::max(2.5f * sigma, 0.25f * bgL);
        if (!(gap > 1e-6f)) gap = 1e-4f;
        const float black = bgL - gap;
        const float k = preview_k(gap, kSkyTarget);
        const float inv = 1.0f / std::asinh(k);

        std::fill(argbOut, argbOut + (size_t)outW * outH, 0xFF000000u);
        parallel_for(0, drawH, [&](int yy) {
            uint32_t* dst = argbOut + (size_t)(oy + yy) * outW + ox;
            for (int xx = 0; xx < drawW; ++xx) {
                const float* q = &lin[((size_t)yy * drawW + xx) * 3];
                if (std::isnan(q[0])) continue;
                float v[3];
                // Neutral background: each channel's sky level moved onto the common one.
                for (int c = 0; c < 3; ++c) v[c] = std::max(0.0f, q[c] - bg[c] + bgL - black);
                const float L = (v[0] + v[1] + v[2]) / 3.0f;
                const float s = L > 1e-7f ? std::asinh(k * L) * inv / L : k * inv;
                const float Lo = L * s;
                float o[3];
                for (int c = 0; c < 3; ++c) o[c] = std::max(0.0f, Lo + kSaturation * (v[c] * s - Lo));
                const float mx = std::max(o[0], std::max(o[1], o[2]));
                const float fit = mx > 1.0f ? 1.0f / mx : 1.0f;  // over-range: darker, same hue
                uint32_t px = 0xFF000000u;
                for (int c = 0; c < 3; ++c)
                    px |= (uint32_t)(uint8_t)(clampf(o[c] * fit, 0.0f, 1.0f) * 255.0f + 0.5f) << (16 - 8 * c);
                dst[xx] = px;
            }
        });
        return true;
    } catch (...) {
        return false;
    }
}

}  // namespace starcam

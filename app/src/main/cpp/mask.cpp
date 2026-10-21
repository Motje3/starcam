// Sky/ground separation and feathered composite (beta). See PLAN.md §6.5 and mask.h.
//
// Pipeline:
//   1. Luma of skyStack at 1/8 resolution (box downsample).
//   2. Otsu threshold on a 256-bin histogram over [0, p99.5] of the small luma.
//   3. Decide which side of the threshold is "sky" by majority vote on the TOP row
//      (night sky with stars/airglow is usually the BRIGHTER side, but not always).
//   4. Iterative 4-connected flood fill from all top-row sky-side pixels, constrained
//      to the sky side ("sky must touch the top edge").
//   5. Morphological close, radius 5 (separable square kernel: dilate then erode).
//   6. Degenerate check: sky area < 10% or > 95% -> output = skyStack unchanged.
//   7. Nearest upsample to full res, then feather: two box-blur passes of radius featherPx.
//   8. Exposure normalization: median luma ratio sky/ground inside the 0.2 < mask < 0.8
//      band, ground scaled by it (clamped to [0.25, 4]).
//   9. Composite per pixel/channel: out = mask*sky + (1-mask)*scale*ground.
//
// No exceptions escape composite_foreground; failures fall back to the sky stack.

#include "mask.h"

#include <utility>  // std::move

namespace starcam {

namespace {

// Luma of one pixel. 3+ channels: (R + 2G + B)/4 (matches the engine's luma convention);
// otherwise the channel mean.
inline float luma_px(const float* p, int ch) {
    if (ch >= 3) return (p[0] + 2.0f * p[1] + p[2]) * 0.25f;
    float s = 0.0f;
    for (int c = 0; c < ch; ++c) s += p[c];
    return ch > 0 ? s / (float)ch : 0.0f;
}

// One separable min/max pass over a binary (0/1) uint8 mask. Window is [i-r, i+r], clamped.
void minmax_pass(const std::vector<uint8_t>& src, std::vector<uint8_t>& dst,
                 int w, int h, int r, bool horizontal, bool isMax) {
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint8_t v = isMax ? (uint8_t)0 : (uint8_t)1;
            if (horizontal) {
                int x0 = clampi(x - r, 0, w - 1);
                int x1 = clampi(x + r, 0, w - 1);
                const uint8_t* row = &src[(size_t)y * w];
                for (int i = x0; i <= x1; ++i) {
                    uint8_t s = row[i];
                    v = isMax ? (s > v ? s : v) : (s < v ? s : v);
                }
            } else {
                int y0 = clampi(y - r, 0, h - 1);
                int y1 = clampi(y + r, 0, h - 1);
                for (int i = y0; i <= y1; ++i) {
                    uint8_t s = src[(size_t)i * w + x];
                    v = isMax ? (s > v ? s : v) : (s < v ? s : v);
                }
            }
            dst[(size_t)y * w + x] = v;
        }
    }
}

// One full box-blur pass (horizontal then vertical), clamped windows normalized by the
// actual sample count (no edge darkening). Result ends up back in `img`; `tmp` is scratch,
// both must be sized w*h. Lambdas are pure arithmetic — they cannot throw.
void box_blur_1ch(std::vector<float>& img, std::vector<float>& tmp, int w, int h, int r) {
    if (r <= 0 || w <= 0 || h <= 0) return;
    // Horizontal: img -> tmp.
    parallel_for(0, h, [&img, &tmp, w, r](int y) {
        const float* s = &img[(size_t)y * w];
        float* o = &tmp[(size_t)y * w];
        double sum = 0.0;
        int hi = std::min(r, w - 1);
        for (int x = 0; x <= hi; ++x) sum += s[x];
        int cnt = hi + 1;
        for (int x = 0; x < w; ++x) {
            o[x] = (float)(sum / cnt);
            int add = x + r + 1;
            int rem = x - r;
            if (add < w) { sum += s[add]; ++cnt; }
            if (rem >= 0) { sum -= s[rem]; --cnt; }
        }
    });
    // Vertical: tmp -> img.
    parallel_for(0, w, [&img, &tmp, w, h, r](int x) {
        double sum = 0.0;
        int hi = std::min(r, h - 1);
        for (int y = 0; y <= hi; ++y) sum += tmp[(size_t)y * w + x];
        int cnt = hi + 1;
        for (int y = 0; y < h; ++y) {
            img[(size_t)y * w + x] = (float)(sum / cnt);
            int add = y + r + 1;
            int rem = y - r;
            if (add < h) { sum += tmp[(size_t)add * w + x]; ++cnt; }
            if (rem >= 0) { sum -= tmp[(size_t)rem * w + x]; --cnt; }
        }
    });
}

void fill_ones_mask(ImageF* m, int w, int h) {
    if (!m) return;
    m->init(w, h, 1);
    std::fill(m->d.begin(), m->d.end(), 1.0f);
}

// Fallback: composite = sky unchanged, mask = all-sky.
void fallback_sky_only(const ImageF& skyStack, ImageF* outComposite, ImageF* outMaskOptional) {
    if (outComposite && outComposite != &skyStack) *outComposite = skyStack;
    fill_ones_mask(outMaskOptional, skyStack.w, skyStack.h);
}

}  // namespace

void composite_foreground(const ImageF& skyStack, const ImageF& groundStack, float featherPx,
                          ImageF* outComposite, ImageF* outMaskOptional) {
    if (!outComposite && !outMaskOptional) return;
    try {
        const int w = skyStack.w, h = skyStack.h, chn = skyStack.ch;

        // ---- Input validation -------------------------------------------------------------
        if (skyStack.empty() || w <= 0 || h <= 0 || chn < 1 ||
            skyStack.d.size() < (size_t)w * h * chn) {
            SLOGE("mask: empty/invalid sky stack (%dx%dx%d) - nothing to composite", w, h, chn);
            if (outComposite && outComposite != &skyStack) *outComposite = skyStack;
            if (outMaskOptional) *outMaskOptional = ImageF{};
            return;
        }
        const bool groundValid =
            !groundStack.empty() && groundStack.w == w && groundStack.h == h &&
            groundStack.ch == chn && groundStack.d.size() >= (size_t)w * h * chn;
        if (!groundValid) {
            SLOGE("mask: ground stack invalid/mismatched (%dx%dx%d vs %dx%dx%d) - using sky only",
                  groundStack.w, groundStack.h, groundStack.ch, w, h, chn);
            fallback_sky_only(skyStack, outComposite, outMaskOptional);
            return;
        }

        // ---- 1/8-res luma (box downsample) ------------------------------------------------
        const int sw = (w + 7) / 8;
        const int sh = (h + 7) / 8;
        if ((size_t)sw * (size_t)sh > ((size_t)1 << 30)) {  // absurd size; keeps int indices safe
            SLOGE("mask: image too large for mask pass (%dx%d) - using sky only", w, h);
            fallback_sky_only(skyStack, outComposite, outMaskOptional);
            return;
        }
        std::vector<float> lumaS((size_t)sw * sh, 0.0f);
        parallel_for(0, sh, [&skyStack, &lumaS, w, h, sw, chn](int sy) {
            const int y0 = sy * 8, y1 = std::min(y0 + 8, h);
            for (int sx = 0; sx < sw; ++sx) {
                const int x0 = sx * 8, x1 = std::min(x0 + 8, w);
                float acc = 0.0f;
                int n = 0;
                for (int y = y0; y < y1; ++y) {
                    const float* p = skyStack.px(x0, y);
                    for (int x = x0; x < x1; ++x, p += chn) {
                        acc += luma_px(p, chn);
                        ++n;
                    }
                }
                lumaS[(size_t)sy * sw + sx] = n > 0 ? acc / (float)n : 0.0f;
            }
        });

        // ---- Otsu threshold: 256 bins over [0, p99.5] -------------------------------------
        float p995;
        {
            std::vector<float> tmpv = lumaS;  // percentile_of reorders in place
            p995 = percentile_of(tmpv, 0.995f);
        }
        if (!(p995 > 1e-12f)) {
            SLOGE("mask: flat/black luma (p99.5=%g) - using sky only", (double)p995);
            fallback_sky_only(skyStack, outComposite, outMaskOptional);
            return;
        }
        int hist[256] = {0};
        const float binScale = 255.0f / p995;
        for (size_t i = 0; i < lumaS.size(); ++i) {
            const float v = lumaS[i];
            int b = (v > 0.0f) ? (int)(v * binScale) : 0;  // NaN/negative -> bin 0
            hist[clampi(b, 0, 255)]++;
        }
        const double total = (double)lumaS.size();
        double sumAll = 0.0;
        for (int i = 0; i < 256; ++i) sumAll += (double)i * hist[i];
        int thrBin = 127;
        {
            double wB = 0.0, sumB = 0.0, bestVar = -1.0;
            for (int i = 0; i < 256; ++i) {
                wB += hist[i];
                if (wB <= 0.0) continue;
                const double wF = total - wB;
                if (wF <= 0.0) break;
                sumB += (double)i * hist[i];
                const double mB = sumB / wB;
                const double mF = (sumAll - sumB) / wF;
                const double var = wB * wF * (mB - mF) * (mB - mF);
                if (var > bestVar) { bestVar = var; thrBin = i; }
            }
        }
        const float thr = (float)(thrBin + 1) * (p995 / 255.0f);

        // ---- Which side is sky? Majority vote on the top row ------------------------------
        int below = 0, above = 0;
        for (int sx = 0; sx < sw; ++sx) {
            if (lumaS[sx] < thr) ++below; else ++above;
        }
        const bool skyBelow = below > above;  // tie -> brighter side (typical night sky)

        std::vector<uint8_t> side((size_t)sw * sh, 0);
        for (size_t i = 0; i < side.size(); ++i) {
            side[i] = ((lumaS[i] < thr) == skyBelow) ? (uint8_t)1 : (uint8_t)0;
        }

        // ---- Flood fill (4-connected, iterative) from all top-row sky-side pixels ---------
        std::vector<uint8_t> mk((size_t)sw * sh, 0);
        {
            std::vector<int> st;
            st.reserve((size_t)sw * sh / 4 + 16);
            for (int sx = 0; sx < sw; ++sx) {
                if (side[sx] && !mk[sx]) { mk[sx] = 1; st.push_back(sx); }
            }
            while (!st.empty()) {
                const int i = st.back();
                st.pop_back();
                const int x = i % sw, y = i / sw;
                if (x > 0 && side[i - 1] && !mk[i - 1]) { mk[i - 1] = 1; st.push_back(i - 1); }
                if (x + 1 < sw && side[i + 1] && !mk[i + 1]) { mk[i + 1] = 1; st.push_back(i + 1); }
                if (y > 0 && side[i - sw] && !mk[i - sw]) { mk[i - sw] = 1; st.push_back(i - sw); }
                if (y + 1 < sh && side[i + sw] && !mk[i + sw]) { mk[i + sw] = 1; st.push_back(i + sw); }
            }
        }

        // ---- Morphological close, radius 5 (dilate then erode, square kernel) -------------
        {
            std::vector<uint8_t> ta((size_t)sw * sh), tb((size_t)sw * sh);
            minmax_pass(mk, ta, sw, sh, 5, true, true);    // dilate H
            minmax_pass(ta, tb, sw, sh, 5, false, true);   // dilate V
            minmax_pass(tb, ta, sw, sh, 5, true, false);   // erode H
            minmax_pass(ta, mk, sw, sh, 5, false, false);  // erode V
        }

        // ---- Degenerate check -------------------------------------------------------------
        size_t skyCnt = 0;
        for (size_t i = 0; i < mk.size(); ++i) skyCnt += mk[i];
        const float skyFrac = (float)((double)skyCnt / (double)mk.size());
        const bool degenerate = skyFrac < 0.10f || skyFrac > 0.95f;
        if (degenerate) {
            SLOGE("mask: degenerate sky fraction %.1f%% (thr=%.4g, skyBelow=%d) - "
                  "output = sky stack unchanged", (double)skyFrac * 100.0, (double)thr,
                  skyBelow ? 1 : 0);
        }

        // ---- Upsample nearest to full res + feather (needed for output and/or diagnostics)
        ImageF fullMask;
        const bool needMask = (outMaskOptional != nullptr) || !degenerate;
        int r = 0;
        if (featherPx > 0.5f) r = clampi((int)(featherPx + 0.5f), 1, 8192);
        if (needMask) {
            fullMask.init(w, h, 1);
            parallel_for(0, h, [&fullMask, &mk, w, sw, sh](int y) {
                int sy = y >> 3;
                if (sy >= sh) sy = sh - 1;
                const uint8_t* srow = &mk[(size_t)sy * sw];
                float* orow = &fullMask.d[(size_t)y * w];
                for (int x = 0; x < w; ++x) {
                    int sx = x >> 3;
                    if (sx >= sw) sx = sw - 1;
                    orow[x] = srow[sx] ? 1.0f : 0.0f;
                }
            });
            if (r > 0) {
                std::vector<float> scratch((size_t)w * h);
                box_blur_1ch(fullMask.d, scratch, w, h, r);  // pass 1
                box_blur_1ch(fullMask.d, scratch, w, h, r);  // pass 2
            }
        }

        if (degenerate) {
            if (outComposite && outComposite != &skyStack) *outComposite = skyStack;
            if (outMaskOptional) *outMaskOptional = std::move(fullMask);
            return;
        }

        // ---- Exposure normalization on the feather band (0.2 < mask < 0.8) ----------------
        float gscale = 1.0f;
        {
            std::vector<float> ratios;
            for (int y = 0; y < h; y += 2) {           // 2x2 subsample: plenty of band samples
                const float* mrow = &fullMask.d[(size_t)y * w];
                for (int x = 0; x < w; x += 2) {
                    const float m = mrow[x];
                    if (m > 0.2f && m < 0.8f) {
                        const float ls = luma_px(skyStack.px(x, y), chn);
                        const float lg = luma_px(groundStack.px(x, y), chn);
                        if (lg > 1e-6f && ls >= 0.0f) ratios.push_back(ls / lg);
                    }
                }
            }
            if (ratios.size() >= 16) {
                gscale = clampf(median_of(ratios), 0.25f, 4.0f);
            } else {
                SLOG("mask: feather band too small (%d samples), ground scale = 1",
                     (int)ratios.size());
            }
        }
        SLOG("mask: skyFrac=%.1f%% thr=%.4g skyBelow=%d feather=%dpx groundScale=%.3f",
             (double)skyFrac * 100.0, (double)thr, skyBelow ? 1 : 0, r, (double)gscale);

        // ---- Composite: out = m*sky + (1-m)*scale*ground ----------------------------------
        if (outComposite) {
            outComposite->init(w, h, chn);
            const float gs = gscale;
            parallel_for(0, h, [&skyStack, &groundStack, &fullMask, outComposite, w, chn, gs](int y) {
                const float* sp = skyStack.px(0, y);
                const float* gp = groundStack.px(0, y);
                const float* mrow = &fullMask.d[(size_t)y * w];
                float* op = outComposite->px(0, y);
                for (int x = 0; x < w; ++x) {
                    const float m = mrow[x];
                    const float im = 1.0f - m;
                    const size_t base = (size_t)x * chn;
                    for (int c = 0; c < chn; ++c) {
                        op[base + c] = m * sp[base + c] + im * gs * gp[base + c];
                    }
                }
            });
        }
        if (outMaskOptional) *outMaskOptional = std::move(fullMask);
    } catch (...) {
        SLOGE("mask: unexpected exception in composite_foreground - falling back to sky stack");
        try {
            fallback_sky_only(skyStack, outComposite, outMaskOptional);
        } catch (...) {
            SLOGE("mask: fallback copy also failed (out of memory?)");
        }
    }
}

}  // namespace starcam

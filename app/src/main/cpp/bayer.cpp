// Bayer-domain operations: superpixel extraction, Malvar-He-Cutler demosaic, dark/hot-pixel repair.
// See bayer.h for contracts and PLAN.md §6.1 / §6.4 for context.
//
// Normalization (applied everywhere a value leaves the raw domain), per 2x2 raster position `pos`:
//   v = max(0, raw - blackLevel[pos]) * wbGain[pos] / (whiteLevel - blackLevel[pos]),  clamped to [0, 4].

#include "bayer.h"

namespace starcam {

namespace {

// Precomputed per-position normalization: v = max(0, raw - black[pos]) * scale[pos], clamp [0,4].
struct NormCtx {
    float black[4];
    float scale[4];
};

NormCtx make_norm(const SensorInfo& si, const float wbGain[4]) {
    NormCtx c;
    for (int j = 0; j < 4; ++j) {
        float bl = si.blackLevel[j];
        float denom = (float)si.whiteLevel - bl;
        if (denom < 1e-6f) denom = 1e-6f;  // guard: degenerate white/black levels
        c.black[j] = bl;
        c.scale[j] = wbGain[j] / denom;
    }
    return c;
}

inline float norm_px(uint16_t raw, int pos, const NormCtx& c) {
    float v = (float)raw - c.black[pos];
    if (v < 0.0f) v = 0.0f;
    v *= c.scale[pos];
    return v > 4.0f ? 4.0f : v;
}

// Sanitize a possibly-out-of-range CFA value before indexing cfaColorAt's table.
inline CFA safe_cfa(CFA cfa) { return static_cast<CFA>(static_cast<uint32_t>(cfa) & 3u); }

}  // namespace

// ---------------------------------------------------------------------------
// superpixel_luma
// ---------------------------------------------------------------------------
void superpixel_luma(const uint16_t* bayer, int w, int h, int strideElems, const SensorInfo& si,
                     const float wbGain[4], ImageF* outLuma) {
    if (!outLuma) return;
    int ow = (w > 1) ? w / 2 : 0;
    int oh = (h > 1) ? h / 2 : 0;
    try {
        outLuma->init(ow, oh, 1);
    } catch (...) {
        SLOGE("superpixel_luma: alloc failed (%d x %d)", ow, oh);
        outLuma->w = 0; outLuma->h = 0; outLuma->ch = 1; outLuma->d.clear();
        return;
    }
    if (!bayer || ow <= 0 || oh <= 0) {
        if (!bayer) SLOGE("superpixel_luma: null input buffer");
        return;
    }
    if (strideElems < w) strideElems = w;  // never trust a stride smaller than the row

    const NormCtx nc = make_norm(si, wbGain);
    const size_t stride = (size_t)strideElems;
    float* outD = outLuma->d.data();

    parallel_for(0, oh, [&](int oy) {
        const uint16_t* r0 = bayer + (size_t)(2 * oy) * stride;
        const uint16_t* r1 = r0 + stride;
        float* orow = outD + (size_t)oy * ow;
        for (int ox = 0; ox < ow; ++ox) {
            int x2 = 2 * ox;
            float v0 = norm_px(r0[x2], 0, nc);
            float v1 = norm_px(r0[x2 + 1], 1, nc);
            float v2 = norm_px(r1[x2], 2, nc);
            float v3 = norm_px(r1[x2 + 1], 3, nc);
            orow[ox] = 0.25f * (v0 + v1 + v2 + v3);
        }
    });
}

// ---------------------------------------------------------------------------
// superpixel_rgb
// ---------------------------------------------------------------------------
void superpixel_rgb(const uint16_t* bayer, int w, int h, int strideElems, const SensorInfo& si,
                    const float wbGain[4], ImageF* outRgb) {
    if (!outRgb) return;
    int ow = (w > 1) ? w / 2 : 0;
    int oh = (h > 1) ? h / 2 : 0;
    try {
        outRgb->init(ow, oh, 3);
    } catch (...) {
        SLOGE("superpixel_rgb: alloc failed (%d x %d)", ow, oh);
        outRgb->w = 0; outRgb->h = 0; outRgb->ch = 3; outRgb->d.clear();
        return;
    }
    if (!bayer || ow <= 0 || oh <= 0) {
        if (!bayer) SLOGE("superpixel_rgb: null input buffer");
        return;
    }
    if (strideElems < w) strideElems = w;

    // Which raster position within the 2x2 tile holds R / G / G / B.
    const CFA cfa = safe_cfa(si.cfa);
    int ri = -1, bi = -1, g0 = -1, g1 = -1;
    for (int j = 0; j < 4; ++j) {
        int c = cfaColorAt(cfa, j);
        if (c == 0) ri = j;
        else if (c == 2) bi = j;
        else if (g0 < 0) g0 = j;
        else g1 = j;
    }
    if (ri < 0 || bi < 0 || g0 < 0 || g1 < 0) {  // cannot happen for cfa 0..3, but be safe
        SLOGE("superpixel_rgb: degenerate CFA table, assuming RGGB");
        ri = 0; g0 = 1; g1 = 2; bi = 3;
    }

    const NormCtx nc = make_norm(si, wbGain);
    const size_t stride = (size_t)strideElems;
    float* outD = outRgb->d.data();

    parallel_for(0, oh, [&](int oy) {
        const uint16_t* r0 = bayer + (size_t)(2 * oy) * stride;
        const uint16_t* r1 = r0 + stride;
        float* orow = outD + (size_t)oy * ow * 3;
        for (int ox = 0; ox < ow; ++ox) {
            int x2 = 2 * ox;
            float n[4];
            n[0] = norm_px(r0[x2], 0, nc);
            n[1] = norm_px(r0[x2 + 1], 1, nc);
            n[2] = norm_px(r1[x2], 2, nc);
            n[3] = norm_px(r1[x2 + 1], 3, nc);
            float* o = orow + (size_t)ox * 3;
            o[0] = n[ri];
            o[1] = 0.5f * (n[g0] + n[g1]);
            o[2] = n[bi];
        }
    });
}

// ---------------------------------------------------------------------------
// copy_region_u16
// ---------------------------------------------------------------------------
void copy_region_u16(const uint16_t* bayer, int w, int h, int x0, int y0, int rw, int rh,
                     std::vector<uint16_t>* out) {
    if (!out) return;
    if (!bayer || w <= 0 || h <= 0 || rw <= 0 || rh <= 0) {
        out->clear();
        return;
    }
    try {
        out->assign((size_t)rw * rh, 0);
    } catch (...) {
        SLOGE("copy_region_u16: alloc failed (%d x %d)", rw, rh);
        out->clear();
        return;
    }
    uint16_t* dst = out->data();

    // Split each row into [left clamp | direct memcpy | right clamp]. 64-bit math avoids overflow
    // for pathological x0.
    long long lo = -(long long)x0;             // first x with x0+x >= 0
    long long hi = (long long)w - (long long)x0;  // first x with x0+x >= w
    int mid0 = (int)(lo < 0 ? 0 : (lo > rw ? rw : lo));
    int mid1 = (int)(hi < 0 ? 0 : (hi > rw ? rw : hi));
    if (mid1 < mid0) mid1 = mid0;

    for (int y = 0; y < rh; ++y) {
        int sy = clampi(y0 + y, 0, h - 1);
        const uint16_t* srow = bayer + (size_t)sy * w;
        uint16_t* drow = dst + (size_t)y * rw;
        for (int x = 0; x < mid0; ++x) drow[x] = srow[0];
        if (mid1 > mid0) {
            std::memcpy(drow + mid0, srow + (x0 + mid0), (size_t)(mid1 - mid0) * sizeof(uint16_t));
        }
        for (int x = mid1; x < rw; ++x) drow[x] = srow[w - 1];
    }
}

// ---------------------------------------------------------------------------
// subtract_dark_region
// ---------------------------------------------------------------------------
void subtract_dark_region(std::vector<uint16_t>& region, int rw, int rh, int x0, int y0,
                          const Image16& masterDark, const float blackLevel[4]) {
    if (rw <= 0 || rh <= 0) return;
    if (region.size() < (size_t)rw * rh) {
        SLOGE("subtract_dark_region: region buffer too small (%zu < %d x %d)", region.size(), rw, rh);
        return;
    }
    if (masterDark.empty() || masterDark.w <= 0 || masterDark.h <= 0) return;

    const uint16_t* dk = masterDark.d.data();
    const int mw = masterDark.w;
    const int mh = masterDark.h;

    for (int y = 0; y < rh; ++y) {
        int fy = y0 + y;                       // FULL-frame row
        int posRow = (fy & 1) << 1;            // (& 1 is parity-correct for negatives too)
        int cy = clampi(fy, 0, mh - 1);
        const uint16_t* drow = dk + (size_t)cy * mw;
        uint16_t* rrow = region.data() + (size_t)y * rw;
        for (int x = 0; x < rw; ++x) {
            int fx = x0 + x;                   // FULL-frame column
            int pos = posRow | (fx & 1);
            int cx = clampi(fx, 0, mw - 1);
            float bl = blackLevel[pos];
            float lo = clampf(bl, 0.0f, 65535.0f);
            float v = (float)rrow[x] - ((float)drow[cx] - bl);
            v = clampf(v, lo, 65535.0f);
            rrow[x] = (uint16_t)(v + 0.5f);
        }
    }
}

// ---------------------------------------------------------------------------
// fix_hot_pixels_region
// ---------------------------------------------------------------------------
void fix_hot_pixels_region(std::vector<uint16_t>& region, int rw, int rh, int x0, int y0, int fullW,
                           int fullH, const std::vector<uint32_t>& hotIdx) {
    if (rw <= 0 || rh <= 0 || fullW <= 0 || fullH <= 0) return;
    if (region.size() < (size_t)rw * rh) {
        SLOGE("fix_hot_pixels_region: region buffer too small (%zu < %d x %d)", region.size(), rw, rh);
        return;
    }
    uint16_t* rd = region.data();
    static const int dx[4] = {-2, 2, 0, 0};
    static const int dy[4] = {0, 0, -2, 2};

    for (uint32_t idx : hotIdx) {
        int hx = (int)(idx % (uint32_t)fullW);
        int hy = (int)(idx / (uint32_t)fullW);
        if (hy >= fullH) continue;             // corrupt index, ignore
        int rx = hx - x0;
        int ry = hy - y0;
        if (rx < 0 || rx >= rw || ry < 0 || ry >= rh) continue;  // hot pixel not in this region

        uint32_t sum = 0;
        uint32_t cnt = 0;
        for (int k = 0; k < 4; ++k) {          // ±2 px keeps the CFA position identical
            int nx = rx + dx[k];
            int ny = ry + dy[k];
            if (nx < 0 || nx >= rw || ny < 0 || ny >= rh) continue;  // outside region: skip
            sum += rd[(size_t)ny * rw + nx];
            ++cnt;
        }
        if (cnt > 0) rd[(size_t)ry * rw + rx] = (uint16_t)((sum + cnt / 2) / cnt);
    }
}

// ---------------------------------------------------------------------------
// demosaic_region_malvar
// ---------------------------------------------------------------------------
// Malvar-He-Cutler 5x5 kernels (all /8, DC gain 1):
//   G at R/B site:            center 4; axial ±1: +2; axial ±2: -1.
//   R/B at G site (horizontal chroma neighbors): center 5; horiz ±1: +4; diag ±1: -1;
//                              horiz ±2: -1; vert ±2: +1/2.   (transpose for vertical neighbors)
//   R at B site / B at R site: center 6; diag ±1: +2; axial ±2: -3/2.
void demosaic_region_malvar(const uint16_t* region, int rw, int rh, const SensorInfo& si,
                            const float wbGain[4], ImageF* outRgb) {
    if (!outRgb) return;
    if (!region || rw <= 0 || rh <= 0) {
        if (!region) SLOGE("demosaic_region_malvar: null region");
        outRgb->w = 0; outRgb->h = 0; outRgb->ch = 3; outRgb->d.clear();
        return;
    }

    std::vector<float> norm;
    try {
        norm.resize((size_t)rw * rh);
        outRgb->init(rw, rh, 3);
    } catch (...) {
        SLOGE("demosaic_region_malvar: alloc failed (%d x %d)", rw, rh);
        outRgb->w = 0; outRgb->h = 0; outRgb->ch = 3; outRgb->d.clear();
        return;
    }

    // Normalize the whole mosaic once (per actual pixel position), then run the kernels on floats.
    const NormCtx nc = make_norm(si, wbGain);
    for (int y = 0; y < rh; ++y) {
        const uint16_t* srow = region + (size_t)y * rw;
        float* nrow = norm.data() + (size_t)y * rw;
        int posRow = (y & 1) << 1;             // region phase == si.cfa (even x0/y0 precondition)
        for (int x = 0; x < rw; ++x) nrow[x] = norm_px(srow[x], posRow | (x & 1), nc);
    }

    const CFA cfa = safe_cfa(si.cfa);
    int cIdx[4];
    for (int j = 0; j < 4; ++j) cIdx[j] = cfaColorAt(cfa, j);

    const float* nd = norm.data();
    float* od = outRgb->d.data();

    auto V = [&](int xx, int yy) -> float {    // clamped tap
        xx = xx < 0 ? 0 : (xx >= rw ? rw - 1 : xx);
        yy = yy < 0 ? 0 : (yy >= rh ? rh - 1 : yy);
        return nd[(size_t)yy * rw + xx];
    };

    // NOTE: deliberately serial — stack.cpp calls this from inside its own parallel_for over tiles
    // (common.h: nesting is only safe if the inner call is serial).
    for (int y = 0; y < rh; ++y) {
        for (int x = 0; x < rw; ++x) {
            float C = nd[(size_t)y * rw + x];
            float N1 = V(x, y - 1), S1 = V(x, y + 1), W1 = V(x - 1, y), E1 = V(x + 1, y);
            float N2 = V(x, y - 2), S2 = V(x, y + 2), W2 = V(x - 2, y), E2 = V(x + 2, y);
            float NW = V(x - 1, y - 1), NE = V(x + 1, y - 1);
            float SW = V(x - 1, y + 1), SE = V(x + 1, y + 1);

            float axial2 = N2 + S2 + W2 + E2;
            float diag = NW + NE + SW + SE;

            int idx = ((y & 1) << 1) | (x & 1);
            int c = cIdx[idx];
            float r, g, b;
            if (c == 1) {
                // Green site. Horizontal ±1 neighbors carry cIdx[idx ^ 1] (R or B); vertical ±1
                // carry the other chroma.
                g = C;
                float horiz = 0.125f * (5.0f * C + 4.0f * (W1 + E1) - diag - (W2 + E2)
                                        + 0.5f * (N2 + S2));
                float vert = 0.125f * (5.0f * C + 4.0f * (N1 + S1) - diag - (N2 + S2)
                                       + 0.5f * (W2 + E2));
                if (cIdx[idx ^ 1] == 0) { r = horiz; b = vert; }
                else                    { r = vert;  b = horiz; }
            } else {
                // R or B site: G from the axial kernel, opposite chroma from the diagonal kernel.
                g = 0.125f * (4.0f * C + 2.0f * (N1 + S1 + W1 + E1) - axial2);
                float opp = 0.125f * (6.0f * C + 2.0f * diag - 1.5f * axial2);
                if (c == 0) { r = C; b = opp; }
                else        { b = C; r = opp; }
            }

            float* o = od + ((size_t)y * rw + x) * 3;
            o[0] = clampf(r, 0.0f, 4.0f);
            o[1] = clampf(g, 0.0f, 4.0f);
            o[2] = clampf(b, 0.0f, 4.0f);
        }
    }
}


void apply_shading_region(ImageF& rgb, int x0, int y0, int fullW, int fullH, const ShadingMap& sm) {
    if (sm.empty() || rgb.empty() || rgb.ch != 3) return;
    if (fullW < 2 || fullH < 2 || rgb.w <= 0 || rgb.h <= 0) return;
    const int rw = rgb.w, rh = rgb.h;

    // The column interpolation is the same for every row of the region — hoisting it out keeps
    // the pixel loop down to two lerps per channel.
    std::vector<int> colIdx((size_t)rw);
    std::vector<float> colW((size_t)rw);
    for (int x = 0; x < rw; ++x) {
        const float u = clampf((float)(x0 + x) / (float)(fullW - 1), 0.0f, 1.0f);
        const float g = u * (float)(sm.cols - 1);
        int i0 = (int)g;
        if (i0 > sm.cols - 2) i0 = sm.cols - 2;
        if (i0 < 0) i0 = 0;
        colIdx[(size_t)x] = i0;
        colW[(size_t)x] = clampf(g - (float)i0, 0.0f, 1.0f);
    }

    for (int y = 0; y < rh; ++y) {
        const float v = clampf((float)(y0 + y) / (float)(fullH - 1), 0.0f, 1.0f);
        const float g = v * (float)(sm.rows - 1);
        int j0 = (int)g;
        if (j0 > sm.rows - 2) j0 = sm.rows - 2;
        if (j0 < 0) j0 = 0;
        const float wy = clampf(g - (float)j0, 0.0f, 1.0f);
        const float* row0 = &sm.gain[(size_t)j0 * (size_t)sm.cols * 3];
        const float* row1 = &sm.gain[(size_t)(j0 + 1) * (size_t)sm.cols * 3];
        float* p = rgb.px(0, y);
        for (int x = 0; x < rw; ++x, p += 3) {
            const size_t o = (size_t)colIdx[(size_t)x] * 3;
            const float wx = colW[(size_t)x];
            const float* a = row0 + o;
            const float* b = row1 + o;
            for (int c = 0; c < 3; ++c) {
                const float top = a[c] + (a[c + 3] - a[c]) * wx;
                const float bot = b[c] + (b[c + 3] - b[c]) * wx;
                p[c] *= top + (bot - top) * wy;
            }
        }
    }
}

}  // namespace starcam

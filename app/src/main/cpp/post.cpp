// post.cpp — StarCam astro post-processing pipeline (PLAN.md §6.7).
//
// Input: linear sRGB float image, roughly [0,1], channel-interleaved (ch == 3).
// Output (in place): display-referred, sRGB-gamma-encoded [0,1].
//
// Masks/statistics are computed on a half-res luma copy plus strided full-res
// samples; corrections are applied at full resolution. Each stage reports
// progress("post", pct). No exceptions escape postprocess().

#include "post.h"

#include <cmath>
#include <cstdint>
#include <vector>
#include <algorithm>

namespace starcam {
namespace {

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
std::vector<uint8_t> dilatedMask(const std::vector<uint8_t>& src, int w, int h, int r) {
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
bool solve6x6(double A[6][6], double B[6][3], double out[3][6]) {
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

}  // namespace

void postprocess(ImageF& rgb, const PostParams& p, const ProgressFn& progress) {
    try {
        auto report = [&progress](int pct) {
            if (progress) {
                try { progress("post", pct); } catch (...) {}
            }
        };

        if (rgb.empty() || rgb.ch != 3 || rgb.w <= 0 || rgb.h <= 0 ||
            rgb.d.size() < (size_t)rgb.w * rgb.h * 3) {
            SLOGE("postprocess: invalid image (w=%d h=%d ch=%d size=%zu)",
                  rgb.w, rgb.h, rgb.ch, rgb.d.size());
            return;
        }
        const int w = rgb.w, h = rgb.h;
        float* img = rgb.d.data();
        report(0);

        // ---------------- half-res luma + star mask (shared by several stages) ----------------
        const int hw = std::max(1, w / 2);
        const int hh = std::max(1, h / 2);
        std::vector<uint8_t> maskRaw((size_t)hw * hh, 0);
        {
            std::vector<float> hl((size_t)hw * hh, 0.0f);
            parallel_for(0, hh, [&](int hy) {
                int y0 = hy * 2, y1 = std::min(y0 + 1, h - 1);
                const float* r0 = &img[(size_t)y0 * w * 3];
                const float* r1 = &img[(size_t)y1 * w * 3];
                float* orow = &hl[(size_t)hy * hw];
                for (int hx = 0; hx < hw; ++hx) {
                    int x0 = hx * 2, x1 = std::min(x0 + 1, w - 1);
                    float s = lumaOf(r0 + (size_t)x0 * 3) + lumaOf(r0 + (size_t)x1 * 3) +
                              lumaOf(r1 + (size_t)x0 * 3) + lumaOf(r1 + (size_t)x1 * 3);
                    orow[hx] = 0.25f * s;
                }
            });

            // Coarse 32x32 grid background (median) + noise (MAD sigma) on half-res luma.
            const int gx = std::min(32, hw), gy = std::min(32, hh);
            std::vector<float> bg((size_t)gx * gy, 0.0f);
            std::vector<float> sg((size_t)gx * gy, 1e9f);  // huge default => nothing masked on failure
            parallel_for(0, gy, [&](int cy) {
                try {
                    int ys = (int)((int64_t)cy * hh / gy);
                    int ye = (int)((int64_t)(cy + 1) * hh / gy);
                    std::vector<float> cell, dev;
                    for (int cx = 0; cx < gx; ++cx) {
                        int xs = (int)((int64_t)cx * hw / gx);
                        int xe = (int)((int64_t)(cx + 1) * hw / gx);
                        cell.clear();
                        cell.reserve((size_t)(ye - ys) * (size_t)(xe - xs));
                        for (int yy = ys; yy < ye; ++yy) {
                            const float* rr = &hl[(size_t)yy * hw];
                            for (int xx = xs; xx < xe; ++xx) cell.push_back(rr[xx]);
                        }
                        if (cell.empty()) continue;
                        float med = median_of(cell);
                        dev.assign(cell.size(), 0.0f);
                        for (size_t i = 0; i < cell.size(); ++i) dev[i] = std::fabs(cell[i] - med);
                        float sigma = 1.4826f * median_of(dev);
                        bg[(size_t)cy * gx + cx] = med;
                        sg[(size_t)cy * gx + cx] = std::max(sigma, 1e-5f);
                    }
                } catch (...) {}
            });
            parallel_for(0, hh, [&](int hy) {
                int cy = (int)((int64_t)hy * gy / hh);
                if (cy >= gy) cy = gy - 1;
                const float* bgr = &bg[(size_t)cy * gx];
                const float* sgr = &sg[(size_t)cy * gx];
                const float* lr = &hl[(size_t)hy * hw];
                uint8_t* mr = &maskRaw[(size_t)hy * hw];
                for (int hx = 0; hx < hw; ++hx) {
                    int cx = (int)((int64_t)hx * gx / hw);
                    if (cx >= gx) cx = gx - 1;
                    mr[hx] = (lr[hx] > bgr[cx] + 3.0f * sgr[cx]) ? 1 : 0;
                }
            });
        }  // half-res luma released here
        std::vector<uint8_t> mask2 = dilatedMask(maskRaw, hw, hh, 2);

        // Even sampling stride for full-res statistics (keeps gathers <= ~1.5M samples).
        int st = 2;
        while ((int64_t)(w / st) * (int64_t)(h / st) > 1500000) st += 2;

        // ---------------- (1) gradient removal ----------------
        report(5);
        if (p.gradientRemoval) {
            struct Cell {
                double med[3];
                double lum;
                double X, Y;
            };
            std::vector<Cell> cells;
            {
                const int gx = std::min(32, w), gy = std::min(32, h);
                cells.reserve((size_t)gx * gy);
                std::vector<float> vr, vg, vb, vl;
                for (int cy = 0; cy < gy; ++cy) {
                    int ys = (int)((int64_t)cy * h / gy);
                    int ye = (int)((int64_t)(cy + 1) * h / gy);
                    for (int cx = 0; cx < gx; ++cx) {
                        int xs = (int)((int64_t)cx * w / gx);
                        int xe = (int)((int64_t)(cx + 1) * w / gx);
                        vr.clear(); vg.clear(); vb.clear(); vl.clear();
                        for (int y = ys; y < ye; y += 2) {
                            int hy = y >> 1; if (hy >= hh) hy = hh - 1;
                            const uint8_t* mrow = &mask2[(size_t)hy * hw];
                            const float* row = &img[(size_t)y * w * 3];
                            for (int x = xs; x < xe; x += 2) {
                                int hx = x >> 1; if (hx >= hw) hx = hw - 1;
                                if (mrow[hx]) continue;
                                const float* q = row + (size_t)x * 3;
                                vr.push_back(q[0]);
                                vg.push_back(q[1]);
                                vb.push_back(q[2]);
                                vl.push_back(lumaOf(q));
                            }
                        }
                        if ((int)vl.size() < 8) continue;  // cell too star-covered / tiny
                        Cell c;
                        c.med[0] = median_of(vr);
                        c.med[1] = median_of(vg);
                        c.med[2] = median_of(vb);
                        c.lum = median_of(vl);
                        double cxc = 0.5 * (xs + xe - 1), cyc = 0.5 * (ys + ye - 1);
                        c.X = (w > 1) ? (2.0 * cxc / (w - 1) - 1.0) : 0.0;
                        c.Y = (h > 1) ? (2.0 * cyc / (h - 1) - 1.0) : 0.0;
                        cells.push_back(c);
                    }
                }
            }
            bool fitted = false;
            double coef[3][6] = {};
            if ((int)cells.size() >= 12) {
                std::vector<float> lums;
                lums.reserve(cells.size());
                for (const Cell& c : cells) lums.push_back((float)c.lum);
                float cut = percentile_of(lums, 0.8f);  // exclude brightest 20% of cells
                double A[6][6] = {};
                double B[6][3] = {};
                int used = 0;
                for (const Cell& c : cells) {
                    if (c.lum > cut) continue;
                    double phi[6] = {1.0, c.X, c.Y, c.X * c.Y, c.X * c.X, c.Y * c.Y};
                    for (int i = 0; i < 6; ++i) {
                        for (int j = 0; j < 6; ++j) A[i][j] += phi[i] * phi[j];
                        B[i][0] += phi[i] * c.med[0];
                        B[i][1] += phi[i] * c.med[1];
                        B[i][2] += phi[i] * c.med[2];
                    }
                    ++used;
                }
                if (used >= 8) fitted = solve6x6(A, B, coef);
                if (!fitted)
                    SLOGE("postprocess: gradient fit skipped (cells=%d used=%d)",
                          (int)cells.size(), used);
            } else {
                SLOGE("postprocess: gradient removal skipped, too few clean cells (%d)",
                      (int)cells.size());
            }
            if (fitted) {
                // Per-channel global medians (strided sample) to re-add after subtraction.
                float gmed[3] = {0, 0, 0};
                {
                    std::vector<float> v;
                    v.reserve((size_t)(w / st + 1) * (size_t)(h / st + 1));
                    for (int c = 0; c < 3; ++c) {
                        v.clear();
                        for (int y = 0; y < h; y += st) {
                            const float* row = &img[(size_t)y * w * 3];
                            for (int x = 0; x < w; x += st) v.push_back(row[(size_t)x * 3 + c]);
                        }
                        gmed[c] = median_of(v);
                    }
                }
                std::vector<float> xn((size_t)w);
                for (int x = 0; x < w; ++x)
                    xn[x] = (w > 1) ? (float)(2.0 * x / (w - 1) - 1.0) : 0.0f;
                parallel_for(0, h, [&](int y) {
                    double Yn = (h > 1) ? (2.0 * y / (h - 1) - 1.0) : 0.0;
                    float a0[3], a1[3], a2[3];
                    for (int c = 0; c < 3; ++c) {
                        a0[c] = (float)(coef[c][0] + coef[c][2] * Yn + coef[c][5] * Yn * Yn) - gmed[c];
                        a1[c] = (float)(coef[c][1] + coef[c][3] * Yn);
                        a2[c] = (float)coef[c][4];
                    }
                    float* row = &img[(size_t)y * w * 3];
                    for (int x = 0; x < w; ++x) {
                        float X = xn[x], X2 = X * X;
                        float* q = row + (size_t)x * 3;
                        q[0] -= a0[0] + a1[0] * X + a2[0] * X2;
                        q[1] -= a0[1] + a1[1] * X + a2[1] * X2;
                        q[2] -= a0[2] + a1[2] * X + a2[2] * X2;
                    }
                });
                SLOG("postprocess: gradient removed (cells=%d)", (int)cells.size());
            }
        }

        // ---------------- (2) gray-world WB trim on background pixels ----------------
        report(20);
        {
            double sum[3] = {0, 0, 0};
            int64_t cnt = 0;
            for (int y = 0; y < h; y += st) {
                int hy = y >> 1; if (hy >= hh) hy = hh - 1;
                const uint8_t* mrow = &mask2[(size_t)hy * hw];
                const float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; x += st) {
                    int hx = x >> 1; if (hx >= hw) hx = hw - 1;
                    if (mrow[hx]) continue;
                    const float* q = row + (size_t)x * 3;
                    sum[0] += q[0];
                    sum[1] += q[1];
                    sum[2] += q[2];
                    ++cnt;
                }
            }
            if (cnt > 0) {
                double mAll = (sum[0] + sum[1] + sum[2]) / (3.0 * (double)cnt);
                float gain[3];
                for (int c = 0; c < 3; ++c) {
                    double mc = sum[c] / (double)cnt;
                    float g = (mc > 1e-9) ? (float)(mAll / mc) : 1.0f;
                    gain[c] = clampf(g, 0.9f, 1.1f);
                }
                if (gain[0] != 1.0f || gain[1] != 1.0f || gain[2] != 1.0f) {
                    parallel_for(0, h, [&](int y) {
                        float* row = &img[(size_t)y * w * 3];
                        for (int x = 0; x < w; ++x) {
                            float* q = row + (size_t)x * 3;
                            q[0] *= gain[0];
                            q[1] *= gain[1];
                            q[2] *= gain[2];
                        }
                    });
                }
                SLOG("postprocess: gray-world gains %.3f %.3f %.3f", gain[0], gain[1], gain[2]);
            }
        }

        // ---------------- (3) black point ----------------
        report(30);
        {
            float bp[3] = {0, 0, 0};
            std::vector<float> all, bgv;
            for (int c = 0; c < 3; ++c) {
                all.clear();
                bgv.clear();
                for (int y = 0; y < h; y += st) {
                    int hy = y >> 1; if (hy >= hh) hy = hh - 1;
                    const uint8_t* mrow = &mask2[(size_t)hy * hw];
                    const float* row = &img[(size_t)y * w * 3];
                    for (int x = 0; x < w; x += st) {
                        float v = row[(size_t)x * 3 + c];
                        all.push_back(v);
                        int hx = x >> 1; if (hx >= hw) hx = hw - 1;
                        if (!mrow[hx]) bgv.push_back(v);
                    }
                }
                float p0 = percentile_of(all, 0.0025f);
                float sigma = 0.0f;
                if (!bgv.empty()) {
                    float med = median_of(bgv);
                    for (float& v : bgv) v = std::fabs(v - med);
                    sigma = 1.4826f * median_of(bgv);
                }
                bp[c] = p0 - 0.5f * sigma;
            }
            parallel_for(0, h, [&](int y) {
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    float* q = row + (size_t)x * 3;
                    q[0] = std::max(0.0f, q[0] - bp[0]);
                    q[1] = std::max(0.0f, q[1] - bp[1]);
                    q[2] = std::max(0.0f, q[2] - bp[2]);
                }
            });
            SLOG("postprocess: black point %.5f %.5f %.5f", bp[0], bp[1], bp[2]);
        }

        // ---------------- (4) asinh stretch (luma-scaled, color-preserving) ----------------
        report(45);
        {
            static const float kTargets[3] = {0.06f, 0.09f, 0.13f};
            const float tgt = kTargets[clampi(p.stretchStrength, 0, 2)];
            std::vector<float> lv;
            for (int y = 0; y < h; y += st) {
                int hy = y >> 1; if (hy >= hh) hy = hh - 1;
                const uint8_t* mrow = &mask2[(size_t)hy * hw];
                const float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; x += st) {
                    int hx = x >> 1; if (hx >= hw) hx = hw - 1;
                    if (mrow[hx]) continue;
                    lv.push_back(lumaOf(row + (size_t)x * 3));
                }
            }
            float bgMed = lv.empty() ? 0.0f : median_of(lv);
            if (bgMed < kTiny) bgMed = kTiny;
            auto overshoot = [bgMed, tgt](double k) {
                return std::asinh(k * (double)bgMed) / std::asinh(k) - (double)tgt;
            };
            double lo = 10.0, hi = 900.0, k;
            if (overshoot(lo) >= 0.0) {
                k = lo;
            } else if (overshoot(hi) <= 0.0) {
                k = hi;
            } else {
                for (int it = 0; it < 25; ++it) {
                    double mid = 0.5 * (lo + hi);
                    if (overshoot(mid) >= 0.0) hi = mid; else lo = mid;
                }
                k = 0.5 * (lo + hi);
            }
            const float kf = (float)k;
            const float invAsK = (float)(1.0 / std::asinh(k));
            parallel_for(0, h, [&](int y) {
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    float* q = row + (size_t)x * 3;
                    float L = lumaOf(q);
                    float s = std::asinh(kf * L) * invAsK / std::max(L, kTiny);
                    q[0] *= s;
                    q[1] *= s;
                    q[2] *= s;
                }
            });
            SLOG("postprocess: stretch k=%.1f bgMed=%.5f target=%.2f", k, bgMed, tgt);
        }

        // ---------------- (5) saturation in YCbCr, highlight-protected ----------------
        report(60);
        {
            const float sat = clampf(p.saturation, 0.0f, 4.0f);
            parallel_for(0, h, [&](int y) {
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    float* q = row + (size_t)x * 3;
                    float R = q[0], G = q[1], B = q[2];
                    float Y = 0.299f * R + 0.587f * G + 0.114f * B;
                    float cbv = 0.564f * (B - Y);
                    float crv = 0.713f * (R - Y);
                    float hp = 1.0f - 0.7f * smoothstepf(0.8f, 1.0f, Y);  // 1 -> 0.3 over Y 0.8..1
                    float g = sat * hp;
                    cbv *= g;
                    crv *= g;
                    float Rn = Y + crv * (1.0f / 0.713f);
                    float Bn = Y + cbv * (1.0f / 0.564f);
                    float Gn = (Y - 0.299f * Rn - 0.114f * Bn) * (1.0f / 0.587f);
                    q[0] = clampf(Rn, 0.0f, 1.0f);
                    q[1] = clampf(Gn, 0.0f, 1.0f);
                    q[2] = clampf(Bn, 0.0f, 1.0f);
                }
            });
        }

        // ---------------- (5b) optional style grade ----------------
        // "Nightfall": the cool purple-blue astro look — blue-shifted channel gains, slightly
        // deepened blacks, a touch more chroma. Applied display-referred so it grades the image
        // the user actually sees; the linear TIFF master stays untouched for honest PC editing.
        if (p.style == 1) {
            constexpr float kGainR = 1.03f, kGainG = 0.86f, kGainB = 1.20f;
            constexpr float kBlack = 0.015f;               // deepen blacks a touch
            constexpr float kInvRange = 1.0f / (1.0f - kBlack);
            parallel_for(0, h, [&](int y) {
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    float* q = row + (size_t)x * 3;
                    float R = clampf(q[0] * kGainR, 0.0f, 1.0f);
                    float G = clampf(q[1] * kGainG, 0.0f, 1.0f);
                    float B = clampf(q[2] * kGainB, 0.0f, 1.0f);
                    q[0] = clampf((R - kBlack) * kInvRange, 0.0f, 1.0f);
                    q[1] = clampf((G - kBlack) * kInvRange, 0.0f, 1.0f);
                    q[2] = clampf((B - kBlack) * kInvRange, 0.0f, 1.0f);
                }
            });
            SLOG("postprocess: nightfall grade applied");
        }

        // ---------------- (6) chroma denoise at 1/4 res ----------------
        report(70);
        if (p.chromaDenoise) {
            const int qw = std::max(1, (w + 3) / 4);
            const int qh = std::max(1, (h + 3) / 4);
            std::vector<float> cbP((size_t)qw * qh, 0.0f);
            std::vector<float> crP((size_t)qw * qh, 0.0f);
            std::vector<float> tmpP((size_t)qw * qh, 0.0f);
            parallel_for(0, qh, [&](int qy) {
                int ys = qy * 4, ye = std::min(ys + 4, h);
                float* cbRow = &cbP[(size_t)qy * qw];
                float* crRow = &crP[(size_t)qy * qw];
                for (int qx = 0; qx < qw; ++qx) {
                    int xs = qx * 4, xe = std::min(xs + 4, w);
                    float sr = 0.0f, sg2 = 0.0f, sb = 0.0f;
                    int n = 0;
                    for (int y = ys; y < ye; ++y) {
                        const float* q = &img[((size_t)y * w + xs) * 3];
                        for (int x = xs; x < xe; ++x) {
                            sr += q[0];
                            sg2 += q[1];
                            sb += q[2];
                            q += 3;
                            ++n;
                        }
                    }
                    float inv = (n > 0) ? 1.0f / (float)n : 0.0f;
                    float R = sr * inv, G = sg2 * inv, B = sb * inv;
                    float Y = 0.299f * R + 0.587f * G + 0.114f * B;
                    cbRow[qx] = 0.564f * (B - Y);
                    crRow[qx] = 0.713f * (R - Y);
                }
            });
            auto box3 = [&](std::vector<float>& plane) {
                parallel_for(0, qh, [&](int y) {
                    const float* srow = &plane[(size_t)y * qw];
                    float* trow = &tmpP[(size_t)y * qw];
                    for (int x = 0; x < qw; ++x) {
                        int x0 = std::max(0, x - 1), x1 = std::min(qw - 1, x + 1);
                        trow[x] = (srow[x0] + srow[x] + srow[x1]) * (1.0f / 3.0f);
                    }
                });
                parallel_for(0, qh, [&](int y) {
                    int y0 = std::max(0, y - 1), y1 = std::min(qh - 1, y + 1);
                    const float* r0 = &tmpP[(size_t)y0 * qw];
                    const float* r1 = &tmpP[(size_t)y * qw];
                    const float* r2 = &tmpP[(size_t)y1 * qw];
                    float* orow = &plane[(size_t)y * qw];
                    for (int x = 0; x < qw; ++x)
                        orow[x] = (r0[x] + r1[x] + r2[x]) * (1.0f / 3.0f);
                });
            };
            box3(cbP); box3(cbP);  // two 3x3 box passes
            box3(crP); box3(crP);
            std::vector<int> bx0((size_t)w), bx1((size_t)w);
            std::vector<float> bwx((size_t)w);
            for (int x = 0; x < w; ++x) {
                float fx = clampf((x + 0.5f) * 0.25f - 0.5f, 0.0f, (float)(qw - 1));
                int x0 = (int)fx;
                bx0[x] = x0;
                bx1[x] = std::min(x0 + 1, qw - 1);
                bwx[x] = fx - (float)x0;
            }
            parallel_for(0, h, [&](int y) {
                float fy = clampf((y + 0.5f) * 0.25f - 0.5f, 0.0f, (float)(qh - 1));
                int y0 = (int)fy;
                int y1 = std::min(y0 + 1, qh - 1);
                float wy = fy - (float)y0;
                const float* cb0 = &cbP[(size_t)y0 * qw];
                const float* cb1 = &cbP[(size_t)y1 * qw];
                const float* cr0 = &crP[(size_t)y0 * qw];
                const float* cr1 = &crP[(size_t)y1 * qw];
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    int x0 = bx0[x], x1 = bx1[x];
                    float wx = bwx[x];
                    float w00 = (1.0f - wy) * (1.0f - wx), w01 = (1.0f - wy) * wx;
                    float w10 = wy * (1.0f - wx), w11 = wy * wx;
                    float cbv = cb0[x0] * w00 + cb0[x1] * w01 + cb1[x0] * w10 + cb1[x1] * w11;
                    float crv = cr0[x0] * w00 + cr0[x1] * w01 + cr1[x0] * w10 + cr1[x1] * w11;
                    float* q = row + (size_t)x * 3;
                    float Y = 0.299f * q[0] + 0.587f * q[1] + 0.114f * q[2];
                    float Rn = Y + crv * (1.0f / 0.713f);
                    float Bn = Y + cbv * (1.0f / 0.564f);
                    float Gn = (Y - 0.299f * Rn - 0.114f * Bn) * (1.0f / 0.587f);
                    q[0] = clampf(Rn, 0.0f, 1.0f);
                    q[1] = clampf(Gn, 0.0f, 1.0f);
                    q[2] = clampf(Bn, 0.0f, 1.0f);
                }
            });
        }

        // ---------------- (7) star sharpen (masked unsharp, radius ~1.5) ----------------
        report(82);
        if (p.starSharpen) {
            // Three 3-wide box passes == separable 7-tap kernel [1 3 6 7 6 3 1]/27 (sigma ~1.41).
            static const float K7[7] = {1.0f / 27.0f, 3.0f / 27.0f, 6.0f / 27.0f, 7.0f / 27.0f,
                                        6.0f / 27.0f, 3.0f / 27.0f, 1.0f / 27.0f};
            std::vector<uint8_t> mask3 = dilatedMask(maskRaw, hw, hh, 3);
            std::vector<uint8_t> rowAny((size_t)hh, 0);
            for (int hy = 0; hy < hh; ++hy) {
                const uint8_t* mr = &mask3[(size_t)hy * hw];
                for (int x = 0; x < hw; ++x) {
                    if (mr[x]) { rowAny[hy] = 1; break; }
                }
            }
            // Snapshot luma so the blur never reads rows another thread already sharpened.
            std::vector<float> lp((size_t)w * h);
            parallel_for(0, h, [&](int y) {
                const float* row = &img[(size_t)y * w * 3];
                float* d = &lp[(size_t)y * w];
                for (int x = 0; x < w; ++x) d[x] = lumaOf(row + (size_t)x * 3);
            });
            parallel_for(0, h, [&](int y) {
                try {
                    int hy = y >> 1; if (hy >= hh) hy = hh - 1;
                    if (!rowAny[hy]) return;
                    const uint8_t* mrow = &mask3[(size_t)hy * hw];
                    std::vector<float> hb((size_t)7 * w, 0.0f);
                    for (int j = 0; j < 7; ++j) {
                        const float* srow = &lp[(size_t)clampi(y - 3 + j, 0, h - 1) * w];
                        float* drow = &hb[(size_t)j * w];
                        for (int x = 0; x < w; ++x) {
                            float s = 0.0f;
                            for (int t = 0; t < 7; ++t)
                                s += K7[t] * srow[clampi(x - 3 + t, 0, w - 1)];
                            drow[x] = s;
                        }
                    }
                    float* row = &img[(size_t)y * w * 3];
                    const float* lrow = &lp[(size_t)y * w];
                    for (int x = 0; x < w; ++x) {
                        int hx = x >> 1; if (hx >= hw) hx = hw - 1;
                        if (!mrow[hx]) continue;
                        float L = lrow[x];
                        float Lb = 0.0f;
                        for (int j = 0; j < 7; ++j) Lb += K7[j] * hb[(size_t)j * w + x];
                        float Ls = L + 0.3f * (L - Lb);  // amount 0.3
                        float s = (L > kTiny) ? clampf(Ls / L, 0.0f, 4.0f) : 1.0f;
                        float* q = row + (size_t)x * 3;
                        q[0] *= s;
                        q[1] *= s;
                        q[2] *= s;
                    }
                } catch (...) {}
            });
        }

        // ---------------- (8) clamp + sRGB gamma encode ----------------
        report(92);
        parallel_for(0, h, [&](int y) {
            float* row = &img[(size_t)y * w * 3];
            for (int x = 0; x < w; ++x) {
                float* q = row + (size_t)x * 3;
                for (int c = 0; c < 3; ++c) {
                    float v = q[c];
                    if (!(v > 0.0f)) v = 0.0f;  // also catches NaN
                    else if (v > 1.0f) v = 1.0f;
                    q[c] = (v <= 0.0031308f) ? 12.92f * v
                                             : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
                }
            }
        });
        report(100);
    } catch (...) {
        SLOGE("postprocess: unexpected exception, output may be partially processed");
    }
}

}  // namespace starcam

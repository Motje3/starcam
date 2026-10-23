// post2.cpp — the v2 look (PostParams::version 2). Same contract as postprocess(): linear sRGB
// in, display-referred sRGB out, in place, no exceptions escape.
//
// What it does differently from the classic pipeline (post.cpp), and why:
//  - It finds the landscape: a star-free region, darker than the sky, along the edge that faces
//    the ground (from the phone's orientation, or searched for along every edge). Classic fitted
//    its gradient polynomial through the dark ground too, which lifted the ground to a flat grey
//    and left the sky above the horizon uneven.
//  - The light-pollution gradient is fitted to sky cells only, rejecting the Milky Way's cells
//    iteratively, and under the horizon the correction found at the horizon is carried straight
//    down instead of extrapolating the polynomial into the ground.
//  - The black point comes from the sky background, not from the darkest pixels of the frame
//    (the ground, when there is one), so the sky is no longer grey. Sky and ground each get their
//    own stretch, blended across a feathered horizon.
//  - The background is neutralized with offsets, which leave star colours alone; the chroma
//    denoise no longer runs over stars; colours beyond white are scaled down, not clipped, so
//    bright stars keep their hue.
//  - A light wavelet noise reduction on the luma, stars excluded.

#include "post.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "post_util.h"

namespace starcam {
namespace {

using postutil::dilatedMask;
using postutil::kTiny;
using postutil::lumaOf;
using postutil::smoothstepf;

// Least squares: solves the n x n normal equations A c = b for three right-hand sides (one per
// channel), n <= 10, by Gaussian elimination with partial pivoting. A and B are destroyed.
bool solve_normal(double A[10][10], double B[10][3], int n, double out[3][10]) {
    for (int col = 0; col < n; ++col) {
        int piv = col;
        for (int r = col + 1; r < n; ++r)
            if (std::fabs(A[r][col]) > std::fabs(A[piv][col])) piv = r;
        if (std::fabs(A[piv][col]) < 1e-12) return false;
        if (piv != col) {
            for (int k = 0; k < n; ++k) std::swap(A[col][k], A[piv][k]);
            for (int k = 0; k < 3; ++k) std::swap(B[col][k], B[piv][k]);
        }
        for (int r = col + 1; r < n; ++r) {
            const double f = A[r][col] / A[col][col];
            if (f == 0.0) continue;
            for (int k = col; k < n; ++k) A[r][k] -= f * A[col][k];
            for (int k = 0; k < 3; ++k) B[r][k] -= f * B[col][k];
        }
    }
    for (int c = 0; c < 3; ++c) {
        for (int r = n - 1; r >= 0; --r) {
            double s = B[r][c];
            for (int k = r + 1; k < n; ++k) s -= A[r][k] * out[c][k];
            out[c][r] = s / A[r][r];
        }
    }
    return true;
}

inline float srgb_to_linear(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// The image seen upright: the edge that faces the ground at the bottom. (u, v) are column and
// row of that upright view; the image itself is never rotated.
struct Upright {
    int down = 0, w = 0, h = 0;
    int uw() const { return (down == 1 || down == 3) ? h : w; }
    int uh() const { return (down == 1 || down == 3) ? w : h; }
    void toImage(int u, int v, int* x, int* y) const {
        switch (down) {
            case 1: *x = w - 1 - v; *y = u; break;          // ground along the left edge
            case 2: *x = w - 1 - u; *y = h - 1 - v; break;  // upside down
            case 3: *x = v; *y = h - 1 - u; break;          // ground along the right edge
            default: *x = u; *y = v; break;
        }
    }
    int column(int x, int y) const {
        switch (down) {
            case 1: return y;
            case 2: return w - 1 - x;
            case 3: return h - 1 - y;
            default: return x;
        }
    }
    int row(int x, int y) const {
        switch (down) {
            case 1: return w - 1 - x;
            case 2: return h - 1 - y;
            case 3: return x;
            default: return y;
        }
    }
};

// Background luma and star coverage of square cells of the half-res image.
struct Cells {
    int cw = 0, ch = 0, cs = 1;
    std::vector<float> luma, starFrac;
};

Cells cell_stats(const std::vector<float>& hl, const std::vector<uint8_t>& stars, int hw, int hh) {
    Cells c;
    c.cs = std::max(8, (int)std::lround(std::max(hw, hh) / 64.0));
    c.cw = (hw + c.cs - 1) / c.cs;
    c.ch = (hh + c.cs - 1) / c.cs;
    c.luma.assign((size_t)c.cw * c.ch, 0.0f);
    c.starFrac.assign((size_t)c.cw * c.ch, 0.0f);
    parallel_for(0, c.ch, [&](int cy) {
        try {
            std::vector<float> v;
            for (int cx = 0; cx < c.cw; ++cx) {
                v.clear();
                int n = 0, s = 0;
                const int y1 = std::min(hh, (cy + 1) * c.cs), x1 = std::min(hw, (cx + 1) * c.cs);
                for (int y = cy * c.cs; y < y1; ++y) {
                    for (int x = cx * c.cs; x < x1; ++x) {
                        ++n;
                        if (stars[(size_t)y * hw + x]) ++s;
                        else v.push_back(hl[(size_t)y * hw + x]);
                    }
                }
                c.luma[(size_t)cy * c.cw + cx] = v.empty() ? 0.0f : median_of(v);
                c.starFrac[(size_t)cy * c.cw + cx] = n ? (float)s / (float)n : 0.0f;
            }
        } catch (...) {}
    });
    return c;
}

// How many cell rows, counted from the bottom of the upright view, are landscape in each cell
// column. Returns the landscape's share of all cells, 0 when nothing qualifies.
float ground_depth(const Cells& c, int down, std::vector<int>* depthOut) {
    const Upright up{down, c.cw, c.ch};
    const int UW = up.uw(), UH = up.uh();
    if (UW < 4 || UH < 4) return 0.0f;
    auto at = [&](const std::vector<float>& a, int u, int v) {
        int x, y;
        up.toImage(u, v, &x, &y);
        return a[(size_t)y * c.cw + x];
    };
    std::vector<float> sf, sl;
    for (int v = 0; v < std::max(1, UH * 2 / 5); ++v) {
        for (int u = 0; u < UW; ++u) {
            sf.push_back(at(c.starFrac, u, v));
            sl.push_back(at(c.luma, u, v));
        }
    }
    const float refStar = median_of(sf), refLuma = median_of(sl);
    // Chance 3-sigma pixels alone mark ~0.1% of a cell; a starry sky marks several percent.
    const bool starry = refStar > 0.01f;
    const float starCut = starry ? 0.2f * refStar : 0.004f;
    std::vector<int> depth((size_t)UW, 0);
    for (int u = 0; u < UW; ++u) {
        int d = 0, gap = 0;
        // The landscape never takes more than 60% of the frame: higher up it is cloud.
        for (int v = UH - 1; v >= UH * 2 / 5; --v) {
            const float s = at(c.starFrac, u, v), l = at(c.luma, u, v);
            const bool dark = l < 0.9f * refLuma;
            // Under half the sky's brightness it is landscape unless clearly starrier than the
            // sky: texture, lit windows and stars mirrored in a lake fool the star count, not the
            // darkness. Two such cells used to end the landscape and leave the rest of it black.
            const bool groundLike = (s < starCut &&
                (starry ? (dark || s < 0.25f * starCut) : l < 0.75f * refLuma)) ||
                (l < 0.5f * refLuma && s < std::max(3.0f * refStar, 0.02f));
            if (groundLike) {
                d = UH - v;
                gap = 0;
            } else if (++gap > 1) {
                break;  // one odd cell (a house light, a stray detection) is tolerated
            }
        }
        depth[(size_t)u] = d;
    }
    // Median of 5 columns: a single star-poor sky cell must not poke a hole in the horizon.
    std::vector<int> sm(depth);
    for (int u = 0; u < UW; ++u) {
        int w[5], n = 0;
        for (int k = -2; k <= 2; ++k) w[n++] = depth[(size_t)clampi(u + k, 0, UW - 1)];
        std::nth_element(w, w + 2, w + 5);
        sm[(size_t)u] = w[2];
    }
    int cells = 0, cols = 0;
    for (int u = 0; u < UW; ++u) {
        cells += sm[(size_t)u];
        if (sm[(size_t)u] > 0) ++cols;
    }
    // A landscape runs along a good part of the frame.
    if (cols < UW / 4) return 0.0f;
    *depthOut = sm;
    return (float)cells / (float)(UW * UH);
}

struct Ground {
    bool found = false;
    Upright up;                    // on the half-res image
    std::vector<float> horizon;    // per upright half-res column: first ground row (uh = none)
    std::vector<float> mask;       // half-res, 1 = ground, feathered
};

Ground find_ground(const std::vector<float>& hl, const std::vector<uint8_t>& stars, int hw, int hh,
                   int downHint) {
    Ground g;
    const Cells c = cell_stats(hl, stars, hw, hh);
    std::vector<int> depth;
    int down = -1;
    if (downHint >= 0 && downHint <= 3) {
        if (ground_depth(c, downHint, &depth) > 0.02f) down = downHint;
    } else {
        float best = 0.03f;
        for (int d = 0; d < 4; ++d) {
            std::vector<int> dd;
            const float a = ground_depth(c, d, &dd);
            if (a > best) {
                best = a;
                down = d;
                depth = dd;
            }
        }
    }
    if (down < 0) return g;

    const Upright upC{down, c.cw, c.ch};
    g.up = Upright{down, hw, hh};
    const int UWh = g.up.uw(), UHh = g.up.uh(), UWc = upC.uw();
    const int cs = c.cs;
    // Smoothed half-res luma for the per-column horizon search (5 rows x 3 columns box).
    std::vector<float> sm((size_t)hw * hh);
    parallel_for(0, hh, [&](int y) {
        for (int x = 0; x < hw; ++x) {
            float s = 0.0f;
            int n = 0;
            for (int yy = std::max(0, y - 2); yy <= std::min(hh - 1, y + 2); ++yy)
                for (int xx = std::max(0, x - 1); xx <= std::min(hw - 1, x + 1); ++xx) {
                    s += hl[(size_t)yy * hw + xx];
                    ++n;
                }
            sm[(size_t)y * hw + x] = s / (float)n;
        }
    });
    g.horizon.assign((size_t)UWh, (float)UHh);
    parallel_for(0, UWh, [&](int u) {
        try {
            const float cu = ((float)u + 0.5f) / (float)cs - 0.5f;
            const int c0 = clampi((int)std::floor(cu), 0, UWc - 1), c1 = std::min(c0 + 1, UWc - 1);
            const float t = clampf(cu - (float)c0, 0.0f, 1.0f);
            const float dCells = (float)depth[(size_t)c0] * (1.0f - t) + (float)depth[(size_t)c1] * t;
            if (dCells <= 0.0f) return;
            const int vRough = clampi((int)std::lround((float)UHh - dCells * (float)cs), 0, UHh - 1);
            auto lum = [&](int v) {
                int x, y;
                g.up.toImage(u, v, &x, &y);
                return sm[(size_t)y * hw + x];
            };
            std::vector<float> skyV, gndV;
            for (int v = std::max(0, vRough - 3 * cs); v < std::max(0, vRough - cs); ++v) skyV.push_back(lum(v));
            for (int v = std::min(UHh - 1, vRough + cs); v < std::min(UHh, vRough + 3 * cs); ++v) gndV.push_back(lum(v));
            if (skyV.size() < 4 || gndV.size() < 4) {
                g.horizon[(size_t)u] = (float)vRough;
                return;
            }
            const float sky = median_of(skyV), gnd = median_of(gndV);
            const float thr = 0.5f * (sky + gnd);
            const bool groundDarker = gnd < sky;
            float found = (float)vRough;
            const int v0 = std::max(0, vRough - 2 * cs), v1 = std::min(UHh - 4, vRough + 2 * cs);
            for (int v = v0; v <= v1; ++v) {
                bool cross = true;
                for (int k = 0; k < 4 && cross; ++k) {
                    const float l = lum(v + k);
                    cross = groundDarker ? l < thr : l > thr;
                }
                if (cross) {
                    found = (float)v;
                    break;
                }
            }
            g.horizon[(size_t)u] = found;
        } catch (...) {}
    });
    // 3-column median against single-column spikes.
    {
        std::vector<float> h2(g.horizon);
        for (int u = 1; u + 1 < UWh; ++u) {
            float a = g.horizon[(size_t)u - 1], b = g.horizon[(size_t)u], cc = g.horizon[(size_t)u + 1];
            h2[(size_t)u] = std::max(std::min(a, b), std::min(std::max(a, b), cc));
        }
        g.horizon.swap(h2);
    }
    // Half-res mask, softened by one 3x3 box (about 3 full-res pixels): sky and ground have
    // their own tone curves, and a wide blend between them draws a dark line along the horizon.
    // Columns without landscape (horizon = uh) stay sky to the very edge.
    g.mask.assign((size_t)hw * hh, 0.0f);
    parallel_for(0, hh, [&](int y) {
        for (int x = 0; x < hw; ++x) {
            const int u = g.up.column(x, y), v = g.up.row(x, y);
            const float hz = g.horizon[(size_t)u];
            g.mask[(size_t)y * hw + x] = hz < (float)UHh && (float)v >= hz - 2.0f ? 1.0f : 0.0f;
        }
    });
    std::vector<float> tmp((size_t)hw * hh);
    for (int pass = 0; pass < 1; ++pass) {
        parallel_for(0, hh, [&](int y) {
            const float* r = &g.mask[(size_t)y * hw];
            float* o = &tmp[(size_t)y * hw];
            for (int x = 0; x < hw; ++x) {
                float s = 0.0f;
                int n = 0;
                for (int k = -1; k <= 1; ++k) {
                    const int xx = x + k;
                    if (xx < 0 || xx >= hw) continue;
                    s += r[xx];
                    ++n;
                }
                o[x] = s / (float)n;
            }
        });
        parallel_for(0, hh, [&](int y) {
            float* o = &g.mask[(size_t)y * hw];
            for (int x = 0; x < hw; ++x) {
                float s = 0.0f;
                int n = 0;
                for (int k = -1; k <= 1; ++k) {
                    const int yy = y + k;
                    if (yy < 0 || yy >= hh) continue;
                    s += tmp[(size_t)yy * hw + x];
                    ++n;
                }
                o[x] = s / (float)n;
            }
        });
    }
    g.found = true;
    return g;
}

// asinh stretch strength k so that a background luma L lands at target t (both linear).
double stretch_k(double L, double t) {
    L = std::max(L, 1e-7);
    auto over = [L, t](double k) { return std::asinh(k * L) / std::asinh(k) - t; };
    double lo = 0.5, hi = 20000.0;
    if (over(lo) >= 0.0) return lo;
    if (over(hi) <= 0.0) return hi;
    for (int it = 0; it < 40; ++it) {
        const double mid = std::sqrt(lo * hi);
        if (over(mid) >= 0.0) hi = mid; else lo = mid;
    }
    return std::sqrt(lo * hi);
}

}  // namespace

void postprocess_v2(ImageF& rgb, const PostParams& p, const ProgressFn& progress) {
    try {
        auto report = [&progress](int pct) {
            if (progress) {
                try { progress("post", pct); } catch (...) {}
            }
        };
        if (rgb.empty() || rgb.ch != 3 || rgb.w <= 0 || rgb.h <= 0 ||
            rgb.d.size() < (size_t)rgb.w * rgb.h * 3) {
            SLOGE("postprocess_v2: invalid image (w=%d h=%d ch=%d)", rgb.w, rgb.h, rgb.ch);
            return;
        }
        const int w = rgb.w, h = rgb.h;
        float* img = rgb.d.data();
        report(0);

        // ---------------- half-res luma, star mask (as classic) ----------------
        const int hw = std::max(1, w / 2), hh = std::max(1, h / 2);
        std::vector<float> hl((size_t)hw * hh, 0.0f);
        parallel_for(0, hh, [&](int hy) {
            const int y0 = hy * 2, y1 = std::min(y0 + 1, h - 1);
            const float* r0 = &img[(size_t)y0 * w * 3];
            const float* r1 = &img[(size_t)y1 * w * 3];
            float* o = &hl[(size_t)hy * hw];
            for (int hx = 0; hx < hw; ++hx) {
                const int x0 = hx * 2, x1 = std::min(x0 + 1, w - 1);
                o[hx] = 0.25f * (lumaOf(r0 + (size_t)x0 * 3) + lumaOf(r0 + (size_t)x1 * 3) +
                                 lumaOf(r1 + (size_t)x0 * 3) + lumaOf(r1 + (size_t)x1 * 3));
            }
        });
        // Star mask: pixels well above their cell's background (median + 3 MAD sigmas, 32x32
        // cells). With [side] (the landscape mask) sky and ground pixels are each judged by their
        // own side of the cell: in a cell that is mostly ground, the ground's statistics would
        // mark the whole strip of sky above the horizon as stars, which then skipped the noise
        // reduction and got the star colour. A side too small to judge has no stars.
        auto starMask = [&](const std::vector<float>* side, std::vector<uint8_t>* out) {
            const int gx = std::min(32, hw), gy = std::min(32, hh), nSides = side ? 2 : 1;
            const size_t minCount = side ? 32 : 1;
            std::vector<float> bg((size_t)gx * gy * nSides, 0.0f), sg((size_t)gx * gy * nSides, -1.0f);
            auto sideOf = [side](size_t i) { return side && (*side)[i] >= 0.5f ? 1 : 0; };
            parallel_for(0, gy, [&](int cy) {
                try {
                    const int ys = (int)((int64_t)cy * hh / gy), ye = (int)((int64_t)(cy + 1) * hh / gy);
                    std::vector<float> cell[2], dev;
                    for (int cx = 0; cx < gx; ++cx) {
                        const int xs = (int)((int64_t)cx * hw / gx), xe = (int)((int64_t)(cx + 1) * hw / gx);
                        cell[0].clear();
                        cell[1].clear();
                        for (int yy = ys; yy < ye; ++yy)
                            for (int xx = xs; xx < xe; ++xx) {
                                const size_t i = (size_t)yy * hw + xx;
                                cell[sideOf(i)].push_back(hl[i]);
                            }
                        for (int sd = 0; sd < nSides; ++sd) {
                            std::vector<float>& v = cell[sd];
                            if (v.size() < minCount) continue;
                            const float med = median_of(v);
                            dev.assign(v.size(), 0.0f);
                            for (size_t i = 0; i < v.size(); ++i) dev[i] = std::fabs(v[i] - med);
                            const size_t ci = ((size_t)cy * gx + cx) * nSides + sd;
                            bg[ci] = med;
                            sg[ci] = std::max(1.4826f * median_of(dev), 1e-5f);
                        }
                    }
                } catch (...) {}
            });
            out->assign((size_t)hw * hh, 0);
            parallel_for(0, hh, [&](int hy) {
                const int cy = std::min(gy - 1, (int)((int64_t)hy * gy / hh));
                for (int hx = 0; hx < hw; ++hx) {
                    const int cx = std::min(gx - 1, (int)((int64_t)hx * gx / hw));
                    const size_t i = (size_t)hy * hw + hx;
                    const size_t ci = ((size_t)cy * gx + cx) * nSides + sideOf(i);
                    (*out)[i] = sg[ci] > 0.0f && hl[i] > bg[ci] + 3.0f * sg[ci];
                }
            });
        };
        std::vector<uint8_t> maskRaw;
        starMask(nullptr, &maskRaw);
        report(5);

        // ---------------- landscape ----------------
        const Ground ground = find_ground(hl, maskRaw, hw, hh, p.downEdge);
        if (ground.found) {
            const size_t before = (size_t)std::count(maskRaw.begin(), maskRaw.end(), (uint8_t)1);
            starMask(&ground.mask, &maskRaw);
            SLOG("postprocess_v2: star mask %.2f%% of the frame (%.2f%% before the landscape was known)",
                 100.0 * (double)std::count(maskRaw.begin(), maskRaw.end(), (uint8_t)1) / (double)maskRaw.size(),
                 100.0 * (double)before / (double)maskRaw.size());
        }
        const std::vector<uint8_t> mask2 = dilatedMask(maskRaw, hw, hh, 2);
        // Typical sky brightness, for judging pixels along the horizon when no gradient is fitted.
        float skyRefL = 0.0f;
        {
            std::vector<float> v;
            for (size_t i = 0; i < hl.size(); i += 7)
                if (!maskRaw[i] && (!ground.found || ground.mask[i] <= 0.0f)) v.push_back(hl[i]);
            skyRefL = median_of(v);
        }
        hl.clear();
        hl.shrink_to_fit();
        auto groundAt = [&](int x, int y) -> float {
            if (!ground.found) return 0.0f;
            return ground.mask[(size_t)std::min(hh - 1, y >> 1) * hw + std::min(hw - 1, x >> 1)];
        };
        if (ground.found) {
            double sum = 0.0;
            for (float v : ground.mask) sum += v;
            SLOG("postprocess_v2: landscape along edge %d, %.0f%% of the frame", ground.up.down,
                 100.0 * sum / (double)ground.mask.size());
        } else {
            SLOG("postprocess_v2: no landscape found");
        }
        report(12);

        int st = 2;
        while ((int64_t)(w / st) * (int64_t)(h / st) > 1500000) st += 2;

        // ---------------- sky gradient (sky cells only, Milky Way rejected) ----------------
        // Light pollution falls off steeply above the horizon and then flattens, which a quadratic
        // follows only halfway; a cubic does better when the sky fills the frame, but over the
        // smaller sky above a landscape it starts to eat into the Milky Way. Auto picks by that.
        const int order = p.gradientOrder > 0 ? p.gradientOrder : (ground.found ? 2 : 3);
        const int nTerms = order >= 3 ? 10 : 6;
        double coef[3][10] = {};
        double ref[3] = {0, 0, 0};
        bool fitted = false;
        auto basis = [nTerms](double X, double Y, double* phi) {
            phi[0] = 1.0; phi[1] = X; phi[2] = Y; phi[3] = X * Y; phi[4] = X * X; phi[5] = Y * Y;
            if (nTerms > 6) {
                phi[6] = X * X * X; phi[7] = X * X * Y; phi[8] = X * Y * Y; phi[9] = Y * Y * Y;
            }
        };
        auto model = [&coef, &basis, nTerms](int c, double X, double Y) {
            double phi[10];
            basis(X, Y, phi);
            double m = 0.0;
            for (int a = 0; a < nTerms; ++a) m += coef[c][a] * phi[a];
            return m;
        };
        if (p.gradientRemoval && p.gradientStrength > 0.0f) {
            struct Cell { double med[3]; double lum, X, Y; };
            std::vector<Cell> cells;
            const int gx = std::min(32, w), gy = std::min(32, h);
            std::vector<float> vr, vg, vb, vl;
            for (int cy = 0; cy < gy; ++cy) {
                const int ys = (int)((int64_t)cy * h / gy), ye = (int)((int64_t)(cy + 1) * h / gy);
                for (int cx = 0; cx < gx; ++cx) {
                    const int xs = (int)((int64_t)cx * w / gx), xe = (int)((int64_t)(cx + 1) * w / gx);
                    vr.clear(); vg.clear(); vb.clear(); vl.clear();
                    bool touchesGround = false;
                    for (int y = ys; y < ye && !touchesGround; y += 2) {
                        const int hy = std::min(hh - 1, y >> 1);
                        const float* row = &img[(size_t)y * w * 3];
                        for (int x = xs; x < xe; x += 2) {
                            if (groundAt(x, y) > 0.01f) { touchesGround = true; break; }
                            const int hx = std::min(hw - 1, x >> 1);
                            if (mask2[(size_t)hy * hw + hx]) continue;
                            const float* q = row + (size_t)x * 3;
                            vr.push_back(q[0]); vg.push_back(q[1]); vb.push_back(q[2]);
                            vl.push_back(lumaOf(q));
                        }
                    }
                    if (touchesGround || vl.size() < 8) continue;
                    Cell c;
                    c.med[0] = median_of(vr); c.med[1] = median_of(vg); c.med[2] = median_of(vb);
                    c.lum = median_of(vl);
                    c.X = (w > 1) ? (double)(xs + xe - 1) / (double)(w - 1) - 1.0 : 0.0;
                    c.Y = (h > 1) ? (double)(ys + ye - 1) / (double)(h - 1) - 1.0 : 0.0;
                    cells.push_back(c);
                }
            }
            std::vector<uint8_t> use(cells.size(), 1);
            {   // start without the brightest tenth (the Milky Way's core, a lit cloud)
                std::vector<float> l;
                for (const Cell& c : cells) l.push_back((float)c.lum);
                const float cut = percentile_of(l, 0.9f);
                for (size_t i = 0; i < cells.size(); ++i) use[i] = cells[i].lum <= cut;
            }
            for (int iter = 0; iter < 4 && (int)cells.size() >= 2 * nTerms; ++iter) {
                double A[10][10] = {}, B[10][3] = {};
                int used = 0;
                for (size_t i = 0; i < cells.size(); ++i) {
                    if (!use[i]) continue;
                    const Cell& c = cells[i];
                    double phi[10];
                    basis(c.X, c.Y, phi);
                    for (int a = 0; a < nTerms; ++a) {
                        for (int b = 0; b < nTerms; ++b) A[a][b] += phi[a] * phi[b];
                        for (int k = 0; k < 3; ++k) B[a][k] += phi[a] * c.med[k];
                    }
                    ++used;
                }
                double trial[3][10] = {};
                if (used < nTerms + 4 || !solve_normal(A, B, nTerms, trial)) break;
                std::copy(&trial[0][0], &trial[0][0] + 30, &coef[0][0]);
                fitted = true;
                // Re-select: cells well above the model are the Milky Way (or a lit cloud).
                std::vector<float> res(cells.size());
                for (size_t i = 0; i < cells.size(); ++i) {
                    const Cell& c = cells[i];
                    const double m = 0.299 * model(0, c.X, c.Y) + 0.587 * model(1, c.X, c.Y) +
                                     0.114 * model(2, c.X, c.Y);
                    res[i] = (float)(c.lum - m);
                }
                std::vector<float> ar;
                for (size_t i = 0; i < cells.size(); ++i) if (use[i]) ar.push_back(std::fabs(res[i]));
                const float sig = std::max(1.4826f * median_of(ar), 1e-6f);
                for (size_t i = 0; i < cells.size(); ++i) use[i] = res[i] < 1.5f * sig && res[i] > -3.0f * sig;
            }
            if (fitted) {
                // Subtract down to the darkest modelled sky: light pollution only ever adds light.
                ref[0] = ref[1] = ref[2] = 1e9;
                for (const Cell& c : cells)
                    for (int k = 0; k < 3; ++k) ref[k] = std::min(ref[k], model(k, c.X, c.Y));
            }
        }

        // Full-res ground weight. Away from the horizon it is the half-res mask; along it, each
        // pixel's own brightness against the sky expected there decides, so silhouettes stay
        // sharp and no sky correction or sky black point lands on the edge of the ground (both
        // drew a dark line around every silhouette). The band reaches 3 half-res rows into the
        // landscape: the mask starts 2 rows above the horizon found, and sky pixels it takes in
        // must not get the ground's stretch. 255 is solid ground; the band stays below it, so
        // the stretch can tell its partly-sky pixels apart.
        std::vector<uint8_t> gFine;
        if (ground.found) {
            gFine.assign((size_t)w * h, 0);
            parallel_for(0, h, [&](int y) {
                const double Y = (h > 1) ? 2.0 * y / (h - 1) - 1.0 : 0.0;
                const float* row = &img[(size_t)y * w * 3];
                uint8_t* grow = &gFine[(size_t)y * w];
                const int hy = std::min(hh - 1, y >> 1);
                for (int x = 0; x < w; ++x) {
                    const float g = groundAt(x, y);
                    if (g <= 0.0f) continue;
                    const int hx = std::min(hw - 1, x >> 1);
                    const float below = (float)ground.up.row(hx, hy) - ground.horizon[(size_t)ground.up.column(hx, hy)];
                    if (g >= 1.0f && below >= 3.0f) { grow[x] = 255; continue; }
                    float skyL = skyRefL;
                    if (fitted) {
                        const double X = (w > 1) ? 2.0 * x / (w - 1) - 1.0 : 0.0;
                        skyL = (float)(0.299 * model(0, X, Y) + 0.587 * model(1, X, Y) +
                                       0.114 * model(2, X, Y));
                    }
                    const float r = lumaOf(row + (size_t)x * 3) / std::max(skyL, kTiny);
                    grow[x] = (uint8_t)std::lround(254.0f * (1.0f - smoothstepf(0.55f, 0.85f, r)));
                }
            });
        }
        auto groundFine = [&](int x, int y) -> float {
            return gFine.empty() ? 0.0f : (float)gFine[(size_t)y * w + x] * (1.0f / 255.0f);
        };

        if (fitted) {
            const float s = clampf(p.gradientStrength, 0.0f, 1.0f);
            // The glow is in the air in front of the sky, not on the ground: the landscape is
            // left as it is and gets its own black point below.
            parallel_for(0, h, [&](int y) {
                const double Y = (h > 1) ? 2.0 * y / (h - 1) - 1.0 : 0.0;
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    const float sky = 1.0f - groundFine(x, y);
                    if (sky <= 0.0f) continue;
                    const double X = (w > 1) ? 2.0 * x / (w - 1) - 1.0 : 0.0;
                    float* q = row + (size_t)x * 3;
                    for (int k = 0; k < 3; ++k) q[k] -= sky * (float)(s * (model(k, X, Y) - ref[k]));
                }
            });
            SLOG("postprocess_v2: sky gradient removed");
        }
        report(30);

        // ---------------- background neutralization (offsets) + statistics ----------------
        // Noise is measured from differences of pixels 3 apart (beyond the demosaic's
        // correlation), so the Milky Way and what is left of the gradient do not count as noise.
        float skyMed[3] = {0, 0, 0}, skySig = 0.0f, gndMed = 0.0f, gndSig = 0.0f;
        {
            std::vector<float> ch[3], sl, sd, gl, gd;
            for (int y = 0; y < h; y += st) {
                const int hy = std::min(hh - 1, y >> 1);
                const float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x + 3 < w; x += st) {
                    const float g = groundFine(x, y);
                    const float* q = row + (size_t)x * 3;
                    const float d = lumaOf(q) - lumaOf(q + 9);
                    if (g > 0.95f) {
                        gl.push_back(lumaOf(q));
                        gd.push_back(std::fabs(d));
                        continue;
                    }
                    if (g > 0.05f || mask2[(size_t)hy * hw + std::min(hw - 1, x >> 1)] ||
                        mask2[(size_t)hy * hw + std::min(hw - 1, (x + 3) >> 1)])
                        continue;
                    for (int k = 0; k < 3; ++k) ch[k].push_back(q[k]);
                    sl.push_back(lumaOf(q));
                    sd.push_back(std::fabs(d));
                }
            }
            if (!sl.empty()) {
                for (int k = 0; k < 3; ++k) skyMed[k] = median_of(ch[k]);
                skySig = 1.4826f * median_of(sd) / std::sqrt(2.0f);
            }
            if (gl.size() > 100) {
                gndMed = median_of(gl);
                gndSig = 1.4826f * median_of(gd) / std::sqrt(2.0f);
            }
        }
        const float neutral = (skyMed[0] + skyMed[1] + skyMed[2]) / 3.0f;
        const float off[3] = {skyMed[0] - neutral, skyMed[1] - neutral, skyMed[2] - neutral};
        SLOG("postprocess_v2: sky bg %.5f %.5f %.5f sigma %.5f, ground %.5f sigma %.5f", skyMed[0],
             skyMed[1], skyMed[2], skySig, gndMed, gndSig);
        report(40);

        // ---------------- stretch: sky and ground each to their own level ----------------
        // The sky background sits skyGap above black: a fraction of its own brightness (smaller
        // is punchier and shows more noise), never less than three noise sigmas.
        const float skyGap = std::max(3.0f * skySig, clampf(p.contrastGap, 0.02f, 0.9f) * neutral);
        const float skyBlack = neutral - skyGap;
        const double kSky = stretch_k(skyGap, srgb_to_linear(clampf(p.skyLevel, 0.02f, 0.6f)));
        const float invSky = (float)(1.0 / std::asinh(kSky));
        float gndBlack = skyBlack;
        double kGnd = kSky;
        const float gndLuma = gndMed;  // the sky's offsets are not applied to the ground (below)
        if (ground.found && gndLuma > 0.0f) {
            const float gGap = std::max(3.0f * gndSig, 0.5f * std::max(gndLuma, 1e-5f));
            gndBlack = gndLuma - gGap;
            kGnd = stretch_k(gGap, srgb_to_linear(clampf(p.groundLevel, 0.0f, 0.6f)));
        }
        const float invGnd = (float)(1.0 / std::asinh(kGnd));
        const float kS = (float)kSky, kG = (float)kGnd;
        // The studio's white balance: warmth moves red against blue, tint green against both.
        const float warm = clampf(p.warmth, -1.0f, 1.0f), tint = clampf(p.tint, -1.0f, 1.0f);
        const float wb[3] = {1.0f + 0.15f * warm + 0.05f * tint, 1.0f - 0.10f * tint,
                             1.0f - 0.15f * warm + 0.05f * tint};
        SLOG("postprocess_v2: stretch sky k=%.1f black %.5f, ground k=%.1f black %.5f", kSky, skyBlack,
             kGnd, gndBlack);
        // A horizon pixel that is partly sky is far brighter than the ground, and the ground's
        // steeper curve drew it as a bright rim along every silhouette: in the horizon band the
        // ground's share is never brighter than the brighter of the two backgrounds.
        const float skyOut = std::asinh(kS * skyGap) * invSky;
        const float gndOut = std::asinh(kG * std::max(0.0f, gndLuma - gndBlack)) * invGnd;
        const float rimCap = std::max(skyOut, gndOut);
        parallel_for(0, h, [&](int y) {
            float* row = &img[(size_t)y * w * 3];
            for (int x = 0; x < w; ++x) {
                float* q = row + (size_t)x * 3;
                const float g = groundFine(x, y);
                // The neutralizing offsets are the sky's; the landscape keeps its own colour.
                const float sky = 1.0f - g;
                float in[3] = {(q[0] - sky * off[0]) * wb[0], (q[1] - sky * off[1]) * wb[1],
                               (q[2] - sky * off[2]) * wb[2]};
                float out[3] = {0, 0, 0};
                auto apply = [&in](float black, float k, float inv, float wgt, float cap, float* o) {
                    float v[3];
                    for (int c = 0; c < 3; ++c) v[c] = std::max(0.0f, in[c] - black);
                    const float L = lumaOf(v);
                    const float sc = L > kTiny ? std::min(std::asinh(k * L) * inv, cap) / L : k * inv;
                    for (int c = 0; c < 3; ++c) o[c] += wgt * v[c] * sc;
                };
                if (g < 1.0f) apply(skyBlack, kS, invSky, 1.0f - g, 1e9f, out);
                if (g > 0.0f) apply(gndBlack, kG, invGnd, g, g < 1.0f ? rimCap : 1e9f, out);
                q[0] = out[0]; q[1] = out[1]; q[2] = out[2];
            }
        });
        report(55);

        // ---------------- saturation; stars get extra ----------------
        const std::vector<uint8_t> starWide = dilatedMask(maskRaw, hw, hh, 1);
        {
            const float sat = clampf(p.saturation, 0.0f, 4.0f);
            const float boost = clampf(p.starColor, 0.0f, 2.0f);
            parallel_for(0, h, [&](int y) {
                const uint8_t* mrow = &starWide[(size_t)std::min(hh - 1, y >> 1) * hw];
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    float* q = row + (size_t)x * 3;
                    const float Y = lumaOf(q);
                    // The lifted landscape is mostly noise in colour: half the boost there.
                    const float gnd = groundFine(x, y);
                    const float base = 1.0f + (sat - 1.0f) * (1.0f - 0.5f * gnd);
                    const float g = base * (mrow[std::min(hw - 1, x >> 1)] ? 1.0f + boost : 1.0f);
                    const float cbv = 0.564f * (q[2] - Y) * g, crv = 0.713f * (q[0] - Y) * g;
                    const float Rn = Y + crv * (1.0f / 0.713f), Bn = Y + cbv * (1.0f / 0.564f);
                    q[0] = std::max(0.0f, Rn);
                    q[2] = std::max(0.0f, Bn);
                    q[1] = std::max(0.0f, (Y - 0.299f * Rn - 0.114f * Bn) * (1.0f / 0.587f));
                }
            });
        }
        // ---------------- optional "nightfall" grade (as classic) ----------------
        if (p.style == 1) {
            constexpr float kGainR = 1.03f, kGainG = 0.86f, kGainB = 1.20f;
            // Classic took 0.015 off a sky at 0.09; the same share of this darker sky.
            const float kBlack = 0.167f * srgb_to_linear(clampf(p.skyLevel, 0.02f, 0.6f));
            const float kInvRange = 1.0f / (1.0f - kBlack);
            parallel_for(0, h, [&](int y) {
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    float* q = row + (size_t)x * 3;
                    q[0] = std::max(0.0f, (q[0] * kGainR - kBlack) * kInvRange);
                    q[1] = std::max(0.0f, (q[1] * kGainG - kBlack) * kInvRange);
                    q[2] = std::max(0.0f, (q[2] * kGainB - kBlack) * kInvRange);
                }
            });
        }
        report(65);

        // ---------------- chroma denoise at 1/4 res, stars keep their own colour ----------------
        if (p.chromaDenoise) {
            const int qw = std::max(1, (w + 3) / 4), qh = std::max(1, (h + 3) / 4);
            std::vector<float> cbP((size_t)qw * qh), crP((size_t)qw * qh), tmpP((size_t)qw * qh);
            parallel_for(0, qh, [&](int qy) {
                const int ys = qy * 4, ye = std::min(ys + 4, h);
                for (int qx = 0; qx < qw; ++qx) {
                    const int xs = qx * 4, xe = std::min(xs + 4, w);
                    float sr = 0, sg2 = 0, sb = 0;
                    int n = 0;
                    for (int y = ys; y < ye; ++y) {
                        const float* q = &img[((size_t)y * w + xs) * 3];
                        for (int x = xs; x < xe; ++x, q += 3) { sr += q[0]; sg2 += q[1]; sb += q[2]; ++n; }
                    }
                    const float inv = n ? 1.0f / (float)n : 0.0f;
                    const float R = sr * inv, G = sg2 * inv, B = sb * inv;
                    const float Y = 0.299f * R + 0.587f * G + 0.114f * B;
                    cbP[(size_t)qy * qw + qx] = 0.564f * (B - Y);
                    crP[(size_t)qy * qw + qx] = 0.713f * (R - Y);
                }
            });
            auto box3 = [&](std::vector<float>& plane) {
                parallel_for(0, qh, [&](int y) {
                    for (int x = 0; x < qw; ++x) {
                        const int x0 = std::max(0, x - 1), x1 = std::min(qw - 1, x + 1);
                        tmpP[(size_t)y * qw + x] = (plane[(size_t)y * qw + x0] + plane[(size_t)y * qw + x] +
                                                    plane[(size_t)y * qw + x1]) * (1.0f / 3.0f);
                    }
                });
                parallel_for(0, qh, [&](int y) {
                    const int y0 = std::max(0, y - 1), y1 = std::min(qh - 1, y + 1);
                    for (int x = 0; x < qw; ++x)
                        plane[(size_t)y * qw + x] = (tmpP[(size_t)y0 * qw + x] + tmpP[(size_t)y * qw + x] +
                                                     tmpP[(size_t)y1 * qw + x]) * (1.0f / 3.0f);
                });
            };
            box3(cbP); box3(cbP);
            box3(crP); box3(crP);
            // Feathered star mask: stars and a ring around them keep their own chroma.
            std::vector<float> keep((size_t)hw * hh);
            {
                const std::vector<uint8_t> m3 = dilatedMask(maskRaw, hw, hh, 2);
                for (size_t i = 0; i < keep.size(); ++i) keep[i] = m3[i] ? 1.0f : 0.0f;
            }
            parallel_for(0, h, [&](int y) {
                const float fy = clampf((y + 0.5f) * 0.25f - 0.5f, 0.0f, (float)(qh - 1));
                const int y0 = (int)fy, y1 = std::min(y0 + 1, qh - 1);
                const float wy = fy - (float)y0;
                float* row = &img[(size_t)y * w * 3];
                const float* krow = &keep[(size_t)std::min(hh - 1, y >> 1) * hw];
                for (int x = 0; x < w; ++x) {
                    float* q = row + (size_t)x * 3;
                    const float k = krow[std::min(hw - 1, x >> 1)];
                    if (k >= 1.0f) continue;
                    const float fx = clampf((x + 0.5f) * 0.25f - 0.5f, 0.0f, (float)(qw - 1));
                    const int x0 = (int)fx, x1 = std::min(x0 + 1, qw - 1);
                    const float wx = fx - (float)x0;
                    auto bil = [&](const std::vector<float>& P) {
                        return (P[(size_t)y0 * qw + x0] * (1 - wx) + P[(size_t)y0 * qw + x1] * wx) * (1 - wy) +
                               (P[(size_t)y1 * qw + x0] * (1 - wx) + P[(size_t)y1 * qw + x1] * wx) * wy;
                    };
                    const float Y = lumaOf(q);
                    const float cbO = 0.564f * (q[2] - Y), crO = 0.713f * (q[0] - Y);
                    const float cbv = k * cbO + (1 - k) * bil(cbP), crv = k * crO + (1 - k) * bil(crP);
                    const float Rn = Y + crv * (1.0f / 0.713f), Bn = Y + cbv * (1.0f / 0.564f);
                    q[0] = std::max(0.0f, Rn);
                    q[2] = std::max(0.0f, Bn);
                    q[1] = std::max(0.0f, (Y - 0.299f * Rn - 0.114f * Bn) * (1.0f / 0.587f));
                }
            });
        }
        report(78);

        // ---------------- luma noise reduction: two-scale a-trous wavelet, stars excluded ------
        // Fine-scale detail is shrunk in proportion to how noise-like it is (a Wiener-style
        // gain against the sky's measured wavelet noise), so faint stars and the Milky Way's
        // structure, which stand well above it, pass. Worked in strips of rows to keep memory
        // to a few megabytes per thread at any resolution.
        if (p.noiseReduction > 0.0f) {
            const float amount = clampf(p.noiseReduction, 0.0f, 1.0f);
            const std::vector<uint8_t> protect = dilatedMask(maskRaw, hw, hh, 2);
            static const float kB3[5] = {1.0f / 16, 4.0f / 16, 6.0f / 16, 4.0f / 16, 1.0f / 16};
            const int strip = 128, margin = 8;
            const int nStrips = (h + strip - 1) / strip;
            float sig1 = 0.0f, sig2 = 0.0f;
            std::vector<std::vector<float>> s1((size_t)nStrips), s2((size_t)nStrips);
            // While the strips are rewritten, a strip's margin rows belong to its neighbours,
            // which may already have changed them: the apply pass reads margins from this copy
            // of the luma around every boundary b (rows b*strip - margin .. b*strip + margin).
            std::vector<float> edges;
            auto run = [&](int s, bool apply) {
                try {
                    const int y0 = s * strip, y1 = std::min(h, y0 + strip);
                    const int ya = std::max(0, y0 - margin), yb = std::min(h, y1 + margin);
                    const int n = yb - ya;
                    std::vector<float> Y((size_t)n * w), t((size_t)n * w), c1((size_t)n * w),
                        c2((size_t)n * w);
                    for (int y = ya; y < yb; ++y) {
                        float* d = &Y[(size_t)(y - ya) * w];
                        if (apply && (y < y0 || y >= y1)) {
                            const int b = y < y0 ? s : s + 1;
                            const float* e = &edges[((size_t)b * 2 * margin + (size_t)(y - (b * strip - margin))) * w];
                            std::copy(e, e + w, d);
                            continue;
                        }
                        const float* row = &img[(size_t)y * w * 3];
                        for (int x = 0; x < w; ++x) d[x] = lumaOf(row + (size_t)x * 3);
                    }
                    auto blur = [&](const std::vector<float>& in, std::vector<float>& out, int step) {
                        for (int r = 0; r < n; ++r) {
                            const float* ir = &in[(size_t)r * w];
                            float* tr = &t[(size_t)r * w];
                            for (int x = 0; x < w; ++x) {
                                float a = 0.0f;
                                for (int k = -2; k <= 2; ++k) a += kB3[k + 2] * ir[clampi(x + k * step, 0, w - 1)];
                                tr[x] = a;
                            }
                        }
                        for (int r = 0; r < n; ++r) {
                            float* orow = &out[(size_t)r * w];
                            for (int x = 0; x < w; ++x) {
                                float a = 0.0f;
                                for (int k = -2; k <= 2; ++k)
                                    a += kB3[k + 2] * t[(size_t)clampi(r + k * step, 0, n - 1) * w + x];
                                orow[x] = a;
                            }
                        }
                    };
                    blur(Y, c1, 1);
                    blur(c1, c2, 2);
                    for (int y = y0; y < y1; ++y) {
                        const uint8_t* prow = &protect[(size_t)std::min(hh - 1, y >> 1) * hw];
                        float* row = &img[(size_t)y * w * 3];
                        const size_t base = (size_t)(y - ya) * w;
                        for (int x = 0; x < w; ++x) {
                            if (prow[std::min(hw - 1, x >> 1)]) continue;
                            const float yv = Y[base + x], c1v = c1[base + x], c2v = c2[base + x];
                            const float w1 = yv - c1v, w2 = c1v - c2v;
                            if (!apply) {
                                if ((x % 3) == 0 && groundFine(x, y) < 0.05f) {
                                    s1[(size_t)s].push_back(std::fabs(w1));
                                    s2[(size_t)s].push_back(std::fabs(w2));
                                }
                                continue;
                            }
                            auto shrink = [amount](float v, float sig) {
                                const float l2 = 2.25f * sig * sig;  // (1.5 sigma)^2
                                const float keep = v * v / (v * v + l2 + 1e-12f);
                                return v * (1.0f - amount * (1.0f - keep));
                            };
                            const float yn = std::max(0.0f, c2v + shrink(w1, sig1) + shrink(w2, sig2));
                            // Scaling a near-black pixel up would multiply whatever colour noise
                            // it holds into a coloured speck: beyond 4x the rest is added as grey.
                            const float sc = yv > kTiny ? std::min(yn / yv, 4.0f) : 0.0f;
                            const float add = yn - sc * yv;
                            float* q = row + (size_t)x * 3;
                            q[0] = q[0] * sc + add; q[1] = q[1] * sc + add; q[2] = q[2] * sc + add;
                        }
                    }
                } catch (...) {}
            };
            parallel_for(0, nStrips, [&](int s) {
                if (s % 4 == 1) run(s, false);
            });
            std::vector<float> a1, a2;
            for (int s = 0; s < nStrips; ++s) {
                a1.insert(a1.end(), s1[(size_t)s].begin(), s1[(size_t)s].end());
                a2.insert(a2.end(), s2[(size_t)s].begin(), s2[(size_t)s].end());
            }
            if (a1.size() > 1000) {
                try {
                    edges.assign((size_t)nStrips * 2 * margin * w, 0.0f);
                } catch (...) {}  // out of memory: no noise reduction rather than the race
            }
            if (!edges.empty()) {
                sig1 = 1.4826f * median_of(a1);
                sig2 = 1.4826f * median_of(a2);
                parallel_for(1, nStrips, [&](int b) {
                    for (int k = 0; k < 2 * margin; ++k) {
                        const int y = b * strip - margin + k;
                        if (y < 0 || y >= h) continue;
                        const float* row = &img[(size_t)y * w * 3];
                        float* d = &edges[((size_t)b * 2 * margin + k) * w];
                        for (int x = 0; x < w; ++x) d[x] = lumaOf(row + (size_t)x * 3);
                    }
                });
                parallel_for(0, nStrips, [&](int s) { run(s, true); });
                SLOG("postprocess_v2: noise reduction %.2f (wavelet sigma %.4f %.4f)", amount, sig1, sig2);
            }
        }
        // ---------------- optional star sharpening (as classic) ----------------
        report(86);
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

        report(90);

        // ---------------- colour-preserving clip + sRGB gamma ----------------
        parallel_for(0, h, [&](int y) {
            float* row = &img[(size_t)y * w * 3];
            for (int x = 0; x < w; ++x) {
                float* q = row + (size_t)x * 3;
                const float mx = std::max(q[0], std::max(q[1], q[2]));
                const float sc = mx > 1.0f ? 1.0f / mx : 1.0f;
                for (int c = 0; c < 3; ++c) {
                    float v = q[c] * sc;
                    if (!(v > 0.0f)) v = 0.0f;
                    q[c] = (v <= 0.0031308f) ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
                }
            }
        });
        report(100);
    } catch (...) {
        SLOGE("postprocess_v2: unexpected exception, output may be partially processed");
    }
}

}  // namespace starcam

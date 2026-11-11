// post2.cpp — the v2 look (PostParams::version 2). Same contract as postprocess(): linear sRGB
// in, display-referred sRGB out, in place, no exceptions escape.
//
// What it does differently from the classic pipeline (post.cpp), and why:
//  - It finds the landscape: a star-free region, darker than the sky, along the edge that faces
//    the ground (from the phone's orientation, or searched for along every edge). Classic fitted
//    its gradient polynomial through the dark ground too, which lifted the ground to a flat grey
//    and left the sky above the horizon uneven.
//  - Light pollution is modelled as a smooth surface under the sky (a cubic B-spline grid per
//    channel), fitted to the sky between the stars - cells cut by the horizon included, where the
//    glow is strongest - with the cells that have texture (the Milky Way, lit cloud) left out and
//    the rest judged again and again, so the surface follows the background, a town's light dome
//    included, and not the band. The landscape is left out of it.
//  - The black point comes from the sky background, not from the darkest pixels of the frame
//    (the ground, when there is one), so the sky is no longer grey. Sky and ground each get their
//    own stretch, blended across a feathered horizon.
//  - The background is neutralized with offsets, which leave star colours alone; the chroma
//    denoise no longer runs over stars; colours beyond white are scaled down, not clipped, so
//    bright stars keep their hue.
//  - A light wavelet noise reduction on the luma, stars excluded.
//  - For the editing studio: the Milky Way's structure made stronger and stars made smaller,
//    both measured against a star-free copy of the sky.

#include "post.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

#include "post_util.h"

namespace starcam {
namespace {

using postutil::dilatedMask;
using postutil::kTiny;
using postutil::lumaOf;
using postutil::smoothstepf;

// Solves the n x n system A x = b for three right-hand sides (one per channel) by Gaussian
// elimination with partial pivoting. A (n*n) and B (n*3), row-major, are destroyed; out gets the
// solution channel by channel (out[c*n + r]). False when singular.
bool solve_dense(std::vector<double>& A, std::vector<double>& B, int n, std::vector<double>* out) {
    for (int col = 0; col < n; ++col) {
        int piv = col;
        for (int r = col + 1; r < n; ++r)
            if (std::fabs(A[(size_t)r * n + col]) > std::fabs(A[(size_t)piv * n + col])) piv = r;
        if (std::fabs(A[(size_t)piv * n + col]) < 1e-18) return false;
        if (piv != col) {
            for (int k = 0; k < n; ++k) std::swap(A[(size_t)col * n + k], A[(size_t)piv * n + k]);
            for (int k = 0; k < 3; ++k) std::swap(B[(size_t)col * 3 + k], B[(size_t)piv * 3 + k]);
        }
        for (int r = col + 1; r < n; ++r) {
            const double f = A[(size_t)r * n + col] / A[(size_t)col * n + col];
            if (f == 0.0) continue;
            for (int k = col; k < n; ++k) A[(size_t)r * n + k] -= f * A[(size_t)col * n + k];
            for (int k = 0; k < 3; ++k) B[(size_t)r * 3 + k] -= f * B[(size_t)col * 3 + k];
        }
    }
    out->assign((size_t)3 * n, 0.0);
    for (int c = 0; c < 3; ++c) {
        for (int r = n - 1; r >= 0; --r) {
            double v = B[(size_t)r * 3 + c];
            for (int k = r + 1; k < n; ++k) v -= A[(size_t)r * n + k] * (*out)[(size_t)c * n + k];
            (*out)[(size_t)c * n + r] = v / A[(size_t)r * n + r];
        }
    }
    return true;
}

// Uniform cubic B-spline over [0, 1] with k >= 4 control points: the first of the four control
// points that carry weight at t, and their weights.
inline void bspline(double t, int k, int* first, double w[4]) {
    const int m = k - 3;
    double s = std::min(1.0, std::max(0.0, t)) * m;
    const int j = std::min(m - 1, (int)std::floor(s));
    s -= j;
    const double s2 = s * s, s3 = s2 * s;
    w[0] = (1.0 - 3.0 * s + 3.0 * s2 - s3) / 6.0;
    w[1] = (3.0 * s3 - 6.0 * s2 + 4.0) / 6.0;
    w[2] = (-3.0 * s3 + 3.0 * s2 + 3.0 * s + 1.0) / 6.0;
    w[3] = s3 / 6.0;
    *first = j;
}

// The sky's background as a smooth surface over the image (u, v in 0..1): a tensor-product cubic
// B-spline with kx x ky control values per channel.
struct SkySurface {
    int kx = 0, ky = 0;
    std::vector<double> c;  // 3 * kx * ky, channel-major, rows of kx

    double eval(int ch, double u, double v) const {
        int jx, jy;
        double wx[4], wy[4];
        bspline(u, kx, &jx, wx);
        bspline(v, ky, &jy, wy);
        const double* cc = &c[(size_t)ch * kx * ky];
        double s = 0.0;
        for (int b = 0; b < 4; ++b)
            for (int a = 0; a < 4; ++a) s += wy[b] * wx[a] * cc[(size_t)(jy + b) * kx + jx + a];
        return s;
    }
    double luma(double u, double v) const {
        return 0.299 * eval(0, u, v) + 0.587 * eval(1, u, v) + 0.114 * eval(2, u, v);
    }
};

// lumaOf's weights, for models fitted per channel.
constexpr double kLumaW[3] = {0.299, 0.587, 0.114};

// A patch of plain sky: its background colour and luma, and where it is (u, v in 0..1).
struct SkySample {
    double rgb[3];
    double lum, u, v;
    double mottle = 0.0;  // structure within it beyond a smooth curve, in units of its noise (0: unknown)
    int col = 0, row = 0; // its place in the grid of cells
};

// Fits the surface to the samples in [use] by penalised least squares: the data, and the
// curvature of the control grid (second differences along both axes and the twist) weighted
// relative to the data's own weight - along x by [lamX], along y by [lamY], each given for every
// control point (kx * ky, rows of kx). Control points with no sky near them (under the landscape)
// follow from the curvature alone: the surface carries on smoothly. False when too few samples
// are left.
bool fit_surface(const std::vector<SkySample>& smp, const std::vector<uint8_t>& use,
                 const std::vector<double>& lamX, const std::vector<double>& lamY, SkySurface* out) {
    const int kx = out->kx, ky = out->ky, n = kx * ky;
    std::vector<double> A((size_t)n * n, 0.0), B((size_t)n * 3, 0.0);
    int used = 0;
    for (size_t i = 0; i < smp.size(); ++i) {
        if (!use[i]) continue;
        int jx, jy;
        double wx[4], wy[4];
        bspline(smp[i].u, kx, &jx, wx);
        bspline(smp[i].v, ky, &jy, wy);
        int id[16];
        double phi[16];
        for (int b = 0; b < 4; ++b)
            for (int a = 0; a < 4; ++a) {
                id[b * 4 + a] = (jy + b) * kx + jx + a;
                phi[b * 4 + a] = wy[b] * wx[a];
            }
        for (int pp = 0; pp < 16; ++pp) {
            for (int qq = 0; qq < 16; ++qq) A[(size_t)id[pp] * n + id[qq]] += phi[pp] * phi[qq];
            for (int k = 0; k < 3; ++k) B[(size_t)id[pp] * 3 + k] += phi[pp] * smp[i].rgb[k];
        }
        ++used;
    }
    if (used < 12) return false;
    double tr = 0.0;
    for (int r = 0; r < n; ++r) tr += A[(size_t)r * n + r];
    const double unit = tr / n;
    auto pen = [&](const int* id, const double* co, int m, double wgt) {
        for (int a = 0; a < m; ++a)
            for (int b = 0; b < m; ++b) A[(size_t)id[a] * n + id[b]] += wgt * unit * co[a] * co[b];
    };
    static const double d2[3] = {1.0, -2.0, 1.0}, tw[4] = {1.0, -1.0, -1.0, 1.0};
    for (int y = 0; y < ky; ++y) {
        for (int x = 0; x < kx; ++x) {
            const size_t at = (size_t)y * kx + x;
            if (x >= 1 && x + 1 < kx) {
                const int id[3] = {y * kx + x - 1, y * kx + x, y * kx + x + 1};
                pen(id, d2, 3, lamX[at]);
            }
            if (y >= 1 && y + 1 < ky) {
                const int id[3] = {(y - 1) * kx + x, y * kx + x, (y + 1) * kx + x};
                pen(id, d2, 3, lamY[at]);
            }
            if (x + 1 < kx && y + 1 < ky) {
                const int id[4] = {y * kx + x, y * kx + x + 1, (y + 1) * kx + x, (y + 1) * kx + x + 1};
                const double lx = 0.25 * (lamX[at] + lamX[at + 1] + lamX[at + kx] + lamX[at + kx + 1]);
                const double ly = 0.25 * (lamY[at] + lamY[at + 1] + lamY[at + kx] + lamY[at + kx + 1]);
                pen(id, tw, 4, 2.0 * std::sqrt(lx * ly));
            }
        }
    }
    // A whisker of ridge: the system is never singular, whatever the sky covers.
    for (int r = 0; r < n; ++r) A[(size_t)r * n + r] += 1e-9 * tr / n + 1e-12;
    return solve_dense(A, B, n, &out->c);
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
            // Star count over five cells side by side: in the glow over a town, which hides most
            // faint stars, one cell in a few comes out empty by chance; real ground is empty
            // all along.
            float sWide = 0.0f;
            for (int k = -2; k <= 2; ++k) sWide += at(c.starFrac, clampi(u + k, 0, UW - 1), v);
            sWide *= 0.2f;
            // Above landscape already found, a cell under half the sky's brightness continues it
            // unless clearly starrier than the sky: texture, lit windows and stars mirrored in a
            // lake fool the star count, not the darkness (two such cells used to end the
            // landscape and leave the rest of it black). It never starts one: a dim lower sky
            // (uncorrected vignetting, a moonlit gradient) has stars and fails the strict test.
            const bool groundLike = (s < starCut &&
                (starry ? (dark || std::max(s, sWide) < 0.25f * starCut) : l < 0.75f * refLuma)) ||
                (d > 0 && l < 0.5f * refLuma && s < std::max(3.0f * refStar, 0.02f));
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

// Three passes of a running box over a gw x gh grid, together close to a Gaussian of [sigma]
// cells. Outside the grid counts as zero: blurring values times weights and the weights alike
// and dividing (normalized convolution) fills holes from around them and keeps the edges true.
void box_blur3(std::vector<float>& a, int gw, int gh, float sigma) {
    const int r = std::max(1, (int)std::lround((std::sqrt(4.0f * sigma * sigma + 1.0f) - 1.0f) * 0.5f));
    const float inv = 1.0f / (float)(2 * r + 1);
    std::vector<float> t(a.size());
    for (int pass = 0; pass < 3; ++pass) {
        parallel_for(0, gh, [&](int y) {
            const float* in = &a[(size_t)y * gw];
            float* o = &t[(size_t)y * gw];
            double s = 0.0;
            for (int x = 0; x <= std::min(r, gw - 1); ++x) s += in[x];
            for (int x = 0; x < gw; ++x) {
                o[x] = (float)s * inv;
                if (x + r + 1 < gw) s += in[x + r + 1];
                if (x - r >= 0) s -= in[x - r];
            }
        });
        parallel_for(0, gw, [&](int x) {
            double s = 0.0;
            for (int y = 0; y <= std::min(r, gh - 1); ++y) s += t[(size_t)y * gw + x];
            for (int y = 0; y < gh; ++y) {
                a[(size_t)y * gw + x] = (float)s * inv;
                if (y + r + 1 < gh) s += t[(size_t)(y + r + 1) * gw + x];
                if (y - r >= 0) s -= t[(size_t)(y - r) * gw + x];
            }
        });
    }
}

// Minimum (erosion) or maximum (dilation) over a (2r+1) square, separable, in place on a (w x h);
// t is scratch of the same size. The square is cut at the edges.
void min_max_filter(std::vector<float>& a, std::vector<float>& t, int w, int h, int r, bool takeMax) {
    if (r <= 0 || w <= 0 || h <= 0) return;
    parallel_for(0, h, [&](int y) {
        const float* in = &a[(size_t)y * w];
        float* o = &t[(size_t)y * w];
        for (int x = 0; x < w; ++x) {
            const int x0 = std::max(0, x - r), x1 = std::min(w - 1, x + r);
            float v = in[x0];
            for (int xx = x0 + 1; xx <= x1; ++xx) v = takeMax ? std::max(v, in[xx]) : std::min(v, in[xx]);
            o[x] = v;
        }
    });
    parallel_for(0, h, [&](int y) {
        const int y0 = std::max(0, y - r), y1 = std::min(h - 1, y + r);
        float* o = &a[(size_t)y * w];
        for (int x = 0; x < w; ++x) o[x] = t[(size_t)y0 * w + x];
        for (int yy = y0 + 1; yy <= y1; ++yy) {
            const float* in = &t[(size_t)yy * w];
            for (int x = 0; x < w; ++x) o[x] = takeMax ? std::max(o[x], in[x]) : std::min(o[x], in[x]);
        }
    });
}

// Mean over a (2r+1) square, separable, in place; t is scratch. At the edges the mean of what is
// inside (box_blur3 counts the outside as zero, which would fade a mask out along the border).
void box_mean(std::vector<float>& a, std::vector<float>& t, int w, int h, int r) {
    if (r <= 0 || w <= 0 || h <= 0) return;
    parallel_for(0, h, [&](int y) {
        const float* in = &a[(size_t)y * w];
        float* o = &t[(size_t)y * w];
        double s = 0.0;
        int n = 0;
        for (int x = 0; x <= std::min(r, w - 1); ++x, ++n) s += in[x];
        for (int x = 0; x < w; ++x) {
            o[x] = (float)(s / n);
            if (x + r + 1 < w) { s += in[x + r + 1]; ++n; }
            if (x - r >= 0) { s -= in[x - r]; --n; }
        }
    });
    parallel_for(0, w, [&](int x) {
        double s = 0.0;
        int n = 0;
        for (int y = 0; y <= std::min(r, h - 1); ++y, ++n) s += t[(size_t)y * w + x];
        for (int y = 0; y < h; ++y) {
            a[(size_t)y * w + x] = (float)(s / n);
            if (y + r + 1 < h) { s += t[(size_t)(y + r + 1) * w + x]; ++n; }
            if (y - r >= 0) { s -= t[(size_t)(y - r) * w + x]; --n; }
        }
    });
}

// The sky's local level from its own stars: a quadratic in x, y through the points' bg (each star's
// surroundings), so a sky brighter toward the horizon is not taken for something lit. Least squares
// in normalised coordinates, then twice again without the points beyond 2.5 robust sigmas; the
// median for fewer than 20 points or a fit that cannot be solved. Before the first fit the points
// beyond 2.5 robust sigmas of the median go too: a compact bright patch of sky (a Milky Way core)
// is an outlier from the median, while a smooth gradient spreads the points and keeps them. Without
// that the quadratic bent toward a central bright patch, its stars stayed in, and the patch fell
// apart into starless pieces the stars could not claim. Clamped to [0.5 x the 10th, 2 x the 90th
// percentile] of the points' bg, so it cannot run away where there are no stars (over the landscape).
struct StarSkyLevel {
    bool quad = false;
    double c[6] = {0, 0, 0, 0, 0, 0};  // 1, u, v, u^2, uv, v^2
    double cx = 0.0, cy = 0.0, inv = 1.0;  // u = (x - cx) * inv
    float level = 0.0f;                // the median of the points' bg
    float lo = 0.0f, hi = 0.0f;
    int used = 0;                      // points in the final fit

    float at(double x, double y) const {
        double v = level;
        if (quad) {
            const double u = (x - cx) * inv, w = (y - cy) * inv;
            v = c[0] + c[1] * u + c[2] * w + c[3] * u * u + c[4] * u * w + c[5] * w * w;
        }
        if (!std::isfinite(v)) v = level;
        return clampf((float)v, lo, hi);
    }
};

StarSkyLevel fit_star_sky_level(const std::vector<float>& pts) {
    StarSkyLevel s;
    const size_t n = pts.size() / 3;
    if (n == 0) return s;
    std::vector<float> bg(n), sorted(n);
    for (size_t i = 0; i < n; ++i) sorted[i] = bg[i] = pts[i * 3 + 2];
    std::sort(sorted.begin(), sorted.end());
    s.level = sorted[n / 2];
    s.lo = std::max(0.5f * sorted[(size_t)(0.10 * (double)(n - 1))], 1e-7f);
    s.hi = std::max(2.0f * sorted[(size_t)(0.90 * (double)(n - 1))], s.lo);
    s.level = clampf(s.level, s.lo, s.hi);
    s.used = (int)n;
    if (n < 20) return s;
    double mx = 0.0, my = 0.0;
    for (size_t i = 0; i < n; ++i) {
        mx += pts[i * 3];
        my += pts[i * 3 + 1];
    }
    s.cx = mx / (double)n;
    s.cy = my / (double)n;
    double ext = 1.0;
    for (size_t i = 0; i < n; ++i)
        ext = std::max(ext, std::max(std::fabs(pts[i * 3] - s.cx), std::fabs(pts[i * 3 + 1] - s.cy)));
    s.inv = 1.0 / ext;
    std::vector<uint8_t> keep(n, 1);
    std::vector<float> res(n), dev;
    {
        // The median's own outliers first (see above).
        for (size_t i = 0; i < n; ++i) dev.push_back(std::fabs(bg[i] - s.level));
        const float sigma = 1.4826f * median_of(dev);
        int kept = 0;
        for (size_t i = 0; i < n; ++i) kept += std::fabs(bg[i] - s.level) <= 2.5f * sigma ? 1 : 0;
        if (sigma > 0.0f && kept >= 20)
            for (size_t i = 0; i < n; ++i) keep[i] = std::fabs(bg[i] - s.level) <= 2.5f * sigma ? 1 : 0;
    }
    double coef[6];
    bool have = false;
    for (int round = 0; round < 3; ++round) {
        double A[6][6] = {}, B[6][3] = {}, out[3][6] = {};
        int m = 0;
        for (size_t i = 0; i < n; ++i) {
            if (!keep[i]) continue;
            const double u = (pts[i * 3] - s.cx) * s.inv, w = (pts[i * 3 + 1] - s.cy) * s.inv;
            const double f[6] = {1.0, u, w, u * u, u * w, w * w};
            for (int r = 0; r < 6; ++r) {
                for (int k = 0; k < 6; ++k) A[r][k] += f[r] * f[k];
                B[r][0] += f[r] * (double)bg[i];
            }
            ++m;
        }
        if (m < 20 || !postutil::solve6x6(A, B, out)) break;
        bool finite = true;
        for (int k = 0; k < 6; ++k) finite = finite && std::isfinite(out[0][k]);
        if (!finite) break;
        for (int k = 0; k < 6; ++k) coef[k] = out[0][k];
        have = true;
        s.used = m;
        if (round == 2) break;
        // Drop the points beyond 2.5 robust sigmas of this fit (a star on a bright patch, a star
        // whose surroundings a neighbour lit), and fit again.
        dev.clear();
        for (size_t i = 0; i < n; ++i) {
            const double u = (pts[i * 3] - s.cx) * s.inv, w = (pts[i * 3 + 1] - s.cy) * s.inv;
            res[i] = (float)((double)bg[i] - (coef[0] + coef[1] * u + coef[2] * w + coef[3] * u * u +
                                              coef[4] * u * w + coef[5] * w * w));
            if (keep[i]) dev.push_back(std::fabs(res[i]));
        }
        const float sigma = 1.4826f * median_of(dev);
        if (!(sigma > 0.0f)) break;
        for (size_t i = 0; i < n; ++i) keep[i] = std::fabs(res[i]) <= 2.5f * sigma;
    }
    if (have) {
        s.quad = true;
        for (int k = 0; k < 6; ++k) s.c[k] = coef[k];
    } else {
        s.used = (int)n;
    }
    return s;
}

}  // namespace

std::vector<float> scale_sky_points(const std::vector<float>& skyPts, int factor) {
    if (factor <= 1) return skyPts;
    std::vector<float> out = skyPts;
    const float off = 0.5f * (float)(factor - 1), inv = 1.0f / (float)factor;
    for (size_t i = 0; i + 2 < out.size(); i += 3) {
        out[i] = (out[i] - off) * inv;
        out[i + 1] = (out[i + 1] - off) * inv;
    }
    return out;
}

bool sky_lit_mask(const ImageF& rgb, const std::vector<float>& skyPts, std::vector<float>* litHalf,
                  int reduce) {
    if (litHalf == nullptr) return false;
    litHalf->clear();
    try {
        if (rgb.empty() || rgb.ch != 3 || rgb.w <= 0 || rgb.h <= 0 ||
            rgb.d.size() < (size_t)rgb.w * rgb.h * 3)
            return false;
        const int w = rgb.w, h = rgb.h;
        const int hw = std::max(1, w / 2), hh = std::max(1, h / 2);
        // The sky is where its stars are. Its level comes from their surroundings, not from the
        // frame: under a dark sky the landscape is a black silhouette, darker than the sky, and a
        // percentile of the frame (as this first did) landed on the ground and called the whole sky
        // lit. Too few stars, no sky balance.
        std::vector<float> pts;
        for (size_t i = 0; i + 2 < skyPts.size(); i += 3) {
            const float x = skyPts[i], y = skyPts[i + 1], b = skyPts[i + 2];
            if (!(x >= -0.5f && y >= -0.5f && x <= (float)w - 0.5f && y <= (float)h - 0.5f) ||
                !(b > 0.0f) || !std::isfinite(b))
                continue;
            pts.push_back(x);
            pts.push_back(y);
            pts.push_back(b);
        }
        const int nPts = (int)(pts.size() / 3);
        if (nPts < 10) {
            SLOG("sky_lit_mask: %d sky stars in the picture (10 needed): no star balance", nPts);
            return false;
        }
        const StarSkyLevel sky = fit_star_sky_level(pts);
        const float* img = rgb.d.data();
        std::vector<float> a((size_t)hw * hh), t((size_t)hw * hh);

        // Half-res luma in the units the points' bg is given in: (r + 2g + b) / 4.
        auto luma = [](const float* q) { return 0.25f * (q[0] + 2.0f * q[1] + q[2]); };
        parallel_for(0, hh, [&](int hy) {
            const int y0 = std::min(hy * 2, h - 1), y1 = std::min(y0 + 1, h - 1);
            const float* r0 = &img[(size_t)y0 * w * 3];
            const float* r1 = &img[(size_t)y1 * w * 3];
            float* o = &a[(size_t)hy * hw];
            for (int hx = 0; hx < hw; ++hx) {
                const int x0 = std::min(hx * 2, w - 1), x1 = std::min(x0 + 1, w - 1);
                o[hx] = 0.25f * (luma(r0 + (size_t)x0 * 3) + luma(r0 + (size_t)x1 * 3) +
                                 luma(r1 + (size_t)x0 * 3) + luma(r1 + (size_t)x1 * 3));
            }
        });
        // Lit. Bright and starless is not sky. An opening (erode, then dilate, radius 6) removes
        // stars and thin bright lines and keeps what is wide - lit walls, window frames, rows of
        // windows - and what stays above 1.6-2.6 times the sky's local level is bright. Brightness
        // alone cannot tell a lit pillar (3.6 times the sky on 2026-10-31) from the Milky Way's core
        // or a light dome (2-3 times), but the sky has stars in it: each connected bright region
        // holding at least 3 sky stars, at a quarter of the frame's average density of them or
        // more, is sky however bright, and takes the gains. The rest - lit landscape, cloud, the
        // Moon's glare - keeps the camera's balance. Grown by 4 and softened by 4 so the thin bright
        // parts attached to a lit area are covered too and its edge does not show.
        // A closing (dilate, then erode, radius 3) first bridges the dark gaps of a striped lit area
        // - blinds, a row of balusters - which the opening alone took apart slat by slat; a star
        // stays a star through it, and goes in the opening.
        // The radii are for the full-size picture; in a reduced one (the studio's preview) they
        // shrink with it, never below 1.
        const int red = std::max(1, reduce);
        auto radius = [red](int r) { return std::max(1, (r + red / 2) / red); };
        min_max_filter(a, t, hw, hh, radius(3), true);
        min_max_filter(a, t, hw, hh, radius(3), false);
        min_max_filter(a, t, hw, hh, radius(6), false);
        min_max_filter(a, t, hw, hh, radius(6), true);
        parallel_for(0, hh, [&](int y) {
            float* q = &a[(size_t)y * hw];
            const double fy = 2.0 * y + 0.5;  // half-res pixel u is centred on full-res 2u + 0.5
            for (int x = 0; x < hw; ++x)
                q[x] = smoothstepf(1.6f, 2.6f, q[x] / sky.at(2.0 * x + 0.5, fy));
        });
        // The bright regions (8-connected, an explicit stack) and the sky stars in each.
        const size_t np = (size_t)hw * hh;
        std::vector<int32_t> lab(np, -1);
        std::vector<int> area;
        {
            std::vector<int> stk;
            for (size_t i0 = 0; i0 < np; ++i0) {
                if (!(a[i0] > 0.0f) || lab[i0] >= 0) continue;
                const int id = (int)area.size();
                int n = 0;
                lab[i0] = id;
                stk.assign(1, (int)i0);
                while (!stk.empty()) {
                    const int i = stk.back();
                    stk.pop_back();
                    ++n;
                    const int x = i % hw, y = i / hw;
                    for (int dy = -1; dy <= 1; ++dy) {
                        const int ny = y + dy;
                        if (ny < 0 || ny >= hh) continue;
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int nx = x + dx;
                            if (nx < 0 || nx >= hw) continue;
                            const size_t j = (size_t)ny * hw + nx;
                            if (lab[j] < 0 && a[j] > 0.0f) {
                                lab[j] = id;
                                stk.push_back((int)j);
                            }
                        }
                    }
                }
                area.push_back(n);
            }
        }
        std::vector<int> stars(area.size(), 0);
        for (int k = 0; k < nPts; ++k) {
            const int hx = clampi((int)std::lround((pts[k * 3] - 0.5f) * 0.5f), 0, hw - 1);
            const int hy = clampi((int)std::lround((pts[k * 3 + 1] - 0.5f) * 0.5f), 0, hh - 1);
            const int id = lab[(size_t)hy * hw + hx];
            if (id >= 0) ++stars[(size_t)id];
        }
        const double avgDensity = (double)nPts / (double)np;
        std::vector<uint8_t> isSky(area.size(), 0);
        int nSky = 0;
        for (size_t id = 0; id < area.size(); ++id) {
            if (stars[id] >= 3 && (double)stars[id] / (double)area[id] >= 0.25 * avgDensity) {
                isSky[id] = 1;
                ++nSky;
            }
        }
        if (nSky > 0) {
            parallel_for(0, hh, [&](int y) {
                for (int x = 0; x < hw; ++x) {
                    const size_t i = (size_t)y * hw + x;
                    if (lab[i] >= 0 && isSky[(size_t)lab[i]]) a[i] = 0.0f;
                }
            });
        }
        {
            // What was decided, the largest sky regions first.
            std::vector<int> order;
            for (size_t id = 0; id < area.size(); ++id)
                if (isSky[id]) order.push_back((int)id);
            std::sort(order.begin(), order.end(), [&area](int p, int q) { return area[(size_t)p] > area[(size_t)q]; });
            std::string list;
            for (size_t k = 0; k < order.size() && k < 5; ++k) {
                char buf[64];
                snprintf(buf, sizeof(buf), "%s%d px with %d stars", k ? ", " : " (", area[(size_t)order[k]],
                         stars[(size_t)order[k]]);
                list += buf;
            }
            if (!list.empty()) list += ")";
            SLOG("sky_lit_mask: %d sky stars, sky level %.5f (%s of %d stars, held to "
                 "%.5f..%.5f), %zu bright regions, %d of them sky%s",
                 nPts, sky.level, sky.quad ? "quadratic" : "median", sky.used, sky.lo, sky.hi,
                 area.size(), nSky, list.c_str());
        }
        lab.clear();
        lab.shrink_to_fit();
        min_max_filter(a, t, hw, hh, radius(4), true);
        box_mean(a, t, hw, hh, radius(4));
        box_mean(a, t, hw, hh, radius(4));  // twice: close to a Gaussian of 3.7 px
        t.clear();
        t.shrink_to_fit();
        {
            double lit = 0.0;
            for (float v : a) lit += v;
            SLOG("sky_lit_mask: %.1f%% of the frame kept lit", 100.0 * lit / (double)a.size());
        }
        *litHalf = std::move(a);
        return true;
    } catch (...) {
        // Out of memory for the mask: the picture keeps the camera's balance.
        SLOGE("sky_lit_mask: exception, no star balance");
        litHalf->clear();
        return false;
    }
}

void apply_sky_white_balance(ImageF& rgb, float wbR, float wbB, const std::vector<float>& skyPts,
                             std::vector<float>* litHalf) {
    if (litHalf != nullptr) litHalf->clear();
    wbR = std::isfinite(wbR) ? clampf(wbR, 0.25f, 4.0f) : 1.0f;
    wbB = std::isfinite(wbB) ? clampf(wbB, 0.25f, 4.0f) : 1.0f;
    if (wbR == 1.0f && wbB == 1.0f) return;  // the camera's balance: not a bit changes
    std::vector<float> a;
    if (!sky_lit_mask(rgb, skyPts, &a)) return;
    {
        const int w = rgb.w, h = rgb.h;
        const int hw = std::max(1, w / 2), hh = std::max(1, h / 2);
        float* img = rgb.d.data();
        // The gains, with wSky = 1 - lit sampled bilinearly (half-res pixel u is centred on
        // full-res 2u + 0.5) and wHi from the pixel itself.
        const float gR = wbR - 1.0f, gB = wbB - 1.0f;
        parallel_for(0, h, [&](int y) {
            const float v = clampf(0.5f * (float)y - 0.25f, 0.0f, (float)(hh - 1));
            const int v0 = (int)v, v1 = std::min(v0 + 1, hh - 1);
            const float fv = v - (float)v0;
            const float* l0 = &a[(size_t)v0 * hw];
            const float* l1 = &a[(size_t)v1 * hw];
            float* row = &img[(size_t)y * w * 3];
            for (int x = 0; x < w; ++x) {
                float* q = row + (size_t)x * 3;
                const float wHi = 1.0f - smoothstepf(0.75f, 0.95f, std::max(q[0], std::max(q[1], q[2])));
                if (!(wHi > 0.0f)) continue;
                const float u = clampf(0.5f * (float)x - 0.25f, 0.0f, (float)(hw - 1));
                const int u0 = (int)u, u1 = std::min(u0 + 1, hw - 1);
                const float fu = u - (float)u0;
                const float lit = (l0[u0] * (1.0f - fu) + l0[u1] * fu) * (1.0f - fv) +
                                  (l1[u0] * (1.0f - fu) + l1[u1] * fu) * fv;
                const float wgt = (1.0f - lit) * wHi;
                if (!(wgt > 0.0f)) continue;
                q[0] *= 1.0f + wgt * gR;
                q[2] *= 1.0f + wgt * gB;
            }
        });
    }
    if (litHalf != nullptr) *litHalf = std::move(a);
}

namespace {

// Where a star may be, for the stars' white balance, at half resolution: what stands out of the
// sky round it and is small.
//  - Stands out: the half-res luma is more than 4 noise sigmas (the noise growing with the level,
//    as light's own does) above the median of 16 pixels on a ring round it, a 170th of the long
//    side away (24 px of the picture at 12 MP, outside a bright star's skirt). Inside anything
//    wider than the ring - a lit wall, a window, a bank of cloud, the Milky Way's band - the ring
//    lies on the thing itself and nothing stands out; on its edge half the ring does, and the
//    median is the thing's level. (Blocks of the frame were tried for "the sky round it" first:
//    along every roof and frame their medians mix sky and landscape, and the whole rim of a lit
//    wall then stood out of them like a row of stars.)
//  - Small: not part of anything a square 13 pixels across fits into here (26 px of the picture
//    at 12 MP; a bright star's skirt is 20 across): a lamp's glare, a small lit window. Stars that
//    touch each other in a rich field stay stars: no square fits into them.
//  - Not long and thin: the lit edge of a roof against the sky, a cable (a piece longer than 12
//    pixels here that is 3 times as long as wide, or fills under a third of its box, on the slant).
//  - In the sky on every side: of the pixels round the piece's box, 3 away, fewer than a fifth
//    (and fewer than 3) are bright against the piece's own sky without being a star's
//    themselves. The corner of a lit window or a chimney's top stands out of its ring as a
//    star does - three quarters of that ring are sky - but the rest of the window is right
//    beside it.
// A star's skirt (2 more pixels) is in.
std::vector<uint8_t> star_like_mask(const float* img, int w, int h, int hw, int hh, float sigma, float skyLuma) {
    std::vector<float> hl((size_t)hw * hh, 0.0f);
    parallel_for(0, hh, [&](int hy) {
        const int y0 = std::min(h - 1, hy * 2), y1 = std::min(h - 1, y0 + 1);
        float* o = &hl[(size_t)hy * hw];
        for (int hx = 0; hx < hw; ++hx) {
            const int x0 = std::min(w - 1, hx * 2), x1 = std::min(w - 1, x0 + 1);
            o[hx] = 0.25f * (lumaOf(img + ((size_t)y0 * w + x0) * 3) + lumaOf(img + ((size_t)y0 * w + x1) * 3) +
                             lumaOf(img + ((size_t)y1 * w + x0) * 3) + lumaOf(img + ((size_t)y1 * w + x1) * 3));
        }
    });
    const double ringR = std::max(5.0, (double)std::max(w, h) / 340.0);
    int ringX[16], ringY[16];
    for (int k = 0; k < 16; ++k) {
        ringX[k] = (int)std::lround(ringR * std::cos(k * M_PI / 8.0));
        ringY[k] = (int)std::lround(ringR * std::sin(k * M_PI / 8.0));
    }
    // Four pixels averaged carry about 0.7 of one pixel's noise (the demosaic shares it out).
    const float sgH = 0.7f * sigma, skyL = std::max(skyLuma, kTiny);
    auto standsOut = [sgH, skyL](float bg) { return 4.0f * sgH * std::sqrt(std::max(1.0f, bg / skyL)); };
    // The sky round a pixel: the median of its ring. False in the frame's very corner, where
    // there is too little of a ring to judge by.
    auto skyRound = [&](int hx, int hy, float* bg) {
        float v[16];
        int n = 0;
        for (int k = 0; k < 16; ++k) {
            const int sx = hx + ringX[k], sy = hy + ringY[k];
            if (sx < 0 || sy < 0 || sx >= hw || sy >= hh) continue;
            v[n++] = hl[(size_t)sy * hw + sx];
        }
        if (n < 8) return false;
        std::nth_element(v, v + n / 2, v + n);
        *bg = v[n / 2];
        return true;
    };
    std::vector<uint8_t> hot((size_t)hw * hh, 0);
    parallel_for(0, hh, [&](int hy) {
        uint8_t* o = &hot[(size_t)hy * hw];
        for (int hx = 0; hx < hw; ++hx) {
            float bg;
            if (skyRound(hx, hy, &bg)) o[hx] = hl[(size_t)hy * hw + hx] - bg > standsOut(bg) ? 1 : 0;
        }
    });
    // Small: the opening by a square 2r + 1 across (eroded through the complement, grown back,
    // and 2 further so the rim of the thing is not left over), taken out.
    const int r = std::max(4, (int)std::lround((double)std::max(w, h) / 680.0));
    std::vector<uint8_t> inv(hot.size());
    for (size_t i = 0; i < hot.size(); ++i) inv[i] = hot[i] ? 0 : 1;
    std::vector<uint8_t> er = dilatedMask(inv, hw, hh, r);
    inv.clear();
    inv.shrink_to_fit();
    for (size_t i = 0; i < er.size(); ++i) er[i] = er[i] ? 0 : 1;
    const std::vector<uint8_t> broad = dilatedMask(er, hw, hh, r + 2);
    er.clear();
    er.shrink_to_fit();
    for (size_t i = 0; i < hot.size(); ++i) hot[i] = hot[i] && !broad[i];
    // Piece by piece (pixels that touch, corners included): judge(piece, x0, x1, y0, y1) says
    // whether it goes; those that go are taken out once all have been judged, so that no piece
    // is judged by what became of its neighbour.
    auto dropPieces = [&](const std::function<bool(const std::vector<uint32_t>&, int, int, int, int)>& judge) {
        std::vector<uint8_t> seen(hot.size(), 0);
        std::vector<uint32_t> piece, todo, gone;
        for (size_t s0 = 0; s0 < hot.size(); ++s0) {
            if (!hot[s0] || seen[s0]) continue;
            piece.clear();
            todo.assign(1, (uint32_t)s0);
            seen[s0] = 1;
            int x0 = hw, x1 = -1, y0 = hh, y1 = -1;
            while (!todo.empty()) {
                const uint32_t i = todo.back();
                todo.pop_back();
                piece.push_back(i);
                const int x = (int)(i % (uint32_t)hw), y = (int)(i / (uint32_t)hw);
                x0 = std::min(x0, x);
                x1 = std::max(x1, x);
                y0 = std::min(y0, y);
                y1 = std::max(y1, y);
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= hw || ny >= hh) continue;
                        const size_t j = (size_t)ny * hw + nx;
                        if (!hot[j] || seen[j]) continue;
                        seen[j] = 1;
                        todo.push_back((uint32_t)j);
                    }
                }
            }
            if (judge(piece, x0, x1, y0, y1)) gone.insert(gone.end(), piece.begin(), piece.end());
        }
        for (uint32_t i : gone) hot[i] = 0;
    };
    // Not long and thin.
    dropPieces([](const std::vector<uint32_t>& piece, int x0, int x1, int y0, int y1) {
        const int a = std::max(x1 - x0 + 1, y1 - y0 + 1), b = std::min(x1 - x0 + 1, y1 - y0 + 1);
        return a > 12 && (a >= 3 * b || 3 * piece.size() < (size_t)a * (size_t)b);
    });
    // In the sky on every side.
    {
        std::vector<float> bgs;
        dropPieces([&](const std::vector<uint32_t>& piece, int x0, int x1, int y0, int y1) {
            // The piece's own sky: the median of the sky round its pixels (64 of them at most).
            bgs.clear();
            const size_t stride = std::max<size_t>(1, piece.size() / 64);
            for (size_t k = 0; k < piece.size(); k += stride) {
                float bg;
                if (skyRound((int)(piece[k] % (uint32_t)hw), (int)(piece[k] / (uint32_t)hw), &bg)) bgs.push_back(bg);
            }
            if (bgs.empty()) return true;
            const float bg = median_of(bgs), lim = bg + standsOut(bg);
            int around = 0, bright = 0;
            auto look = [&](int x, int y) {
                if (x < 0 || y < 0 || x >= hw || y >= hh) return;
                ++around;
                const size_t j = (size_t)y * hw + x;
                if (!hot[j] && hl[j] > lim) ++bright;
            };
            for (int x = x0 - 3; x <= x1 + 3; ++x) {
                look(x, y0 - 3);
                look(x, y1 + 3);
            }
            for (int y = y0 - 2; y <= y1 + 2; ++y) {
                look(x0 - 3, y);
                look(x1 + 3, y);
            }
            return bright >= std::max(3, around / 5);
        });
    }
    hl.clear();
    hl.shrink_to_fit();
    std::vector<uint8_t> out = dilatedMask(hot, hw, hh, 2);
    for (size_t i = 0; i < out.size(); ++i) out[i] = out[i] && !broad[i];
    return out;
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

        // ---------------- the stars' white balance: where it may not act ----------------
        // The gains (p.wbR, p.wbB: white as the stars themselves show it) are applied just before
        // the stretch, to a star's own light: what is small and stands out of the sky right beside
        // it. Here, before anything changes the picture, where they may not act whatever stands
        // out: wbLit is what is bright and starless - a lit landscape, which the camera's balance
        // was right for - at half resolution. With gains of 1, or too few sky stars, there is no
        // balance and not a bit of the look changes.
        const float wbGR = std::isfinite(p.wbR) ? clampf(p.wbR, 0.25f, 4.0f) : 1.0f;
        const float wbGB = std::isfinite(p.wbB) ? clampf(p.wbB, 0.25f, 4.0f) : 1.0f;
        std::vector<float> wbLit;
        const bool starBalance =
            (wbGR != 1.0f || wbGB != 1.0f) && sky_lit_mask(rgb, p.skyPts, &wbLit, p.skyReduce);
        if (starBalance) SLOG("postprocess_v2: star balance, gains %.2f %.2f", wbGR, wbGB);

        // ---------------- half-res luma, star mask (as classic) ----------------
        const int hw = std::max(1, w / 2), hh = std::max(1, h / 2);
        // How much of a pixel the star balance keeps as lit (only asked when there is a balance).
        const bool litKnown = starBalance && wbLit.size() == (size_t)hw * hh;
        auto litAt = [&](int x, int y) -> float {
            return wbLit[(size_t)std::min(hh - 1, y >> 1) * hw + std::min(hw - 1, x >> 1)];
        };
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

        // ---------------- sky background: light pollution as a smooth surface ----------------
        // Samples: cells about a 24th of the long edge, sky pixels only - a cell cut by the
        // horizon counts with its sky part, placed where that part is, since the glow is
        // strongest just above the horizon - stars masked. A cell's value is the mean of its
        // middle 40% by luma: the sky between the stars, unbiased by a gradient across the cell.
        // Cells with texture (the Milky Way, lit cloud) are left out; a first model, free along
        // the height but nearly straight across, tells the cells something smooth lifts above the
        // background - glow when it rises from the horizon, a town's light dome; then the B-spline
        // surface, fitted and re-judged in turn, follows the background: free to bend with the
        // glow's steep rise and round a dome near the horizon, stiff from side to side higher up,
        // where the band is.
        SkySurface surf;
        double ref[3] = {0, 0, 0};
        bool fitted = false;
        if (p.gradientRemoval && p.gradientStrength > 0.0f) {
            const int cs = std::max(16, (int)std::lround(std::max(w, h) / 24.0));
            const int gx = (w + cs - 1) / cs, gy = (h + cs - 1) / cs;
            std::vector<std::vector<SkySample>> rowSamples((size_t)gy);
            parallel_for(0, gy, [&](int cy) {
                try {
                    struct Px { float l; int x, y; };
                    std::vector<Px> v;
                    std::vector<float> diffs;
                    const int ys = cy * cs, ye = std::min(h, ys + cs);
                    for (int cx = 0; cx < gx; ++cx) {
                        const int xs = cx * cs, xe = std::min(w, xs + cs);
                        v.clear();
                        diffs.clear();
                        int seen = 0;
                        for (int y = ys; y < ye; y += 2) {
                            const int hy = std::min(hh - 1, y >> 1);
                            const float* row = &img[(size_t)y * w * 3];
                            float prev = -1.0f;
                            for (int x = xs; x < xe; x += 2) {
                                ++seen;
                                if (groundAt(x, y) > 0.01f || mask2[(size_t)hy * hw + std::min(hw - 1, x >> 1)]) {
                                    prev = -1.0f;
                                    continue;
                                }
                                const float l = lumaOf(row + (size_t)x * 3);
                                if (prev >= 0.0f) diffs.push_back(std::fabs(l - prev));
                                prev = l;
                                v.push_back({l, x, y});
                            }
                        }
                        if ((int)v.size() < std::max(24, seen / 5)) continue;
                        // Its texture: the medians of 4 x 4 parts of it, less the best quadratic
                        // through them (a glow's smooth curve), against what noise alone would
                        // leave (from the differences between neighbouring samples). Light
                        // pollution is smooth; the Milky Way and lit cloud are not. A cell the
                        // horizon cuts has parts with too little sky: unknown.
                        double mottle = 0.0;
                        if (diffs.size() > 64) {
                            std::vector<float> part[16];
                            const int cw = std::max(1, xe - xs), ch = std::max(1, ye - ys);
                            for (const Px& q : v) part[std::min(3, (q.y - ys) * 4 / ch) * 4 + std::min(3, (q.x - xs) * 4 / cw)].push_back(q.l);
                            double A6[6][6] = {}, B6[6][3] = {}, co[3][6];
                            double med[16];
                            int minN = 1 << 30;
                            bool ok = true;
                            for (int k = 0; k < 16 && ok; ++k) {
                                if (part[k].size() < 8) { ok = false; break; }
                                minN = std::min(minN, (int)part[k].size());
                                med[k] = median_of(part[k]);
                                const double X = (k % 4) - 1.5, Y = (k / 4) - 1.5;
                                const double ph[6] = {1.0, X, Y, X * Y, X * X, Y * Y};
                                for (int a = 0; a < 6; ++a) {
                                    for (int b = 0; b < 6; ++b) A6[a][b] += ph[a] * ph[b];
                                    B6[a][0] += ph[a] * med[k];
                                }
                            }
                            if (ok && postutil::solve6x6(A6, B6, co)) {
                                std::vector<float> res;
                                for (int k = 0; k < 16; ++k) {
                                    const double X = (k % 4) - 1.5, Y = (k / 4) - 1.5;
                                    const double m = co[0][0] + co[0][1] * X + co[0][2] * Y + co[0][3] * X * Y + co[0][4] * X * X + co[0][5] * Y * Y;
                                    res.push_back((float)std::fabs(med[k] - m));
                                }
                                const float sigPix = 1.4826f * median_of(diffs) / std::sqrt(2.0f);
                                const float sigPart = 1.2533f * sigPix / std::sqrt((float)minN);
                                mottle = sigPart > 0.0f ? 1.4826 * median_of(res) / sigPart : 0.0;
                            }
                        }
                        std::sort(v.begin(), v.end(), [](const Px& a, const Px& b) { return a.l < b.l; });
                        const size_t i0 = v.size() * 3 / 10, i1 = std::max(i0 + 1, v.size() * 7 / 10);
                        SkySample sm{};
                        double sx = 0.0, sy = 0.0;
                        for (size_t i = i0; i < i1; ++i) {
                            const float* q = &img[((size_t)v[i].y * w + v[i].x) * 3];
                            for (int k = 0; k < 3; ++k) sm.rgb[k] += q[k];
                            sm.lum += v[i].l;
                        }
                        // Placed at the centre of all its sky pixels, not of the whole cell.
                        for (const Px& q : v) {
                            sx += q.x;
                            sy += q.y;
                        }
                        const double inv = 1.0 / (double)(i1 - i0);
                        for (int k = 0; k < 3; ++k) sm.rgb[k] *= inv;
                        sm.lum *= inv;
                        sm.u = w > 1 ? sx / (double)v.size() / (double)(w - 1) : 0.5;
                        sm.v = h > 1 ? sy / (double)v.size() / (double)(h - 1) : 0.5;
                        sm.mottle = mottle;
                        sm.col = cx;
                        sm.row = cy;
                        rowSamples[(size_t)cy].push_back(sm);
                    }
                } catch (...) {}
            });
            std::vector<SkySample> cells;
            for (const auto& r : rowSamples) cells.insert(cells.end(), r.begin(), r.end());
            // Structured: well above the texture of a typical cell (plain sky, mostly). Too few
            // cells to judge (a tiny frame): no cell is either.
            float mottleCut = 0.0f;
            {
                std::vector<float> m;
                for (const SkySample& c : cells)
                    if (c.mottle > 0.0) m.push_back((float)c.mottle);
                if (m.size() >= 16) mottleCut = std::max(3.0f, 2.5f * median_of(m));
            }
            auto structured = [&](const SkySample& c) { return mottleCut > 0.0f && c.mottle > mottleCut; };
            auto smooth = [&](const SkySample& c) { return mottleCut > 0.0f && c.mottle > 0.0 && c.mottle <= mottleCut; };
            // Next to structure: the band's smooth fringe, never taken for a dome.
            std::vector<uint8_t> fringe(cells.size(), 0);
            {
                std::vector<uint8_t> grid((size_t)gx * gy, 0);
                for (const SkySample& c : cells)
                    if (structured(c)) grid[(size_t)c.row * gx + c.col] = 1;
                for (size_t i = 0; i < cells.size(); ++i)
                    for (int dy = -1; dy <= 1; ++dy)
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int cx = cells[i].col + dx, cy = cells[i].row + dy;
                            if (cx >= 0 && cy >= 0 && cx < gx && cy < gy && grid[(size_t)cy * gx + cx]) fringe[i] = 1;
                        }
            }
            std::vector<uint8_t> use(cells.size(), 1);
            for (size_t i = 0; i < cells.size(); ++i) use[i] = !structured(cells[i]);
            const int down = ground.found ? ground.up.down : (p.downEdge >= 0 ? p.downEdge : 0);
            const bool sideways = down == 1 || down == 3;
            // The cells column by column (upright), from the ground's edge up.
            std::vector<std::vector<int>> columns((size_t)(sideways ? gy : gx));
            {
                std::vector<int> order(cells.size());
                for (size_t i = 0; i < cells.size(); ++i) order[i] = (int)i;
                auto upOf = [&](const SkySample& c) {
                    switch (down) {
                        case 1: return c.col;
                        case 2: return c.row;
                        case 3: return gx - 1 - c.col;
                        default: return gy - 1 - c.row;
                    }
                };
                std::sort(order.begin(), order.end(), [&](int a, int b) { return upOf(cells[(size_t)a]) < upOf(cells[(size_t)b]); });
                for (int i : order) columns[(size_t)(sideways ? cells[(size_t)i].row : cells[(size_t)i].col)].push_back(i);
            }
            // A cell is background unless it has structure, stands well above the model (by the
            // scatter of the cells below it, which the band - only ever adding light - cannot
            // widen) or, with structure, far below it (a piece of landscape, a dark cloud). A
            // smooth one standing high is glow all the same when it rises straight from the
            // horizon, cell on cell, away from the band: a town's light dome.
            // Under the band the surface only guesses, bridging from the sky on either side; the
            // band can only add light, so wherever the guess comes out above one of the cells the
            // band lifted, that cell holds it down (dark lanes at its foot had turned black where
            // the bridge rose toward a dome beside it).
            std::vector<uint8_t> lifted;
            auto classify = [&](const std::function<double(const SkySample&)>& model, double floor) {
                std::vector<float> res(cells.size()), below;
                for (size_t i = 0; i < cells.size(); ++i) {
                    res[i] = (float)(cells[i].lum - model(cells[i]));
                    if (res[i] < 0.0f) below.push_back(-res[i]);
                }
                const float sig = std::max(below.empty() ? 0.0f : 1.4826f * median_of(below), (float)floor);
                for (size_t i = 0; i < cells.size(); ++i)
                    use[i] = !structured(cells[i]) && res[i] < 3.0f * sig && (res[i] > -4.0f * sig || smooth(cells[i]));
                if (lifted.empty()) {
                    lifted.assign(cells.size(), 0);
                    for (size_t i = 0; i < cells.size(); ++i) lifted[i] = structured(cells[i]) && res[i] > 0.0f;
                } else {
                    for (size_t i = 0; i < cells.size(); ++i)
                        if (lifted[i] && res[i] < 0.0f) use[i] = 1;
                }
                for (const std::vector<int>& colm : columns) {
                    for (size_t k = 0; k < colm.size(); ++k) {
                        const SkySample& c = cells[(size_t)colm[k]];
                        const bool high = res[(size_t)colm[k]] >= 3.0f * sig;
                        // The cell the horizon cuts has too little sky to judge its texture:
                        // it counts with the one above it.
                        const bool unknown = c.mottle <= 0.0;
                        const bool glow = high && !fringe[(size_t)colm[k]] &&
                                          (smooth(c) || (unknown && k == 0 && k + 1 < colm.size() &&
                                                         smooth(cells[(size_t)colm[k + 1]])));
                        if (!high) {
                            if (k == 0) continue;
                            break;
                        }
                        if (!glow) break;
                        use[(size_t)colm[k]] = 1;
                    }
                }
            };
            // The first pass, the glow as a model to judge the cells by: light pollution changes
            // mostly with height above the horizon and only gently from side to side, so a model
            // free in height (a B-spline of eight control points) but only quadratic across, fitted
            // without the textured cells, follows the glow however steep it rises over a town, yet
            // not a band crossing the sky.
            const int kh = 8, nq = 2 * kh + 1;
            auto phiOf = [&](const SkySample& c, double* phi) {
                const double hgt = sideways ? c.u : c.v, across = 2.0 * (sideways ? c.v : c.u) - 1.0;
                int j;
                double wh[4];
                bspline(hgt, kh, &j, wh);
                for (int k = 0; k < nq; ++k) phi[k] = 0.0;
                // A level and a side-to-side slope for every height, and one curve across.
                for (int k = 0; k < 4; ++k) {
                    phi[j + k] = wh[k];
                    phi[kh + j + k] = wh[k] * across;
                }
                phi[2 * kh] = across * across;
            };
            // Fitted to the lower quarter of the cells (quantile regression by reweighted least
            // squares): the band, which only adds light, hardly pulls on it.
            std::vector<double> q;
            std::vector<double> wgt(cells.size(), 1.0);
            const double tau = 0.25;
            double medLum = 0.0;
            {
                std::vector<float> l;
                for (const SkySample& c : cells) l.push_back((float)c.lum);
                medLum = l.empty() ? 0.0 : median_of(l);
            }
            for (size_t i = 0; i < cells.size(); ++i)
                if (structured(cells[i])) wgt[i] = 0.0;
            for (int iter = 0; iter < 10 && cells.size() >= 16; ++iter) {
                std::vector<double> A((size_t)nq * nq, 0.0), B((size_t)nq * 3, 0.0), sol;
                double phi[24];
                for (size_t i = 0; i < cells.size(); ++i) {
                    if (wgt[i] == 0.0) continue;
                    phiOf(cells[i], phi);
                    for (int a = 0; a < nq; ++a) {
                        if (phi[a] == 0.0) continue;
                        for (int b = 0; b < nq; ++b) A[(size_t)a * nq + b] += wgt[i] * phi[a] * phi[b];
                        for (int k = 0; k < 3; ++k) B[(size_t)a * 3 + k] += wgt[i] * phi[a] * cells[i].rgb[k];
                    }
                }
                double tr = 0.0;
                for (int r = 0; r < nq; ++r) tr += A[(size_t)r * nq + r];
                // A little curvature penalty along the height, on the levels and the slopes.
                for (int base = 0; base <= kh; base += kh) {
                    for (int k = 1; k + 1 < kh; ++k) {
                        const int id[3] = {base + k - 1, base + k, base + k + 1};
                        const double co[3] = {1.0, -2.0, 1.0};
                        for (int a = 0; a < 3; ++a)
                            for (int b = 0; b < 3; ++b) A[(size_t)id[a] * nq + id[b]] += 0.02 * tr / nq * co[a] * co[b];
                    }
                }
                for (int r = 0; r < nq; ++r) A[(size_t)r * nq + r] += 1e-9 * tr / nq + 1e-12;
                // The levels along the height are kept convex: glow falls away from the horizon,
                // steeply and then gently (or rises toward the moon), and never humps the way a
                // band lying along the horizon does. Where the fit humps, the bend is held straight,
                // the worst place first.
                std::vector<uint8_t> straight((size_t)kh, 0);
                bool solved = false;
                for (int pass = 0; pass < kh; ++pass) {
                    std::vector<double> Ak = A, Bk = B;
                    for (int k = 1; k + 1 < kh; ++k) {
                        if (!straight[(size_t)k]) continue;
                        const int id[3] = {k - 1, k, k + 1};
                        const double co[3] = {1.0, -2.0, 1.0};
                        for (int a = 0; a < 3; ++a)
                            for (int b = 0; b < 3; ++b) Ak[(size_t)id[a] * nq + id[b]] += 1e4 * tr / nq * co[a] * co[b];
                    }
                    if (!solve_dense(Ak, Bk, nq, &sol)) break;
                    solved = true;
                    int worst = -1;
                    double most = 1e-3 * medLum;
                    for (int k = 1; k + 1 < kh; ++k) {
                        double d2 = 0.0;
                        for (int c = 0; c < 3; ++c)
                            d2 += kLumaW[c] * (sol[(size_t)c * nq + k - 1] - 2.0 * sol[(size_t)c * nq + k] + sol[(size_t)c * nq + k + 1]);
                        if (-d2 > most) {
                            most = -d2;
                            worst = k;
                        }
                    }
                    if (worst < 0) break;
                    straight[(size_t)worst] = 1;
                }
                if (!solved) break;
                q.swap(sol);
                const double eps = 1e-3 * std::max(medLum, 1e-6);
                for (size_t i = 0; i < cells.size(); ++i) {
                    phiOf(cells[i], phi);
                    double m[3] = {0, 0, 0};
                    for (int k = 0; k < 3; ++k)
                        for (int a = 0; a < nq; ++a) m[k] += q[(size_t)k * nq + a] * phi[a];
                    const double r = cells[i].lum - (0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2]);
                    if (!structured(cells[i])) wgt[i] = (r > 0.0 ? tau : 1.0 - tau) / std::max(std::fabs(r), eps);
                }
            }
            if (!q.empty()) {
                classify([&](const SkySample& c) {
                    double phi[24], m[3] = {0, 0, 0};
                    phiOf(c, phi);
                    for (int k = 0; k < 3; ++k)
                        for (int a = 0; a < nq; ++a) m[k] += q[(size_t)k * nq + a] * phi[a];
                    return 0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2];
                }, 2e-3 * medLum);
            }
            // The surface: control points a ninth of the short side apart along the height, a
            // little wider across. Bending along the height costs little, across a great deal: the
            // glow rises steeply over a town but changes only gently from side to side, and a band
            // standing up from the horizon would need a sharp bend across.
            const int shortSide = std::max(1, std::min(w, h));
            auto controls = [shortSide](int len, double perShort) {
                return clampi((int)std::lround(perShort * len / shortSide), 6, 14);
            };
            const int kHeight = controls(sideways ? w : h, 9.0), kAcross = controls(sideways ? h : w, 7.5);
            surf.kx = sideways ? kHeight : kAcross;
            surf.ky = sideways ? kAcross : kHeight;
            const double lamH = 0.02, lamA = 30.0;
            // Toward the horizon the stiffness across gives way - as free as along the height up
            // to a tenth of the frame above it, as stiff as everywhere else from 45% up - so the
            // surface bends round a town's light dome there. (Freer and higher, it took too much
            // of the band's foot, where it can only guess the sky under the band.)
            double horizonUp = 0.0;  // the horizon's height above the ground's edge, 0..1
            if (ground.found) {
                std::vector<float> hz;
                const float uh = (float)ground.up.uh();
                for (float r : ground.horizon)
                    if (r < uh) hz.push_back(1.0f - r / uh);
                if (!hz.empty()) horizonUp = median_of(hz);
            }
            const size_t nc = (size_t)surf.kx * surf.ky;
            std::vector<double> lamX(nc), lamY(nc);
            for (int y = 0; y < surf.ky; ++y) {
                for (int x = 0; x < surf.kx; ++x) {
                    const int j = sideways ? x : y, k = sideways ? surf.kx : surf.ky;
                    const double t = std::min(1.0, std::max(0.0, (double)(j - 1) / (double)(k - 3)));
                    const double up = (down == 0 || down == 3) ? 1.0 - t : t;
                    double e = std::min(1.0, std::max(0.0, (up - horizonUp - 0.1) / 0.35));
                    e = e * e * (3.0 - 2.0 * e);
                    const double across = lamH + (lamA - lamH) * e;
                    const size_t at = (size_t)y * surf.kx + x;
                    lamX[at] = sideways ? lamH : across;
                    lamY[at] = sideways ? across : lamH;
                }
            }
            for (int iter = 0; iter < 3 && cells.size() >= 16; ++iter) {
                SkySurface trial = surf;
                if (!fit_surface(cells, use, lamX, lamY, &trial)) break;
                surf = trial;
                fitted = true;
                // Judged again against the surface, which follows the glow more closely.
                if (iter < 2) classify([&surf](const SkySample& c) { return surf.luma(c.u, c.v); }, 2e-3 * medLum);
            }
            if (fitted) {
                // Taken down to the darkest of the modelled sky: light pollution only ever adds.
                ref[0] = ref[1] = ref[2] = 1e9;
                for (const SkySample& c : cells)
                    for (int k = 0; k < 3; ++k) ref[k] = std::min(ref[k], surf.eval(k, c.u, c.v));
                int kept = 0;
                for (uint8_t u : use) kept += u;
                SLOG("postprocess_v2: sky surface %dx%d from %d of %zu cells", surf.kx, surf.ky, kept, cells.size());
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
                const double V = (h > 1) ? (double)y / (h - 1) : 0.5;
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
                    if (fitted) skyL = (float)surf.luma(w > 1 ? (double)x / (w - 1) : 0.5, V);
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
            // The surface's weights along a row, worked out once for every column.
            std::vector<int> jxs((size_t)w);
            std::vector<double> wxs((size_t)w * 4);
            for (int x = 0; x < w; ++x) bspline(w > 1 ? (double)x / (w - 1) : 0.5, surf.kx, &jxs[(size_t)x], &wxs[(size_t)x * 4]);
            // The glow is in the air in front of the sky, not on the ground: the landscape is
            // left as it is and gets its own black point below.
            parallel_for(0, h, [&](int y) {
                int jy;
                double wy[4];
                bspline(h > 1 ? (double)y / (h - 1) : 0.5, surf.ky, &jy, wy);
                // The surface along this row: the control rows folded into one per channel.
                std::vector<double> rowC((size_t)3 * surf.kx, 0.0);
                for (int k = 0; k < 3; ++k)
                    for (int cx = 0; cx < surf.kx; ++cx)
                        for (int b = 0; b < 4; ++b)
                            rowC[(size_t)k * surf.kx + cx] += wy[b] * surf.c[((size_t)k * surf.ky + jy + b) * surf.kx + cx];
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    const float sky = 1.0f - groundFine(x, y);
                    if (sky <= 0.0f) continue;
                    const int jx = jxs[(size_t)x];
                    const double* wx = &wxs[(size_t)x * 4];
                    float* q = row + (size_t)x * 3;
                    for (int k = 0; k < 3; ++k) {
                        const double* rc = &rowC[(size_t)k * surf.kx + jx];
                        const double m = wx[0] * rc[0] + wx[1] * rc[1] + wx[2] * rc[2] + wx[3] * rc[3];
                        q[k] -= sky * (float)(s * (m - ref[k]));
                    }
                }
            });
            SLOG("postprocess_v2: sky background removed");
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

        // ---------------- the stars' white balance ----------------
        // The gains act on a star's own light: where a star may be (star_like_mask), on what
        // stands out of the sky right beside the pixel - the median of 16 pixels on a ring round
        // it that are no star's themselves - from 1.5 noise sigmas above that sky and fully from
        // 4. A ring that lies on the star itself (a wide star, a small picture) is tried again
        // further out, twice; a pixel with no sky within reach even then (the heart of a
        // cluster) has nothing to be judged against and is left as it is.
        //
        // The first design scaled everything above the sky's one neutral level instead. On the
        // phone's photo of 2026-10-31 that left the brightest lit walls alone but moved a third of
        // the frame by 2-7 levels of 255: a sky is not one colour all over, so its glow went
        // greener, its darkest part bluer, and dimly lit brick redder. Now the sky, its glow and
        // the landscape come out as they do without gains, and only the stars take their colours;
        // what is broad in the sky does too - the Milky Way's band keeps the camera's colour until
        // there are dark-sky frames to judge it on.
        //
        // And only where the sky's own stars are around: within 3 times their usual distance from
        // one another of a star that followed the sky (p.skyPts). A row of lit houses has small
        // bright things all over it - a window's frame, a lamp, leaves in its light - and no such
        // star for hundreds of pixels.
        //
        // Done here, before the stretch, as a pass of its own: everything measured above was
        // measured without it, and a pixel that is read (never a star's) is never one that is
        // written (always a star's), so the rows can go side by side.
        if (litKnown) {
            const float starSig = std::max(skySig, kTiny), skyLuma = std::max(lumaOf(skyMed), kTiny);
            const std::vector<uint8_t> starLike = star_like_mask(img, w, h, hw, hh, starSig, skyLuma);
            // Near a sky star, on a grid of cells a 128th of the long side across.
            const int cell = std::max(4, (int)std::lround((double)std::max(w, h) / 128.0));
            const int gw = (w + cell - 1) / cell, gh = (h + cell - 1) / cell;
            std::vector<uint8_t> nearSky((size_t)gw * gh, 0);
            {
                std::vector<std::pair<float, float>> pts;
                for (size_t i = 0; i + 2 < p.skyPts.size(); i += 3) {
                    const float sx = p.skyPts[i], sy = p.skyPts[i + 1];
                    if (sx >= 0.0f && sy >= 0.0f && sx < (float)w && sy < (float)h) pts.push_back({sx, sy});
                }
                std::vector<float> nn;
                for (size_t i = 0; i < pts.size(); ++i) {
                    float best = 1e30f;
                    for (size_t j = 0; j < pts.size(); ++j) {
                        if (j == i) continue;
                        const float dx = pts[i].first - pts[j].first, dy = pts[i].second - pts[j].second;
                        best = std::min(best, dx * dx + dy * dy);
                    }
                    if (best < 1e30f) nn.push_back(std::sqrt(best));
                }
                const float longSide = (float)std::max(w, h);
                const float reach = clampf(3.0f * median_of(nn), longSide / 16.0f, longSide / 4.0f);
                const int rc = (int)std::ceil(reach / (float)cell);
                for (const auto& s : pts) {
                    const int cx = (int)(s.first / (float)cell), cy = (int)(s.second / (float)cell);
                    for (int gy = std::max(0, cy - rc); gy <= std::min(gh - 1, cy + rc); ++gy) {
                        for (int gx = std::max(0, cx - rc); gx <= std::min(gw - 1, cx + rc); ++gx) {
                            const float dx = ((float)gx + 0.5f) * (float)cell - s.first;
                            const float dy = ((float)gy + 0.5f) * (float)cell - s.second;
                            if (dx * dx + dy * dy <= reach * reach) nearSky[(size_t)gy * gw + gx] = 1;
                        }
                    }
                }
            }
            if (starLike.size() == (size_t)hw * hh) {
                const double ringR = std::max(10.0, (double)std::max(w, h) / 170.0);
                constexpr int kRings = 3;
                const double ringScale[kRings] = {1.0, 1.6, 2.5};
                int ringX[kRings][16], ringY[kRings][16];
                for (int g = 0; g < kRings; ++g) {
                    for (int k = 0; k < 16; ++k) {
                        ringX[g][k] = (int)std::lround(ringScale[g] * ringR * std::cos(k * M_PI / 8.0));
                        ringY[g][k] = (int)std::lround(ringScale[g] * ringR * std::sin(k * M_PI / 8.0));
                    }
                }
                auto starAt = [&](int x, int y) -> bool {
                    return starLike[(size_t)std::min(hh - 1, y >> 1) * hw + std::min(hw - 1, x >> 1)] != 0;
                };
                parallel_for(0, h, [&](int y) {
                    float* row = &img[(size_t)y * w * 3];
                    const uint8_t* nearRow = &nearSky[(size_t)std::min(gh - 1, y / cell) * gw];
                    for (int x = 0; x < w; ++x) {
                        if (!starAt(x, y) || !nearRow[std::min(gw - 1, x / cell)]) continue;
                        const float sky = 1.0f - groundFine(x, y);
                        if (sky <= 0.0f) continue;
                        float* q = row + (size_t)x * 3;
                        // A clipped core stays white (wHi), its unclipped skirt takes the colour.
                        const float wHi = 1.0f - smoothstepf(0.75f, 0.95f, std::max(q[0], std::max(q[1], q[2])));
                        const float wgt = sky * (1.0f - litAt(x, y)) * wHi;
                        if (!(wgt > 0.0f)) continue;
                        float vr[16], vb[16], vl[16];
                        int n = 0;
                        for (int g = 0; g < kRings && n < 6; ++g) {
                            n = 0;
                            for (int k = 0; k < 16; ++k) {
                                const int sx = x + ringX[g][k], sy = y + ringY[g][k];
                                if (sx < 0 || sy < 0 || sx >= w || sy >= h || starAt(sx, sy)) continue;
                                const float* s = img + ((size_t)sy * w + sx) * 3;
                                vr[n] = s[0];
                                vb[n] = s[2];
                                vl[n] = lumaOf(s);
                                ++n;
                            }
                        }
                        if (n < 6) continue;
                        std::nth_element(vr, vr + n / 2, vr + n);
                        std::nth_element(vb, vb + n / 2, vb + n);
                        std::nth_element(vl, vl + n / 2, vl + n);
                        const float bgR = vr[n / 2], bgB = vb[n / 2], bgL = vl[n / 2];
                        const float sg = starSig * std::sqrt(std::max(1.0f, bgL / skyLuma));
                        const float star = wgt * smoothstepf(1.5f * sg, 4.0f * sg, lumaOf(q) - bgL);
                        if (!(star > 0.0f)) continue;
                        q[0] = bgR + (1.0f + star * (wbGR - 1.0f)) * (q[0] - bgR);
                        q[2] = bgB + (1.0f + star * (wbGB - 1.0f)) * (q[2] - bgB);
                    }
                });
                SLOG("postprocess_v2: star balance on what is small and stands out of the sky round it: "
                     "%.2f%% of the frame (a ring of %.0f px)",
                     100.0 * (double)std::count(starLike.begin(), starLike.end(), (uint8_t)1) / (double)starLike.size(),
                     ringR);
            }
        }

        parallel_for(0, h, [&](int y) {
            float* row = &img[(size_t)y * w * 3];
            for (int x = 0; x < w; ++x) {
                float* q = row + (size_t)x * 3;
                const float g = groundFine(x, y);
                // The neutralizing offsets are the sky's; the landscape keeps its own colour.
                const float sky = 1.0f - g;
                float in[3] = {q[0] - sky * off[0], q[1] - sky * off[1], q[2] - sky * off[2]};
                for (int c = 0; c < 3; ++c) in[c] *= wb[c];
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
        // The colour a pixel is given is the average of its surroundings without the stars in
        // them (each cell's own pixels that are no star's, the cells then smoothed by how many
        // those are). With the stars in, every star lent its colour to a ring 10-14 px out: seen
        // nowhere while all stars were the sky's own blue, a dull red ring round the brightest
        // orange star (7-11 levels of red on a sky at 1) once the stars had their colours.
        if (p.chromaDenoise) {
            const int qw = std::max(1, (w + 3) / 4), qh = std::max(1, (h + 3) / 4);
            std::vector<float> cbP((size_t)qw * qh), crP((size_t)qw * qh), wP((size_t)qw * qh), tmpP((size_t)qw * qh);
            // Stars and a ring around them keep their own chroma, and lend it to nothing.
            const std::vector<uint8_t> m3 = dilatedMask(maskRaw, hw, hh, 2);
            parallel_for(0, qh, [&](int qy) {
                const int ys = qy * 4, ye = std::min(ys + 4, h);
                for (int qx = 0; qx < qw; ++qx) {
                    const int xs = qx * 4, xe = std::min(xs + 4, w);
                    float sr = 0, sg2 = 0, sb = 0;
                    int n = 0;
                    for (int y = ys; y < ye; ++y) {
                        const float* q = &img[((size_t)y * w + xs) * 3];
                        const uint8_t* mrow = &m3[(size_t)std::min(hh - 1, y >> 1) * hw];
                        for (int x = xs; x < xe; ++x, q += 3) {
                            if (mrow[std::min(hw - 1, x >> 1)]) continue;
                            sr += q[0];
                            sg2 += q[1];
                            sb += q[2];
                            ++n;
                        }
                    }
                    // Sums, not means: the smoothing below weighs a cell by its pixels.
                    const float Y = 0.299f * sr + 0.587f * sg2 + 0.114f * sb;
                    cbP[(size_t)qy * qw + qx] = 0.564f * (sb - Y);
                    crP[(size_t)qy * qw + qx] = 0.713f * (sr - Y);
                    wP[(size_t)qy * qw + qx] = (float)n;
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
            box3(wP); box3(wP);
            std::vector<float> keep((size_t)hw * hh);
            for (size_t i = 0; i < keep.size(); ++i) keep[i] = m3[i] ? 1.0f : 0.0f;
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
                    // Under a sixteenth of a pixel's worth of sky around: nothing to take a colour from.
                    const float wsum = bil(wP);
                    if (!(wsum > 0.0625f)) continue;
                    const float Y = lumaOf(q);
                    const float cbO = 0.564f * (q[2] - Y), crO = 0.713f * (q[0] - Y);
                    const float cbv = k * cbO + (1 - k) * bil(cbP) / wsum, crv = k * crO + (1 - k) * bil(crP) / wsum;
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
        // ---------------- Milky Way detail and smaller stars (the editing studio's) ------------
        // Both work against the sky without its stars: luma on a coarse grid (about 800 cells
        // along the long edge) on a perceptual scale (square root), star and landscape pixels left
        // out and the holes they leave filled from around them. Sizes are fractions of the frame,
        // so the studio's small preview shows what the saved photo will.
        const float detail = clampf(p.detail, 0.0f, 1.0f), smaller = clampf(p.starReduction, 0.0f, 1.0f);
        if (detail > 0.0f || smaller > 0.0f) {
            const int longEdge = std::max(w, h);
            const int f = std::max(1, longEdge / 800);
            const int gw = (w + f - 1) / f, gh = (h + f - 1) / f;
            const std::vector<uint8_t> starArea = dilatedMask(maskRaw, hw, hh, 2);
            std::vector<float> num((size_t)gw * gh, 0.0f), den((size_t)gw * gh, 0.0f);
            parallel_for(0, gh, [&](int gy) {
                for (int gx = 0; gx < gw; ++gx) {
                    float sum = 0.0f;
                    int n = 0, all = 0;
                    for (int y = gy * f; y < std::min(h, gy * f + f); ++y) {
                        const uint8_t* mrow = &starArea[(size_t)std::min(hh - 1, y >> 1) * hw];
                        const float* row = &img[(size_t)y * w * 3];
                        for (int x = gx * f; x < std::min(w, gx * f + f); ++x) {
                            ++all;
                            if (mrow[std::min(hw - 1, x >> 1)] || groundFine(x, y) > 0.5f) continue;
                            sum += std::sqrt(std::max(0.0f, lumaOf(row + (size_t)x * 3)));
                            ++n;
                        }
                    }
                    if (n > 0) {
                        const size_t i = (size_t)gy * gw + gx;
                        den[i] = (float)n / (float)all;
                        num[i] = sum / (float)all;
                    }
                }
            });
            // The sky smoothed at [frac] of the long edge; cells too far from any sky stay -1.
            auto smoothed = [&](float frac) {
                std::vector<float> a = num, b = den;
                const float sg = std::max(0.5f, frac * (float)longEdge / (float)f);
                box_blur3(a, gw, gh, sg);
                box_blur3(b, gw, gh, sg);
                for (size_t i = 0; i < a.size(); ++i) a[i] = b[i] > 1e-3f ? a[i] / b[i] : -1.0f;
                return a;
            };
            const std::vector<float> fine = smoothed(0.0015f);
            // Detail: the structure between a few pixels and a fiftieth of the frame - dust lanes,
            // star clouds - made stronger; the band's overall glow and the pixel noise are not.
            // Only where the sky is brighter than its plain background (the Milky Way, not the
            // empty sky, whose faint blotches would come up with it).
            std::vector<float> band;
            if (detail > 0.0f) {
                band = smoothed(0.02f);
                std::vector<float> v;
                for (size_t i = 0; i < fine.size(); i += 3)
                    if (fine[i] >= 0.0f) v.push_back(fine[i]);
                const float plain = v.empty() ? 0.0f : median_of(v);
                for (size_t i = 0; i < band.size(); ++i) {
                    const float lift = smoothstepf(1.05f * plain, 1.4f * plain, band[i]);
                    band[i] = fine[i] >= 0.0f && band[i] >= 0.0f ? lift * (fine[i] - band[i]) : 0.0f;
                }
            }
            // A grid value at a pixel, bilinear over the corners that have sky.
            auto sample = [&](const std::vector<float>& g, int x, int y, float none) {
                const float fx = clampf(((float)x + 0.5f) / (float)f - 0.5f, 0.0f, (float)(gw - 1));
                const float fy = clampf(((float)y + 0.5f) / (float)f - 0.5f, 0.0f, (float)(gh - 1));
                const int x0 = (int)fx, y0 = (int)fy, x1 = std::min(x0 + 1, gw - 1), y1 = std::min(y0 + 1, gh - 1);
                const float tx = fx - (float)x0, ty = fy - (float)y0;
                const float v[4] = {g[(size_t)y0 * gw + x0], g[(size_t)y0 * gw + x1], g[(size_t)y1 * gw + x0],
                                    g[(size_t)y1 * gw + x1]};
                const float wt[4] = {(1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty};
                float s = 0.0f, ws = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    if (v[k] < 0.0f) continue;
                    s += wt[k] * v[k];
                    ws += wt[k];
                }
                return ws > 1e-4f ? s / ws : none;
            };
            // Smaller stars: each star's profile steepened against its own peak - the core kept,
            // the wings pulled down toward the sky around it - so it shrinks and fades without a
            // dark hole. Judged over a round 5x5 at full size, 3x3 on small frames (the preview).
            // Read from a copy: the rows change meanwhile.
            const int reach = longEdge >= 3000 ? 2 : 1;
            const float steep = 3.0f * smaller;
            std::vector<float> L;
            if (steep > 0.0f) {
                L.resize((size_t)w * h);
                parallel_for(0, h, [&](int y) {
                    const float* row = &img[(size_t)y * w * 3];
                    for (int x = 0; x < w; ++x) L[(size_t)y * w + x] = lumaOf(row + (size_t)x * 3);
                });
            }
            const float gain = 1.6f * detail;
            parallel_for(0, h, [&](int y) {
                const uint8_t* mrow = &starArea[(size_t)std::min(hh - 1, y >> 1) * hw];
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    const float sky = 1.0f - groundFine(x, y);
                    if (sky <= 0.0f) continue;
                    float* q = row + (size_t)x * 3;
                    const float Y = L.empty() ? lumaOf(q) : L[(size_t)y * w + x];
                    float Yn = Y;
                    if (!L.empty() && mrow[std::min(hw - 1, x >> 1)]) {
                        float mn = Y, mx = Y;
                        for (int dy = -reach; dy <= reach; ++dy) {
                            const float* lr = &L[(size_t)clampi(y + dy, 0, h - 1) * w];
                            for (int dx = -reach; dx <= reach; ++dx) {
                                if (dx * dx + dy * dy > 5) continue;
                                const float v = lr[clampi(x + dx, 0, w - 1)];
                                mn = std::min(mn, v);
                                mx = std::max(mx, v);
                            }
                        }
                        // Never below the sky here: what is under the star stays.
                        const float bg = sample(fine, x, y, -1.0f);
                        const float base = std::max(mn, bg >= 0.0f ? std::min(Y, bg * bg) : Y);
                        if (Y > base && mx > base) {
                            const float t = (Y - base) / (mx - base);
                            Yn = Y + sky * ((Y - base) * std::pow(t, steep) + base - Y);
                        }
                    }
                    if (gain > 0.0f) {
                        const float P = std::sqrt(std::max(0.0f, Yn)) + gain * sky * sample(band, x, y, 0.0f);
                        Yn = P > 0.0f ? P * P : 0.0f;
                    }
                    if (Yn == Y) continue;
                    // Colour kept: scaled where there is luma to scale, the rest added as grey.
                    const float sc = Y > 1e-5f ? std::min(Yn / Y, 4.0f) : 0.0f;
                    const float add = Yn - sc * Y;
                    q[0] = std::max(0.0f, q[0] * sc + add);
                    q[1] = std::max(0.0f, q[1] * sc + add);
                    q[2] = std::max(0.0f, q[2] * sc + add);
                }
            });
            SLOG("postprocess_v2: detail %.2f, smaller stars %.2f (grid %dx%d)", detail, smaller, gw, gh);
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

        // ---------------- vignette (the editing studio's) ----------------
        // The corners darkened so the eye stays on the sky: an ellipse fitted to the frame, the
        // middle untouched, the very corners up to 60% darker.
        const float vig = clampf(p.vignette, 0.0f, 1.0f);
        if (vig > 0.0f) {
            parallel_for(0, h, [&](int y) {
                const float dy = h > 1 ? 2.0f * (float)y / (float)(h - 1) - 1.0f : 0.0f;
                float* row = &img[(size_t)y * w * 3];
                for (int x = 0; x < w; ++x) {
                    const float dx = w > 1 ? 2.0f * (float)x / (float)(w - 1) - 1.0f : 0.0f;
                    const float g = 1.0f - 0.6f * vig * smoothstepf(0.15f, 1.0f, 0.5f * (dx * dx + dy * dy));
                    float* q = row + (size_t)x * 3;
                    q[0] *= g;
                    q[1] *= g;
                    q[2] *= g;
                }
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

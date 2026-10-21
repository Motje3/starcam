// stars.cpp — background grid estimation + star detection on half-res luma. PLAN.md §6.1.
//
// Pipeline:
//   1. gridCells x gridCells background grid: per-cell median + MAD*1.4826 sigma
//      (cells larger than 4096 px are subsampled every 2nd pixel in each axis).
//   2. bg(x,y) / sigma(x,y) via bilinear interpolation of cell centers.
//   3. Threshold mask: luma > bg + sigmaK*sigma.
//   4. 8-connected components via iterative flood fill (explicit stack, no recursion).
//   5. Filter by area and by aspect (sqrt of eigenvalue ratio of the flux-weighted
//      second-moment matrix); flux-weighted centroid; FWHM = 2.355*sqrt((mxx+myy)/2).
//   6. SkyStats from the medians of the per-cell medians/sigmas.
//   7. Sort by flux descending, truncate to maxStars.
//
// No exceptions escape detect_stars(); failures log and return empty results.

#include "stars.h"

#include <limits>

namespace starcam {

namespace {

// Bilinear interpolator over the background cell grid. Cell (cx, cy) covers pixels
// [cx*w/G, (cx+1)*w/G) x [cy*h/G, (cy+1)*h/G); for near-uniform cells the center of cell c
// sits at (c + 0.5) * (dim/G) - 0.5 in pixel coordinates, so the continuous cell coordinate
// of pixel x is u = (x + 0.5) * G/dim - 0.5. Edges clamp (constant extrapolation).
struct BgGrid {
    int G = 1;
    float fx = 0.0f, fy = 0.0f;  // G/w, G/h
    std::vector<float> med;      // G*G cell medians
    std::vector<float> sig;      // G*G cell sigmas (MAD * 1.4826)

    void sample(float x, float y, float* bg, float* noise) const {
        float u = (x + 0.5f) * fx - 0.5f;
        float v = (y + 0.5f) * fy - 0.5f;
        int u0 = (int)std::floor(u);
        int v0 = (int)std::floor(v);
        float du = u - (float)u0;
        float dv = v - (float)v0;
        int u1 = clampi(u0 + 1, 0, G - 1);
        int v1 = clampi(v0 + 1, 0, G - 1);
        u0 = clampi(u0, 0, G - 1);
        v0 = clampi(v0, 0, G - 1);
        // Weights always sum to 1; when an index pair collapses at an edge the duplicated
        // taps make the result a clamped (constant) extrapolation.
        float w00 = (1.0f - du) * (1.0f - dv);
        float w10 = du * (1.0f - dv);
        float w01 = (1.0f - du) * dv;
        float w11 = du * dv;
        size_t i00 = (size_t)v0 * G + u0;
        size_t i10 = (size_t)v0 * G + u1;
        size_t i01 = (size_t)v1 * G + u0;
        size_t i11 = (size_t)v1 * G + u1;
        *bg = w00 * med[i00] + w10 * med[i10] + w01 * med[i01] + w11 * med[i11];
        *noise = w00 * sig[i00] + w10 * sig[i10] + w01 * sig[i01] + w11 * sig[i11];
    }
};

constexpr float kSigmaFloor = 1e-6f;  // avoids zero-sigma cells masking pure noise

void detect_stars_impl(const ImageF& luma, const DetectParams& p, std::vector<Star>* out,
                       SkyStats* stats) {
    const int w = luma.w;
    const int h = luma.h;
    const float* ld = luma.d.data();
    const size_t chs = (size_t)luma.ch;  // first channel is the luma value

    // Sanitized parameters.
    const int minArea = std::max(1, p.minArea);
    const int maxArea = std::max(minArea, p.maxArea);
    const int G = clampi(p.gridCells, 1, std::min(std::min(w, h), 512));

    // ---- 1. Per-cell median + MAD sigma -------------------------------------------------
    BgGrid grid;
    grid.G = G;
    grid.fx = (float)G / (float)w;
    grid.fy = (float)G / (float)h;
    grid.med.assign((size_t)G * G, 0.0f);
    grid.sig.assign((size_t)G * G, 0.0f);

    parallel_for(0, G * G, [&](int c) {
        // Must not throw: allocation failures are swallowed, leaving the cell at 0.
        try {
            const int cxc = c % G;
            const int cyc = c / G;
            const int x0 = (int)((int64_t)cxc * w / G);
            const int x1 = (int)((int64_t)(cxc + 1) * w / G);
            const int y0 = (int)((int64_t)cyc * h / G);
            const int y1 = (int)((int64_t)(cyc + 1) * h / G);
            const int64_t cellPx = (int64_t)(x1 - x0) * (int64_t)(y1 - y0);
            const int step = cellPx > 4096 ? 2 : 1;

            std::vector<float> vals;
            vals.reserve((size_t)((x1 - x0 + step - 1) / step) *
                         (size_t)((y1 - y0 + step - 1) / step));
            for (int y = y0; y < y1; y += step) {
                const float* row = ld + (size_t)y * w * chs;
                for (int x = x0; x < x1; x += step) {
                    float v = row[(size_t)x * chs];
                    if (std::isfinite(v)) vals.push_back(v);
                }
            }
            if (vals.empty()) return;
            const float m = median_of(vals);
            for (float& v : vals) v = std::fabs(v - m);
            const float mad = median_of(vals);
            grid.med[(size_t)c] = m;
            grid.sig[(size_t)c] = mad * 1.4826f;
        } catch (...) {
            // leave cell at 0; detection degrades gracefully
        }
    });

    // ---- 6 (early). SkyStats: medians of the cell statistics ----------------------------
    if (stats) {
        std::vector<float> meds = grid.med;
        std::vector<float> sigs = grid.sig;
        stats->bgMedian = median_of(meds);
        stats->noiseSigma = median_of(sigs);
    }

    // ---- 2+3. Threshold mask against interpolated bg/sigma surfaces ---------------------
    std::vector<uint8_t> mask((size_t)w * h, 0);
    parallel_for(0, h, [&](int y) {
        const float* row = ld + (size_t)y * w * chs;
        uint8_t* mrow = mask.data() + (size_t)y * w;
        for (int x = 0; x < w; ++x) {
            float bg, ns;
            grid.sample((float)x, (float)y, &bg, &ns);
            const float thr = bg + p.sigmaK * std::max(ns, kSigmaFloor);
            const float v = row[(size_t)x * chs];
            mrow[x] = (std::isfinite(v) && v > thr) ? (uint8_t)1 : (uint8_t)0;
        }
    });

    // ---- 4+5. Connected components (8-conn, explicit stack) + shape filters -------------
    std::vector<Star> res;
    std::vector<int> stk;
    stk.reserve(256);
    std::vector<int> pixels;      // component pixel indices, capped at maxArea + 1
    pixels.reserve((size_t)maxArea + 1);
    std::vector<float> pixw;      // per-pixel weight (luma - bg), aligned with `pixels`
    pixw.reserve((size_t)maxArea + 1);

    const size_t total = (size_t)w * h;
    for (size_t i0 = 0; i0 < total; ++i0) {
        if (!mask[i0]) continue;
        mask[i0] = 0;  // mask doubles as the visited map
        stk.clear();
        stk.push_back((int)i0);
        pixels.clear();
        int areaCount = 0;

        while (!stk.empty()) {
            const int idx = stk.back();
            stk.pop_back();
            ++areaCount;
            if ((int)pixels.size() <= maxArea) pixels.push_back(idx);
            const int x = idx % w;
            const int y = idx / w;
            for (int dy = -1; dy <= 1; ++dy) {
                const int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                for (int dx = -1; dx <= 1; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    const int nx = x + dx;
                    if (nx < 0 || nx >= w) continue;
                    const size_t nidx = (size_t)ny * w + nx;
                    if (mask[nidx]) {
                        mask[nidx] = 0;
                        stk.push_back((int)nidx);
                    }
                }
            }
        }

        if (areaCount < minArea || areaCount > maxArea) continue;

        // Flux + flux-weighted centroid.
        pixw.clear();
        double F = 0.0, Sx = 0.0, Sy = 0.0;
        for (int idx : pixels) {
            const int x = idx % w;
            const int y = idx / w;
            float bg, ns;
            grid.sample((float)x, (float)y, &bg, &ns);
            double wgt = (double)ld[(size_t)idx * chs] - (double)bg;
            if (!(wgt > 0.0)) wgt = 0.0;  // also catches NaN
            pixw.push_back((float)wgt);
            F += wgt;
            Sx += wgt * (double)x;
            Sy += wgt * (double)y;
        }
        if (!(F > 0.0) || !std::isfinite(F)) continue;
        const double cx = Sx / F;
        const double cy = Sy / F;
        if (!std::isfinite(cx) || !std::isfinite(cy)) continue;

        // Flux-weighted central second moments. The +1/12 diagonal term is the variance of
        // the unit pixel footprint; it keeps 2-px components from degenerating to a zero
        // eigenvalue (infinite aspect) and gives single-pixel stars a nonzero FWHM.
        double Sxx = 0.0, Syy = 0.0, Sxy = 0.0;
        for (size_t k = 0; k < pixels.size(); ++k) {
            const double dx = (double)(pixels[k] % w) - cx;
            const double dy = (double)(pixels[k] / w) - cy;
            const double wgt = (double)pixw[k];
            Sxx += wgt * dx * dx;
            Syy += wgt * dy * dy;
            Sxy += wgt * dx * dy;
        }
        const double mxx = Sxx / F + 1.0 / 12.0;
        const double myy = Syy / F + 1.0 / 12.0;
        const double mxy = Sxy / F;

        // Eigenvalues of [[mxx,mxy],[mxy,myy]] -> aspect = sqrt(larger/smaller).
        const double tr2 = 0.5 * (mxx + myy);
        const double det = mxx * myy - mxy * mxy;
        double disc = tr2 * tr2 - det;
        if (disc < 0.0) disc = 0.0;
        disc = std::sqrt(disc);
        const double l1 = tr2 + disc;
        double l2 = tr2 - disc;
        if (l2 < 1e-9) l2 = 1e-9;
        const double aspect = std::sqrt(l1 / l2);
        if (aspect > (double)p.maxAspect) continue;

        Star st;
        st.x = (float)cx;
        st.y = (float)cy;
        st.flux = (float)F;
        st.fwhm = (float)(2.355 * std::sqrt(std::max(0.0, tr2)));
        st.area = areaCount;
        res.push_back(st);
    }

    // ---- 7. Brightest first, cap at maxStars --------------------------------------------
    std::sort(res.begin(), res.end(),
              [](const Star& a, const Star& b) { return a.flux > b.flux; });
    if (p.maxStars <= 0) {
        res.clear();
    } else if ((int)res.size() > p.maxStars) {
        res.resize((size_t)p.maxStars);
    }

    SLOG("detect_stars: %dx%d grid=%d stars=%d bg=%.5f sigma=%.5f", w, h, G, (int)res.size(),
         stats ? stats->bgMedian : 0.0f, stats ? stats->noiseSigma : 0.0f);

    if (out) *out = std::move(res);
}

}  // namespace

void detect_stars(const ImageF& luma, const DetectParams& p, std::vector<Star>* out,
                  SkyStats* stats) {
    if (out) out->clear();
    if (stats) *stats = SkyStats{};

    if (luma.empty() || luma.w <= 0 || luma.h <= 0 || luma.ch <= 0 ||
        luma.d.size() < (size_t)luma.w * luma.h * luma.ch) {
        SLOG("detect_stars: empty/invalid luma (%dx%d ch=%d), returning empty", luma.w, luma.h,
             luma.ch);
        return;
    }
    // Pixel indices are stored as int during flood fill; refuse absurd images.
    if ((size_t)luma.w * (size_t)luma.h > (size_t)std::numeric_limits<int>::max()) {
        SLOGE("detect_stars: image too large (%dx%d)", luma.w, luma.h);
        return;
    }

    try {
        detect_stars_impl(luma, p, out, stats);
    } catch (...) {
        SLOGE("detect_stars: exception caught, returning empty results");
        if (out) out->clear();
        if (stats) *stats = SkyStats{};
    }
}

}  // namespace starcam

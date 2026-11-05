// StarCam native engine — star-pattern alignment (PLAN.md §6.3).
// All coordinates are LUMA (half-res) coordinates. The estimated Transform maps
// REFERENCE frame coords -> CURRENT frame coords.
//
// align_stars:     predicted-position NN matching (grid hash) + flux gate -> RANSAC similarity
//                  (2-point minimal, complex formulation) as the seed -> refine.
// align_bootstrap: guess-free: the shift most stars agree on, and triangle-similarity voting over
//                  the brightest stars -> the same seed and refine; the better wins.
// refine:          every reference star (300, spread over the frame) matched where the model puts
//                  it and a homography fitted to the matches, in three rounds with the match
//                  radius shrinking 8 -> 3 -> 2 px: a RANSAC over homographies at 1 px first,
//                  then least squares (normalized DLT, trimmed); the inliers counted at inlierPx.
// align_frame:     both; the better wins.
//
// Why a homography: a frame differs from the reference by a turn of the view (the sky turning,
// or the phone settling on its mount), and a turned view is a homography. The similarity this
// fitted until 0.6.1 matched only a band of stars once the view had turned a few tenths of a
// degree: on the phone's sessions of 2026-10-31, 16-53 of 150 stars, so stacks blurred toward
// the edges and the autopilot took clear sky for cloud.

#include "align.h"

#include <cstddef>
#include <cstdint>
#include <random>
#include <unordered_map>
#include <utility>
#include <vector>

namespace starcam {

namespace {

// A matched (reference, current) point pair, in luma pixel coordinates.
struct PPair {
    double rx, ry;  // reference star position
    double cx, cy;  // current-frame star position
};

// ---------------------------------------------------------------------------
// Spatial grid hash over the current frame's stars. Cell size >= query radius,
// so a 3x3 cell neighborhood always covers the search disc -> O(1) per query.
// ---------------------------------------------------------------------------
struct StarGrid {
    float minX = 0.0f, minY = 0.0f;
    float cell = 1.0f;
    int cols = 0, rows = 0;
    std::vector<std::vector<int>> cells;

    void build(const std::vector<Star>& stars, float cellSize) {
        cols = rows = 0;
        cells.clear();
        cell = cellSize > 1.0f ? cellSize : 1.0f;

        // Bounding box over finite star positions only.
        bool haveBox = false;
        float maxX = 0.0f, maxY = 0.0f;
        for (const Star& s : stars) {
            if (!std::isfinite(s.x) || !std::isfinite(s.y)) continue;
            if (!haveBox) {
                minX = maxX = s.x;
                minY = maxY = s.y;
                haveBox = true;
            } else {
                if (s.x < minX) minX = s.x;
                if (s.x > maxX) maxX = s.x;
                if (s.y < minY) minY = s.y;
                if (s.y > maxY) maxY = s.y;
            }
        }
        if (!haveBox) return;

        // Choose grid dims; grow the cell if a pathological span would explode the table.
        // Dims are computed in double and range-checked BEFORE the int cast (cast of an
        // out-of-range double is UB).
        const double spanX = (double)maxX - (double)minX;
        const double spanY = (double)maxY - (double)minY;
        const double kMaxCells = (double)((int64_t)1 << 22);
        for (;;) {
            const double dcols = std::floor(spanX / (double)cell) + 1.0;
            const double drows = std::floor(spanY / (double)cell) + 1.0;
            if (dcols * drows <= kMaxCells) {
                cols = dcols < 1.0 ? 1 : (int)dcols;
                rows = drows < 1.0 ? 1 : (int)drows;
                break;
            }
            cell *= 2.0f;
        }
        cells.assign((size_t)cols * (size_t)rows, std::vector<int>());
        for (size_t i = 0; i < stars.size(); ++i) {
            const Star& s = stars[i];
            if (!std::isfinite(s.x) || !std::isfinite(s.y)) continue;
            int cx = clampi((int)((s.x - minX) / cell), 0, cols - 1);
            int cy = clampi((int)((s.y - minY) / cell), 0, rows - 1);
            cells[(size_t)cy * (size_t)cols + (size_t)cx].push_back((int)i);
        }
    }

    // Calls fn(index, d2) for every star within `radius` (<= cell) of (qx,qy).
    template <typename Fn>
    void within(const std::vector<Star>& stars, double qx, double qy, float radius, Fn fn) const {
        if (cols <= 0 || rows <= 0) return;
        if (!std::isfinite(qx) || !std::isfinite(qy)) return;
        const double r2 = (double)radius * (double)radius;
        // Clamp cell coords into a safe range before the int cast (query may lie far outside
        // the bounding box; the explicit distance test rejects any false candidates).
        double fx = (qx - (double)minX) / (double)cell;
        double fy = (qy - (double)minY) / (double)cell;
        if (fx < -1.0) fx = -1.0;
        if (fx > (double)cols) fx = (double)cols;
        if (fy < -1.0) fy = -1.0;
        if (fy > (double)rows) fy = (double)rows;
        const int cx = (int)std::floor(fx);
        const int cy = (int)std::floor(fy);
        const int gx0 = clampi(cx - 1, 0, cols - 1), gx1 = clampi(cx + 1, 0, cols - 1);
        const int gy0 = clampi(cy - 1, 0, rows - 1), gy1 = clampi(cy + 1, 0, rows - 1);
        for (int gy = gy0; gy <= gy1; ++gy) {
            for (int gx = gx0; gx <= gx1; ++gx) {
                for (int idx : cells[(size_t)gy * (size_t)cols + (size_t)gx]) {
                    const double dx = (double)stars[(size_t)idx].x - qx;
                    const double dy = (double)stars[(size_t)idx].y - qy;
                    const double d2 = dx * dx + dy * dy;
                    if (d2 <= r2) fn(idx, d2);
                }
            }
        }
    }

    // Index of the nearest star within `radius` (<= cell) of (qx,qy), or -1.
    int nearest(const std::vector<Star>& stars, double qx, double qy, float radius) const {
        int best = -1;
        double bestD2 = 0.0;
        within(stars, qx, qy, radius, [&](int idx, double d2) {
            if (best < 0 || d2 < bestD2) {
                best = idx;
                bestD2 = d2;
            }
        });
        return best;
    }
};

// Indices of the (up to) maxN brightest stars, brightest first.
std::vector<int> brightest_indices(const std::vector<Star>& stars, size_t maxN) {
    std::vector<int> order(stars.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = (int)i;
    const size_t keep = maxN < order.size() ? maxN : order.size();
    std::partial_sort(order.begin(), order.begin() + (ptrdiff_t)keep, order.end(),
                      [&stars](int a, int b) { return stars[(size_t)a].flux > stars[(size_t)b].flux; });
    order.resize(keep);
    return order;
}

// Indices of (up to) maxN stars spread over the frame: on a 6 x 6 grid, the brightest of every
// cell first, then every cell's second brightest, and so on. The brightest stars alone can all
// be lights on the ground: on 2026-10-31, 101 of the 150 brightest sat on the houses under the
// sky, which (nearer, and the phone moving) do not move as the stars do.
std::vector<int> spread_indices(const std::vector<Star>& stars, size_t maxN) {
    constexpr int G = 6;
    float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    bool first = true;
    for (const Star& s : stars) {
        if (!std::isfinite(s.x) || !std::isfinite(s.y)) continue;
        if (first) {
            x0 = x1 = s.x;
            y0 = y1 = s.y;
            first = false;
        }
        x0 = std::min(x0, s.x);
        x1 = std::max(x1, s.x);
        y0 = std::min(y0, s.y);
        y1 = std::max(y1, s.y);
    }
    const float cw = std::max(1.0f, (x1 - x0) / G), ch = std::max(1.0f, (y1 - y0) / G);
    std::vector<std::vector<int>> cells(G * G);
    for (int i : brightest_indices(stars, stars.size())) {
        const Star& s = stars[(size_t)i];
        if (!std::isfinite(s.x) || !std::isfinite(s.y)) continue;
        const int cx = clampi((int)((s.x - x0) / cw), 0, G - 1);
        const int cy = clampi((int)((s.y - y0) / ch), 0, G - 1);
        cells[(size_t)(cy * G + cx)].push_back(i);  // brightest first within the cell
    }
    // Cells in the order of their brightest star, so every round goes brightest to faintest.
    std::vector<int> cellOrder;
    for (int c = 0; c < G * G; ++c)
        if (!cells[(size_t)c].empty()) cellOrder.push_back(c);
    std::sort(cellOrder.begin(), cellOrder.end(), [&](int a, int b) {
        return stars[(size_t)cells[(size_t)a][0]].flux > stars[(size_t)cells[(size_t)b][0]].flux;
    });
    std::vector<int> out;
    out.reserve(std::min(maxN, stars.size()));
    for (size_t rank = 0; out.size() < maxN; ++rank) {
        bool any = false;
        for (int c : cellOrder) {
            const std::vector<int>& v = cells[(size_t)c];
            if (rank >= v.size()) continue;
            any = true;
            out.push_back(v[rank]);
            if (out.size() >= maxN) break;
        }
        if (!any) break;
    }
    return out;
}

bool flux_ok(const Star& ref, const Star& cur, const AlignParams& p) {
    if (!(ref.flux > 0.0f)) return false;  // avoid divide-by-zero / NaN flux
    const float ratio = cur.flux / ref.flux;
    return ratio >= p.fluxRatioMin && ratio <= p.fluxRatioMax;
}

// Solves A x = b (n x n, row-major, n <= 8) by Gaussian elimination with partial pivoting.
bool solve_linear(const double* A, const double* b, int n, double* out) {
    double M[8][9];
    for (int r = 0; r < n; ++r) {
        for (int c = 0; c < n; ++c) M[r][c] = A[r * n + c];
        M[r][n] = b[r];
    }
    for (int col = 0; col < n; ++col) {
        int piv = col;
        double pv = std::fabs(M[col][col]);
        for (int r = col + 1; r < n; ++r) {
            const double v = std::fabs(M[r][col]);
            if (v > pv) {
                pv = v;
                piv = r;
            }
        }
        if (pv < 1e-12) return false;  // singular / ill-conditioned
        if (piv != col) {
            for (int c = col; c <= n; ++c) std::swap(M[piv][c], M[col][c]);
        }
        for (int r = col + 1; r < n; ++r) {
            const double f = M[r][col] / M[col][col];
            if (f == 0.0) continue;
            for (int c = col; c <= n; ++c) M[r][c] -= f * M[col][c];
        }
    }
    for (int r = n - 1; r >= 0; --r) {
        double s = M[r][n];
        for (int c = r + 1; c < n; ++c) s -= M[r][c] * out[c];
        out[r] = s / M[r][r];
    }
    for (int i = 0; i < n; ++i) {
        if (!std::isfinite(out[i])) return false;
    }
    return true;
}

double resid2(const Transform& T, const PPair& q) {
    double ox, oy;
    T.apply(q.rx, q.ry, &ox, &oy);
    const double dx = ox - q.cx, dy = oy - q.cy;
    const double d2 = dx * dx + dy * dy;
    return std::isfinite(d2) ? d2 : 1e30;
}

// Count inliers (residual < inlierPx) of similarity (a,b,tx,ty) over `pairs`; optionally
// collect their indices.
int count_inliers(const std::vector<PPair>& pairs, double a, double b, double tx, double ty,
                  double inl2, std::vector<size_t>* outIdx) {
    int count = 0;
    for (size_t k = 0; k < pairs.size(); ++k) {
        const PPair& q = pairs[k];
        const double ox = a * q.rx - b * q.ry + tx;
        const double oy = b * q.rx + a * q.ry + ty;
        const double dx = ox - q.cx;
        const double dy = oy - q.cy;
        if (dx * dx + dy * dy < inl2) {
            ++count;
            if (outIdx) outIdx->push_back(k);
        }
    }
    return count;
}

// Whether a similarity (a, b, tx, ty) can relate two frames of the same sky: finite, and scale
// sqrt(a^2 + b^2) within maxDev of 1. Pairing does not force one-to-one matches, so several
// reference stars paired with one current star yield a scale-0 model that fits them with zero
// residual. Accepted, its inverse later blew every transform past the int range (2026-10-21).
bool plausible_similarity(double a, double b, double tx, double ty, double maxDev) {
    if (!std::isfinite(a) || !std::isfinite(b) || !std::isfinite(tx) || !std::isfinite(ty)) {
        return false;
    }
    const double s2 = a * a + b * b;
    const double lo = 1.0 - maxDev;
    const double hi = 1.0 + maxDev;
    return s2 >= lo * lo && s2 <= hi * hi;
}

// Least-squares similarity over `pairs`. Rows per pair (ref=(x,y), cur=(u,v)):
//   [x -y 1 0] * [a b tx ty]^T = u
//   [y  x 0 1] * [a b tx ty]^T = v
bool fit_similarity(const std::vector<PPair>& pairs, double maxScaleDev, Transform* out) {
    if (pairs.size() < 2) return false;
    double ATA[16] = {0};
    double ATb[4] = {0, 0, 0, 0};
    for (const PPair& q : pairs) {
        const double r1[4] = {q.rx, -q.ry, 1.0, 0.0};
        const double r2[4] = {q.ry, q.rx, 0.0, 1.0};
        for (int ii = 0; ii < 4; ++ii) {
            for (int jj = 0; jj < 4; ++jj) ATA[ii * 4 + jj] += r1[ii] * r1[jj] + r2[ii] * r2[jj];
            ATb[ii] += r1[ii] * q.cx + r2[ii] * q.cy;
        }
    }
    double s[4];
    if (!solve_linear(ATA, ATb, 4, s) || !plausible_similarity(s[0], s[1], s[2], s[3], maxScaleDev))
        return false;
    *out = Transform::similarity(s[0], s[1], s[2], s[3]);
    return true;
}

// RANSAC (2-point minimal similarity, complex-number formulation), then a least-squares refit
// on the best model's inliers within thrPx. Only models with a plausible scale take part, so a
// false match can neither win nor survive the refit.
bool ransac_similarity(const std::vector<PPair>& pairs, const AlignParams& p, double thrPx,
                       Transform* out) {
    const size_t n = pairs.size();
    if (n < 2) return false;

    const double inl2 = thrPx * thrPx;
    std::mt19937 rng(12345u);  // fixed seed: deterministic alignment
    std::uniform_int_distribution<int> pick(0, (int)n - 1);

    double bA = 1.0, bB = 0.0, bTx = 0.0, bTy = 0.0;
    int bestCount = -1;
    const int iters = p.ransacIters > 0 ? p.ransacIters : 1;
    for (int it = 0; it < iters; ++it) {
        const int i = pick(rng);
        const int j = pick(rng);
        if (i == j) continue;  // degenerate sample: same pair twice
        const PPair& P1 = pairs[(size_t)i];
        const PPair& P2 = pairs[(size_t)j];
        const double drx = P2.rx - P1.rx;
        const double dry = P2.ry - P1.ry;
        const double den = drx * drx + dry * dry;
        if (den < 1e-12) continue;  // |r2 - r1| < 1e-6: degenerate
        const double dcx = P2.cx - P1.cx;
        const double dcy = P2.cy - P1.cy;
        // s = (c2 - c1) / (r2 - r1) in the complex plane -> s = a + b*i
        const double a = (dcx * drx + dcy * dry) / den;
        const double b = (dcy * drx - dcx * dry) / den;
        // t = c1 - s*r1
        const double tx = P1.cx - (a * P1.rx - b * P1.ry);
        const double ty = P1.cy - (b * P1.rx + a * P1.ry);
        if (!plausible_similarity(a, b, tx, ty, p.maxScaleDev)) continue;
        const int count = count_inliers(pairs, a, b, tx, ty, inl2, nullptr);
        if (count > bestCount) {
            bestCount = count;
            bA = a;
            bB = b;
            bTx = tx;
            bTy = ty;
        }
    }
    if (bestCount < 2) {
        SLOG("align: RANSAC found no usable model (%d best inliers over %zu pairs)", bestCount, n);
        return false;
    }
    *out = Transform::similarity(bA, bB, bTx, bTy);
    std::vector<size_t> inl;
    count_inliers(pairs, bA, bB, bTx, bTy, inl2, &inl);
    std::vector<PPair> sub;
    sub.reserve(inl.size());
    for (size_t k : inl) sub.push_back(pairs[k]);
    Transform refit;
    // else: keep the minimal-sample model (degenerate normal equations or an implausible
    // refit; the minimal model passed the scale check)
    if (fit_similarity(sub, p.maxScaleDev, &refit)) *out = refit;
    return true;
}

// Least-squares homography over `pairs` (normalized DLT, m[8] = 1). Both point sets are moved to
// their centroid and scaled to a mean distance of sqrt(2) first, which keeps the 8 x 8 normal
// equations well conditioned; rows per pair (ref=(x,y), cur=(u,v), normalized):
//   [x y 1 0 0 0 -u*x -u*y] * h = u
//   [0 0 0 x y 1 -v*x -v*y] * h = v
bool fit_homography(const std::vector<PPair>& pairs, Transform* out) {
    const size_t n = pairs.size();
    if (n < 4) return false;
    double mrx = 0, mry = 0, mcx = 0, mcy = 0;
    for (const PPair& q : pairs) {
        mrx += q.rx;
        mry += q.ry;
        mcx += q.cx;
        mcy += q.cy;
    }
    mrx /= (double)n;
    mry /= (double)n;
    mcx /= (double)n;
    mcy /= (double)n;
    double dr = 0, dc = 0;
    for (const PPair& q : pairs) {
        dr += std::hypot(q.rx - mrx, q.ry - mry);
        dc += std::hypot(q.cx - mcx, q.cy - mcy);
    }
    dr /= (double)n;
    dc /= (double)n;
    if (!(dr > 1e-6) || !(dc > 1e-6)) return false;
    const double sr = std::sqrt(2.0) / dr, sc = std::sqrt(2.0) / dc;

    double A[64] = {0};
    double b[8] = {0};
    for (const PPair& q : pairs) {
        const double x = (q.rx - mrx) * sr, y = (q.ry - mry) * sr;
        const double u = (q.cx - mcx) * sc, v = (q.cy - mcy) * sc;
        const double r1[8] = {x, y, 1, 0, 0, 0, -u * x, -u * y};
        const double r2[8] = {0, 0, 0, x, y, 1, -v * x, -v * y};
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 8; ++j) A[i * 8 + j] += r1[i] * r1[j] + r2[i] * r2[j];
            b[i] += r1[i] * u + r2[i] * v;
        }
    }
    double h[8];
    if (!solve_linear(A, b, 8, h)) return false;
    // h maps normalized ref -> normalized cur: undo both normalizations.
    Transform hn, normRef, unnormCur;
    for (int i = 0; i < 8; ++i) hn.m[i] = h[i];
    normRef.m[0] = sr;
    normRef.m[2] = -sr * mrx;
    normRef.m[4] = sr;
    normRef.m[5] = -sr * mry;
    unnormCur.m[0] = 1.0 / sc;
    unnormCur.m[2] = mcx;
    unnormCur.m[4] = 1.0 / sc;
    unnormCur.m[5] = mcy;
    *out = unnormCur.compose(hn.compose(normRef));
    for (double v : out->m) {
        if (!std::isfinite(v)) return false;
    }
    return true;
}

// The reference stars' bounding box: where a fitted model has to hold.
struct Box {
    double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
};

// AlignResult::coverage: the cell of an 8 x 8 grid over the box a reference point falls in, and
// the inliers counted at most kCoverCap a cell.
constexpr int kCoverGrid = 8, kCoverCap = 2;

int cover_cell(const Box& box, double x, double y) {
    const double w = std::max(1.0, box.x1 - box.x0), h = std::max(1.0, box.y1 - box.y0);
    const int cx = clampi((int)((x - box.x0) * kCoverGrid / w), 0, kCoverGrid - 1);
    const int cy = clampi((int)((y - box.y0) * kCoverGrid / h), 0, kCoverGrid - 1);
    return cy * kCoverGrid + cx;
}

int coverage_of(const std::vector<PPair>& inliers, const Box& box) {
    int counts[kCoverGrid * kCoverGrid] = {0};
    int score = 0;
    for (const PPair& q : inliers)
        if (counts[cover_cell(box, q.rx, q.ry)]++ < kCoverCap) ++score;
    return score;
}

// Whether a homography can relate two frames of the same sky over `box`: in front of the camera
// at every corner (w well above 0), no mirror, scale near 1 in the middle, and a perspective far
// below what a turn of tens of degrees gives (m[6], m[7] ~ sin(turn) / focal length, 1e-5 for
// a degree at a phone's 1400 px). A fit to a few stars in one corner can bend wildly beyond them.
bool plausible_homography(const Transform& H, const Box& box, double maxScaleDev) {
    for (double v : H.m) {
        if (!std::isfinite(v)) return false;
    }
    if (std::fabs(H.m[6]) > 3e-4 || std::fabs(H.m[7]) > 3e-4) return false;
    const double xs[2] = {box.x0, box.x1}, ys[2] = {box.y0, box.y1};
    for (double x : xs) {
        for (double y : ys) {
            if (!(H.m[6] * x + H.m[7] * y + H.m[8] > 0.5)) return false;
        }
    }
    const double x = 0.5 * (box.x0 + box.x1), y = 0.5 * (box.y0 + box.y1);
    const double X = H.m[0] * x + H.m[1] * y + H.m[2];
    const double Y = H.m[3] * x + H.m[4] * y + H.m[5];
    const double W = H.m[6] * x + H.m[7] * y + H.m[8];
    const double w2 = W * W;
    const double j00 = (H.m[0] * W - X * H.m[6]) / w2, j01 = (H.m[1] * W - X * H.m[7]) / w2;
    const double j10 = (H.m[3] * W - Y * H.m[6]) / w2, j11 = (H.m[4] * W - Y * H.m[7]) / w2;
    const double det = j00 * j11 - j01 * j10;
    const double lo = 1.0 - maxScaleDev, hi = 1.0 + maxScaleDev;
    return det >= lo * lo && det <= hi * hi;
}

// Whether `pairs` cover the frame in two directions, so the perspective terms are pinned down:
// the narrower spread (the smaller axis of their covariance) at least 60 px.
bool spread_enough(const std::vector<PPair>& pairs) {
    const double n = (double)pairs.size();
    double mx = 0, my = 0;
    for (const PPair& q : pairs) {
        mx += q.rx;
        my += q.ry;
    }
    mx /= n;
    my /= n;
    double sxx = 0, syy = 0, sxy = 0;
    for (const PPair& q : pairs) {
        sxx += (q.rx - mx) * (q.rx - mx);
        syy += (q.ry - my) * (q.ry - my);
        sxy += (q.rx - mx) * (q.ry - my);
    }
    sxx /= n;
    syy /= n;
    sxy /= n;
    const double tr2 = 0.5 * (sxx + syy);
    const double disc = std::sqrt(std::max(0.0, tr2 * tr2 - (sxx * syy - sxy * sxy)));
    return tr2 - disc >= 60.0 * 60.0;
}

constexpr size_t kMinHomographyPairs = 12;

// The model for a set of matches: a homography when there are enough of them, spread enough to
// pin it down, and it is plausible; else the least-squares similarity.
bool fit_model(const std::vector<PPair>& pairs, const Box& box, const AlignParams& p,
               Transform* out) {
    if (pairs.size() >= kMinHomographyPairs && spread_enough(pairs)) {
        Transform h;
        if (fit_homography(pairs, &h) && plausible_homography(h, box, p.maxScaleDev)) {
            *out = h;
            return true;
        }
    }
    return fit_similarity(pairs, p.maxScaleDev, out);
}

// RANSAC over homographies (4-point minimal samples) at a tight threshold, then a least-squares
// refit on the winner's inliers. Where two groups of points move apart - the stars, and lights
// on the ground, nearer than the stars, while the phone shifts - one wins outright instead of a
// fit through both that suits neither: the one spread over more of the frame (the coverage),
// which is the sky's even where the lights outnumber the stars.
bool ransac_homography(const std::vector<PPair>& pairs, double thrPx, const Box& box,
                       const AlignParams& p, Transform* out) {
    const size_t n = pairs.size();
    if (n < kMinHomographyPairs || !spread_enough(pairs)) return false;
    const double t2 = thrPx * thrPx;
    std::mt19937 rng(54321u);  // fixed seed: deterministic alignment
    std::uniform_int_distribution<int> pick(0, (int)n - 1);
    std::vector<int> cellOf(n);
    for (size_t k = 0; k < n; ++k) cellOf[k] = cover_cell(box, pairs[k].rx, pairs[k].ry);
    int bestScore = -1, bestCount = 0;
    Transform best;
    std::vector<PPair> sample(4);
    const int iters = p.ransacIters > 0 ? p.ransacIters : 1;
    for (int it = 0; it < iters; ++it) {
        const int a = pick(rng), b = pick(rng), c = pick(rng), d = pick(rng);
        if (a == b || a == c || a == d || b == c || b == d || c == d) continue;
        sample[0] = pairs[(size_t)a];
        sample[1] = pairs[(size_t)b];
        sample[2] = pairs[(size_t)c];
        sample[3] = pairs[(size_t)d];
        Transform h;
        if (!fit_homography(sample, &h) || !plausible_homography(h, box, p.maxScaleDev)) continue;
        int counts[kCoverGrid * kCoverGrid] = {0};
        int score = 0, count = 0;
        for (size_t k = 0; k < n; ++k) {
            if (resid2(h, pairs[k]) >= t2) continue;
            ++count;
            if (counts[cellOf[k]]++ < kCoverCap) ++score;
        }
        if (score > bestScore || (score == bestScore && count > bestCount)) {
            bestScore = score;
            bestCount = count;
            best = h;
        }
    }
    if (bestCount < (int)kMinHomographyPairs) return false;
    std::vector<PPair> inl;
    for (const PPair& q : pairs)
        if (resid2(best, q) < t2) inl.push_back(q);
    Transform refit;
    *out = fit_homography(inl, &refit) && plausible_homography(refit, box, p.maxScaleDev) ? refit
                                                                                         : best;
    return true;
}

// What a frame is aligned against: the reference's stars spread over the frame, the grid over
// the current frame's, and the box the model must hold over.
struct Ctx {
    const std::vector<Star>& ref;
    const std::vector<Star>& cur;
    const AlignParams& p;
    std::vector<int> sel;  // up to refStars reference stars, spread over the frame
    StarGrid grid;
    Box box;

    Ctx(const std::vector<Star>& r, const std::vector<Star>& c, const AlignParams& prm)
        : ref(r), cur(c), p(prm) {
        sel = spread_indices(ref, p.refStars > 0 ? (size_t)p.refStars : ref.size());
        grid.build(cur, std::max(p.matchRadius, 8.0f));
        bool first = true;
        for (int i : sel) {
            const Star& s = ref[(size_t)i];
            if (!std::isfinite(s.x) || !std::isfinite(s.y)) continue;
            if (first) {
                box.x0 = box.x1 = s.x;
                box.y0 = box.y1 = s.y;
                first = false;
            }
            box.x0 = std::min(box.x0, (double)s.x);
            box.x1 = std::max(box.x1, (double)s.x);
            box.y0 = std::min(box.y0, (double)s.y);
            box.y1 = std::max(box.y1, (double)s.y);
        }
    }

    // Each selected reference star paired with the nearest current star within `radius` of
    // where T puts it (flux-gated).
    void guided(const Transform& T, double radius, std::vector<PPair>* out) const {
        out->clear();
        for (int ri : sel) {
            const Star& rs = ref[(size_t)ri];
            double px, py;
            T.apply((double)rs.x, (double)rs.y, &px, &py);
            const int ci = grid.nearest(cur, px, py, (float)radius);
            if (ci < 0 || !flux_ok(rs, cur[(size_t)ci], p)) continue;
            const Star& cs = cur[(size_t)ci];
            out->push_back(PPair{(double)rs.x, (double)rs.y, (double)cs.x, (double)cs.y});
        }
    }

    // From a seed: three rounds of matching where the model puts each star and refitting, the
    // radius shrinking as the model improves - the first a RANSAC over homographies at 1 px (of
    // two groups moving apart, the one over more of the frame wins), the others, and the first
    // when there are too few stars for it, least squares trimmed to the pairs the fit explains;
    // then the inliers within inlierPx.
    AlignResult refine(const Transform& seed) const {
        static const double kRadius[3] = {8.0, 3.0, 2.0};
        static const double kTrim[3] = {4.0, 1.5, 1.0};
        constexpr double kRansacPx = 1.0;
        Transform T = seed;
        std::vector<PPair> pairs, kept;
        for (int round = 0; round < 3; ++round) {
            guided(T, kRadius[round], &pairs);
            Transform fit;
            if (round == 0 && ransac_homography(pairs, kRansacPx, box, p, &fit)) {
                T = fit;
                continue;
            }
            if (!fit_model(pairs, box, p, &fit)) break;
            const double t2 = kTrim[round] * kTrim[round];
            kept.clear();
            for (const PPair& q : pairs)
                if (resid2(fit, q) < t2) kept.push_back(q);
            Transform fit2;
            if (kept.size() < pairs.size() && kept.size() >= 2 && fit_model(kept, box, p, &fit2))
                fit = fit2;
            T = fit;
        }
        AlignResult res;
        res.T = T;
        guided(T, p.inlierPx, &pairs);
        double sum2 = 0.0;
        for (const PPair& q : pairs) sum2 += resid2(T, q);
        res.inliers = (int)pairs.size();
        res.residual = pairs.empty() ? 0.0f : (float)std::sqrt(sum2 / (double)pairs.size());
        res.coverage = coverage_of(pairs, box);
        res.ok = res.inliers >= p.minInliers;
        return res;
    }
};

bool better(const AlignResult& a, const AlignResult& b) {
    if (a.ok != b.ok) return a.ok;
    if (a.coverage != b.coverage) return a.coverage > b.coverage;
    if (a.inliers != b.inliers) return a.inliers > b.inliers;
    return a.residual < b.residual;
}

// ---------------------------------------------------------------------------
// Triangle machinery for bootstrap.
// ---------------------------------------------------------------------------

// Vertices are LOCAL indices (into the brightest-N sublist), ordered by the side they sit
// OPPOSITE to after sorting side lengths ascending: va<->shortest, vb<->middle, vc<->longest.
// Similar triangles then correspond vertex-by-vertex in this order.
struct Tri {
    float u, v;  // invariant: (L1/L3, L2/L3), L1 <= L2 <= L3
    int va, vb, vc;
};

double star_dist(const Star& a, const Star& b) {
    const double dx = (double)a.x - (double)b.x;
    const double dy = (double)a.y - (double)b.y;
    return std::sqrt(dx * dx + dy * dy);
}

void build_triangles(const std::vector<Star>& stars, const std::vector<int>& sel,
                     std::vector<Tri>& out) {
    const int n = (int)sel.size();
    out.clear();
    if (n < 3) return;
    out.reserve((size_t)n * (size_t)(n - 1) * (size_t)(n - 2) / 6);
    for (int i = 0; i < n; ++i) {
        for (int j = i + 1; j < n; ++j) {
            for (int k = j + 1; k < n; ++k) {
                const Star& si = stars[(size_t)sel[(size_t)i]];
                const Star& sj = stars[(size_t)sel[(size_t)j]];
                const Star& sk = stars[(size_t)sel[(size_t)k]];
                struct SideVert {
                    double d;
                    int v;
                } sv[3] = {
                    {star_dist(sj, sk), i},  // side opposite vertex i
                    {star_dist(si, sk), j},  // side opposite vertex j
                    {star_dist(si, sj), k},  // side opposite vertex k
                };
                // Sort the 3 sides ascending (tiny fixed sort).
                if (sv[0].d > sv[1].d) std::swap(sv[0], sv[1]);
                if (sv[1].d > sv[2].d) std::swap(sv[1], sv[2]);
                if (sv[0].d > sv[1].d) std::swap(sv[0], sv[1]);
                if (sv[2].d < 1e-6) continue;  // degenerate (coincident stars)
                Tri t;
                t.u = (float)(sv[0].d / sv[2].d);
                t.v = (float)(sv[1].d / sv[2].d);
                t.va = sv[0].v;
                t.vb = sv[1].v;
                t.vc = sv[2].v;
                out.push_back(t);
            }
        }
    }
}

// Packs two ints into one map key. Values may be negative (invariant buckets near zero),
// so shift via uint32_t to avoid signed-shift UB; uniqueness is preserved for int range.
inline int64_t bucket_key(int ku, int kv) {
    return (int64_t)(((uint64_t)(uint32_t)ku << 32) | (uint64_t)(uint32_t)kv);
}

// Triangle-similarity voting over the brightest `bootstrapStars` stars of each frame (invariant =
// sorted side-length ratios, 1% tolerance): the vertex correspondences with >= 3 votes.
void triangle_pairs(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                    const AlignParams& p, std::vector<PPair>* out) {
    out->clear();
    const size_t nBoot = p.bootstrapStars > 0 ? (size_t)p.bootstrapStars : 0;
    const std::vector<int> refSel = brightest_indices(refStars, nBoot);
    const std::vector<int> curSel = brightest_indices(curStars, nBoot);
    if (refSel.size() < 3 || curSel.size() < 3) return;

    std::vector<Tri> refTris, curTris;
    build_triangles(refStars, refSel, refTris);
    build_triangles(curStars, curSel, curTris);
    if (refTris.empty() || curTris.empty()) return;

    // Bucket ref triangles by invariants rounded to 0.005.
    const double kBucket = 0.005;
    std::unordered_map<int64_t, std::vector<int>> buckets;
    buckets.reserve(refTris.size() * 2);
    for (size_t t = 0; t < refTris.size(); ++t) {
        const int ku = (int)std::lround((double)refTris[t].u / kBucket);
        const int kv = (int)std::lround((double)refTris[t].v / kBucket);
        buckets[bucket_key(ku, kv)].push_back((int)t);
    }

    // Match cur triangles against ref buckets (+-2 buckets covers the 0.01 tolerance),
    // vote for vertex correspondences (local index -> local index).
    const double tol = 0.01;  // 1% agreement on each invariant component
    std::unordered_map<int64_t, int> votes;
    for (const Tri& ct : curTris) {
        const int ku = (int)std::lround((double)ct.u / kBucket);
        const int kv = (int)std::lround((double)ct.v / kBucket);
        for (int du = -2; du <= 2; ++du) {
            for (int dv = -2; dv <= 2; ++dv) {
                auto it = buckets.find(bucket_key(ku + du, kv + dv));
                if (it == buckets.end()) continue;
                for (int rt : it->second) {
                    const Tri& r = refTris[(size_t)rt];
                    if (std::fabs((double)r.u - (double)ct.u) > tol) continue;
                    if (std::fabs((double)r.v - (double)ct.v) > tol) continue;
                    ++votes[bucket_key(r.va, ct.va)];
                    ++votes[bucket_key(r.vb, ct.vb)];
                    ++votes[bucket_key(r.vc, ct.vc)];
                }
            }
        }
    }

    // Keep correspondences with >= 3 votes; sort for deterministic pair order.
    struct Corr {
        int refLocal, curLocal;
    };
    std::vector<Corr> corrs;
    for (const auto& kvp : votes) {
        if (kvp.second >= 3) {
            corrs.push_back(Corr{(int)(kvp.first >> 32), (int)(kvp.first & 0xffffffffLL)});
        }
    }
    std::sort(corrs.begin(), corrs.end(), [](const Corr& a, const Corr& b) {
        if (a.refLocal != b.refLocal) return a.refLocal < b.refLocal;
        return a.curLocal < b.curLocal;
    });
    for (const Corr& c : corrs) {
        if (c.refLocal < 0 || c.refLocal >= (int)refSel.size()) continue;
        if (c.curLocal < 0 || c.curLocal >= (int)curSel.size()) continue;
        const Star& rs = refStars[(size_t)refSel[(size_t)c.refLocal]];
        const Star& cs = curStars[(size_t)curSel[(size_t)c.curLocal]];
        out->push_back(PPair{(double)rs.x, (double)rs.y, (double)cs.x, (double)cs.y});
    }
}

// The shift most stars agree on: every pair of a reference star (the first 150 of the spread
// selection) and a current star up to 400 px apart votes for their displacement on a 4 px grid,
// and the pairs within 6 px of the displacement with the most votes around it seed the fit. A
// turn spreads the votes, so this holds for shifts of any size but turns of a degree or two.
void shift_vote_pairs(const Ctx& ctx, std::vector<PPair>* out) {
    out->clear();
    constexpr double kMaxShift = 400.0, kBin = 4.0, kNear = 6.0;
    const int nb = (int)std::ceil(kMaxShift / kBin);
    const int side = 2 * nb + 1;
    const size_t nRef = std::min<size_t>(150, ctx.sel.size());
    std::vector<int> hist((size_t)side * (size_t)side, 0);
    for (size_t k = 0; k < nRef; ++k) {
        const Star& r = ctx.ref[(size_t)ctx.sel[k]];
        for (const Star& c : ctx.cur) {
            const double dx = (double)c.x - r.x, dy = (double)c.y - r.y;
            if (!(std::fabs(dx) <= kMaxShift && std::fabs(dy) <= kMaxShift)) continue;
            const int ix = (int)std::lround(dx / kBin) + nb, iy = (int)std::lround(dy / kBin) + nb;
            ++hist[(size_t)iy * (size_t)side + (size_t)ix];
        }
    }
    int best = -1, bx = 0, by = 0;
    for (int iy = 1; iy < side - 1; ++iy) {
        for (int ix = 1; ix < side - 1; ++ix) {
            int s = 0;
            for (int j = -1; j <= 1; ++j) {
                for (int i = -1; i <= 1; ++i) s += hist[(size_t)(iy + j) * (size_t)side + (size_t)(ix + i)];
            }
            if (s > best) {
                best = s;
                bx = ix;
                by = iy;
            }
        }
    }
    if (best < 3) return;
    const double sx = (bx - nb) * kBin, sy = (by - nb) * kBin;
    for (size_t k = 0; k < nRef; ++k) {
        const Star& r = ctx.ref[(size_t)ctx.sel[k]];
        ctx.grid.within(ctx.cur, r.x + sx, r.y + sy, (float)kNear, [&](int ci, double) {
            const Star& c = ctx.cur[(size_t)ci];
            if (flux_ok(r, c, ctx.p)) out->push_back(PPair{(double)r.x, (double)r.y, (double)c.x, (double)c.y});
        });
    }
}

}  // namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

AlignResult align_stars(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                        const Transform& initGuess, const AlignParams& p) {
    try {
        if (refStars.empty() || curStars.empty()) {
            SLOG("align_stars: empty star list (ref=%zu cur=%zu)", refStars.size(),
                 curStars.size());
            return AlignResult();
        }
        const Ctx ctx(refStars, curStars, p);
        // The guess as it is (the previous frame's model: right when little has moved since).
        AlignResult best = ctx.refine(initGuess);

        // Seed from 150 reference stars spread over the frame, each paired with every current
        // star within matchRadius of where the guess puts it.
        const size_t nSeed = std::min<size_t>(150, ctx.sel.size());
        const float radius = p.matchRadius > 0.0f ? p.matchRadius : 0.0f;
        std::vector<PPair> pairs;
        pairs.reserve(nSeed * 2);
        for (size_t k = 0; k < nSeed; ++k) {
            const int ri = ctx.sel[k];
            const Star& rs = refStars[(size_t)ri];
            double px = 0.0, py = 0.0;
            initGuess.apply((double)rs.x, (double)rs.y, &px, &py);
            ctx.grid.within(curStars, px, py, radius, [&](int ci, double) {
                const Star& cs = curStars[(size_t)ci];
                if (flux_ok(rs, cs, p))
                    pairs.push_back(PPair{(double)rs.x, (double)rs.y, (double)cs.x, (double)cs.y});
            });
        }
        Transform seed;
        if (ransac_similarity(pairs, p, p.seedInlierPx, &seed)) {
            const AlignResult r = ctx.refine(seed);
            if (better(r, best)) best = r;
        }
        SLOG("align_stars: pairs=%zu inliers=%d residual=%.3f ok=%d", pairs.size(), best.inliers,
             (double)best.residual, best.ok ? 1 : 0);
        return best;
    } catch (...) {
        SLOGE("align_stars: internal exception, returning ok=false");
        return AlignResult();
    }
}

AlignResult align_bootstrap(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                            const AlignParams& p) {
    try {
        if (refStars.size() < 3 || curStars.size() < 3) {
            SLOG("align_bootstrap: too few stars (ref=%zu cur=%zu)", refStars.size(),
                 curStars.size());
            return AlignResult();
        }
        const Ctx ctx(refStars, curStars, p);
        AlignResult best;
        Transform seed;
        std::vector<PPair> pairs;
        // The shift most stars agree on: survives any shift, and turns of a degree or two.
        shift_vote_pairs(ctx, &pairs);
        const size_t shiftPairs = pairs.size();
        if (ransac_similarity(pairs, p, p.seedInlierPx, &seed)) best = ctx.refine(seed);
        // Triangles: blind to turns as well, but they need the same bright stars in both frames.
        triangle_pairs(refStars, curStars, p, &pairs);
        if (ransac_similarity(pairs, p, p.seedInlierPx, &seed)) {
            const AlignResult r = ctx.refine(seed);
            if (better(r, best)) best = r;
        }
        SLOG("align_bootstrap: shift pairs=%zu triangle pairs=%zu inliers=%d residual=%.3f ok=%d",
             shiftPairs, pairs.size(), best.inliers, (double)best.residual, best.ok ? 1 : 0);
        return best;
    } catch (...) {
        SLOGE("align_bootstrap: internal exception, returning ok=false");
        return AlignResult();
    }
}

AlignResult align_frame(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                        const Transform& initGuess, const AlignParams& p) {
    const AlignResult a = align_stars(refStars, curStars, initGuess, p);
    const AlignResult b = align_bootstrap(refStars, curStars, p);
    // The guided result keeps one frame's model following the last; the guess-free one takes
    // over only when it holds over clearly more of the frame (it rescues a jump - it must not
    // flip between two groups of about the same reach, such as the sky and lights below it).
    if (b.ok && (!a.ok || b.coverage > a.coverage + std::max(2, a.coverage / 10))) return b;
    return a;
}

}  // namespace starcam

// StarCam native engine — star-pattern alignment (PLAN.md §6.3).
// All coordinates are LUMA (half-res) coordinates. The estimated Transform maps
// REFERENCE frame coords -> CURRENT frame coords.
//
// align_stars:     predicted-position NN matching (grid hash) + flux gate -> RANSAC similarity
//                  (2-point minimal, complex formulation) -> least-squares refit on inliers.
// align_bootstrap: triangle-similarity voting over brightest stars -> same RANSAC/refit path.

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

    // Index of the nearest star within `radius` of (qx,qy), or -1.
    int nearest(const std::vector<Star>& stars, double qx, double qy, float radius) const {
        if (cols <= 0 || rows <= 0) return -1;
        if (!std::isfinite(qx) || !std::isfinite(qy)) return -1;
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

        int best = -1;
        double bestD2 = r2;
        const int gx0 = clampi(cx - 1, 0, cols - 1), gx1 = clampi(cx + 1, 0, cols - 1);
        const int gy0 = clampi(cy - 1, 0, rows - 1), gy1 = clampi(cy + 1, 0, rows - 1);
        for (int gy = gy0; gy <= gy1; ++gy) {
            for (int gx = gx0; gx <= gx1; ++gx) {
                const std::vector<int>& bucket = cells[(size_t)gy * (size_t)cols + (size_t)gx];
                for (int idx : bucket) {
                    const double dx = (double)stars[(size_t)idx].x - qx;
                    const double dy = (double)stars[(size_t)idx].y - qy;
                    const double d2 = dx * dx + dy * dy;
                    if (d2 <= r2 && (best < 0 || d2 < bestD2)) {
                        best = idx;
                        bestD2 = d2;
                    }
                }
            }
        }
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

// Solve A*x = b for a 4x4 system via Gaussian elimination with partial pivoting.
bool solve4x4(const double A[4][4], const double b[4], double out[4]) {
    double M[4][5];
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) M[r][c] = A[r][c];
        M[r][4] = b[r];
    }
    for (int col = 0; col < 4; ++col) {
        int piv = col;
        double pv = std::fabs(M[col][col]);
        for (int r = col + 1; r < 4; ++r) {
            const double v = std::fabs(M[r][col]);
            if (v > pv) {
                pv = v;
                piv = r;
            }
        }
        if (pv < 1e-12) return false;  // singular / ill-conditioned
        if (piv != col) {
            for (int c = col; c < 5; ++c) std::swap(M[piv][c], M[col][c]);
        }
        for (int r = col + 1; r < 4; ++r) {
            const double f = M[r][col] / M[col][col];
            if (f == 0.0) continue;
            for (int c = col; c < 5; ++c) M[r][c] -= f * M[col][c];
        }
    }
    for (int r = 3; r >= 0; --r) {
        double s = M[r][4];
        for (int c = r + 1; c < 4; ++c) s -= M[r][c] * out[c];
        out[r] = s / M[r][r];
    }
    for (int i = 0; i < 4; ++i) {
        if (!std::isfinite(out[i])) return false;
    }
    return true;
}

// Count inliers (residual < inlierPx) of transform (a,b,tx,ty) over `pairs`; optionally
// collect their indices and accumulate the sum of squared residuals.
int count_inliers(const std::vector<PPair>& pairs, double a, double b, double tx, double ty,
                  double inl2, std::vector<size_t>* outIdx, double* outSum2) {
    int count = 0;
    double sum2 = 0.0;
    for (size_t k = 0; k < pairs.size(); ++k) {
        const PPair& q = pairs[k];
        const double ox = a * q.rx - b * q.ry + tx;
        const double oy = b * q.rx + a * q.ry + ty;
        const double dx = ox - q.cx;
        const double dy = oy - q.cy;
        const double d2 = dx * dx + dy * dy;
        if (d2 < inl2) {
            ++count;
            sum2 += d2;
            if (outIdx) outIdx->push_back(k);
        }
    }
    if (outSum2) *outSum2 = sum2;
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

// RANSAC (2-point minimal similarity, complex-number formulation) + least-squares refit
// on the best model's inliers. Shared by align_stars and align_bootstrap. Only models with a
// plausible scale take part, so a false match can neither win nor survive the refit.
AlignResult ransac_similarity(const std::vector<PPair>& pairs, const AlignParams& p) {
    AlignResult res;
    const size_t n = pairs.size();
    if (n < 2) return res;

    const double inl2 = (double)p.inlierPx * (double)p.inlierPx;
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
        const int count = count_inliers(pairs, a, b, tx, ty, inl2, nullptr, nullptr);
        if (count > bestCount) {
            bestCount = count;
            bA = a;
            bB = b;
            bTx = tx;
            bTy = ty;
        }
    }
    if (bestCount < 2) {
        SLOG("align: RANSAC found no usable model (%d best inliers over %zu pairs)", bestCount,
             n);
        return res;
    }

    // Least-squares refit on the best model's inliers. Rows per pair (ref=(x,y), cur=(u,v)):
    //   [x -y 1 0] * [a b tx ty]^T = u
    //   [y  x 0 1] * [a b tx ty]^T = v
    std::vector<size_t> inl;
    inl.reserve((size_t)bestCount);
    count_inliers(pairs, bA, bB, bTx, bTy, inl2, &inl, nullptr);

    double fA = bA, fB = bB, fTx = bTx, fTy = bTy;
    if (inl.size() >= 2) {
        double ATA[4][4] = {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}};
        double ATb[4] = {0, 0, 0, 0};
        for (size_t k : inl) {
            const PPair& q = pairs[k];
            const double r1[4] = {q.rx, -q.ry, 1.0, 0.0};
            const double r2[4] = {q.ry, q.rx, 0.0, 1.0};
            for (int ii = 0; ii < 4; ++ii) {
                for (int jj = 0; jj < 4; ++jj) {
                    ATA[ii][jj] += r1[ii] * r1[jj] + r2[ii] * r2[jj];
                }
                ATb[ii] += r1[ii] * q.cx + r2[ii] * q.cy;
            }
        }
        double sol[4];
        if (solve4x4(ATA, ATb, sol) &&
            plausible_similarity(sol[0], sol[1], sol[2], sol[3], p.maxScaleDev)) {
            fA = sol[0];
            fB = sol[1];
            fTx = sol[2];
            fTy = sol[3];
        }
        // else: keep the RANSAC minimal-sample model (degenerate normal equations or an
        // implausible refit; the minimal model passed the scale check)
    }

    // Final inlier count + RMS residual under the refit transform.
    double sum2 = 0.0;
    const int finalCount = count_inliers(pairs, fA, fB, fTx, fTy, inl2, nullptr, &sum2);
    if (finalCount <= 0) {
        // Refit went pathological; fall back to the raw RANSAC model.
        double sum2b = 0.0;
        const int cb = count_inliers(pairs, bA, bB, bTx, bTy, inl2, nullptr, &sum2b);
        res.T.a = bA;
        res.T.b = bB;
        res.T.tx = bTx;
        res.T.ty = bTy;
        res.inliers = cb;
        res.residual = cb > 0 ? (float)std::sqrt(sum2b / (double)cb) : 0.0f;
        res.ok = cb >= p.minInliers;
        return res;
    }
    res.T.a = fA;
    res.T.b = fB;
    res.T.tx = fTx;
    res.T.ty = fTy;
    res.inliers = finalCount;
    res.residual = (float)std::sqrt(sum2 / (double)finalCount);
    res.ok = finalCount >= p.minInliers;
    return res;
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

}  // namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

AlignResult align_stars(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                        const Transform& initGuess, const AlignParams& p) {
    AlignResult res;
    try {
        if (refStars.empty() || curStars.empty()) {
            SLOG("align_stars: empty star list (ref=%zu cur=%zu)", refStars.size(),
                 curStars.size());
            return res;
        }
        // Up to the 150 brightest reference stars.
        const std::vector<int> order = brightest_indices(refStars, 150);

        // Grid hash over current stars, cell = matchRadius -> O(1) NN queries.
        const float radius = p.matchRadius > 0.0f ? p.matchRadius : 0.0f;
        StarGrid grid;
        grid.build(curStars, radius);

        std::vector<PPair> pairs;
        pairs.reserve(order.size());
        for (int ri : order) {
            const Star& rs = refStars[(size_t)ri];
            double px = 0.0, py = 0.0;
            initGuess.apply((double)rs.x, (double)rs.y, &px, &py);
            const int ci = grid.nearest(curStars, px, py, radius);
            if (ci < 0) continue;
            const Star& cs = curStars[(size_t)ci];
            if (!(rs.flux > 0.0f)) continue;  // avoid divide-by-zero / NaN flux
            const float ratio = cs.flux / rs.flux;
            if (!(ratio >= p.fluxRatioMin && ratio <= p.fluxRatioMax)) continue;
            PPair q;
            q.rx = (double)rs.x;
            q.ry = (double)rs.y;
            q.cx = (double)cs.x;
            q.cy = (double)cs.y;
            pairs.push_back(q);
        }
        if (pairs.size() < 2) {
            SLOG("align_stars: only %zu matched pairs, need >= 2", pairs.size());
            return res;
        }
        res = ransac_similarity(pairs, p);
        SLOG("align_stars: pairs=%zu inliers=%d residual=%.3f ok=%d", pairs.size(), res.inliers,
             (double)res.residual, res.ok ? 1 : 0);
        return res;
    } catch (...) {
        SLOGE("align_stars: internal exception, returning ok=false");
        return AlignResult();
    }
}

AlignResult align_bootstrap(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                            const AlignParams& p) {
    AlignResult res;
    try {
        const size_t nBoot = p.bootstrapStars > 0 ? (size_t)p.bootstrapStars : 0;
        const std::vector<int> refSel = brightest_indices(refStars, nBoot);
        const std::vector<int> curSel = brightest_indices(curStars, nBoot);
        if (refSel.size() < 3 || curSel.size() < 3) {
            SLOG("align_bootstrap: too few stars (ref=%zu cur=%zu)", refSel.size(),
                 curSel.size());
            return res;
        }

        std::vector<Tri> refTris, curTris;
        build_triangles(refStars, refSel, refTris);
        build_triangles(curStars, curSel, curTris);
        if (refTris.empty() || curTris.empty()) {
            SLOG("align_bootstrap: no valid triangles");
            return res;
        }

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
            int refLocal, curLocal, count;
        };
        std::vector<Corr> corrs;
        for (const auto& kvp : votes) {
            if (kvp.second >= 3) {
                Corr c;
                c.refLocal = (int)(kvp.first >> 32);
                c.curLocal = (int)(kvp.first & 0xffffffffLL);
                c.count = kvp.second;
                corrs.push_back(c);
            }
        }
        std::sort(corrs.begin(), corrs.end(), [](const Corr& a, const Corr& b) {
            if (a.refLocal != b.refLocal) return a.refLocal < b.refLocal;
            return a.curLocal < b.curLocal;
        });

        std::vector<PPair> pairs;
        pairs.reserve(corrs.size());
        for (const Corr& c : corrs) {
            if (c.refLocal < 0 || c.refLocal >= (int)refSel.size()) continue;
            if (c.curLocal < 0 || c.curLocal >= (int)curSel.size()) continue;
            const Star& rs = refStars[(size_t)refSel[(size_t)c.refLocal]];
            const Star& cs = curStars[(size_t)curSel[(size_t)c.curLocal]];
            PPair q;
            q.rx = (double)rs.x;
            q.ry = (double)rs.y;
            q.cx = (double)cs.x;
            q.cy = (double)cs.y;
            pairs.push_back(q);
        }
        if (pairs.size() < 2) {
            SLOG("align_bootstrap: only %zu voted pairs, need >= 2", pairs.size());
            return res;
        }
        res = ransac_similarity(pairs, p);
        SLOG("align_bootstrap: tris=%zu/%zu pairs=%zu inliers=%d residual=%.3f ok=%d",
             refTris.size(), curTris.size(), pairs.size(), res.inliers, (double)res.residual,
             res.ok ? 1 : 0);
        return res;
    } catch (...) {
        SLOGE("align_bootstrap: internal exception, returning ok=false");
        return AlignResult();
    }
}

}  // namespace starcam

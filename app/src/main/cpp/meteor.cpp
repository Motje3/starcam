// meteor.cpp — Transient streak detection & classification (beta). See PLAN.md §6.6 and meteor.h.
//
// addFrame: diff the current half-res luma against the pixelwise median of the last 3..5
// PREVIOUS frames (the current frame is pushed into the history only AFTER the diff is
// computed, so it never contaminates its own median). Robust background/sigma of the diff
// via global median/MAD (every 4th pixel sampled), threshold at bg + 6*sigma, 8-connected
// components (iterative flood fill), PCA line fit per component (closed-form eigen of the
// 2x2 covariance); keep components with length >= 25 px, aspect >= 4, solidity >= 0.7.
//
// finalizeClassification: groups streaks whose infinite lines are colinear (angle diff
// < 3 deg AND mutual perpendicular midpoint offset < 15 px, union-find over all pairs);
// per group, over sorted unique frame indices:
//   - >= 3 members in strictly consecutive frames            -> satellite (cls = 1)
//   - >= 3 members strictly every-other-frame (diffs == 2,
//     which spans >= 5 frames)                                -> plane (cls = 2, blinking)
//   - >= 3 members near-continuous (each gap <= 1 missing
//     frame)                                                  -> satellite (cls = 1)
//   - otherwise                                               -> meteor candidate (cls = 0)
// The every-other-frame (blink) test is evaluated between the two satellite tests so that
// both the "consecutive-ish" satellite rule and the alternating plane rule are reachable.

#include "meteor.h"

#include <climits>
#include <cstddef>

namespace starcam {

namespace {

constexpr int kHistoryCap = 5;
constexpr int kMinHistory = 3;
constexpr float kSigmaK = 6.0f;
constexpr int kMinComponentPx = 15;
constexpr float kMinLengthPx = 25.0f;
constexpr float kMinAspect = 4.0f;
constexpr float kMinSolidity = 0.7f;
constexpr float kSolidityDistPx = 2.0f;
constexpr float kAngleTolRad = 3.0f * (float)M_PI / 180.0f;
constexpr float kPerpTolPx = 15.0f;

// Median of k floats, k in [1, kHistoryCap]. Sorts v in place (insertion sort).
inline float smallMedian(float* v, int k) {
    for (int i = 1; i < k; ++i) {
        float t = v[i];
        int j = i - 1;
        while (j >= 0 && v[j] > t) { v[j + 1] = v[j]; --j; }
        v[j + 1] = t;
    }
    if (k & 1) return v[k / 2];
    return 0.5f * (v[k / 2 - 1] + v[k / 2]);
}

// Union-find with path halving.
int ufFind(std::vector<int>& p, int i) {
    while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
    return i;
}
void ufUnite(std::vector<int>& p, int a, int b) {
    a = ufFind(p, a);
    b = ufFind(p, b);
    if (a != b) p[b] = a;
}

}  // namespace

std::vector<Streak> MeteorDetector::addFrame(const ImageF& luma, int frameIndex) {
    std::vector<Streak> found;
    try {
        if (luma.empty() || luma.w <= 0 || luma.h <= 0 || luma.ch < 1) {
            SLOGE("MeteorDetector::addFrame: empty/invalid luma (frame %d)", frameIndex);
            return found;
        }
        if (!recent_.empty() &&
            (recent_.front().w != luma.w || recent_.front().h != luma.h)) {
            SLOGE("MeteorDetector::addFrame: frame size changed %dx%d -> %dx%d, resetting history",
                  recent_.front().w, recent_.front().h, luma.w, luma.h);
            recent_.clear();
        }

        const int w = luma.w, h = luma.h;
        const size_t n = (size_t)w * (size_t)h;
        const int nHist = (int)recent_.size();

        if (nHist >= kMinHistory && n > 0 && n <= (size_t)INT_MAX) {
            // ---- diff = luma - pixelwise median of the previous nHist (3..5) frames ----
            std::vector<float> diff(n, 0.0f);
            const float* hist[kHistoryCap] = {};
            size_t histCh[kHistoryCap] = {};
            for (int f = 0; f < nHist; ++f) {
                hist[f] = recent_[(size_t)f].d.data();
                histCh[f] = (size_t)recent_[(size_t)f].ch;  // channel 0 of each pixel
            }
            const float* cur = luma.d.data();
            const size_t curCh = (size_t)luma.ch;
            parallel_for(0, h, [&](int y) {
                const size_t row = (size_t)y * (size_t)w;
                for (int x = 0; x < w; ++x) {
                    const size_t i = row + (size_t)x;
                    float v[kHistoryCap];
                    for (int f = 0; f < nHist; ++f) v[f] = hist[f][i * histCh[f]];
                    diff[i] = cur[i * curCh] - smallMedian(v, nHist);
                }
            });

            // ---- robust background & sigma of diff: global median / MAD, every 4th px ----
            std::vector<float> samp;
            samp.reserve(n / 4 + 1);
            for (size_t i = 0; i < n; i += 4) samp.push_back(diff[i]);
            const float bg = median_of(samp);            // reorders samp; same multiset though
            for (float& s : samp) s = std::fabs(s - bg);
            float sigma = median_of(samp) * 1.4826f;
            if (!(sigma > 1e-6f)) sigma = 1e-6f;         // also guards NaN
            const float thr = bg + kSigmaK * sigma;

            // ---- threshold mask ----
            std::vector<uint8_t> mask(n, 0);
            size_t above = 0;
            for (size_t i = 0; i < n; ++i) {
                if (diff[i] > thr) { mask[i] = 1; ++above; }
            }

            if (above > n / 8) {
                SLOGE("MeteorDetector::addFrame: degenerate threshold, %d%% of frame %d above; skipping",
                      (int)(above * 100 / n), frameIndex);
            } else if (above > 0) {
                // ---- 8-connected components, iterative flood fill ----
                std::vector<int> stack;
                std::vector<int> comp;
                const int W = w, H = h;
                for (int seed = 0; seed < (int)n; ++seed) {
                    if (!mask[(size_t)seed]) continue;
                    stack.clear();
                    comp.clear();
                    mask[(size_t)seed] = 0;
                    stack.push_back(seed);
                    while (!stack.empty()) {
                        const int idx = stack.back();
                        stack.pop_back();
                        comp.push_back(idx);
                        const int cx = idx % W, cy = idx / W;
                        for (int dy = -1; dy <= 1; ++dy) {
                            const int ny = cy + dy;
                            if (ny < 0 || ny >= H) continue;
                            for (int dx = -1; dx <= 1; ++dx) {
                                if (dx == 0 && dy == 0) continue;
                                const int nx = cx + dx;
                                if (nx < 0 || nx >= W) continue;
                                const int ni = ny * W + nx;
                                if (mask[(size_t)ni]) {
                                    mask[(size_t)ni] = 0;
                                    stack.push_back(ni);
                                }
                            }
                        }
                    }
                    if ((int)comp.size() < kMinComponentPx) continue;

                    // ---- PCA line fit: closed-form eigen of the 2x2 covariance ----
                    const double cnt = (double)comp.size();
                    double mx = 0.0, my = 0.0;
                    for (int idx : comp) { mx += (double)(idx % W); my += (double)(idx / W); }
                    mx /= cnt;
                    my /= cnt;
                    double sxx = 0.0, sxy = 0.0, syy = 0.0;
                    for (int idx : comp) {
                        const double dxp = (double)(idx % W) - mx;
                        const double dyp = (double)(idx / W) - my;
                        sxx += dxp * dxp;
                        sxy += dxp * dyp;
                        syy += dyp * dyp;
                    }
                    sxx /= cnt;
                    sxy /= cnt;
                    syy /= cnt;
                    const double half = 0.5 * (sxx + syy);
                    const double dd = 0.5 * (sxx - syy);
                    const double disc = std::sqrt(std::max(0.0, dd * dd + sxy * sxy));
                    const double l1 = half + disc;                 // major eigenvalue
                    const double l2 = std::max(0.0, half - disc);  // minor, clamp fp noise
                    const float aspect = (float)std::sqrt(l1 / (l2 + 1e-9));

                    // Major-axis unit direction (eigenvector of l1).
                    double ux, uy;
                    if (std::fabs(sxy) > 1e-12) {
                        ux = l1 - syy;
                        uy = sxy;
                    } else if (sxx >= syy) {
                        ux = 1.0; uy = 0.0;
                    } else {
                        ux = 0.0; uy = 1.0;
                    }
                    const double un = std::sqrt(ux * ux + uy * uy);
                    if (un > 1e-12) { ux /= un; uy /= un; } else { ux = 1.0; uy = 0.0; }

                    // Extent along the axis, solidity within 2 px of the line, peak diff.
                    double tmin = 1e30, tmax = -1e30;
                    int nearLine = 0;
                    float maxDiff = -1e30f;
                    for (int idx : comp) {
                        const double dxp = (double)(idx % W) - mx;
                        const double dyp = (double)(idx / W) - my;
                        const double t = dxp * ux + dyp * uy;
                        const double p = dyp * ux - dxp * uy;  // perpendicular offset
                        if (t < tmin) tmin = t;
                        if (t > tmax) tmax = t;
                        if (std::fabs(p) <= (double)kSolidityDistPx) ++nearLine;
                        const float dv = diff[(size_t)idx];
                        if (dv > maxDiff) maxDiff = dv;
                    }
                    const float length = (float)(tmax - tmin);
                    const float solidity = (float)nearLine / (float)comp.size();

                    if (length >= kMinLengthPx && aspect >= kMinAspect && solidity >= kMinSolidity) {
                        Streak s;
                        s.frameIndex = frameIndex;
                        s.x0 = (float)(mx + ux * tmin);
                        s.y0 = (float)(my + uy * tmin);
                        s.x1 = (float)(mx + ux * tmax);
                        s.y1 = (float)(my + uy * tmax);
                        s.length = length;
                        s.brightness = std::max(0.0f, maxDiff - bg);  // peak diff above background
                        s.cls = 0;
                        found.push_back(s);
                    }
                }
                if (!found.empty()) {
                    SLOG("MeteorDetector: frame %d -> %d streak candidate(s)",
                         frameIndex, (int)found.size());
                    all_.insert(all_.end(), found.begin(), found.end());
                }
            }
        }

        // Push AFTER computing so the current frame is never part of its own median.
        recent_.push_back(luma);
        while ((int)recent_.size() > kHistoryCap) recent_.pop_front();
    } catch (...) {
        SLOGE("MeteorDetector::addFrame: exception caught (frame %d)", frameIndex);
    }
    return found;
}

std::vector<Streak> MeteorDetector::finalizeClassification() {
    std::vector<Streak> out;
    try {
        const int m = (int)all_.size();
        if (m > 1) {
            // Per-streak infinite-line parameters: unit direction, angle mod pi, midpoint.
            std::vector<float> ang((size_t)m), cx((size_t)m), cy((size_t)m);
            std::vector<float> dux((size_t)m), duy((size_t)m);
            for (int i = 0; i < m; ++i) {
                const Streak& s = all_[(size_t)i];
                float dx = s.x1 - s.x0, dy = s.y1 - s.y0;
                const float L = std::sqrt(dx * dx + dy * dy);
                if (L > 1e-6f) { dx /= L; dy /= L; } else { dx = 1.0f; dy = 0.0f; }
                dux[(size_t)i] = dx;
                duy[(size_t)i] = dy;
                float a = std::atan2(dy, dx);
                if (a < 0.0f) a += (float)M_PI;
                ang[(size_t)i] = a;
                cx[(size_t)i] = 0.5f * (s.x0 + s.x1);
                cy[(size_t)i] = 0.5f * (s.y0 + s.y1);
            }

            // Group colinear streaks (angle diff < 3 deg, mutual perpendicular offset < 15 px).
            std::vector<int> parent((size_t)m);
            for (int i = 0; i < m; ++i) parent[(size_t)i] = i;
            for (int i = 0; i < m; ++i) {
                for (int j = i + 1; j < m; ++j) {
                    float da = std::fabs(ang[(size_t)i] - ang[(size_t)j]);
                    if (da > (float)M_PI * 0.5f) da = (float)M_PI - da;
                    if (da >= kAngleTolRad) continue;
                    const float pij = std::fabs(dux[(size_t)i] * (cy[(size_t)j] - cy[(size_t)i]) -
                                                duy[(size_t)i] * (cx[(size_t)j] - cx[(size_t)i]));
                    const float pji = std::fabs(dux[(size_t)j] * (cy[(size_t)i] - cy[(size_t)j]) -
                                                duy[(size_t)j] * (cx[(size_t)i] - cx[(size_t)j]));
                    if (pij < kPerpTolPx && pji < kPerpTolPx) ufUnite(parent, i, j);
                }
            }

            // Collect members per group root.
            std::vector<std::vector<int>> groups;
            std::vector<int> slot((size_t)m, -1);
            for (int i = 0; i < m; ++i) {
                const int r = ufFind(parent, i);
                if (slot[(size_t)r] < 0) {
                    slot[(size_t)r] = (int)groups.size();
                    groups.emplace_back();
                }
                groups[(size_t)slot[(size_t)r]].push_back(i);
            }

            // Classify each group from its sorted unique frame indices.
            for (const std::vector<int>& g : groups) {
                std::vector<int> fr;
                fr.reserve(g.size());
                for (int i : g) fr.push_back(all_[(size_t)i].frameIndex);
                std::sort(fr.begin(), fr.end());
                fr.erase(std::unique(fr.begin(), fr.end()), fr.end());
                if (fr.size() < 3) continue;

                int run1 = 1, best1 = 1;  // strictly consecutive frames (diff == 1)
                int run2 = 1, best2 = 1;  // strictly alternating (diff == 2)
                int runc = 1, bestc = 1;  // consecutive-ish (at most 1 missing frame per gap)
                for (size_t k = 1; k < fr.size(); ++k) {
                    const int d = fr[k] - fr[k - 1];
                    run1 = (d == 1) ? run1 + 1 : 1;
                    if (run1 > best1) best1 = run1;
                    run2 = (d == 2) ? run2 + 1 : 1;
                    if (run2 > best2) best2 = run2;
                    runc = (d <= 2) ? runc + 1 : 1;
                    if (runc > bestc) bestc = runc;
                }
                int cls = 0;
                if (best1 >= 3) {
                    cls = 1;  // steady presence over >=3 consecutive frames: satellite
                } else if (best2 >= 3) {
                    cls = 2;  // present-missing-present blink; 3 members span >=5 frames: plane
                } else if (bestc >= 3) {
                    cls = 1;  // near-continuous track with single-frame dropouts: satellite
                }
                if (cls != 0) {
                    for (int i : g) all_[(size_t)i].cls = cls;
                }
            }
        }
        out = all_;
    } catch (...) {
        SLOGE("MeteorDetector::finalizeClassification: exception caught");
        try { out = all_; } catch (...) { out.clear(); }
    }
    return out;
}

}  // namespace starcam

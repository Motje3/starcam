// hotpix.cpp — hot pixels found from the light frames themselves. See hotpix.h.
//
// A hot pixel belongs to the sensor, not to the sky: it sits far above its same-colour neighbours
// in every frame, and none of its light reaches them. Everything the lens draws - a star, a lamp,
// the edge of a lit window - is spread over several pixels by the lens itself, so its neighbours
// share its light; and a star that moves with the sky is not on the same pixel in every frame.
//
// Three passes:
//   1. the mean over the frames of each site's value over its frame's own black (made a band of
//      rows at a time: about 4 MB at 12 MP, where a plane of the whole frame was 48 MB, and
//      200 MB on a 50 MP sensor);
//   2. candidates: sites whose mean is more than max(5 sigma, 1 DN) above the median of their 8
//      same-colour neighbours 2 px away, sigma a robust spread of that excess per CFA position,
//      and above all of those neighbours but one;
//   3. each candidate checked on every frame on its own (persistent) and on the mean (not shared).
//
// Two checks go beyond "above its neighbours in every frame, neighbours not lit", both from the
// phone's frames of 2026-10-31 (8 frames, the camera moving a little between them):
//  - Persistent also means steady: the median excess must be at least the frame-to-frame change
//    of the neighbours themselves (each one's spread over the frames around that frame's median
//    of them). In the lit buildings the scene slid under the pixels, and sites on window edges
//    stayed above their neighbours in most frames by 10-300 DN that came and went: 24 of the 40
//    sites that read 10 DN and more, at which the session 10 minutes earlier showed nothing (the
//    16 left do show there). The sky's sites change by their noise alone, so a warm pixel there
//    loses little.
//  - Not shared is asked of the adjacent pixels too, each colour against its own sites 4 px
//    further out: what the lens draws lights them (a star 3.5 px wide at 0.8 of its peak, its
//    same-colour neighbours 2 px away at 0.2-0.4), a defect of one pixel does not. With the stars
//    standing still (stacklab's baseline, one frame stacked onto itself) the 2 px test alone let
//    star cores through: the stars' peak share fell by up to a fifth.
//
// Nothing here throws: allocations that fail inside parallel_for are swallowed there, the rest is
// caught by the wrapper and gives an empty list (no repair, as before the list existed).

#include "hotpix.h"

#include <cstdlib>
#include <memory>

#include "sraw.h"

namespace starcam {
namespace {

constexpr int kMaxFrames = 32;
// Candidates: an excess over the neighbours of more than this many robust sigmas of the excess,
// and at least kFloorDn (in DN: the mean is of raw values, so near-noiseless quantized frames do
// not flag every site one count above its neighbours). 5, not 4: over 12 million sites a 4 sigma
// excess of the noise alone happens hundreds of times, and with 8 frames such a site is above its
// neighbours frame by frame just as steadily as a warm pixel of that level - nothing later tells
// them apart (the self-test's still frames: 24 such sites at 4 sigma). On the phone's frames of
// 2026-10-31, 5 sigma is 1.9-2.3 DN, about find_hot_pixels' own 2 DN floor for darks.
constexpr float kSigmaK = 5.0f;
constexpr float kFloorDn = 1.0f;
constexpr int kMargin = 5;        // the adjacent pixels' own sites 4 px further out must be inside
constexpr float kShared = 0.15f;  // neighbours lifted by this share of the site's lift: the lens's
constexpr float kSteady = 1.0f;   // median excess over the neighbours' frame-to-frame change
// More candidates than this share of a band's sites means the frames are not what this is built
// for (pure noise, a test chart): only the strongest are checked, so the time stays bounded.
constexpr size_t kMaxCandidateShare = 20;  // 1 / 20 = 5 %
constexpr int kBand = 256;  // rows of sites per band

// Mean of the middle two of 8 / of 16 (sorted in place).
inline float median8(float* v) {
    std::sort(v, v + 8);
    return 0.5f * (v[3] + v[4]);
}
inline float median16(float* v) {
    std::sort(v, v + 16);
    return 0.5f * (v[7] + v[8]);
}

struct Cand {
    uint32_t idx;
    float excess;
};

std::vector<uint32_t> find_impl(const std::vector<FrameRecord>& frames, const SensorInfo& si,
                                HotLightsReport& rep, const std::atomic<bool>* abortFlag) {
    std::vector<uint32_t> hot;
    const int W = si.width, H = si.height;
    if (W < 4 * kMargin || H < 4 * kMargin) return hot;
    auto aborted = [abortFlag]() { return abortFlag != nullptr && abortFlag->load(); };

    // ---- the frames: every usable one, or 32 spread evenly over the session ----
    std::vector<int> pool;
    for (int i = 0; i < (int)frames.size(); ++i)
        if (!frames[i].rejected && !frames[i].srawPath.empty()) pool.push_back(i);
    std::vector<int> pick;
    if ((int)pool.size() <= kMaxFrames) {
        pick = pool;
    } else {
        for (int k = 0; k < kMaxFrames; ++k)
            pick.push_back(pool[(size_t)std::lround((double)k * (double)(pool.size() - 1) /
                                                    (double)(kMaxFrames - 1))]);
    }
    std::vector<std::unique_ptr<SrawReader>> readers;
    std::vector<const uint16_t*> data;
    std::vector<float> black;  // 4 per frame, raster order
    for (int i : pick) {
        auto rd = std::make_unique<SrawReader>();
        if (!rd->open(frames[i].srawPath)) continue;
        if ((int)rd->header().width != W || (int)rd->header().height != H || !rd->data()) continue;
        data.push_back(rd->data());
        for (int b = 0; b < 4; ++b) black.push_back(rd->header().blackLevel[b]);
        readers.push_back(std::move(rd));
    }
    const int N = (int)readers.size();
    rep.frames = N;
    if (N < 2) {
        SLOG("find_hot_pixels_lights: %d usable frame(s), need 2", N);
        return hot;
    }

    // ---- 1. the mean over the frames, over each frame's own black ----
    // Made a band of rows at a time (kBand rows and the kMargin each side the checks look at):
    // one plane of the whole frame was 48 MB at 12 MP and 200 MB at 50 MP.
    const float invN = 1.0f / (float)N;
    auto meanRow = [&](int y, float* m) {
        const int pr = (y & 1) << 1;
        for (int x = 0; x < W; ++x) m[x] = 0.0f;
        for (int k = 0; k < N; ++k) {
            const uint16_t* row = data[(size_t)k] + (size_t)y * W;
            const float b0 = black[(size_t)k * 4 + pr], b1 = black[(size_t)k * 4 + pr + 1];
            int x = 0;
            for (; x + 1 < W; x += 2) {
                m[x] += (float)row[x] - b0;
                m[x + 1] += (float)row[x + 1] - b1;
            }
            if (x < W) m[x] += (float)row[x] - b0;
        }
        for (int x = 0; x < W; ++x) m[x] *= invN;
    };
    // The same mean at one site (the same sums, in the same order).
    auto meanAt = [&](size_t i, int pos) {
        float s = 0.0f;
        for (int k = 0; k < N; ++k) s += (float)data[(size_t)k][i] - black[(size_t)k * 4 + pos];
        return s * invN;
    };

    // Neighbour offsets as index steps: the 8 same-colour ones at distance 2, the ring of 16 at
    // distance 4; the 8 adjacent pixels (every colour) and, for each, its own colour's 4 sites
    // 4 px further out.
    ptrdiff_t off8[8], off16[16], off1[8], off1bg[8][4];
    {
        int n8 = 0, n16 = 0, n1 = 0;
        for (int dy = -4; dy <= 4; dy += 2) {
            for (int dx = -4; dx <= 4; dx += 2) {
                const int cheb = std::max(std::abs(dx), std::abs(dy));
                const ptrdiff_t o = (ptrdiff_t)dy * W + dx;
                if (cheb == 2) off8[n8++] = o;
                else if (cheb == 4) off16[n16++] = o;
            }
        }
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0) continue;
                const ptrdiff_t o = (ptrdiff_t)dy * W + dx;
                off1[n1] = o;
                off1bg[n1][0] = o + 4;
                off1bg[n1][1] = o - 4;
                off1bg[n1][2] = o + 4 * (ptrdiff_t)W;
                off1bg[n1][3] = o - 4 * (ptrdiff_t)W;
                ++n1;
            }
        }
    }
    // ---- 2a. the spread of the excess, per CFA position, from every 4th 2 x 2 block each way ----
    // (the means of a sampled site and its neighbours made there and then, from the frames)
    constexpr int kStride = 4;
    std::vector<int> sampleRows;
    for (int y = kMargin; y < H - kMargin; ++y)
        if (((y >> 1) % kStride) == 0) sampleRows.push_back(y);
    std::vector<std::vector<float>> rowSamples(sampleRows.size() * 2);  // [row][x parity]
    parallel_for(0, (int)sampleRows.size(), [&](int r) {
        try {
            const int y = sampleRows[(size_t)r];
            for (int x = kMargin; x < W - kMargin; ++x) {
                if (((x >> 1) % kStride) != 0) continue;
                const size_t i = (size_t)y * W + x;
                const int pos = ((y & 1) << 1) | (x & 1);
                float nb[8];
                for (int k = 0; k < 8; ++k) nb[k] = meanAt((size_t)((ptrdiff_t)i + off8[k]), pos);
                rowSamples[(size_t)r * 2 + (x & 1)].push_back(meanAt(i, pos) - median8(nb));
            }
        } catch (...) {
            // a row short of samples changes the spread estimate by nothing measurable
        }
    });
    float thr[4];
    for (int pos = 0; pos < 4; ++pos) {
        std::vector<float> s;
        for (size_t r = 0; r < sampleRows.size(); ++r) {
            if (((sampleRows[r] & 1) << 1) != (pos & 2)) continue;
            const std::vector<float>& v = rowSamples[r * 2 + (pos & 1)];
            s.insert(s.end(), v.begin(), v.end());
        }
        float sigma = 0.0f;
        if (s.size() >= 64) {
            const float med = median_of(s);
            for (float& v : s) v = std::fabs(v - med);
            sigma = 1.4826f * median_of(s);
        }
        thr[pos] = std::max(kSigmaK * sigma, kFloorDn);
        rep.threshold[pos] = thr[pos];
    }
    rowSamples.clear();
    rowSamples.shrink_to_fit();
    if (aborted()) return hot;

    // ---- 2b and 3, a band of rows at a time ----
    const size_t total = (size_t)W * H;
    std::vector<float> band((size_t)(kBand + 2 * kMargin) * W);
    std::vector<std::vector<Cand>> rowCand((size_t)kBand);
    std::vector<Cand> cand, keep;
    std::vector<uint8_t> flag;
    std::vector<float> strength;
    for (int y0 = kMargin; y0 < H - kMargin; y0 += kBand) {
        if (aborted()) return hot;
        const int y1 = std::min(y0 + kBand, H - kMargin);  // the band's sites: rows y0..y1-1
        const int b0 = y0 - kMargin, b1 = y1 + kMargin;     // the mean's rows: b0..b1-1
        parallel_for(b0, b1, [&](int y) { meanRow(y, &band[(size_t)(y - b0) * W]); });
        // Site i's mean in the band (the checks reach kMargin rows up and down).
        const float* md = band.data();
        const size_t base = (size_t)b0 * W;

        // ---- 2b. candidates ----
        parallel_for(y0, y1, [&](int y) {
            std::vector<Cand>& out = rowCand[(size_t)(y - y0)];
            out.clear();
            try {
                const float t0 = thr[(y & 1) << 1], t1 = thr[((y & 1) << 1) + 1];
                for (int x = kMargin; x < W - kMargin; ++x) {
                    const size_t i = (size_t)y * W + x;
                    const float* p = md + (i - base);
                    const float v = p[0];
                    const float t = (x & 1) ? t1 : t0;
                    float nb[8];
                    float mn = p[off8[0]];
                    for (int k = 0; k < 8; ++k) {
                        nb[k] = p[off8[k]];
                        mn = std::min(mn, nb[k]);
                    }
                    if (!(v - mn > t)) continue;  // the median is at least the smallest of them
                    const float e = v - median8(nb);  // nb sorted from here
                    // A peak of its own: above all its neighbours but one (a second hot pixel
                    // beside it), by half its excess. On the flank of a star the neighbours toward
                    // the star are brighter than the site, and noise there lifted sites over the
                    // median.
                    if (e > t && v - nb[6] >= 0.5f * e) out.push_back(Cand{(uint32_t)i, e});
                }
            } catch (...) {
                // out of memory for this row's list: its candidates are not repaired
            }
        });
        cand.clear();
        for (int y = y0; y < y1; ++y) {
            const std::vector<Cand>& v = rowCand[(size_t)(y - y0)];
            cand.insert(cand.end(), v.begin(), v.end());
        }
        rep.candidates += (int)cand.size();
        const size_t maxCand = std::max<size_t>(1, (size_t)(y1 - y0) * W / kMaxCandidateShare);
        if (cand.size() > maxCand) {
            std::nth_element(cand.begin(), cand.begin() + maxCand, cand.end(),
                             [](const Cand& a, const Cand& b) { return a.excess > b.excess; });
            cand.resize(maxCand);
        }

        // ---- 3. persistent (frame by frame) and not shared (on the mean) ----
        flag.assign(cand.size(), 0);
        strength.assign(cand.size(), 0.0f);
        parallel_for(0, (int)cand.size(), [&](int c) {
            const size_t i = cand[(size_t)c].idx;
            const float* p = md + (i - base);
            float nb[8], ring[16];
            for (int k = 0; k < 8; ++k) nb[k] = p[off8[k]];
            for (int k = 0; k < 16; ++k) ring[k] = p[off16[k]];
            const float med = median8(nb), b = median16(ring);
            const float lift = p[0] - b;
            // Not shared, 2 px out (same colour) and next door (each pixel over its own colour).
            float adj[8];
            for (int j = 0; j < 8; ++j) {
                float bg[4];
                for (int q = 0; q < 4; ++q) bg[q] = p[off1bg[j][q]];
                std::sort(bg, bg + 4);
                adj[j] = p[off1[j]] - 0.5f * (bg[1] + bg[2]);
            }
            const float adjLift = median8(adj);
            const bool notShared =
                lift > 0.0f && (med - b) < kShared * lift && adjLift < kShared * lift;
            // Per frame: the site over the median of its neighbours in that frame (the black is
            // the same for all of them, a site and its neighbours being one colour), and how far
            // each neighbour strays from that median.
            float e[kMaxFrames];
            float dev[8][kMaxFrames];
            for (int k = 0; k < N; ++k) {
                const uint16_t* d = data[(size_t)k] + i;
                float v[8];
                for (int j = 0; j < 8; ++j) nb[j] = v[j] = (float)d[off8[j]];
                const float m = median8(nb);
                e[k] = (float)d[0] - m;
                for (int j = 0; j < 8; ++j) dev[j][k] = v[j] - m;
            }
            // The neighbours' frame-to-frame change: each one's spread over the frames, the median.
            float spread[8];
            for (int j = 0; j < 8; ++j) {
                double s = 0.0, s2 = 0.0;
                for (int k = 0; k < N; ++k) {
                    s += dev[j][k];
                    s2 += (double)dev[j][k] * dev[j][k];
                }
                const double mu = s / N;
                spread[j] = (float)std::sqrt(std::max(0.0, s2 / N - mu * mu));
            }
            const float change = median8(spread);
            std::sort(e, e + N);
            const float p25 = e[(size_t)(0.25f * (float)(N - 1))];
            const float me = (N & 1) ? e[N / 2] : 0.5f * (e[N / 2 - 1] + e[N / 2]);
            const bool persistent = me > 0.0f && p25 >= 0.5f * me && me >= kSteady * change;
            flag[(size_t)c] = (uint8_t)((persistent ? 1 : 0) | (notShared ? 2 : 0));
            // Ranked by the median frame, which one frame's cosmic ray does not lift.
            strength[(size_t)c] = me;
        });
        for (size_t c = 0; c < cand.size(); ++c) {
            if (flag[c] & 1) {
                ++rep.persistent;
                if (flag[c] & 2) keep.push_back(Cand{cand[c].idx, strength[c]});
                else ++rep.shared;
            }
        }
    }
    band.clear();
    band.shrink_to_fit();

    // The cap of find_hot_pixels: 0.1 % of the pixels, strongest first.
    const size_t cap = std::max<size_t>(1, total / 1000);
    if (keep.size() > cap) {
        std::nth_element(keep.begin(), keep.begin() + cap, keep.end(),
                         [](const Cand& a, const Cand& b) { return a.excess > b.excess; });
        keep.resize(cap);
    }
    hot.reserve(keep.size());
    for (const Cand& c : keep) hot.push_back(c.idx);
    std::sort(hot.begin(), hot.end());
    rep.hot = (int)hot.size();
    SLOG("find_hot_pixels_lights: %d frames, threshold %.2f %.2f %.2f %.2f DN, %d candidates, "
         "%d persistent (%d of them lit their neighbours), %d hot",
         N, thr[0], thr[1], thr[2], thr[3], rep.candidates, rep.persistent, rep.shared, rep.hot);
    return hot;
}

}  // namespace

std::vector<uint32_t> find_hot_pixels_lights(const std::vector<FrameRecord>& frames,
                                             const SensorInfo& si, HotLightsReport* report,
                                             const std::atomic<bool>* abortFlag) {
    HotLightsReport local;
    HotLightsReport& rep = report != nullptr ? *report : local;
    rep = HotLightsReport();
    try {
        return find_impl(frames, si, rep, abortFlag);
    } catch (...) {
        SLOGE("find_hot_pixels_lights: exception (out of memory?), no list from the lights");
        rep.hot = 0;
        return std::vector<uint32_t>();
    }
}

std::vector<uint32_t> merge_hot_lists(const std::vector<uint32_t>& a, const std::vector<uint32_t>& b) {
    std::vector<uint32_t> out;
    out.reserve(a.size() + b.size());
    out.insert(out.end(), a.begin(), a.end());
    out.insert(out.end(), b.begin(), b.end());
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}

}  // namespace starcam

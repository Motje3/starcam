// EngineSession — native session orchestrator. See engine.h and PLAN.md §4, §6.6, §7.
#include "engine.h"

#include <sys/stat.h>
#include <sys/types.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>

#include "align.h"
#include "bayer.h"
#include "json_mini.h"
#include "mask.h"
#include "post.h"
#include "post_options.h"
#include "stack.h"
#include "stars.h"
#include "tiffout.h"

namespace starcam {

namespace {

// ---------- filesystem helpers ----------

bool is_sep(char c) { return c == '/' || c == '\\'; }

// mkdir -p: creates every prefix of `path`, ignoring EEXIST. Returns true if the final
// path exists as a directory afterwards.
bool make_dir_p(const std::string& path) {
    if (path.empty()) return false;
    for (size_t i = 1; i <= path.size(); ++i) {
        if (i == path.size() || is_sep(path[i])) {
            std::string pre = path.substr(0, i);
            if (pre.empty() || pre == "/") continue;
            if (pre.size() == 2 && pre[1] == ':') continue;  // host-build drive letter
            if (mkdir(pre.c_str(), 0775) != 0 && errno != EEXIST) {
                struct stat sb;
                if (stat(pre.c_str(), &sb) != 0 || !S_ISDIR(sb.st_mode)) {
                    SLOGE("make_dir_p: mkdir failed for %s (errno=%d)", pre.c_str(), errno);
                    return false;
                }
            }
        }
    }
    struct stat sb;
    return stat(path.c_str(), &sb) == 0 && S_ISDIR(sb.st_mode);
}

// True if the file exists and is at least large enough to hold an sraw header.
bool sraw_file_exists(const std::string& p) {
    if (p.empty()) return false;
    struct stat sb;
    if (stat(p.c_str(), &sb) != 0) return false;
    return (uint64_t)sb.st_size >= (uint64_t)kSrawHeaderSize;
}

// Whole-file read, capped: everything we read this way is a small sidecar written by our own
// Kotlin side, so a huge file means something is wrong and refusing beats allocating it.
bool read_text_file(const std::string& path, std::string* out, size_t maxBytes = 4u << 20) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    out->clear();
    char buf[8192];
    size_t got;
    bool ok = true;
    while ((got = fread(buf, 1, sizeof(buf), f)) > 0) {
        if (out->size() + got > maxBytes) { ok = false; break; }
        out->append(buf, got);
    }
    fclose(f);
    return ok;
}

/**
 * Loads meta/shading.json, written by the Kotlin side when the session starts (and therefore also
 * present for a later re-stack of the same session).
 *
 * Everything questionable returns an empty map, because the contract with the rest of the engine
 * is that no map means no change at all:
 *   - absent, unparseable, or the wrong length
 *   - any gain that is not finite, or outside [0.5, 16] (a map that far off is not a lens profile)
 *   - a map that is effectively identity (max gain < 1.02), which some HALs return when they have
 *     nothing to say — correcting by it would only cost time
 */
ShadingMap load_shading_map(const std::string& sessionDir) {
    ShadingMap sm;
    std::string text;
    const std::string p = sessionDir + "/meta/shading.json";
    if (!read_text_file(p, &text) || text.empty()) return sm;
    jsonmini::Obj o;
    if (!jsonmini::Obj::parse(text, &o)) {
        SLOGE("shading: %s is not parseable — ignoring", p.c_str());
        return sm;
    }
    const int cols = o.numi("cols", 0);
    const int rows = o.numi("rows", 0);
    const std::vector<double> g = o.arr("gains");
    if (cols < 2 || rows < 2 || g.size() != (size_t)cols * (size_t)rows * 3) {
        SLOGE("shading: %dx%d with %d values is inconsistent — ignoring", cols, rows, (int)g.size());
        return sm;
    }
    float maxG = 0.0f;
    std::vector<float> gains;
    gains.reserve(g.size());
    for (double v : g) {
        if (!std::isfinite(v) || v < 0.5 || v > 16.0) {
            SLOGE("shading: gain %.3f out of range — ignoring the whole map", v);
            return sm;
        }
        const float f = (float)v;
        gains.push_back(f);
        maxG = std::max(maxG, f);
    }
    if (maxG < 1.02f) {
        SLOG("shading: map is flat (max gain %.3f) — nothing to correct", maxG);
        return sm;
    }
    sm.cols = cols;
    sm.rows = rows;
    sm.gain = std::move(gains);
    sm.maxGain = maxG;
    SLOG("shading: loaded %dx%d map, max gain %.2f", cols, rows, maxG);
    return sm;
}

bool write_text_file(const std::string& path, const std::string& text) {
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = fwrite(text.data(), 1, text.size(), f) == text.size();
    if (fclose(f) != 0) ok = false;
    return ok;
}


// ---------- small stats helpers ----------

float median_fwhm_of(const std::vector<Star>& stars) {
    if (stars.empty()) return 0.0f;
    std::vector<float> f;
    f.reserve(stars.size());
    for (const Star& s : stars) f.push_back(s.fwhm);
    return median_of(f);
}

std::string error_json(const std::string& msg) {
    jsonmini::Builder b;
    b.add("ok", false).add("error", msg);
    return b.build();
}

// Hot pixels from the master dark: value > median + 6*MAD (subsampled stats),
// capped at 0.1% of pixels (worst offenders kept). Returned sorted ascending.
std::vector<uint32_t> find_hot_pixels(const Image16& dark) {
    std::vector<uint32_t> hot;
    if (dark.empty()) return hot;
    const size_t total = dark.d.size();
    size_t step = total / 200000;
    if (step < 1) step = 1;
    std::vector<float> s;
    s.reserve(total / step + 1);
    for (size_t i = 0; i < total; i += step) s.push_back((float)dark.d[i]);
    float med = median_of(s);
    for (float& v : s) v = std::fabs(v - med);
    float mad = median_of(s);
    float margin = 6.0f * mad;
    if (margin < 2.0f) margin = 2.0f;  // quantized darks: avoid flagging half the sensor
    const float thresh = med + margin;
    for (size_t i = 0; i < total; ++i) {
        if ((float)dark.d[i] > thresh) hot.push_back((uint32_t)i);
    }
    const size_t cap = std::max<size_t>(1, total / 1000);
    if (hot.size() > cap) {
        std::nth_element(hot.begin(), hot.begin() + cap, hot.end(),
                         [&dark](uint32_t a, uint32_t b) { return dark.d[a] > dark.d[b]; });
        hot.resize(cap);
    }
    std::sort(hot.begin(), hot.end());
    SLOG("find_hot_pixels: %d flagged (median=%.1f mad=%.2f)", (int)hot.size(), med, mad);
    return hot;
}

// Re-detect stars on the (linear) stack: half-res luma via 2x2 mean of channel means,
// then median FWHM of the top 30 by flux.
float measure_stack_fwhm(const ImageF& rgb) {
    if (rgb.empty() || rgb.ch != 3 || rgb.w < 4 || rgb.h < 4) return 0.0f;
    ImageF luma;
    luma.init(rgb.w / 2, rgb.h / 2, 1);
    const int lw = luma.w, lh = luma.h;
    const int rw = rgb.w, rh = rgb.h;
    parallel_for(0, lh, [&](int y) {
        for (int x = 0; x < lw; ++x) {
            int x0 = 2 * x, y0 = 2 * y;
            int x1 = std::min(x0 + 1, rw - 1), y1 = std::min(y0 + 1, rh - 1);
            const float* p00 = rgb.px(x0, y0);
            const float* p10 = rgb.px(x1, y0);
            const float* p01 = rgb.px(x0, y1);
            const float* p11 = rgb.px(x1, y1);
            float s = 0.0f;
            for (int c = 0; c < 3; ++c) s += p00[c] + p10[c] + p01[c] + p11[c];
            luma.d[(size_t)y * lw + x] = s * (1.0f / 12.0f);
        }
    });
    std::vector<Star> stars;
    SkyStats st;
    detect_stars(luma, DetectParams(), &stars, &st);
    if (stars.empty()) return 0.0f;
    const size_t n = std::min<size_t>(30, stars.size());  // sorted by flux desc already
    std::vector<float> f(n);
    for (size_t i = 0; i < n; ++i) f[i] = stars[i].fwhm;
    return median_of(f);
}

// Warp-accumulate one frame's half-res RGB into the running live-preview mean.
// T maps reference luma coords -> this frame's luma coords.
void accumulate_live(ImageF& liveSum, ImageF& liveCnt, const ImageF& rgb, const Transform& T) {
    if (rgb.empty() || rgb.ch != 3) return;
    if (liveSum.empty()) {
        liveSum.init(rgb.w, rgb.h, 3);
        liveCnt.init(rgb.w, rgb.h, 1);
    }
    const int dw = liveSum.w, dh = liveSum.h;
    const int sw = rgb.w, sh = rgb.h;
    if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;
    parallel_for(0, dh, [&](int y) {
        float* sumRow = &liveSum.d[(size_t)y * dw * 3];
        float* cntRow = &liveCnt.d[(size_t)y * dw];
        for (int x = 0; x < dw; ++x) {
            double sx, sy;
            T.apply((double)x, (double)y, &sx, &sy);
            // An "inside" test, so a NaN coordinate counts as outside.
            if (!(sx >= 0.0 && sy >= 0.0 && sx <= (double)(sw - 1) && sy <= (double)(sh - 1))) continue;
            int x0 = (int)sx, y0 = (int)sy;
            int x1 = std::min(x0 + 1, sw - 1), y1 = std::min(y0 + 1, sh - 1);
            float fx = (float)(sx - x0), fy = (float)(sy - y0);
            const float* p00 = rgb.px(x0, y0);
            const float* p10 = rgb.px(x1, y0);
            const float* p01 = rgb.px(x0, y1);
            const float* p11 = rgb.px(x1, y1);
            float w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy);
            float w01 = (1 - fx) * fy, w11 = fx * fy;
            float* sp = sumRow + (size_t)x * 3;
            for (int c = 0; c < 3; ++c)
                sp[c] += p00[c] * w00 + p10[c] * w10 + p01[c] * w01 + p11[c] * w11;
            cntRow[x] += 1.0f;
        }
    });
}

// In-place 3x3 CCM on an RGB ImageF (used by the TRAILS path so its colors match the
// ASTRO stack, which applies the CCM inside stack_frames).
void apply_ccm_inplace(ImageF& rgb, const float ccm[9]) {
    if (rgb.empty() || rgb.ch != 3) return;
    const int w = rgb.w;
    parallel_for(0, rgb.h, [&](int y) {
        float* p = rgb.px(0, y);
        for (int x = 0; x < w; ++x, p += 3) {
            float r = p[0], g = p[1], b = p[2];
            p[0] = std::max(0.0f, ccm[0] * r + ccm[1] * g + ccm[2] * b);
            p[1] = std::max(0.0f, ccm[3] * r + ccm[4] * g + ccm[5] * b);
            p[2] = std::max(0.0f, ccm[6] * r + ccm[7] * g + ccm[8] * b);
        }
    });
}

// Box-averages an image down by a whole factor so its long edge is at most maxEdge.
ImageF box_downscale(const ImageF& in, int maxEdge) {
    const int f = std::max(1, (std::max(in.w, in.h) + maxEdge - 1) / maxEdge);
    ImageF out;
    out.init(std::max(1, in.w / f), std::max(1, in.h / f), in.ch);
    const float inv = 1.0f / (float)(f * f);
    parallel_for(0, out.h, [&](int y) {
        for (int x = 0; x < out.w; ++x) {
            float* o = out.px(x, y);
            for (int c = 0; c < in.ch; ++c) o[c] = 0.0f;
            for (int yy = 0; yy < f; ++yy) {
                const float* s = in.px(x * f, y * f + yy);
                for (int xx = 0; xx < f; ++xx)
                    for (int c = 0; c < in.ch; ++c) o[c] += s[xx * in.ch + c];
            }
            for (int c = 0; c < in.ch; ++c) o[c] *= inv;
        }
    });
    return out;
}

// METEOR: lighten-blend each cls==0 streak, warped into reference geometry, onto the linear
// stack - along the streak only: a feathered band around its line, not the whole bounding box,
// which brought back one frame's noise and brightened every ordinary star inside the box.
// Returns the number of candidates blended.
// outFrameIndices (nullable): receives the deduplicated frameIndex of each blended candidate.
int blend_meteors(const std::vector<Streak>& streaks, const std::vector<FrameRecord>& frames,
                  int refPos, const SensorInfo& si, const Image16& masterDark,
                  const float* darkPedestal, const float* darkBlack,
                  const std::vector<uint32_t>& hotIdx, bool hotFix, const ShadingMap* shading,
                  ImageF& stack, std::vector<int>* outFrameIndices) {
    // Half-width of the band blended at full strength around the streak's centre line, and the
    // width over which it fades out (RAW pixels). The region below is padded by 24.
    constexpr double kCore = 8.0;
    constexpr double kFeather = 12.0;
    int count = 0;
    std::vector<uint16_t> region;
    for (const Streak& s : streaks) {
        if (s.cls != 0) continue;
        int pos = -1;
        for (size_t i = 0; i < frames.size(); ++i) {
            if (frames[i].meta.index == s.frameIndex) { pos = (int)i; break; }
        }
        if (pos < 0) continue;
        const FrameRecord& fr = frames[pos];
        if (fr.srawPath.empty()) continue;
        // A frame left out of the stack (bumped, trailed, unaligned) is left out here too.
        if (fr.rejected) continue;
        if (!fr.aligned && pos != refPos) continue;  // cannot warp an unaligned frame
        SrawReader r;
        if (!r.open(fr.srawPath)) continue;
        const int W = (int)r.header().width, H = (int)r.header().height;
        if (W < 4 || H < 4) continue;
        // Streak bbox: luma coords *2 -> raw coords, pad 24, snap even.
        int bx0 = (int)std::floor((double)std::min(s.x0, s.x1) * 2.0) - 24;
        int by0 = (int)std::floor((double)std::min(s.y0, s.y1) * 2.0) - 24;
        int bx1 = (int)std::ceil((double)std::max(s.x0, s.x1) * 2.0) + 24;
        int by1 = (int)std::ceil((double)std::max(s.y0, s.y1) * 2.0) + 24;
        bx0 = clampi(bx0, 0, W - 2) & ~1;
        by0 = clampi(by0, 0, H - 2) & ~1;
        bx1 = clampi(bx1, bx0 + 1, W - 1);
        by1 = clampi(by1, by0 + 1, H - 1);
        const int rw = bx1 - bx0 + 1, rh = by1 - by0 + 1;
        copy_region_u16(r.data(), W, H, bx0, by0, rw, rh, &region);
        if (region.size() < (size_t)rw * (size_t)rh) continue;  // out of memory
        // Per-frame dynamic black, matching stack.cpp — otherwise the blended region gets a
        // different black offset than the surrounding stack (visible brightness step).
        SensorInfo siK = si;
        for (int b = 0; b < 4; ++b) siK.blackLevel[b] = r.header().blackLevel[b];
        if (!masterDark.empty() && masterDark.w == W && masterDark.h == H)
            subtract_dark_region(region, rw, rh, bx0, by0, masterDark, siK.blackLevel,
                                 darkPedestal, darkBlack);
        if (hotFix && !hotIdx.empty() && W == si.width && H == si.height)
            fix_hot_pixels_region(region, rw, rh, bx0, by0, W, H, hotIdx);
        ImageF rgb;
        demosaic_region_malvar(region.data(), rw, rh, siK, r.header().wbGain, &rgb);
        if (rgb.w != rw || rgb.h != rh || rgb.empty()) continue;
        // The same vignetting correction the stack under it had.
        if (shading != nullptr) apply_shading_region(rgb, bx0, by0, W, H, *shading);
        const float* ccm = r.header().ccm;
        // The streak's centre line in this frame's RAW coordinates (luma u -> RAW 2u + 0.5).
        const double ax = 2.0 * s.x0 + 0.5, ay = 2.0 * s.y0 + 0.5;
        const double vx = 2.0 * (s.x1 - s.x0), vy = 2.0 * (s.y1 - s.y0);
        const double vv = vx * vx + vy * vy;
        Transform Traw = fr.T.lumaToRaw();  // ref raw -> frame raw
        Transform inv = Traw.inverse();
        // Bounding box of the region in reference coords.
        double cx[4] = {(double)bx0, (double)bx1, (double)bx0, (double)bx1};
        double cy[4] = {(double)by0, (double)by0, (double)by1, (double)by1};
        double minx = 1e18, miny = 1e18, maxx = -1e18, maxy = -1e18;
        for (int k = 0; k < 4; ++k) {
            double ox, oy;
            inv.apply(cx[k], cy[k], &ox, &oy);
            minx = std::min(minx, ox); maxx = std::max(maxx, ox);
            miny = std::min(miny, oy); maxy = std::max(maxy, oy);
        }
        const int px0 = floor_clamp(minx - 2.0, 0, stack.w - 1);
        const int py0 = floor_clamp(miny - 2.0, 0, stack.h - 1);
        const int px1 = ceil_clamp(maxx + 2.0, 0, stack.w - 1);
        const int py1 = ceil_clamp(maxy + 2.0, 0, stack.h - 1);
        for (int py = py0; py <= py1; ++py) {
            for (int px = px0; px <= px1; ++px) {
                double sx, sy;
                Traw.apply((double)px, (double)py, &sx, &sy);
                const double lx = sx - bx0, ly = sy - by0;
                if (!(lx >= 0.0 && ly >= 0.0 && lx <= (double)(rw - 1) && ly <= (double)(rh - 1)))
                    continue;  // outside the region, or not finite
                // Distance from the streak's line segment decides how much of this frame shows.
                double t = vv > 0.0 ? ((sx - ax) * vx + (sy - ay) * vy) / vv : 0.0;
                t = std::min(1.0, std::max(0.0, t));
                const double ddx = sx - (ax + t * vx), ddy = sy - (ay + t * vy);
                const double dist = std::sqrt(ddx * ddx + ddy * ddy);
                if (!(dist < kCore + kFeather)) continue;
                float wgt = 1.0f;
                if (dist > kCore) {
                    const float f = (float)((dist - kCore) / kFeather);
                    wgt = 1.0f - f * f * (3.0f - 2.0f * f);  // smoothstep falloff
                }
                int x0 = (int)lx, y0 = (int)ly;
                int x1 = std::min(x0 + 1, rw - 1), y1 = std::min(y0 + 1, rh - 1);
                float fx = (float)(lx - x0), fy = (float)(ly - y0);
                float smp[3];
                for (int c = 0; c < 3; ++c) {
                    float v00 = rgb.px(x0, y0)[c], v10 = rgb.px(x1, y0)[c];
                    float v01 = rgb.px(x0, y1)[c], v11 = rgb.px(x1, y1)[c];
                    smp[c] = v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
                             v01 * (1 - fx) * fy + v11 * fx * fy;
                }
                float out0 = std::max(0.0f, ccm[0] * smp[0] + ccm[1] * smp[1] + ccm[2] * smp[2]);
                float out1 = std::max(0.0f, ccm[3] * smp[0] + ccm[4] * smp[1] + ccm[5] * smp[2]);
                float out2 = std::max(0.0f, ccm[6] * smp[0] + ccm[7] * smp[1] + ccm[8] * smp[2]);
                float* dp = stack.px(px, py);
                dp[0] += wgt * std::max(0.0f, out0 - dp[0]);
                dp[1] += wgt * std::max(0.0f, out1 - dp[1]);
                dp[2] += wgt * std::max(0.0f, out2 - dp[2]);
            }
        }
        if (outFrameIndices != nullptr &&
            std::find(outFrameIndices->begin(), outFrameIndices->end(), s.frameIndex) ==
                outFrameIndices->end()) {
            outFrameIndices->push_back(s.frameIndex);
        }
        ++count;
    }
    return count;
}

std::string frame_stats_json(int index, bool saved, int starCount, float medianFwhm, float bgFrac,
                             const Transform& T, int inliers, float residual, bool aligned,
                             int streaks) {
    jsonmini::Builder b;
    b.add("index", index)
        .add("saved", saved)
        .add("starCount", starCount)
        .add("medianFwhm", (double)medianFwhm)
        .add("bgFrac", (double)bgFrac)
        .add("dx", T.tx)
        .add("dy", T.ty)
        .add("rotDeg", T.rotationDeg())
        .add("inliers", inliers)
        .add("residual", (double)residual)
        .add("aligned", aligned)
        .add("streaks", streaks);
    return b.build();
}

}  // namespace

// ---------- EngineSession ----------

EngineSession* EngineSession::create(const EngineConfig& cfg) {
    try {
        if (cfg.sessionDir.empty()) {
            SLOGE("EngineSession::create: empty sessionDir");
            return nullptr;
        }
        if (!make_dir_p(cfg.sessionDir + "/cache") || !make_dir_p(cfg.sessionDir + "/out")) {
            SLOGE("EngineSession::create: cannot create dirs under %s", cfg.sessionDir.c_str());
            return nullptr;
        }
        EngineSession* e = new EngineSession();
        e->cfg_ = cfg;
        SLOG("EngineSession created: %s mode=%d sensor=%dx%d cfa=%d", cfg.sessionDir.c_str(),
             (int)cfg.mode, cfg.sensor.width, cfg.sensor.height, (int)cfg.sensor.cfa);
        return e;
    } catch (...) {
        SLOGE("EngineSession::create: exception");
        return nullptr;
    }
}

EngineSession::~EngineSession() {
    SLOG("EngineSession destroyed");
}

std::string EngineSession::addFrame(const uint16_t* bayer, int w, int h, int strideElems,
                                    const FrameMeta& meta) {
    try {
        std::lock_guard<std::mutex> lk(mu_);
        if (!bayer || w < 2 || h < 2 || strideElems < w) {
            SLOGE("addFrame: bad args w=%d h=%d stride=%d", w, h, strideElems);
            return frame_stats_json(meta.index, false, 0, 0, 0, Transform(), 0, 0, false, 0);
        }
        const int fileNo = frameCounter_++;
        FrameRecord rec;
        rec.meta = meta;
        if (rec.meta.index < 0) rec.meta.index = fileNo;

        // 1) Persist to cache (crash-safe). A failed write keeps the record alive so the
        //    live preview still works; finalize will skip the missing file.
        SrawHeader hdr;
        hdr.width = (uint32_t)w;
        hdr.height = (uint32_t)h;
        hdr.cfa = cfg_.sensor.cfa;
        hdr.whiteLevel = cfg_.sensor.whiteLevel;
        for (int i = 0; i < 4; ++i) {
            // Per-frame dynamic black when the capture side provided it (the sensor's black
            // point drifts upward as it warms); the static characteristics value otherwise.
            hdr.blackLevel[i] =
                meta.hasBlackLevel ? meta.blackLevel[i] : cfg_.sensor.blackLevel[i];
            hdr.wbGain[i] = meta.wbGain[i];
        }
        for (int i = 0; i < 9; ++i) hdr.ccm[i] = meta.ccm[i];
        hdr.exposureNs = meta.exposureNs > 0 ? (uint64_t)meta.exposureNs : 0;
        hdr.iso = meta.iso > 0 ? (uint32_t)meta.iso : 0;
        hdr.timestampNs = meta.timestampNs > 0 ? (uint64_t)meta.timestampNs : 0;
        // A re-stack re-feeds frames from their cache files; those are used as they are.
        // Rewriting them truncated files that another engine (a second re-stack, the session
        // still finalizing) had mapped (SIGBUS), and a kill mid-write cut a kept frame short.
        bool saved;
        std::string path;
        if (!meta.sourcePath.empty() && sraw_file_exists(meta.sourcePath)) {
            path = meta.sourcePath;
            saved = true;
        } else {
            char name[48];
            snprintf(name, sizeof(name), "/cache/frame_%04d.sraw", fileNo);
            path = cfg_.sessionDir + name;
            saved = sraw_write(path, hdr, bayer, strideElems);
        }
        if (saved) {
            rec.srawPath = path;
        } else {
            SLOGE("addFrame: sraw write failed for %s (disk full?)", path.c_str());
        }

        // 2) Luma + stars.
        ImageF luma;
        superpixel_luma(bayer, w, h, strideElems, cfg_.sensor, meta.wbGain, &luma);
        SkyStats stats;
        detect_stars(luma, DetectParams(), &rec.stars, &stats);
        rec.bgFrac = stats.bgMedian;
        rec.noiseSigma = stats.noiseSigma;
        rec.medianFwhm = median_fwhm_of(rec.stars);

        // 3) Reference / incremental alignment.
        if (refIndex_ < 0) {
            if ((int)rec.stars.size() >= 10) {
                refIndex_ = (int)frames_.size();  // this record's position in frames_
                refStars_ = rec.stars;
                rec.T = Transform();
                rec.aligned = true;
                rec.inliers = (int)rec.stars.size();
                rec.residual = 0.0f;
                prevT_ = Transform();
                SLOG("addFrame: frame %d is reference (%d stars, fwhm %.2f)", rec.meta.index,
                     (int)rec.stars.size(), rec.medianFwhm);
            }
            // else: pre-reference frame, stays unaligned with identity T.
        } else {
            AlignParams ap;
            AlignResult ar = align_stars(refStars_, rec.stars, prevT_, ap);
            if (!ar.ok) ar = align_bootstrap(refStars_, rec.stars, ap);
            if (ar.ok) {
                rec.T = ar.T;
                rec.aligned = true;
                rec.inliers = ar.inliers;
                rec.residual = ar.residual;
                prevT_ = ar.T;
            } else {
                rec.T = prevT_;  // best guess; finalize's refine pass may still rescue it
                rec.aligned = false;
                SLOGE("addFrame: alignment failed for frame %d (%d stars)", rec.meta.index,
                      (int)rec.stars.size());
            }
        }

        // 4) Mode accumulators / preview.
        int streakCount = 0;
        if (cfg_.mode == SessionMode::ASTRO || cfg_.mode == SessionMode::PRO) {
            if (cfg_.livePreview) {
                ImageF rgb;
                superpixel_rgb(bayer, w, h, strideElems, cfg_.sensor, meta.wbGain, &rgb);
                accumulate_live(liveSum_, liveCnt_, rgb, rec.T);
            }
        } else {  // TRAILS / METEOR
            ImageF rgb;
            superpixel_rgb(bayer, w, h, strideElems, cfg_.sensor, meta.wbGain, &rgb);
            trailsPreview_.add(rgb);
            if (cfg_.mode == SessionMode::METEOR) {
                std::vector<Streak> found = meteor_.addFrame(luma, rec.meta.index);
                streakCount = (int)found.size();
            }
        }

        std::string out = frame_stats_json(rec.meta.index, saved, (int)rec.stars.size(),
                                           rec.medianFwhm, rec.bgFrac, rec.T, rec.inliers,
                                           rec.residual, rec.aligned, streakCount);
        frames_.push_back(std::move(rec));
        return out;
    } catch (...) {
        SLOGE("addFrame: exception");
        return frame_stats_json(meta.index, false, 0, 0, 0, Transform(), 0, 0, false, 0);
    }
}

std::string EngineSession::addDark(const uint16_t* bayer, int w, int h, int strideElems,
                                   const FrameMeta& meta) {
    try {
        std::lock_guard<std::mutex> lk(mu_);
        const int idx = darkCounter_++;
        double mean = 0.0;
        if (bayer && w > 0 && h > 0 && strideElems >= w && w == cfg_.sensor.width &&
            h == cfg_.sensor.height) {
            if (darkSum_.empty()) darkSum_.assign((size_t)w * h, 0.0f);
            if (darkSum_.size() == (size_t)w * h) {
                double total = 0.0;
                for (int y = 0; y < h; ++y) {
                    const uint16_t* row = bayer + (size_t)y * strideElems;
                    float* dst = &darkSum_[(size_t)y * w];
                    for (int x = 0; x < w; ++x) {
                        dst[x] += (float)row[x];
                        total += (double)row[x];
                    }
                }
                mean = total / ((double)w * (double)h);
                // The dark's own (dynamic) black when the capture side sent it, like a light's.
                for (int i = 0; i < 4; ++i) {
                    darkBlackSum_[i] +=
                        meta.hasBlackLevel ? meta.blackLevel[i] : cfg_.sensor.blackLevel[i];
                }
                darkCount_++;
            }
        } else {
            SLOGE("addDark: bad args or geometry mismatch (got %dx%d, sensor %dx%d)", w, h,
                  cfg_.sensor.width, cfg_.sensor.height);
        }
        jsonmini::Builder b;
        b.add("index", idx).add("darkCount", darkCount_).add("meanLevel", mean);
        return b.build();
    } catch (...) {
        SLOGE("addDark: exception");
        jsonmini::Builder b;
        b.add("index", -1).add("darkCount", 0).add("meanLevel", 0.0);
        return b.build();
    }
}

void EngineSession::discardDarks() {
    std::lock_guard<std::mutex> lk(mu_);
    if (darkCount_ > 0) SLOG("discardDarks: dropping %d dark frame(s)", darkCount_);
    darkSum_.clear();
    darkSum_.shrink_to_fit();
    darkCount_ = 0;
    for (double& b : darkBlackSum_) b = 0.0;
}

void EngineSession::rejectFrame(int index, const std::string& reason) {
    try {
        std::lock_guard<std::mutex> lk(mu_);
        for (FrameRecord& fr : frames_) {
            if (fr.meta.index == index) {
                fr.rejected = true;
                fr.rejectReason = reason.empty() ? "rejected" : reason;
                SLOG("rejectFrame: %d (%s)", index, fr.rejectReason.c_str());
                return;
            }
        }
        SLOGE("rejectFrame: index %d not found", index);
    } catch (...) {
        SLOGE("rejectFrame: exception");
    }
}

bool EngineSession::getPreview(uint32_t* argbOut, int outW, int outH) {
    try {
        if (!argbOut || outW <= 0 || outH <= 0) return false;
        std::lock_guard<std::mutex> lk(mu_);
        const bool trailsMode =
            (cfg_.mode == SessionMode::TRAILS || cfg_.mode == SessionMode::METEOR);
        const ImageF* src = nullptr;
        const ImageF* cnt = nullptr;
        if (trailsMode) {
            if (trailsPreview_.empty()) return false;
            src = &trailsPreview_.result();
        } else {
            if (liveSum_.empty() || liveCnt_.empty()) return false;
            src = &liveSum_;
            cnt = &liveCnt_;
        }
        const int sw = src->w, sh = src->h;
        if (sw <= 0 || sh <= 0 || src->ch != 3) return false;

        auto fetch = [src, cnt, sw](int x, int y, float rgb[3]) {
            const float* p = src->px(x, y);
            if (cnt) {
                const float c = cnt->d[(size_t)y * sw + x];
                if (c > 0.0f) {
                    rgb[0] = p[0] / c; rgb[1] = p[1] / c; rgb[2] = p[2] / c;
                } else {
                    rgb[0] = rgb[1] = rgb[2] = 0.0f;
                }
            } else {
                rgb[0] = p[0]; rgb[1] = p[1]; rgb[2] = p[2];
            }
        };

        // Black point: 10th percentile of subsampled mean-luma.
        const size_t total = (size_t)sw * sh;
        size_t step = total / 20000;
        if (step < 1) step = 1;
        std::vector<float> samples;
        samples.reserve(total / step + 1);
        for (size_t i = 0; i < total; i += step) {
            const int x = (int)(i % (size_t)sw);
            const int y = (int)(i / (size_t)sw);
            float rgb[3];
            fetch(x, y, rgb);
            samples.push_back((rgb[0] + rgb[1] + rgb[2]) * (1.0f / 3.0f));
        }
        const float black = percentile_of(samples, 0.10f);
        const float inv = 1.0f / std::asinh(200.0f);

        // Aspect-fit with black bars, nearest neighbor, turned upright like the final photo.
        const int rot = cfg_.rotation;
        int rw, rh;
        rotated_size(rot, sw, sh, &rw, &rh);
        const double scale = std::min((double)outW / rw, (double)outH / rh);
        int drawW = std::max(1, (int)std::lround(rw * scale));
        int drawH = std::max(1, (int)std::lround(rh * scale));
        if (drawW > outW) drawW = outW;
        if (drawH > outH) drawH = outH;
        const int ox = (outW - drawW) / 2;
        const int oy = (outH - drawH) / 2;

        std::fill(argbOut, argbOut + (size_t)outW * outH, 0xFF000000u);
        parallel_for(0, drawH, [&](int yy) {
            const int ry = std::min(rh - 1, (int)(((int64_t)yy * rh) / drawH));
            uint32_t* dst = argbOut + (size_t)(oy + yy) * outW + ox;
            for (int xx = 0; xx < drawW; ++xx) {
                const int rx = std::min(rw - 1, (int)(((int64_t)xx * rw) / drawW));
                int sx, sy;
                rotated_source(rot, sw, sh, rx, ry, &sx, &sy);
                float rgb[3];
                fetch(sx, sy, rgb);
                uint32_t px = 0xFF000000u;
                for (int c = 0; c < 3; ++c) {
                    float v = std::asinh(200.0f * std::max(0.0f, rgb[c] - black)) * inv;
                    v = clampf(v, 0.0f, 1.0f);
                    px |= (uint32_t)(uint8_t)(v * 255.0f + 0.5f) << (16 - 8 * c);
                }
                dst[xx] = px;
            }
        });
        return true;
    } catch (...) {
        SLOGE("getPreview: exception");
        return false;
    }
}

void EngineSession::abort() {
    abort_.store(true);
    SLOG("EngineSession: abort requested");
}

std::string EngineSession::finalizeStack(const std::string& optionsJson,
                                         const ProgressFn& progress) {
    const auto t0 = std::chrono::steady_clock::now();
    auto report = [&progress](const char* stage, int pct) {
        if (progress) progress(stage, pct);
    };
    try {
        if (abort_.load()) return error_json("aborted");

        // ---- Copy state under a short lock; getPreview must stay responsive. ----
        std::vector<FrameRecord> frames;
        std::vector<Star> refStars;
        int refPos = -1;
        Image16 masterDark;
        std::vector<Streak> streaks;
        int darkFrames = 0;
        // The black level the master dark was shot at (raster order).
        float darkBlack[4] = {cfg_.sensor.blackLevel[0], cfg_.sensor.blackLevel[1],
                              cfg_.sensor.blackLevel[2], cfg_.sensor.blackLevel[3]};
        {
            std::lock_guard<std::mutex> lk(mu_);
            frames = frames_;
            refStars = refStars_;
            refPos = refIndex_;
            darkFrames = darkCount_;
            if (darkCount_ > 0 &&
                darkSum_.size() == (size_t)cfg_.sensor.width * (size_t)cfg_.sensor.height) {
                masterDark.init(cfg_.sensor.width, cfg_.sensor.height);
                const double invC = 1.0 / (double)darkCount_;
                for (size_t i = 0; i < darkSum_.size(); ++i) {
                    masterDark.d[i] =
                        (uint16_t)clampi((int)std::lround(darkSum_[i] * invC), 0, 65535);
                }
                for (int i = 0; i < 4; ++i) darkBlack[i] = (float)(darkBlackSum_[i] * invC);
            }
            if (cfg_.mode == SessionMode::METEOR) streaks = meteor_.finalizeClassification();
        }
        // No darks this session, but a master dark may still be sitting in the cache: either
        // the dark library (Kotlin copies a matching one in before the session starts) or a
        // previous run of this same session, when re-stacking. Loading it is what makes dark
        // reuse work at all. Mismatched geometry is ignored rather than trusted.
        bool darkReused = false;
        if (masterDark.empty()) {
            const std::string inPath = cfg_.sessionDir + "/cache/master_dark.sraw";
            SrawReader dr;
            if (dr.open(inPath)) {
                const SrawHeader& dh = dr.header();
                if ((int)dh.width == cfg_.sensor.width && (int)dh.height == cfg_.sensor.height &&
                    dh.cfa == cfg_.sensor.cfa) {
                    masterDark.init(cfg_.sensor.width, cfg_.sensor.height);
                    std::memcpy(masterDark.d.data(), dr.data(),
                                masterDark.d.size() * sizeof(uint16_t));
                    for (int i = 0; i < 4; ++i) darkBlack[i] = dh.blackLevel[i];
                    darkReused = true;
                    SLOG("finalize: reusing master dark from %s", inPath.c_str());
                } else {
                    SLOGE("finalize: cached master dark is %ux%u cfa %u, session is %dx%d cfa %u"
                          " — ignored",
                          dh.width, dh.height, (unsigned)dh.cfa, cfg_.sensor.width,
                          cfg_.sensor.height, (unsigned)cfg_.sensor.cfa);
                }
            }
        }
        const char* darkSource = darkFrames > 0 ? "captured" : (darkReused ? "library" : "none");

        // Persist the master dark so a later re-stack of a kept cache can reuse it. Header
        // defaults cover wb (all 1), ccm (identity) and exposure/iso/timestamp (0). Non-fatal.
        if (!masterDark.empty() && !darkReused) {
            SrawHeader dh;
            dh.width = (uint32_t)masterDark.w;
            dh.height = (uint32_t)masterDark.h;
            dh.cfa = cfg_.sensor.cfa;
            dh.whiteLevel = cfg_.sensor.whiteLevel;
            // Its own baseline, so a re-stack or a later session using it as a library dark
            // subtracts it relative to the black it was actually shot at.
            for (int i = 0; i < 4; ++i) dh.blackLevel[i] = darkBlack[i];
            const std::string darkPath = cfg_.sessionDir + "/cache/master_dark.sraw";
            if (sraw_write(darkPath, dh, masterDark.d.data(), masterDark.w)) {
                SLOG("finalize: master dark (%d frames) saved to %s", darkFrames,
                     darkPath.c_str());
            } else {
                SLOGE("finalize: master dark save failed for %s", darkPath.c_str());
            }
        }
        const SessionMode mode = cfg_.mode;
        const std::string outDir = cfg_.sessionDir + "/out";
        if (frames.empty()) return error_json("no frames");

        // ---- prepare (0-5) ----
        report("prepare", 0);
        jsonmini::Obj opt;
        if (!optionsJson.empty() && !jsonmini::Obj::parse(optionsJson, &opt)) {
            SLOGE("finalize: options JSON parse failed, using defaults");
        }
        StackParams sp;
        if (opt.has("sigmaK")) {
            sp.sigmaFinal = (float)opt.num("sigmaK", sp.sigmaFinal);
            sp.sigmaFirst = sp.sigmaFinal + 0.7f;
        }
        sp.sigmaFirst = (float)opt.num("sigmaFirst", sp.sigmaFirst);
        sp.sigmaFinal = (float)opt.num("sigmaFinal", sp.sigmaFinal);
        sp.hotPixelFix = opt.boolean("hotPixelFix", true);
        sp.applyCCM = true;
        const bool fgComposite = opt.boolean("foregroundComposite", false);
        const float featherPx = (float)opt.num("featherPx", 40.0);
        PostParams pp = read_post_params(opt);
        // Clockwise turn that makes the photo upright (from the phone's attitude at capture):
        // the outputs are written turned, and the post pipeline knows where the ground is -
        // unless the phone pointed too near the zenith to tell and the turn is only a guess.
        const int rawRotation = opt.numi("rotation", -1);
        const int rotation = rawRotation >= 0 ? normalize_rotation(rawRotation) : 0;
        const bool rotationGuessed = opt.boolean("rotationGuessed", false);
        pp.downEdge = rawRotation >= 0 && !rotationGuessed ? down_edge_for_rotation(rotation) : -1;

        const std::vector<uint32_t> hotIdx = find_hot_pixels(masterDark);
        // A master dark from the dark library comes from another night, with another black
        // point: subtract only its structure. Kotlin leaves a marker beside the copy it installs
        // (a session with a library dark shoots none of its own), and a re-stack - which feeds
        // the same file back as a dark frame - finds it too. Darks shot in the session keep the
        // original subtraction.
        struct stat libSb;
        const bool darkFromLibrary =
            !masterDark.empty() &&
            stat((cfg_.sessionDir + "/cache/master_dark.library").c_str(), &libSb) == 0;
        float darkPed[4] = {0, 0, 0, 0};
        const float* darkPedPtr = nullptr;
        if (darkFromLibrary) {
            dark_pedestal(masterDark, darkPed);
            darkPedPtr = darkPed;
            SLOG("finalize: library dark, pedestal %.1f %.1f %.1f %.1f", darkPed[0], darkPed[1],
                 darkPed[2], darkPed[3]);
        }
        sp.darkPedestal = darkPedPtr;
        const float* darkBlackPtr = masterDark.empty() ? nullptr : darkBlack;
        sp.darkBlack = darkBlackPtr;
        if (darkBlackPtr != nullptr) {
            SLOG("finalize: master dark black %.1f %.1f %.1f %.1f", darkBlack[0], darkBlack[1],
                 darkBlack[2], darkBlack[3]);
        }
        // Off unless the Kotlin side both wrote a map and left the toggle on.
        const ShadingMap shading =
            opt.boolean("lensShading", true) ? load_shading_map(cfg_.sessionDir) : ShadingMap();
        sp.shading = shading.empty() ? nullptr : &shading;
        report("prepare", 5);
        if (abort_.load()) return error_json("aborted");

        // ---- align (5-15): refine every frame against the reference, then reject. ----
        const bool needAlign = (mode != SessionMode::TRAILS);
        if (needAlign) {
            if (refPos < 0 || refPos >= (int)frames.size() || refStars.empty())
                return error_json("no reference frame (too few stars)");
            FrameRecord& ref = frames[refPos];
            ref.T = Transform();  // reference must be identity — force it
            ref.aligned = true;
            ref.inliers = (int)refStars.size();
            ref.residual = 0.0f;
            if (!sraw_file_exists(ref.srawPath))
                return error_json("reference frame cache missing");
            AlignParams ap;
            const int n = (int)frames.size();
            for (int i = 0; i < n; ++i) {
                if (abort_.load()) return error_json("aborted");
                FrameRecord& fr = frames[i];
                if (i != refPos && !fr.rejected && !fr.stars.empty()) {
                    AlignResult r = align_stars(refStars, fr.stars, fr.T, ap);
                    if (r.ok) {
                        fr.T = r.T;
                        fr.aligned = true;
                        fr.inliers = r.inliers;
                        fr.residual = r.residual;
                    }
                }
                report("align", 5 + (10 * (i + 1)) / n);
            }
            // Rejections per PLAN §4.3 (gyro rejections already applied via rejectFrame).
            const float refStarN = (float)frames[refPos].stars.size();
            const float refFwhm = frames[refPos].medianFwhm;
            for (int i = 0; i < n; ++i) {
                if (i == refPos) continue;
                FrameRecord& fr = frames[i];
                if (fr.rejected) continue;
                if (!sraw_file_exists(fr.srawPath)) {
                    fr.rejected = true; fr.rejectReason = "cache-missing";
                } else if (refStarN > 0 && (float)fr.stars.size() < 0.25f * refStarN) {
                    fr.rejected = true; fr.rejectReason = "few-stars";
                } else if (refFwhm > 0 && fr.medianFwhm > 1.8f * refFwhm) {
                    fr.rejected = true; fr.rejectReason = "soft-fwhm";
                } else if (!fr.aligned) {
                    fr.rejected = true; fr.rejectReason = "unaligned";
                } else if (fr.residual > 2.0f || fr.inliers < 8) {
                    fr.rejected = true; fr.rejectReason = "align-poor";
                }
            }
            // Best-reference selection: among surviving frames pick the one with many stars
            // and small FWHM, then rebase every transform onto it so CCM and output geometry
            // follow the sharpest frame instead of merely the first good one.
            {
                int best = refPos;
                float bestScore = -1.0f;
                const float fwhmNorm = std::max(refFwhm, 0.1f);
                for (int i = 0; i < n; ++i) {
                    const FrameRecord& fr = frames[i];
                    if (fr.rejected || !fr.aligned) continue;
                    const float score =
                        (float)fr.stars.size() * std::exp(-fr.medianFwhm / fwhmNorm);
                    if (score > bestScore) {
                        bestScore = score;
                        best = i;
                    }
                }
                if (best != refPos) {
                    // T maps reference luma coords -> frame luma coords. Rebasing onto frame
                    // r: T_new_i = T_i.compose(Tr.inverse()); frame r itself becomes identity
                    // (forced exactly, to kill fp drift from compose(inverse)).
                    const Transform trInv = frames[best].T.inverse();
                    for (FrameRecord& fr : frames) fr.T = fr.T.compose(trInv);
                    FrameRecord& nref = frames[best];
                    nref.T = Transform();
                    nref.aligned = true;
                    nref.inliers = (int)nref.stars.size();
                    nref.residual = 0.0f;
                    SLOG("finalize: reference rebased %d -> %d (frame %d -> %d, score %.1f)",
                         refPos, best, frames[refPos].meta.index, nref.meta.index, bestScore);
                    refPos = best;
                    refStars = nref.stars;
                } else {
                    SLOG("finalize: reference kept at %d (score %.1f)", refPos, bestScore);
                }
            }
            // Per-frame stacking weights: inverse noise variance times squared sharpness
            // ratio, normalized so the best frame is 1.0. Floor at 0.25 — rejection handles
            // true outliers, weighting only de-emphasizes hazier/softer frames.
            {
                float fwhmBest = 1e9f;
                for (int i = 0; i < n; ++i)
                    if (!frames[i].rejected)
                        fwhmBest = std::min(fwhmBest, frames[i].medianFwhm);
                if (fwhmBest <= 0.0f || fwhmBest >= 1e8f) fwhmBest = 0.1f;
                float maxW = 0.0f;
                for (int i = 0; i < n; ++i) {
                    FrameRecord& fr = frames[i];
                    if (fr.rejected) continue;
                    const float ns = std::max(fr.noiseSigma, 1e-6f);
                    const float sharp = fwhmBest / std::max(fr.medianFwhm, 0.1f);
                    fr.weight = (1.0f / (ns * ns)) * sharp * sharp;
                    maxW = std::max(maxW, fr.weight);
                }
                if (maxW > 0.0f) {
                    float loW = 1.0f, hiW = 0.0f;
                    for (int i = 0; i < n; ++i) {
                        FrameRecord& fr = frames[i];
                        if (fr.rejected) continue;
                        fr.weight = clampf(fr.weight / maxW, 0.25f, 1.0f);
                        loW = std::min(loW, fr.weight);
                        hiW = std::max(hiW, fr.weight);
                    }
                    SLOG("finalize: frame weights min %.2f max %.2f (fwhmBest %.2f)", loW, hiW,
                         fwhmBest);
                }
            }
        } else {
            for (FrameRecord& fr : frames) {
                if (!fr.rejected && !sraw_file_exists(fr.srawPath)) {
                    fr.rejected = true;
                    fr.rejectReason = "cache-missing";
                }
            }
        }
        int used = 0;
        for (const FrameRecord& fr : frames)
            if (!fr.rejected) ++used;
        if (used < 2) return error_json("not enough usable frames");
        report("align", 15);
        if (abort_.load()) return error_json("aborted");

        // ---- stack (15-70) ----
        ImageF stackRGB;
        float rejPct = 0.0f;
        float fwhmStack = 0.0f;
        int meteorCount = 0;
        std::vector<int> meteorFrameIdx;  // frameIndex of each composited meteor candidate

        if (mode == SessionMode::TRAILS) {
            std::vector<int> active;
            for (int i = 0; i < (int)frames.size(); ++i)
                if (!frames[i].rejected) active.push_back(i);
            TrailsAccumulator acc;
            std::vector<uint16_t> region;
            ImageF rgb;
            int done = 0;
            for (int i : active) {
                if (abort_.load()) return error_json("aborted");
                SrawReader r;
                if (!r.open(frames[i].srawPath)) {
                    frames[i].rejected = true;
                    frames[i].rejectReason = "cache-missing";
                    continue;
                }
                const int W = (int)r.header().width, H = (int)r.header().height;
                if (W < 4 || H < 4) continue;
                copy_region_u16(r.data(), W, H, 0, 0, W, H, &region);
                // Both stages clear their output when memory runs out; an empty image must not
                // reach the accumulator.
                if (region.size() < (size_t)W * (size_t)H)
                    return error_json("out of memory while compositing trails");
                // Per-frame dynamic black, matching stack.cpp: the black point drifts upward as
                // the sensor warms across a long trails session.
                SensorInfo siK = cfg_.sensor;
                for (int b = 0; b < 4; ++b) siK.blackLevel[b] = r.header().blackLevel[b];
                if (!masterDark.empty() && masterDark.w == W && masterDark.h == H)
                    subtract_dark_region(region, W, H, 0, 0, masterDark, siK.blackLevel,
                                         darkPedPtr, darkBlackPtr);
                if (sp.hotPixelFix && !hotIdx.empty() && W == cfg_.sensor.width &&
                    H == cfg_.sensor.height)
                    fix_hot_pixels_region(region, W, H, 0, 0, W, H, hotIdx);
                demosaic_region_malvar(region.data(), W, H, siK, r.header().wbGain, &rgb);
                if (rgb.w != W || rgb.h != H || rgb.empty())
                    return error_json("out of memory while compositing trails");
                // Same correction the stacking path applies, so a trails composite is not the one
                // output that keeps the lens falloff.
                if (sp.shading != nullptr) apply_shading_region(rgb, 0, 0, W, H, *sp.shading);
                apply_ccm_inplace(rgb, r.header().ccm);
                acc.add(rgb);
                ++done;
                report("stack", 15 + (55 * done) / (int)active.size());
            }
            if (acc.empty()) return error_json("no usable trail frames");
            // Steal the accumulator's buffer (its underlying object is non-const) to avoid a
            // full-resolution copy; `acc` is not used afterwards.
            stackRGB = std::move(const_cast<ImageF&>(acc.result()));
        } else {
            sp.refIndex = refPos;
            sp.identityTransforms = false;
            ProgressFn spr = [&report](const char*, int pct) {
                report("stack", 15 + clampi(pct, 0, 100) * 55 / 100);
            };
            if (!stack_frames(frames, cfg_.sensor, masterDark, hotIdx, sp, &stackRGB, spr,
                              &abort_, &rejPct)) {
                return error_json(abort_.load() ? "aborted" : "stack failed");
            }
            if (stackRGB.empty()) return error_json("empty stack");
            fwhmStack = measure_stack_fwhm(stackRGB);
            if (mode == SessionMode::METEOR) {
                meteorCount = blend_meteors(streaks, frames, refPos, cfg_.sensor, masterDark,
                                            darkPedPtr, darkBlackPtr, hotIdx, sp.hotPixelFix,
                                            sp.shading, stackRGB, &meteorFrameIdx);
                SLOG("finalize: %d meteor candidate(s) composited", meteorCount);
            }
        }
        report("stack", 70);
        if (abort_.load()) return error_json("aborted");

        // 16-bit linear master (always the pure stack, pre-foreground, pre-post).
        const std::string tiffPath = outDir + "/master_linear16.tiff";
        const bool tiffOk = write_tiff16(tiffPath, stackRGB, rotation);
        if (!tiffOk) SLOGE("finalize: tiff write failed for %s", tiffPath.c_str());

        // ---- foreground (70-80) ----
        std::string fgOutcome;  // "" when the composite path was not attempted/completed
        if ((mode == SessionMode::ASTRO || mode == SessionMode::PRO) && fgComposite) {
            report("foreground", 70);
            StackParams fg = sp;
            fg.identityTransforms = true;
            ProgressFn fpr = [&report](const char*, int pct) {
                report("foreground", 70 + clampi(pct, 0, 100) * 8 / 100);
            };
            ImageF fgRGB;
            float fgRej = 0.0f;
            if (stack_frames(frames, cfg_.sensor, masterDark, hotIdx, fg, &fgRGB, fpr, &abort_,
                             &fgRej) &&
                !fgRGB.empty()) {
                ImageF comp;
                ImageF fgMask;
                composite_foreground(stackRGB, fgRGB, featherPx, &comp, &fgMask, std::max(0, pp.downEdge));
                if (!comp.empty() && comp.w == stackRGB.w && comp.h == stackRGB.h) {
                    if (!fgMask.empty()) {
                        // Sanity-check the mask: a near-empty or near-full sky mask means the
                        // Otsu split found no real horizon — blending would only hurt.
                        double sum = 0.0;
                        for (float v : fgMask.d) sum += v;
                        const float skyFrac = (float)(sum / (double)fgMask.d.size());
                        const int skyPct = (int)std::lround((double)skyFrac * 100.0);
                        char fgBuf[48];
                        if (skyFrac >= 0.10f && skyFrac <= 0.95f) {
                            stackRGB = std::move(comp);
                            snprintf(fgBuf, sizeof(fgBuf), "applied (sky %d%%)", skyPct);
                        } else {
                            snprintf(fgBuf, sizeof(fgBuf), "skipped (sky %d%%)", skyPct);
                        }
                        fgOutcome = fgBuf;
                        SLOG("finalize: foreground %s", fgOutcome.c_str());
                    } else {
                        // No mask diagnostics returned — keep the previous blind behavior.
                        stackRGB = std::move(comp);
                    }
                } else {
                    SLOGE("finalize: composite_foreground produced no image, keeping sky stack");
                }
            } else {
                if (abort_.load()) return error_json("aborted");
                SLOGE("finalize: foreground stack failed, skipping composite");
            }
        }
        report("foreground", 80);
        if (abort_.load()) return error_json("aborted");

        // ---- post (80-95) ----
        if (mode == SessionMode::TRAILS) pp.gradientRemoval = false;
        ProgressFn ppr = [&report](const char*, int pct) {
            report("post", 80 + clampi(pct, 0, 100) * 15 / 100);
        };
        postprocess(stackRGB, pp, ppr);
        report("post", 95);
        if (abort_.load()) return error_json("aborted");

        // ---- write (95-100) ----
        report("write", 95);
        const std::string finalPath = outDir + "/final_rgb.raw";
        if (!write_rgb8(finalPath, stackRGB, rotation)) return error_json("failed to write final image");
        int outW, outH;
        rotated_size(rotation, stackRGB.w, stackRGB.h, &outW, &outH);

        // One frame developed exactly like the stack, small, for "1 frame vs stacked": what the
        // stacking bought, side by side. Not at full-res sizes, where one more full frame in
        // memory beside the stack is a real risk. The frame is the stack's own path with a
        // single survivor - warped into the stack's geometry, so stars line up across the
        // divider. Not the reference: it is chosen for the most stars, which a satellite or a
        // plane crossing it can inflate. Of the frames with at least the median star count, the
        // one that moved least against the reference: the sky turns during a session, and a
        // frame from far away in time leaves black wedges where it does not cover the stack.
        std::string singlePath;
        int singleW = 0, singleH = 0;
        int singlePos = -1;
        {
            std::vector<int> counts;
            for (const FrameRecord& fr : frames)
                if (!fr.rejected) counts.push_back((int)fr.stars.size());
            if (counts.size() >= 2) {
                std::sort(counts.begin(), counts.end());
                const int medianStars = counts[(counts.size() - 1) / 2];
                const double lw = stackRGB.w / 2.0, lh = stackRGB.h / 2.0;  // luma coordinates
                double least = 1e30;
                for (int i = 0; i < (int)frames.size(); ++i) {
                    const FrameRecord& fr = frames[i];
                    if (fr.rejected || i == refPos || (int)fr.stars.size() < medianStars) continue;
                    double shift = 0.0;
                    const double cx[4] = {0.0, lw, 0.0, lw}, cy[4] = {0.0, 0.0, lh, lh};
                    for (int k = 0; k < 4; ++k) {
                        double ox, oy;
                        fr.T.apply(cx[k], cy[k], &ox, &oy);
                        shift = std::max(shift, std::hypot(ox - cx[k], oy - cy[k]));
                    }
                    if (shift < least) {
                        least = shift;
                        singlePos = i;
                    }
                }
            }
        }
        if ((mode == SessionMode::ASTRO || mode == SessionMode::PRO) && singlePos >= 0 &&
            (int64_t)stackRGB.w * stackRGB.h <= 20000000LL && !abort_.load()) {
            // Optional: running out of memory here costs the comparison, not the photo.
            try {
                std::vector<FrameRecord> justOne = frames;
                for (int i = 0; i < (int)justOne.size(); ++i) justOne[i].rejected = i != singlePos;
                ImageF one;
                float oneRej = 0.0f;
                ProgressFn quiet = [](const char*, int) {};
                if (stack_frames(justOne, cfg_.sensor, masterDark, hotIdx, sp, &one, quiet, &abort_, &oneRej) &&
                    one.w == stackRGB.w && one.h == stackRGB.h) {
                    postprocess(one, pp, nullptr);
                    const ImageF small = box_downscale(one, 1600);
                    singlePath = outDir + "/single_rgb.raw";
                    if (!small.empty() && write_rgb8(singlePath, small, rotation)) {
                        rotated_size(rotation, small.w, small.h, &singleW, &singleH);
                    } else {
                        singlePath.clear();
                    }
                }
            } catch (...) {
                SLOGE("finalize: single-frame comparison skipped (exception)");
                singlePath.clear();
                singleW = singleH = 0;
            }
        }

        used = 0;
        std::string rejStr;
        std::string framesArr = "[";
        for (size_t i = 0; i < frames.size(); ++i) {
            const FrameRecord& fr = frames[i];
            if (!fr.rejected) {
                ++used;
            } else {
                if (!rejStr.empty()) rejStr += ";";
                rejStr += std::to_string(fr.meta.index) + ":" +
                          (fr.rejectReason.empty() ? "rejected" : fr.rejectReason);
            }
            jsonmini::Builder fb;
            fb.add("index", fr.meta.index)
                .add("aligned", fr.aligned)
                .add("rejected", fr.rejected)
                .add("reason", fr.rejectReason)
                .add("starCount", (int)fr.stars.size())
                .add("fwhm", (double)fr.medianFwhm)
                .add("inliers", fr.inliers)
                .add("residual", (double)fr.residual)
                .add("dx", fr.T.tx)
                .add("dy", fr.T.ty)
                .add("rotDeg", fr.T.rotationDeg());
            if (i) framesArr += ",";
            framesArr += fb.build();
        }
        framesArr += "]";
        std::string streaksArr = "[";
        for (size_t i = 0; i < streaks.size(); ++i) {
            const Streak& s = streaks[i];
            jsonmini::Builder sb;
            sb.add("frameIndex", s.frameIndex)
                .add("x0", (double)s.x0)
                .add("y0", (double)s.y0)
                .add("x1", (double)s.x1)
                .add("y1", (double)s.y1)
                .add("length", (double)s.length)
                .add("cls", s.cls);
            if (i) streaksArr += ",";
            streaksArr += sb.build();
        }
        streaksArr += "]";
        std::string meteorFramesStr;
        for (size_t i = 0; i < meteorFrameIdx.size(); ++i) {
            if (i) meteorFramesStr += ",";
            meteorFramesStr += std::to_string(meteorFrameIdx[i]);
        }
        const float fwhmRef =
            (refPos >= 0 && refPos < (int)frames.size()) ? frames[refPos].medianFwhm : 0.0f;
        const int64_t elapsedMs = (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
                                      std::chrono::steady_clock::now() - t0)
                                      .count();

        jsonmini::Builder rb;
        rb.add("mode", (int)mode)
            .add("framesTotal", (int)frames.size())
            .add("framesUsed", used)
            .add("rejectionPct", (double)rejPct)
            .add("fwhmRef", (double)fwhmRef)
            .add("fwhmStack", (double)fwhmStack)
            .add("meteorCount", meteorCount)
            .add("hotPixels", (int)hotIdx.size())
            .add("darkFrames", darkFrames)
            .add("darkSource", std::string(darkSource))
            .add("lensShading", !shading.empty())
            .add("lensShadingMaxGain", (double)(shading.empty() ? 1.0f : shading.maxGain))
            .add("elapsedMs", elapsedMs)
            .addJson("frames", framesArr)
            .addJson("streaks", streaksArr);
        if (!write_text_file(outDir + "/stack_report.json", rb.build()))
            SLOGE("finalize: failed to write stack_report.json");

        // Successful finalize: drop the raw cache unless the user keeps it.
        if (!cfg_.keepCache) {
            for (const FrameRecord& fr : frames)
                if (!fr.srawPath.empty()) ::remove(fr.srawPath.c_str());
            SLOG("finalize: sraw cache removed");
        }
        report("write", 100);

        jsonmini::Builder res;
        res.add("ok", true)
            .add("error", "")
            .add("width", outW)
            .add("height", outH)
            .add("rotation", rotation)
            .add("finalRgbPath", finalPath)
            .add("tiffPath", tiffOk ? tiffPath : std::string())
            .add("framesUsed", used)
            .add("framesTotal", (int)frames.size())
            .add("rejected", rejStr)
            .add("rejectionPct", (double)rejPct)
            .add("fwhmRef", (double)fwhmRef)
            .add("fwhmStack", (double)fwhmStack)
            .add("elapsedMs", elapsedMs)
            .add("meteorCount", meteorCount)
            .add("meteorFrames", meteorFramesStr)
            .add("foreground", fgOutcome)
            .add("singlePath", singlePath)
            .add("singleWidth", singleW)
            .add("singleHeight", singleH);
        SLOG("finalize: done in %lld ms, %d/%d frames", (long long)elapsedMs, used,
             (int)frames.size());
        return res.build();
    } catch (...) {
        SLOGE("finalizeStack: exception");
        return error_json("internal error");
    }
}

// ---------- free functions ----------

std::string analyze_frame(const uint16_t* bayer, int w, int h, int strideElems,
                          const SensorInfo& si, const float wbGain[4]) {
    try {
        if (!bayer || w < 2 || h < 2 || strideElems < w) {
            jsonmini::Builder b;
            b.add("bgFrac", 0.0).add("starCount", 0).add("medianFwhm", 0.0).add("noiseSigma", 0.0);
            return b.build();
        }
        ImageF luma;
        superpixel_luma(bayer, w, h, strideElems, si, wbGain, &luma);
        std::vector<Star> stars;
        SkyStats stats;
        detect_stars(luma, DetectParams(), &stars, &stats);
        jsonmini::Builder b;
        b.add("bgFrac", (double)stats.bgMedian)
            .add("starCount", (int)stars.size())
            .add("medianFwhm", (double)median_fwhm_of(stars))
            .add("noiseSigma", (double)stats.noiseSigma);
        return b.build();
    } catch (...) {
        SLOGE("analyze_frame: exception");
        jsonmini::Builder b;
        b.add("bgFrac", 0.0).add("starCount", 0).add("medianFwhm", 0.0).add("noiseSigma", 0.0);
        return b.build();
    }
}

}  // namespace starcam

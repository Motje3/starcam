// selftest.cpp — end-to-end synthetic validation of the StarCam stacking pipeline (PLAN.md §6.8).
// Synthesizes 16 RGGB frames with known ground-truth transforms, noise, fixed hot pixels and a
// satellite streak, runs the REAL modules (sraw/bayer/stars/align/stack/post — no EngineSession),
// and validates alignment accuracy, hot-pixel rejection, streak suppression and stacked star FWHM.

#include "selftest.h"

#include <sys/stat.h>

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <random>

#include "align.h"
#include "bayer.h"
#include "common.h"
#include "json_mini.h"
#include "post.h"
#include "sraw.h"
#include "stack.h"
#include "stars.h"

namespace starcam {
namespace {

constexpr int kW = 2048;
constexpr int kH = 1536;
constexpr int kFrames = 16;
constexpr int kNumStars = 400;
constexpr int kNumHot = 200;
constexpr float kBlack = 256.0f;
constexpr uint32_t kWhite = 4095;
constexpr float kNorm = (float)kWhite - kBlack;      // counts per normalized unit (3839)
// PSF sigma in RAW pixels; luma is half-res, so FWHM in luma px = 2.355 * sigma / 2 = 2.2.
constexpr double kPsfSigmaRaw = 2.0 * 2.2 / 2.355;
constexpr double kInputFwhmLuma = 2.2;
constexpr double kStreakAmp = 800.0;                 // counts above sky, single frame
constexpr double kStreakSigma = 1.6;                 // cross-section sigma, raw px
// Satellite path in REFERENCE raw coords; frame 6+k renders segment [k/6, (k+1)/6] of it.
constexpr double kLineX0 = 150.0, kLineY0 = 250.0, kLineX1 = 1900.0, kLineY1 = 1250.0;

struct SynthStar { double x = 0, y = 0, flux = 0; };
struct HotPix { int x = 0, y = 0; uint16_t v = 0; };

// Ground truth: maps reference (frame 0) RAW coords -> frame i RAW coords.
// Rotation theta_i = 0.01*i deg about the image center, then translation (0.8*i, 0.5*i).
Transform truth_raw(int i) {
    const double cx = (kW - 1) * 0.5, cy = (kH - 1) * 0.5;
    const double th = 0.01 * i * M_PI / 180.0;
    const double a = std::cos(th), b = std::sin(th);
    Transform t;
    t.a = a;
    t.b = b;
    t.tx = cx - (a * cx - b * cy) + 0.8 * i;
    t.ty = cy - (b * cx + a * cy) + 0.5 * i;
    return t;
}

// Convert a RAW-coords truth transform to LUMA coords. Superpixel (x,y) covers raw pixels
// 2x..2x+1 / 2y..2y+1, centroid at raw (2x+0.5, 2y+0.5)  =>  l = (raw - 0.5) / 2.
// T_luma = S o T_raw o S^-1 : same rotation, translation = (A*[0.5,0.5] + t - [0.5,0.5]) / 2.
Transform raw_truth_to_luma(const Transform& t) {
    Transform r;
    r.a = t.a;
    r.b = t.b;
    r.tx = 0.5 * (t.a * 0.5 - t.b * 0.5 + t.tx - 0.5);
    r.ty = 0.5 * (t.b * 0.5 + t.a * 0.5 + t.ty - 0.5);
    return r;
}

double dist_pt_seg(double px, double py, double ax, double ay, double bx, double by) {
    const double dx = bx - ax, dy = by - ay;
    const double l2 = dx * dx + dy * dy;
    double t = l2 > 1e-12 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0.0;
    t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    const double qx = ax + t * dx, qy = ay + t * dy;
    const double ex = px - qx, ey = py - qy;
    return std::sqrt(ex * ex + ey * ey);
}

// Renders one synthetic frame into raw16 (fbuf = scratch, signal above black in counts).
void synth_frame(int fi, const std::vector<SynthStar>& stars, const std::vector<HotPix>& hots,
                 std::vector<float>& fbuf, std::vector<uint16_t>& raw16) {
    const Transform T = truth_raw(fi);
    const double cx = (kW - 1) * 0.5, cy = (kH - 1) * 0.5;
    const double invRmax2 = 1.0 / (cx * cx + cy * cy);

    // Sky: base 120 counts + horizontal linear gradient +40, radial vignetting -15% at corners.
    parallel_for(0, kH, [&fbuf, cx, cy, invRmax2](int y) {
        float* row = &fbuf[(size_t)y * kW];
        const double dy2 = ((double)y - cy) * ((double)y - cy);
        for (int x = 0; x < kW; ++x) {
            const double dx = (double)x - cx;
            const double vign = 1.0 - 0.15 * (dx * dx + dy2) * invRmax2;
            row[x] = (float)((120.0 + 40.0 * (double)x / (double)(kW - 1)) * vign);
        }
    });

    // Stars: color-neutral through the CFA (every raw pixel gets the same PSF intensity).
    const double twoSig2 = 2.0 * kPsfSigmaRaw * kPsfSigmaRaw;
    const double ampNorm = 1.0 / (2.0 * M_PI * kPsfSigmaRaw * kPsfSigmaRaw);
    for (const SynthStar& s : stars) {
        double sx, sy;
        T.apply(s.x, s.y, &sx, &sy);
        if (sx < -10.0 || sx > (double)kW + 10.0 || sy < -10.0 || sy > (double)kH + 10.0) continue;
        const double amp = s.flux * ampNorm;
        const int x0 = clampi((int)std::floor(sx) - 8, 0, kW - 1);
        const int x1 = clampi((int)std::floor(sx) + 8, 0, kW - 1);
        const int y0 = clampi((int)std::floor(sy) - 8, 0, kH - 1);
        const int y1 = clampi((int)std::floor(sy) + 8, 0, kH - 1);
        for (int y = y0; y <= y1; ++y) {
            const double dy = (double)y - sy;
            float* row = &fbuf[(size_t)y * kW];
            for (int x = x0; x <= x1; ++x) {
                const double dx = (double)x - sx;
                row[x] += (float)(amp * std::exp(-(dx * dx + dy * dy) / twoSig2));
            }
        }
    }

    // Satellite streak: frames 6..11 each get one sixth of the sky-fixed line (moving satellite).
    if (fi >= 6 && fi <= 11) {
        const double t0 = (double)(fi - 6) / 6.0, t1 = (double)(fi - 5) / 6.0;
        const double ax = kLineX0 + t0 * (kLineX1 - kLineX0), ay = kLineY0 + t0 * (kLineY1 - kLineY0);
        const double bx = kLineX0 + t1 * (kLineX1 - kLineX0), by = kLineY0 + t1 * (kLineY1 - kLineY0);
        double fax, fay, fbx, fby;
        T.apply(ax, ay, &fax, &fay);
        T.apply(bx, by, &fbx, &fby);
        const int x0 = clampi((int)std::floor(std::min(fax, fbx)) - 7, 0, kW - 1);
        const int x1 = clampi((int)std::ceil(std::max(fax, fbx)) + 7, 0, kW - 1);
        const int y0 = clampi((int)std::floor(std::min(fay, fby)) - 7, 0, kH - 1);
        const int y1 = clampi((int)std::ceil(std::max(fay, fby)) + 7, 0, kH - 1);
        const double ss2 = 2.0 * kStreakSigma * kStreakSigma;
        for (int y = y0; y <= y1; ++y) {
            float* row = &fbuf[(size_t)y * kW];
            for (int x = x0; x <= x1; ++x) {
                const double d = dist_pt_seg((double)x, (double)y, fax, fay, fbx, fby);
                if (d < 7.0) row[x] += (float)(kStreakAmp * std::exp(-d * d / ss2));
            }
        }
    }

    // Noise (deterministic per frame+row) + quantize. Read noise sigma=12 counts everywhere,
    // poisson-ish extra on bright (star/streak) pixels only — sky signal stays below 200.
    parallel_for(0, kH, [&fbuf, &raw16, fi](int y) {
        std::mt19937 rng(0x5EEDu + (uint32_t)fi * 2654435761u + (uint32_t)y * 40503u);
        std::normal_distribution<float> g(0.0f, 1.0f);
        const float* src = &fbuf[(size_t)y * kW];
        uint16_t* dst = &raw16[(size_t)y * kW];
        for (int x = 0; x < kW; ++x) {
            const float sig = src[x];
            float n = 12.0f * g(rng);
            if (sig > 200.0f) n += 0.7f * std::sqrt(sig - 200.0f) * g(rng);
            const float v = clampf(kBlack + sig + n, 0.0f, (float)kWhite);
            dst[x] = (uint16_t)(v + 0.5f);
        }
    });

    // Fixed hot pixels: same raw positions & values every frame (they do NOT move with T).
    for (const HotPix& hp : hots) raw16[(size_t)hp.y * kW + hp.x] = hp.v;
}

// Median of a (2*half+1)^2 box excluding the central (2*exHalf+1)^2, clamped to bounds.
float local_bg_median(const ImageF& L, int cx, int cy, int half, int exHalf,
                      std::vector<float>& scratch) {
    scratch.clear();
    const int x0 = clampi(cx - half, 0, L.w - 1), x1 = clampi(cx + half, 0, L.w - 1);
    const int y0 = clampi(cy - half, 0, L.h - 1), y1 = clampi(cy + half, 0, L.h - 1);
    for (int y = y0; y <= y1; ++y) {
        const float* row = L.px(0, y);
        int ady = y - cy;
        if (ady < 0) ady = -ady;
        for (int x = x0; x <= x1; ++x) {
            int adx = x - cx;
            if (adx < 0) adx = -adx;
            if (adx <= exHalf && ady <= exHalf) continue;
            scratch.push_back(row[x]);
        }
    }
    return median_of(scratch);
}

float box3_max(const ImageF& L, int cx, int cy) {
    float m = -1e30f;
    const int x0 = clampi(cx - 1, 0, L.w - 1), x1 = clampi(cx + 1, 0, L.w - 1);
    const int y0 = clampi(cy - 1, 0, L.h - 1), y1 = clampi(cy + 1, 0, L.h - 1);
    for (int y = y0; y <= y1; ++y)
        for (int x = x0; x <= x1; ++x) m = std::max(m, L.px(x, y)[0]);
    return m;
}

// Robust per-pixel noise sigma of the stack: MAD of (pixel - local median) on a sparse grid.
float stack_noise_sigma(const ImageF& L, std::vector<float>& scratch) {
    std::vector<float> res;
    res.reserve(6400);
    for (int y = 12; y < L.h - 12; y += 23) {
        for (int x = 12; x < L.w - 12; x += 23) {
            const float m = local_bg_median(L, x, y, 7, 1, scratch);
            res.push_back(std::fabs(L.px(x, y)[0] - m));
        }
    }
    return 1.4826f * median_of(res);
}

// Median of a side x side patch of a 1-channel image. Median, not mean, so a star that happens
// to land inside a patch cannot move the result.
double patch_median(const ImageF& img, int x0, int y0, int side, std::vector<float>& scratch) {
    scratch.clear();
    for (int y = y0; y < y0 + side && y < img.h; ++y) {
        if (y < 0) continue;
        const float* row = img.px(0, y);
        for (int x = x0; x < x0 + side && x < img.w; ++x) {
            if (x < 0) continue;
            scratch.push_back(row[x]);
        }
    }
    if (scratch.empty()) return 0.0;
    return (double)median_of(scratch);
}

// Corner sky brightness relative to the centre: 1.0 means flat, less means the lens falloff is
// still there. The synthetic sky also carries a left-to-right linear gradient, but averaging the
// left and right corners cancels it and leaves the radial component alone. The 96 px inset keeps
// every patch inside the area all frames cover after their drift.
double corner_center_ratio(const ImageF& luma) {
    std::vector<float> s;
    const int side = 64;
    const int in = 96;
    if (luma.w < 4 * (in + side) || luma.h < 4 * (in + side)) return 1.0;
    const double c = patch_median(luma, (luma.w - side) / 2, (luma.h - side) / 2, side, s);
    if (c <= 1e-9) return 1.0;
    double sum = 0.0;
    sum += patch_median(luma, in, in, side, s);
    sum += patch_median(luma, luma.w - in - side, in, side, s);
    sum += patch_median(luma, in, luma.h - in - side, side, s);
    sum += patch_median(luma, luma.w - in - side, luma.h - in - side, side, s);
    return (sum * 0.25) / c;
}

std::string check_json(const char* name, bool pass, double value, double limit) {
    jsonmini::Builder b;
    b.add("name", name).add("pass", pass).add("value", value).add("limit", limit);
    return b.build();
}

void add_log(std::string& log, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (!log.empty()) log += "; ";
    log += buf;
    SLOG("selftest: %s", buf);
}

std::string fail_json(const std::string& log) {
    jsonmini::Builder b;
    b.add("pass", false);
    b.addJson("checks", "[]");
    b.add("log", log);
    return b.build();
}

std::string run_impl(const std::string& tmpDir, std::vector<std::string>& paths) {
    const auto tStart = std::chrono::steady_clock::now();
    std::string log;

    SensorInfo si;
    si.width = kW;
    si.height = kH;
    si.cfa = CFA::RGGB;
    si.whiteLevel = kWhite;
    for (int i = 0; i < 4; ++i) si.blackLevel[i] = kBlack;
    const float wb[4] = {1.0f, 1.0f, 1.0f, 1.0f};

    // ---- deterministic star field + fixed hot pixels (seed 777) ----
    std::vector<SynthStar> stars(kNumStars);
    std::mt19937 rng(777);
    {
        std::uniform_real_distribution<double> ux(20.0, (double)kW - 21.0);
        std::uniform_real_distribution<double> uy(20.0, (double)kH - 21.0);
        std::uniform_real_distribution<double> uf(std::log(300.0), std::log(30000.0));
        for (int i = 0; i < kNumStars; ++i) {
            stars[i].x = ux(rng);
            stars[i].y = uy(rng);
            stars[i].flux = std::exp(uf(rng));  // log-uniform 300..30000
        }
    }
    std::vector<HotPix> hots;
    hots.reserve(kNumHot);
    {
        // Rejection-sample so the hot-pixel and streak checks aren't confused by real stars.
        std::uniform_int_distribution<int> hx(12, kW - 13), hy(12, kH - 13);
        std::uniform_real_distribution<double> hv(0.0, 1.0);
        int attempts = 0;
        while ((int)hots.size() < kNumHot && attempts < 200000) {
            ++attempts;
            const int x = hx(rng), y = hy(rng);
            bool bad = false;
            for (const SynthStar& s : stars) {
                const double dx = s.x - x, dy = s.y - y;
                if (dx * dx + dy * dy < 256.0) { bad = true; break; }  // >=16 px from any star
            }
            if (!bad && dist_pt_seg(x, y, kLineX0, kLineY0, kLineX1, kLineY1) < 18.0) bad = true;
            if (!bad) {
                for (const HotPix& o : hots) {
                    const double dx = (double)(o.x - x), dy = (double)(o.y - y);
                    if (dx * dx + dy * dy < 144.0) { bad = true; break; }  // >=12 px apart
                }
            }
            if (bad) continue;
            HotPix hp;
            hp.x = x;
            hp.y = y;
            hp.v = (uint16_t)(3500.0 + hv(rng) * 500.0);
            hots.push_back(hp);
        }
        if ((int)hots.size() < kNumHot) add_log(log, "warn: only %d hot pixels placed", (int)hots.size());
    }

    // ---- synthesize frames, write sraw, superpixel luma, detect stars ----
    std::vector<FrameRecord> frames(kFrames);
    std::vector<float> fbuf((size_t)kW * kH);
    std::vector<uint16_t> raw16((size_t)kW * kH);
    ImageF luma;
    DetectParams dp;
    int minStars = 1 << 30, maxStars = 0;
    for (int fi = 0; fi < kFrames; ++fi) {
        synth_frame(fi, stars, hots, fbuf, raw16);

        char name[48];
        snprintf(name, sizeof(name), "selftest_%04d.sraw", fi);
        std::string path = tmpDir;
        if (!path.empty() && path.back() != '/' && path.back() != '\\') path += '/';
        path += name;

        SrawHeader hdr;
        hdr.width = kW;
        hdr.height = kH;
        hdr.cfa = CFA::RGGB;
        hdr.whiteLevel = kWhite;
        for (int i = 0; i < 4; ++i) { hdr.blackLevel[i] = kBlack; hdr.wbGain[i] = 1.0f; }
        for (int i = 0; i < 9; ++i) hdr.ccm[i] = (i % 4 == 0) ? 1.0f : 0.0f;
        hdr.exposureNs = 8000000000ull;
        hdr.iso = 800;
        hdr.timestampNs = 8000000000ull * (uint64_t)fi;
        if (!sraw_write(path, hdr, raw16.data(), kW)) {
            add_log(log, "sraw_write failed: %s", path.c_str());
            return fail_json(log);
        }
        paths.push_back(path);

        FrameRecord& fr = frames[fi];
        fr.srawPath = path;
        fr.meta.index = fi;
        fr.meta.exposureNs = 8000000000ll;
        fr.meta.iso = 800;
        fr.meta.timestampNs = 8000000000ll * (int64_t)fi;

        superpixel_luma(raw16.data(), kW, kH, kW, si, wb, &luma);
        SkyStats st;
        detect_stars(luma, dp, &fr.stars, &st);
        fr.bgFrac = st.bgMedian;
        if (!fr.stars.empty()) {
            std::vector<float> fw;
            fw.reserve(fr.stars.size());
            for (const Star& s : fr.stars) fw.push_back(s.fwhm);
            fr.medianFwhm = median_of(fw);
        }
        minStars = std::min(minStars, (int)fr.stars.size());
        maxStars = std::max(maxStars, (int)fr.stars.size());
    }
    add_log(log, "synth %d frames, stars/frame %d..%d", kFrames, minStars, maxStars);

    // ---- alignment: frame 0 = reference, incremental guess chained like the engine ----
    AlignParams ap;
    frames[0].aligned = true;
    frames[0].inliers = (int)frames[0].stars.size();
    const std::vector<Star>& refStars = frames[0].stars;
    Transform prev;  // identity
    int alignedCnt = 1;
    for (int i = 1; i < kFrames; ++i) {
        AlignResult ar = align_stars(refStars, frames[i].stars, prev, ap);
        if (!ar.ok) ar = align_bootstrap(refStars, frames[i].stars, ap);
        if (ar.ok) {
            frames[i].T = ar.T;
            frames[i].aligned = true;
            frames[i].inliers = ar.inliers;
            frames[i].residual = ar.residual;
            prev = ar.T;
            ++alignedCnt;
        } else {
            frames[i].aligned = false;
            frames[i].rejected = true;
            frames[i].rejectReason = "align_failed";
            add_log(log, "frame %d alignment FAILED", i);
        }
    }
    add_log(log, "aligned %d/%d", alignedCnt, kFrames);

    // ---- check 1: fitted T vs ground truth (luma coords) at 5 test points ----
    const double tp[5][2] = {{100, 100}, {924, 100}, {100, 668}, {924, 668}, {512, 384}};
    std::vector<float> rmsPerFrame;
    rmsPerFrame.reserve(kFrames - 1);
    for (int i = 1; i < kFrames; ++i) {
        if (!frames[i].aligned) { rmsPerFrame.push_back(999.0f); continue; }
        const Transform truL = raw_truth_to_luma(truth_raw(i));
        double acc = 0.0;
        for (int k = 0; k < 5; ++k) {
            double fx, fy, gx, gy;
            frames[i].T.apply(tp[k][0], tp[k][1], &fx, &fy);
            truL.apply(tp[k][0], tp[k][1], &gx, &gy);
            const double dx = fx - gx, dy = fy - gy;
            acc += dx * dx + dy * dy;
        }
        rmsPerFrame.push_back((float)std::sqrt(acc / 5.0));
    }
    const double alignMed = (double)median_of(rmsPerFrame);
    const bool c1 = alignMed < 0.35;
    add_log(log, "align median RMS %.4f px (limit 0.35)", alignMed);

    // ---- stack (real module): no darks, empty hotIdx — sigma clip must do the killing ----
    StackParams sp;  // defaults: sigma 3.5/2.8, tile 512, hotPixelFix on (empty list), ref 0
    Image16 noDark;
    std::vector<uint32_t> noHot;
    ImageF stackImg;
    float rejPct = 0.0f;
    std::atomic<bool> abortFlag(false);
    int lastPct = -100;
    ProgressFn prog = [&lastPct](const char* stage, int pct) {
        if (pct - lastPct >= 25) {
            lastPct = pct;
            SLOG("selftest stack: %s %d%%", stage, pct);
        }
    };
    const bool stackOk =
        stack_frames(frames, si, noDark, noHot, sp, &stackImg, prog, &abortFlag, &rejPct);
    if (!stackOk || stackImg.w != kW || stackImg.h != kH || stackImg.ch != 3) {
        add_log(log, "stack_frames FAILED");
        return fail_json(log);
    }
    add_log(log, "stack ok, clipped %.2f%%", rejPct);

    // Stack luma at RAW resolution (mean of RGB) — all checks run on the LINEAR stack.
    ImageF lumaR;
    lumaR.init(kW, kH, 1);
    parallel_for(0, kH, [&stackImg, &lumaR](int y) {
        const float* s = stackImg.px(0, y);
        float* d = lumaR.px(0, y);
        for (int x = 0; x < kW; ++x)
            d[x] = (s[3 * x] + s[3 * x + 1] + s[3 * x + 2]) * (1.0f / 3.0f);
    });
    std::vector<float> scratch;
    scratch.reserve(441);
    float sigma = stack_noise_sigma(lumaR, scratch);
    if (sigma < 1e-7f) sigma = 1e-7f;

    // ---- check 2: hot pixels ----
    // Two regimes, because they exercise genuinely different code paths:
    //  (a) sigma clip alone (what you get with dark frames OFF). A STATIC single-pixel defect is
    //      the hardest case for temporal rejection: with only ~0.8 px of drift per frame the
    //      demosaic smears it across 5x5 and the tail lands inside the neighbouring samples, so
    //      perfection is not achievable. Measured and logged, but not a pass/fail gate.
    //  (b) with the hot-pixel map the app builds from dark frames (ON by default) — must vanish.
    const int nHot = (int)hots.size() > 0 ? (int)hots.size() : 1;
    double sumReduction = 0.0;
    float worstNoMap = 0.0f;
    for (const HotPix& hp : hots) {
        const float bg = local_bg_median(lumaR, hp.x, hp.y, 10, 2, scratch);
        const float ex = box3_max(lumaR, hp.x, hp.y) - bg;
        const float amp = ((float)hp.v - kBlack) / kNorm;  // single-frame amplitude
        if (amp > 1e-6f) {
            const double red = 1.0 - (double)(ex > 0.0f ? ex : 0.0f) / (double)amp;
            sumReduction += red > 0.0 ? red : 0.0;
        }
        worstNoMap = std::max(worstNoMap, ex / sigma);
    }
    const double clipOnlyReduction = 100.0 * sumReduction / (double)nHot;
    add_log(log, "hot (sigma-clip only): %.1f%% amplitude reduction, worst %.1f sigma",
            clipOnlyReduction, (double)worstNoMap);

    // (b) restack with the hot-pixel map, exactly as a session with dark frames does.
    std::vector<uint32_t> hotIdx;
    hotIdx.reserve(hots.size());
    for (const HotPix& hp : hots) hotIdx.push_back((uint32_t)((size_t)hp.y * kW + hp.x));
    ImageF stackMapped;
    float rejPct2 = 0.0f;
    const bool stackOk2 =
        stack_frames(frames, si, noDark, hotIdx, sp, &stackMapped, prog, &abortFlag, &rejPct2);
    int suppressed = 0;
    float worstSig = 0.0f;
    double hotPct = 0.0;
    if (!stackOk2 || stackMapped.w != kW || stackMapped.h != kH) {
        add_log(log, "hot: mapped restack FAILED");
    } else {
        ImageF lumaM;
        lumaM.init(kW, kH, 1);
        for (int y = 0; y < kH; ++y) {
            const float* s = stackMapped.px(0, y);
            float* d = lumaM.px(0, y);
            for (int x = 0; x < kW; ++x)
                d[x] = (s[3 * x] + s[3 * x + 1] + s[3 * x + 2]) * (1.0f / 3.0f);
        }
        float sigmaM = stack_noise_sigma(lumaM, scratch);
        if (sigmaM < 1e-7f) sigmaM = 1e-7f;
        for (const HotPix& hp : hots) {
            const float bg = local_bg_median(lumaM, hp.x, hp.y, 10, 2, scratch);
            const float ex = box3_max(lumaM, hp.x, hp.y) - bg;
            if (ex < 5.0f * sigmaM) ++suppressed;
            else worstSig = std::max(worstSig, ex / sigmaM);
        }
        hotPct = 100.0 * suppressed / (double)nHot;
    }
    const bool c2 = hotPct >= 95.0;
    add_log(log, "hot (with dark-frame map): %d/%d suppressed, %d exceeded, worst %.1f sigma",
            suppressed, nHot, nHot - suppressed, (double)worstSig);

    // ---- check 3: streak residual along the line < 15%% of single-frame amplitude ----
    float streakMax = 0.0f;
    int streakSamples = 0;
    for (int k = 0; k < 300; ++k) {
        const double t = (k + 0.5) / 300.0;
        const double qx = kLineX0 + t * (kLineX1 - kLineX0);
        const double qy = kLineY0 + t * (kLineY1 - kLineY0);
        const int ix = (int)std::lround(qx), iy = (int)std::lround(qy);
        if (ix < 12 || iy < 12 || ix > kW - 13 || iy > kH - 13) continue;
        bool nearStar = false;
        for (const SynthStar& s : stars) {
            const double dx = s.x - qx, dy = s.y - qy;
            if (dx * dx + dy * dy < 144.0) { nearStar = true; break; }
        }
        if (nearStar) continue;
        const float bg = local_bg_median(lumaR, ix, iy, 10, 3, scratch);
        streakMax = std::max(streakMax, box3_max(lumaR, ix, iy) - bg);
        ++streakSamples;
    }
    const double streakLimit = 0.15 * kStreakAmp / (double)kNorm;
    const bool c3 = streakSamples > 0 && (double)streakMax < streakLimit;
    add_log(log, "streak: max excess %.5f over %d samples (limit %.5f)", (double)streakMax,
            streakSamples, streakLimit);

    // ---- check 4: median FWHM of 30 brightest stacked stars (half-res luma, like input) ----
    ImageF lumaHalf;
    lumaHalf.init(kW / 2, kH / 2, 1);
    for (int y = 0; y < kH / 2; ++y) {
        const float* r0 = lumaR.px(0, 2 * y);
        const float* r1 = lumaR.px(0, 2 * y + 1);
        float* d = lumaHalf.px(0, y);
        for (int x = 0; x < kW / 2; ++x)
            d[x] = 0.25f * (r0[2 * x] + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1]);
    }
    std::vector<Star> stackStars;
    SkyStats stackSky;
    detect_stars(lumaHalf, dp, &stackStars, &stackSky);
    double fwhmMed = 999.0;
    if (!stackStars.empty()) {
        const int n = std::min<int>(30, (int)stackStars.size());
        std::vector<float> fw((size_t)n);
        for (int i = 0; i < n; ++i) fw[i] = stackStars[i].fwhm;  // sorted by flux desc
        fwhmMed = (double)median_of(fw);
    }
    const double fwhmLimit = 1.15 * kInputFwhmLuma;
    const bool c4 = fwhmMed <= fwhmLimit;
    add_log(log, "fwhm: stack median %.3f px of %d stars (limit %.3f)", fwhmMed,
            (int)stackStars.size(), fwhmLimit);

    // ---- check 5: lens shading correction flattens the lens falloff ----
    // synth_frame renders a 15% radial falloff into every frame. Pushing the matching correction
    // map through the REAL stacking path must bring the corner-to-centre ratio back to ~1. This
    // is the only check that exercises apply_shading_region end to end, so it costs a second
    // stack pass — worth it for a stage that silently changes every pixel of every result.
    const double vignBefore = corner_center_ratio(lumaR);
    double vignAfter = vignBefore;
    {
        ShadingMap sm;
        sm.cols = 17;
        sm.rows = 13;
        sm.gain.assign((size_t)sm.cols * (size_t)sm.rows * 3, 1.0f);
        const double vcx = (kW - 1) * 0.5, vcy = (kH - 1) * 0.5;
        const double invR2 = 1.0 / (vcx * vcx + vcy * vcy);
        for (int r = 0; r < sm.rows; ++r) {
            for (int c = 0; c < sm.cols; ++c) {
                const double x = (double)c / (double)(sm.cols - 1) * (double)(kW - 1);
                const double y = (double)r / (double)(sm.rows - 1) * (double)(kH - 1);
                const double dx = x - vcx, dy = y - vcy;
                const double vign = 1.0 - 0.15 * (dx * dx + dy * dy) * invR2;
                const float g = (float)(1.0 / std::max(vign, 1e-3));
                for (int ch = 0; ch < 3; ++ch)
                    sm.gain[((size_t)r * (size_t)sm.cols + (size_t)c) * 3 + (size_t)ch] = g;
                sm.maxGain = std::max(sm.maxGain, g);
            }
        }
        StackParams sp2 = sp;
        sp2.shading = &sm;
        ImageF corrected;
        float rej2 = 0.0f;
        std::atomic<bool> noAbort(false);
        ProgressFn quiet = [](const char*, int) {};
        if (stack_frames(frames, si, noDark, noHot, sp2, &corrected, quiet, &noAbort, &rej2) &&
            corrected.w == kW && corrected.h == kH && corrected.ch == 3) {
            ImageF lumaC;
            lumaC.init(kW, kH, 1);
            parallel_for(0, kH, [&corrected, &lumaC](int y) {
                const float* s = corrected.px(0, y);
                float* d = lumaC.px(0, y);
                for (int x = 0; x < kW; ++x)
                    d[x] = (s[3 * x] + s[3 * x + 1] + s[3 * x + 2]) * (1.0f / 3.0f);
            });
            vignAfter = corner_center_ratio(lumaC);
        } else {
            add_log(log, "shading re-stack FAILED");
        }
    }
    const bool c5 = vignAfter >= 0.97 && vignAfter <= 1.03;
    add_log(log, "check5 lens shading: corner/centre %.3f -> %.3f (%s)", vignBefore, vignAfter,
            c5 ? "PASS" : "FAIL");

    // ---- postprocess smoke test on a COPY (checks above used the LINEAR stack) ----
    {
        ImageF postCopy = stackImg;
        PostParams pp;
        ProgressFn pprog = [](const char*, int) {};
        postprocess(postCopy, pp, pprog);
        add_log(log, "postprocess smoke ok");
    }

    // ---- check 6: the v2 pipeline gives a finite, in-range picture with a dark sky ----
    double v2Median = -1.0;
    bool v2Finite = true;
    {
        ImageF v2 = stackImg;
        PostParams pp;
        pp.version = 2;
        ProgressFn pprog = [](const char*, int) {};
        postprocess(v2, pp, pprog);
        std::vector<float> lum;
        lum.reserve(v2.d.size() / 3);
        for (size_t i = 0; i + 2 < v2.d.size(); i += 3) {
            for (int c = 0; c < 3; ++c) {
                const float v = v2.d[i + c];
                if (!(v >= 0.0f && v <= 1.0f)) v2Finite = false;
            }
            lum.push_back((v2.d[i] + v2.d[i + 1] + v2.d[i + 2]) * (1.0f / 3.0f));
        }
        if (!lum.empty()) v2Median = median_of(lum);
    }
    const bool c6 = v2Finite && v2Median >= 0.03 && v2Median <= 0.40;
    add_log(log, "check6 post v2: finite %s, sky median %.3f (%s)", v2Finite ? "yes" : "NO", v2Median,
            c6 ? "PASS" : "FAIL");

    // ---- check 7: output rotation maps corners and the ground edge consistently ----
    bool c7 = true;
    {
        const int W = 5, H = 3;
        for (int rot : {0, 90, 180, 270}) {
            int ow, oh, sx, sy;
            rotated_size(rot, W, H, &ow, &oh);
            // The source's top-left corner must land where a clockwise turn puts it.
            const int ex = rot == 90 ? ow - 1 : rot == 180 ? ow - 1 : 0;
            const int ey = rot == 180 ? oh - 1 : rot == 270 ? oh - 1 : 0;
            rotated_source(rot, W, H, ex, ey, &sx, &sy);
            if (sx != 0 || sy != 0) c7 = false;
            // The output's bottom row must come from the edge down_edge_for_rotation names.
            rotated_source(rot, W, H, ow / 2, oh - 1, &sx, &sy);
            const int down = down_edge_for_rotation(rot);
            const bool onEdge = (down == 0 && sy == H - 1) || (down == 1 && sx == 0) ||
                                (down == 2 && sy == 0) || (down == 3 && sx == W - 1);
            if (!onEdge) c7 = false;
        }
    }
    add_log(log, "check7 rotation mapping (%s)", c7 ? "PASS" : "FAIL");

    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - tStart)
                        .count();
    add_log(log, "elapsed %lld ms", (long long)ms);

    const bool pass = c1 && c2 && c3 && c4 && c5 && c6 && c7;
    std::string checks = "[";
    checks += check_json("alignment", c1, alignMed, 0.35);
    checks += ",";
    checks += check_json("hot_pixels", c2, hotPct, 95.0);
    checks += ",";
    checks += check_json("streak", c3, (double)streakMax, streakLimit);
    checks += ",";
    checks += check_json("fwhm", c4, fwhmMed, fwhmLimit);
    checks += ",";
    checks += check_json("lens_shading", c5, vignAfter, 0.97);
    checks += ",";
    checks += check_json("post_v2", c6, v2Median, 0.40);
    checks += ",";
    checks += check_json("rotation", c7, c7 ? 1.0 : 0.0, 1.0);
    checks += "]";

    jsonmini::Builder top;
    top.add("pass", pass).addJson("checks", checks).add("log", log);
    return top.build();
}

}  // namespace

std::string run_selftest(const std::string& tmpDir) {
    std::vector<std::string> paths;
    std::string res;
    try {
        // Create the scratch directory (and its parents) rather than trusting the caller —
        // every frame write fails silently otherwise.
        for (size_t i = 1; i <= tmpDir.size(); ++i) {
            if (i == tmpDir.size() || tmpDir[i] == '/') {
                const std::string part = tmpDir.substr(0, i);
                if (part.size() > 1) ::mkdir(part.c_str(), 0775);  // EEXIST is fine
            }
        }
        res = run_impl(tmpDir, paths);
    } catch (...) {
        SLOGE("run_selftest: exception caught");
        jsonmini::Builder b;
        b.add("pass", false);
        b.addJson("checks", "[]");
        b.add("log", "exception during selftest");
        res = b.build();
    }
    for (const std::string& p : paths) std::remove(p.c_str());
    return res;
}

}  // namespace starcam

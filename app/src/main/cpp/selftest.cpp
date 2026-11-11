// selftest.cpp — end-to-end synthetic validation of the StarCam stacking pipeline (PLAN.md §6.8).
// Synthesizes 16 RGGB frames with known ground-truth transforms, noise, fixed hot pixels and a
// satellite streak, runs the REAL modules (sraw/bayer/stars/align/stack/post — no EngineSession),
// and validates alignment accuracy, hot-pixel rejection, streak suppression and stacked star FWHM;
// then hot pixels found from the lights alone (also with the stars standing still), sky-verified
// stars and their photometry (a second, small sequence with lamps fixed in the frame), and the v2
// look's white-balance gains.

#include "selftest.h"

#include <sys/stat.h>

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <random>

#include "align.h"
#include "bayer.h"
#include "common.h"
#include "hotpix.h"
#include "json_mini.h"
#include "post.h"
#include "skystars.h"
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

// Ground truth: maps reference (frame 0) RAW coords -> frame i RAW coords. The view turns a
// little further each frame, as it does when the sky turns or the phone settles on its mount:
// 0.8*i px across and 0.5*i px down at the centre, and 0.01*i deg about it, through a lens of
// 1600 px focal length (65 degrees across the frame, as wide as a phone's). A turned view is the
// homography K R K^-1; toward the edges it bends away from any shift-and-rotation, by about
// 2.5 luma px in the last frame, which a similarity cannot fit.
Transform truth_raw(int i) {
    const double f = 1600.0, cx = (kW - 1) * 0.5, cy = (kH - 1) * 0.5;
    const double pan = std::atan(0.8 * i / f), tilt = std::atan(0.5 * i / f);
    const double roll = 0.01 * i * M_PI / 180.0;
    // R = Rz(roll) * Ry(pan) * Rx(tilt): pan carries the centre toward +x, tilt toward +y, roll
    // turns the picture about it.
    const double cp = std::cos(pan), sp = std::sin(pan);
    const double ct = std::cos(tilt), st = std::sin(tilt);
    const double cr = std::cos(roll), sr = std::sin(roll);
    Transform ry, rx, rz, k, kInv;
    ry.m[0] = cp;
    ry.m[2] = sp;
    ry.m[6] = -sp;
    ry.m[8] = cp;
    rx.m[4] = ct;
    rx.m[5] = st;
    rx.m[7] = -st;
    rx.m[8] = ct;
    rz.m[0] = cr;
    rz.m[1] = -sr;
    rz.m[3] = sr;
    rz.m[4] = cr;
    k.m[0] = f;
    k.m[2] = cx;
    k.m[4] = f;
    k.m[5] = cy;
    kInv.m[0] = 1.0 / f;
    kInv.m[2] = -cx / f;
    kInv.m[4] = 1.0 / f;
    kInv.m[5] = -cy / f;
    return k.compose(rz.compose(ry.compose(rx.compose(kInv))));
}

// Convert a RAW-coords truth transform to LUMA coords. Superpixel (x,y) covers raw pixels
// 2x..2x+1 / 2y..2y+1, centroid at raw (2x+0.5, 2y+0.5), so with S: raw = 2*l + 0.5,
// T_luma = S^-1 o T_raw o S (the inverse of Transform::lumaToRaw).
Transform raw_truth_to_luma(const Transform& t) {
    Transform s, sInv;
    s.m[0] = 2.0;
    s.m[2] = 0.5;
    s.m[4] = 2.0;
    s.m[5] = 0.5;
    sInv.m[0] = 0.5;
    sInv.m[2] = -0.25;
    sInv.m[4] = 0.5;
    sInv.m[5] = -0.25;
    return sInv.compose(t.compose(s));
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

// Renders one synthetic frame into raw16 (fbuf = scratch, signal above black in counts): the stars
// through T (reference RAW -> this frame's RAW), the satellite when fi is 6..11 and streak is on,
// noise seeded by fi.
void synth_frame(int fi, const Transform& T, bool streak, const std::vector<SynthStar>& stars,
                 const std::vector<HotPix>& hots, std::vector<float>& fbuf,
                 std::vector<uint16_t>& raw16) {
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
    if (streak && fi >= 6 && fi <= 11) {
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

// The mean of r, g and b at RAW resolution: the luma the checks measure on.
ImageF raw_luma(const ImageF& rgb) {
    ImageF l;
    l.init(rgb.w, rgb.h, 1);
    parallel_for(0, rgb.h, [&rgb, &l](int y) {
        const float* s = rgb.px(0, y);
        float* d = l.px(0, y);
        for (int x = 0; x < rgb.w; ++x)
            d[x] = (s[3 * x] + s[3 * x + 1] + s[3 * x + 2]) * (1.0f / 3.0f);
    });
    return l;
}

// How many of the synthetic hot pixels a stack no longer shows: the brightest pixel of the 3 x 3
// around each less than 5 noise sigmas over its local background (the hot_pixels criterion).
int hot_suppressed(const ImageF& stackRGB, const std::vector<HotPix>& hots,
                   std::vector<float>& scratch, float* worstSig) {
    const ImageF l = raw_luma(stackRGB);
    float sigma = stack_noise_sigma(l, scratch);
    if (sigma < 1e-7f) sigma = 1e-7f;
    int suppressed = 0;
    *worstSig = 0.0f;
    for (const HotPix& hp : hots) {
        const float bg = local_bg_median(l, hp.x, hp.y, 10, 2, scratch);
        const float ex = box3_max(l, hp.x, hp.y) - bg;
        if (ex < 5.0f * sigma) ++suppressed;
        else *worstSig = std::max(*worstSig, ex / sigma);
    }
    return suppressed;
}

// Of a hot-pixel list, how many sites are not synthetic hot pixels.
int false_hot_sites(const std::vector<uint32_t>& found, const std::vector<HotPix>& hots) {
    std::vector<uint32_t> truth;
    for (const HotPix& hp : hots) truth.push_back((uint32_t)((size_t)hp.y * kW + hp.x));
    std::sort(truth.begin(), truth.end());
    int n = 0;
    for (uint32_t i : found)
        if (!std::binary_search(truth.begin(), truth.end(), i)) ++n;
    return n;
}

// ---- the sky-star sequence: coloured stars drifting with the sky, lamps fixed in the frame ----
constexpr int kSkyW = 1024;
constexpr int kSkyH = 768;
constexpr int kSkyFrames = 8;
constexpr int kSkyStars = 160;
constexpr int kLamps = 25;
constexpr float kStarColour[3] = {1.15f, 1.0f, 0.7f};  // per CFA colour, on the stars' light
constexpr float kLampColour[3] = {1.3f, 1.0f, 0.5f};
constexpr double kLampSigmaRaw = 1.6;

// Reference RAW -> frame fi's RAW: a turn of 0.04 degrees a frame about the centre and a slide of
// (2.0, 1.2) px, so a star has moved more than 3 luma px from frame 3 on.
Transform sky_motion(int fi) {
    const double a = 0.04 * fi * M_PI / 180.0, c = std::cos(a), s = std::sin(a);
    const double cx = (kSkyW - 1) * 0.5, cy = (kSkyH - 1) * 0.5;
    return Transform::similarity(c, s, cx - c * cx + s * cy + 2.0 * fi, cy - s * cx - c * cy + 1.2 * fi);
}

void add_blob(std::vector<float>& fbuf, int w, int h, double sx, double sy, double flux,
              double sigma, const float colour[3]) {
    const double twoSig2 = 2.0 * sigma * sigma;
    const double amp = flux / (2.0 * M_PI * sigma * sigma);
    const int x0 = clampi((int)std::floor(sx) - 8, 0, w - 1), x1 = clampi((int)std::floor(sx) + 8, 0, w - 1);
    const int y0 = clampi((int)std::floor(sy) - 8, 0, h - 1), y1 = clampi((int)std::floor(sy) + 8, 0, h - 1);
    for (int y = y0; y <= y1; ++y) {
        float* row = &fbuf[(size_t)y * w];
        for (int x = x0; x <= x1; ++x) {
            const double dx = x - sx, dy = y - sy;
            const int c = cfaColorAt(CFA::RGGB, ((y & 1) << 1) | (x & 1));
            row[x] += (float)(amp * colour[c] * std::exp(-(dx * dx + dy * dy) / twoSig2));
        }
    }
}

void synth_sky_frame(int fi, const std::vector<SynthStar>& stars, const std::vector<SynthStar>& lamps,
                     std::vector<float>& fbuf, std::vector<uint16_t>& raw16) {
    std::fill(fbuf.begin(), fbuf.end(), 120.0f);
    const Transform T = sky_motion(fi);
    for (const SynthStar& s : stars) {
        double sx, sy;
        T.apply(s.x, s.y, &sx, &sy);
        if (sx < -10.0 || sy < -10.0 || sx > kSkyW + 10.0 || sy > kSkyH + 10.0) continue;
        add_blob(fbuf, kSkyW, kSkyH, sx, sy, s.flux, kPsfSigmaRaw, kStarColour);
    }
    for (const SynthStar& l : lamps) add_blob(fbuf, kSkyW, kSkyH, l.x, l.y, l.flux, kLampSigmaRaw, kLampColour);
    parallel_for(0, kSkyH, [&fbuf, &raw16, fi](int y) {
        std::mt19937 rng(0xA11u + (uint32_t)fi * 2654435761u + (uint32_t)y * 40503u);
        std::normal_distribution<float> g(0.0f, 1.0f);
        const float* src = &fbuf[(size_t)y * kSkyW];
        uint16_t* dst = &raw16[(size_t)y * kSkyW];
        for (int x = 0; x < kSkyW; ++x) {
            const float sig = src[x];
            float n = 12.0f * g(rng);
            if (sig > 200.0f) n += 0.7f * std::sqrt(sig - 200.0f) * g(rng);
            dst[x] = (uint16_t)(clampf(kBlack + sig + n, 0.0f, (float)kWhite) + 0.5f);
        }
    });
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
        synth_frame(fi, truth_raw(fi), true, stars, hots, fbuf, raw16);

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
        const AlignResult ar = align_frame(refStars, frames[i].stars, prev, ap);
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
            // And a point back: rotated_point is rotated_source's inverse.
            for (int oy = 0; oy < oh; ++oy) {
                for (int ox = 0; ox < ow; ++ox) {
                    rotated_source(rot, W, H, ox, oy, &sx, &sy);
                    double px, py;
                    rotated_point(rot, W, H, sx, sy, &px, &py);
                    if (px != ox || py != oy) c7 = false;
                }
            }
        }
    }
    add_log(log, "check7 rotation mapping (%s)", c7 ? "PASS" : "FAIL");

    auto frame_path = [&tmpDir](const char* stem, int i) {
        char name[64];
        snprintf(name, sizeof(name), "%s_%04d.sraw", stem, i);
        std::string p = tmpDir;
        if (!p.empty() && p.back() != '/' && p.back() != '\\') p += '/';
        return p + name;
    };
    auto write_frame = [&](const std::string& path, int w, int h, const std::vector<uint16_t>& px) {
        SrawHeader hdr;
        hdr.width = (uint32_t)w;
        hdr.height = (uint32_t)h;
        hdr.cfa = CFA::RGGB;
        hdr.whiteLevel = kWhite;
        for (int i = 0; i < 4; ++i) { hdr.blackLevel[i] = kBlack; hdr.wbGain[i] = 1.0f; }
        hdr.exposureNs = 8000000000ull;
        hdr.iso = 800;
        const bool ok = sraw_write(path, hdr, px.data(), w);
        if (ok) paths.push_back(path);
        return ok;
    };

    // ---- check 8: hot pixels from the light frames alone (no dark map) ----
    // (a) the frames above: the list must catch the synthetic hot pixels, and the stack with it
    //     suppress them as the dark's list does; (b) 8 frames in which the stars do not move at
    //     all, so every star core is as persistent as a hot pixel: none may be taken for one.
    double autoPct = 0.0;
    int autoFalse = -1, staticFalse = -1;
    {
        HotLightsReport hr;
        const std::vector<uint32_t> autoHot = find_hot_pixels_lights(frames, si, &hr);
        autoFalse = false_hot_sites(autoHot, hots);
        ImageF st;
        float rj = 0.0f, worst = 0.0f;
        ProgressFn quiet = [](const char*, int) {};
        if (stack_frames(frames, si, noDark, autoHot, sp, &st, quiet, &abortFlag, &rj) &&
            st.w == kW && st.h == kH) {
            autoPct = 100.0 * hot_suppressed(st, hots, scratch, &worst) / (double)nHot;
        } else {
            add_log(log, "hot auto: restack FAILED");
        }
        add_log(log, "hot (lights only): %d listed (%d candidates, %d persistent, %d lit their "
                "neighbours), %d not synthetic, %.1f%% suppressed, worst %.1f sigma",
                (int)autoHot.size(), hr.candidates, hr.persistent, hr.shared, autoFalse, autoPct,
                (double)worst);

        std::vector<FrameRecord> still(8);
        bool okStill = true;
        for (int k = 0; k < (int)still.size() && okStill; ++k) {
            synth_frame(100 + k, Transform(), false, stars, hots, fbuf, raw16);
            const std::string p = frame_path("selftest_still", k);
            okStill = write_frame(p, kW, kH, raw16);
            still[k].srawPath = p;
            still[k].meta.index = k;
        }
        if (okStill) {
            HotLightsReport hs;
            const std::vector<uint32_t> stillHot = find_hot_pixels_lights(still, si, &hs);
            staticFalse = false_hot_sites(stillHot, hots);
            // Of those, on a star (within 6 px of one): the sites this check is about.
            int onStar = 0;
            {
                std::vector<uint32_t> truth;
                for (const HotPix& hp : hots) truth.push_back((uint32_t)((size_t)hp.y * kW + hp.x));
                std::sort(truth.begin(), truth.end());
                for (uint32_t i : stillHot) {
                    if (std::binary_search(truth.begin(), truth.end(), i)) continue;
                    const double x = (double)(i % kW), y = (double)(i / kW);
                    for (const SynthStar& s : stars) {
                        if (std::hypot(s.x - x, s.y - y) < 6.0) {
                            ++onStar;
                            break;
                        }
                    }
                }
            }
            add_log(log, "hot (lights only, stars standing still): %d listed (%d persistent, %d lit "
                    "their neighbours), %d not synthetic, %d of them on a star",
                    (int)stillHot.size(), hs.persistent, hs.shared, staticFalse, onStar);
        } else {
            add_log(log, "hot still frames: sraw_write FAILED");
        }
    }
    const bool c8 = autoPct >= 95.0 && autoFalse >= 0 && autoFalse <= 10 && staticFalse >= 0 &&
                    staticFalse <= 10;
    add_log(log, "check8 hot pixels from lights (%s)", c8 ? "PASS" : "FAIL");

    // ---- check 9: sky-verified stars and their photometry ----
    // Coloured stars drifting with the sky and lamps fixed in the frame: the stars must come out
    // sky-verified, the lamps not, and the stack's photometry must give the stars' colour back.
    double skyFrac = 0.0, colourDev = 1.0;
    int nTrue = 0, nTrueSky = 0, nLamp = 0, nLampSky = 0;
    {
        std::mt19937 rs(4242);
        std::uniform_real_distribution<double> ux(30.0, kSkyW - 31.0), uy(30.0, kSkyH - 31.0);
        std::uniform_real_distribution<double> uf(std::log(3000.0), std::log(40000.0));
        std::uniform_real_distribution<double> ul(20000.0, 40000.0);
        std::vector<SynthStar> sstars(kSkyStars), lamps;
        for (SynthStar& s : sstars) {
            s.x = ux(rs);
            s.y = uy(rs);
            s.flux = std::exp(uf(rs));
        }
        for (int tries = 0; (int)lamps.size() < kLamps && tries < 100000; ++tries) {
            SynthStar l;
            l.x = ux(rs);
            l.y = uy(rs);
            l.flux = ul(rs);
            bool bad = false;
            for (const SynthStar& s : sstars)
                if (std::hypot(s.x - l.x, s.y - l.y) < 30.0) { bad = true; break; }
            for (const SynthStar& o : lamps)
                if (std::hypot(o.x - l.x, o.y - l.y) < 30.0) { bad = true; break; }
            if (!bad) lamps.push_back(l);
        }
        SensorInfo ss = si;
        ss.width = kSkyW;
        ss.height = kSkyH;
        std::vector<float> sb((size_t)kSkyW * kSkyH);
        std::vector<uint16_t> sr((size_t)kSkyW * kSkyH);
        std::vector<FrameRecord> sf(kSkyFrames);
        bool ok = true;
        for (int fi = 0; fi < kSkyFrames && ok; ++fi) {
            synth_sky_frame(fi, sstars, lamps, sb, sr);
            const std::string p = frame_path("selftest_sky", fi);
            ok = write_frame(p, kSkyW, kSkyH, sr);
            FrameRecord& fr = sf[fi];
            fr.srawPath = p;
            fr.meta.index = fi;
            ImageF l;
            superpixel_luma(sr.data(), kSkyW, kSkyH, kSkyW, ss, wb, &l);
            SkyStats sk;
            detect_stars(l, dp, &fr.stars, &sk);
            fr.noiseSigma = sk.noiseSigma;
        }
        Transform prevT;
        sf[0].aligned = true;
        for (int fi = 1; fi < kSkyFrames && ok; ++fi) {
            const AlignResult ar = align_frame(sf[0].stars, sf[fi].stars, prevT, ap);
            sf[fi].aligned = ar.ok;
            sf[fi].rejected = !ar.ok;
            if (ar.ok) {
                sf[fi].T = ar.T;
                prevT = ar.T;
            }
        }
        std::vector<SkyStar> ver = ok ? verify_sky_stars(sf, 0, kSkyW / 2, kSkyH / 2)
                                      : std::vector<SkyStar>();
        // Which reference entries are stars and which lamps: within 2 RAW px of one.
        std::vector<int> kind(ver.size(), 0);  // 1 star, 2 lamp
        for (size_t k = 0; k < ver.size(); ++k) {
            const double rx = 2.0 * ver[k].x + 0.5, ry = 2.0 * ver[k].y + 0.5;
            for (const SynthStar& s : sstars)
                if (std::hypot(s.x - rx, s.y - ry) < 2.0) kind[k] = 1;
            for (const SynthStar& l : lamps)
                if (std::hypot(l.x - rx, l.y - ry) < 2.0) kind[k] = 2;
            if (kind[k] == 1) { ++nTrue; if (ver[k].sky) ++nTrueSky; }
            if (kind[k] == 2) { ++nLamp; if (ver[k].sky) ++nLampSky; }
        }
        skyFrac = nTrue > 0 ? (double)nTrueSky / nTrue : 0.0;
        // Photometry on the stack of the sequence, as finalize measures it.
        ImageF st;
        float rj = 0.0f;
        ProgressFn quiet = [](const char*, int) {};
        std::vector<float> rg, bg;
        StarPhotometry ph;
        if (ok && stack_frames(sf, ss, noDark, noHot, sp, &st, quiet, &abortFlag, &rj) &&
            st.w == kSkyW && st.h == kSkyH) {
            ImageF sl;
            stack_luma_half(st, &sl);
            measure_stars(st, sl, (float)(2.0 * kInputFwhmLuma), &ver, &ph);
            for (size_t k = 0; k < ver.size(); ++k) {
                const SkyStar& s = ver[k];
                if (kind[k] != 1 || !s.sky || !s.measured || s.sat || s.snr < 20.0f || !(s.g > 0.0))
                    continue;
                rg.push_back((float)(s.r / s.g));
                bg.push_back((float)(s.b / s.g));
            }
            if (!rg.empty()) {
                const double a = median_of(rg) / kStarColour[0], b = median_of(bg) / kStarColour[2];
                colourDev = std::max(std::fabs(a - 1.0), std::fabs(b - 1.0));
            }
            const std::string js = stars_json(ver, ph, st.w, st.h, 90, kSkyFrames);
            add_log(log, "sky stars: stars.json %zu bytes, aperture %.1f px (fwhm %.2f), sky bg %.5f",
                    js.size(), (double)ph.radius, (double)ph.fwhm, (double)ph.skyBg[1]);
        } else {
            add_log(log, "sky stars: sequence or stack FAILED");
        }
        add_log(log, "sky stars: %zu reference, stars %d/%d sky-verified, lamps %d/%d; colour of %zu "
                "stars off by %.2f%%", ver.size(), nTrueSky, nTrue, nLampSky, nLamp, rg.size(),
                100.0 * colourDev);
    }
    const bool c9 = nTrue > 0 && skyFrac >= 0.90 && nLamp >= 20 && nLampSky <= 2 && colourDev < 0.05;
    add_log(log, "check9 sky stars (%s)", c9 ? "PASS" : "FAIL");

    // ---- check 10: white-balance gains on the sky in the v2 look ----
    // The sky points are made as finalize makes them: each star's position and the luma of its
    // surroundings (here the median of a ring 8-14 px out) in the picture itself.
    // (a) gains of 1 change nothing; (b) on a dark sky with stars the gain weights are 1 away from
    // clipped cores (the channels as multiplied beforehand), and in the look the stars come out as
    // a premultiplied picture's would while the background stays as it is without gains (the
    // look applies the gains to a star's own light: what is small and stands out of the sky round
    // it); (c) a bright starless area keeps its
    // colour; (d) a clipped star core stays white; (e) under a sky over a black landscape (darker
    // than the sky) the sky still gets the full gains; (f) with fewer than 10 sky points the gains
    // change nothing; (g) a bright region of the sky with stars in it (a Milky Way core, 3 x the
    // sky) gets the full gains, stars and background; (h) a sky twice as bright at the bottom as at
    // the top gets the full gains everywhere; (i) in the look only the stars change: a glow in
    // the sky with a colour of its own, the lit edge of a roof, a window too dim for the lit mask
    // and a row of lamps under a sky with no star near them all come out as they do without gains.
    // ((c), (e), (g), (h) are of the weights alone: where the gains may act. What they act on
    // there is the look's, checked by (b) and (i).)
    double wbDiffA = 1.0, wbDiffB = 1.0, wbRect = 1.0, wbCore = 1.0, wbDark = 1.0, wbFew = 1.0;
    double wbOnly = 1.0, wbStarsMoved = 0.0;
    int wbOnlyBlocks = 0, wbOnlyX = 0, wbOnlyY = 0;
    double wbBg = 1.0;
    double wbCoreMW = 1.0, wbGrad = 1.0;
    int darkStarPx = 0, mwPts = 0;
    {
        const int w = 1024, h = 768;
        const float sky = 0.005f;
        const float gR = 1.7f, gB = 0.6f;
        std::mt19937 rw(99);
        std::normal_distribution<float> gn(0.0f, 0.0005f);
        // A sky of the given level (luma; neutral) with noise.
        auto skyOf = [&](const std::function<float(int, int)>& level) {
            ImageF img;
            img.init(w, h, 3);
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    const float l = level(x, y);
                    float* q = img.px(x, y);
                    for (int c = 0; c < 3; ++c) q[c] = l + gn(rw);
                }
            return img;
        };
        auto addStar = [](ImageF& img, double cx, double cy, double peak, double sigma, bool clip) {
            for (int y = std::max(0, (int)cy - 12); y <= std::min(img.h - 1, (int)cy + 12); ++y) {
                for (int x = std::max(0, (int)cx - 12); x <= std::min(img.w - 1, (int)cx + 12); ++x) {
                    const double d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                    float* q = img.px(x, y);
                    for (int c = 0; c < 3; ++c) {
                        float v = q[c] + (float)(peak * std::exp(-d2 / (2.0 * sigma * sigma)));
                        if (clip) v = std::min(v, 1.0f);
                        q[c] = v;
                    }
                }
            }
        };
        // The sky points of the stars at `at` in img: x, y and the median luma of a ring 8-14 px out.
        auto pointsOf = [](const ImageF& img, const std::vector<std::pair<double, double>>& at) {
            std::vector<float> pts, ring;
            for (const auto& s : at) {
                ring.clear();
                for (int y = (int)s.second - 14; y <= (int)s.second + 14; ++y)
                    for (int x = (int)s.first - 14; x <= (int)s.first + 14; ++x) {
                        if (x < 0 || y < 0 || x >= img.w || y >= img.h) continue;
                        const double d = std::hypot(x - s.first, y - s.second);
                        if (d < 8.0 || d > 14.0) continue;
                        const float* q = img.px(x, y);
                        ring.push_back(0.25f * (q[0] + 2.0f * q[1] + q[2]));
                    }
                pts.push_back((float)s.first);
                pts.push_back((float)s.second);
                pts.push_back(median_of(ring));
            }
            return pts;
        };
        auto premultiplied = [gR, gB](const ImageF& img) {
            ImageF out = img;
            for (size_t i = 0; i < out.d.size(); i += 3) {
                out.d[i] *= gR;
                out.d[i + 2] *= gB;
            }
            return out;
        };
        // The largest relative difference from the premultiplied picture, short of clipped cores.
        auto fullGains = [&premultiplied](const ImageF& before, const ImageF& after) {
            const ImageF ref = premultiplied(before);
            double worst = 0.0;
            for (size_t i = 0; i < before.d.size(); i += 3) {
                if (std::max(before.d[i], std::max(before.d[i + 1], before.d[i + 2])) >= 0.75f) continue;
                for (int c = 0; c < 3; ++c)
                    worst = std::max(worst, (double)std::fabs(after.d[i + c] - ref.d[i + c]) /
                                                std::max(1e-6, (double)std::fabs(ref.d[i + c])));
            }
            return worst;
        };
        std::uniform_real_distribution<double> px(20.0, w - 21.0), py(20.0, h - 21.0), pk(0.02, 0.5);

        // The plain sky: 150 stars and 6 with clipped cores.
        ImageF S = skyOf([sky](int, int) { return sky; });
        std::vector<std::pair<double, double>> at;
        for (int i = 0; i < 150; ++i) {
            const double x = px(rw), y = py(rw);
            addStar(S, x, y, pk(rw), 2.0, false);
            at.push_back({x, y});
        }
        std::vector<std::pair<int, int>> clipped;
        for (int i = 0; i < 6; ++i) {
            const int cx = 100 + 150 * i, cy = 120 + 90 * (i % 3);
            addStar(S, cx, cy, 3.0, 2.0, true);
            clipped.push_back({cx, cy});
            at.push_back({(double)cx, (double)cy});
        }
        const std::vector<float> ptsS = pointsOf(S, at);
        ProgressFn quiet = [](const char*, int) {};
        // (a)
        {
            ImageF a1 = stackImg, a2 = stackImg;
            PostParams p1, p2;
            p1.version = p2.version = 2;
            p2.wbR = 1.0f;
            p2.wbB = 1.0f;
            postprocess_v2(a1, p1, quiet);
            postprocess_v2(a2, p2, quiet);
            wbDiffA = 0.0;
            for (size_t i = 0; i < a1.d.size(); ++i)
                wbDiffA = std::max(wbDiffA, (double)std::fabs(a1.d[i] - a2.d[i]));
        }
        // (b) the gain weights alone first (exactly the premultiplied values short of clipped
        // cores), then the whole look: its stars against the look of the premultiplied picture
        // (the two stretch a little differently: 0.04 of the range), its background against the
        // look without gains (the mean, per channel, within 2 of 255: its noise takes the gains like
        // any light, and the stretch is not a straight line)
        double stepDiff = 1.0;
        {
            ImageF g = S;
            apply_sky_white_balance(g, gR, gB, ptsS);
            stepDiff = fullGains(S, g);
        }
        {
            ImageF a = S, b = premultiplied(S), plain = S;
            PostParams pa, pb, pc;
            pa.version = pb.version = pc.version = 2;
            pa.wbR = gR;
            pa.wbB = gB;
            pa.skyPts = ptsS;
            postprocess_v2(a, pa, quiet);
            postprocess_v2(b, pb, quiet);
            postprocess_v2(plain, pc, quiet);
            wbDiffB = 0.0;
            double bgA[3] = {0, 0, 0}, bgP[3] = {0, 0, 0};
            size_t nBg = 0;
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    bool near = false;
                    for (const auto& c : clipped)
                        near = near || (std::abs(x - c.first) <= 25 && std::abs(y - c.second) <= 25);
                    if (near) continue;
                    const float* s = S.px(x, y);
                    const float lum = (s[0] + s[1] + s[2]) / 3.0f;
                    if (lum > sky + 0.02f) {  // a star
                        for (int c = 0; c < 3; ++c)
                            wbDiffB = std::max(wbDiffB, (double)std::fabs(a.px(x, y)[c] - b.px(x, y)[c]));
                    } else if (lum < 1.3f * sky) {  // the background
                        for (int c = 0; c < 3; ++c) {
                            bgA[c] += a.px(x, y)[c];
                            bgP[c] += plain.px(x, y)[c];
                        }
                        ++nBg;
                    }
                }
            wbBg = 0.0;
            for (int c = 0; c < 3; ++c)
                wbBg = std::max(wbBg, nBg > 0 ? std::fabs(bgA[c] - bgP[c]) / (double)nBg : 1.0);
            if (stepDiff > 1e-5) wbDiffB = std::max(wbDiffB, stepDiff);
        }
        // (c), (d): a bright starless rectangle 200 px wide at 5 x the sky, and the clipped cores
        {
            ImageF g = S;
            const int rx0 = 400, ry0 = 380, rw0 = 200, rh0 = 300;
            for (int y = ry0; y < ry0 + rh0; ++y)
                for (int x = rx0; x < rx0 + rw0; ++x) {
                    float* q = g.px(x, y);
                    q[0] = 6.0f * sky;
                    q[1] = 5.0f * sky;
                    q[2] = 4.0f * sky;
                }
            std::vector<std::pair<double, double>> outside;  // the rectangle hides its stars
            for (const auto& s : at)
                if (s.first < rx0 || s.first >= rx0 + rw0 || s.second < ry0 || s.second >= ry0 + rh0)
                    outside.push_back(s);
            const ImageF before = g;
            apply_sky_white_balance(g, gR, gB, pointsOf(before, outside));
            double s0[3] = {0, 0, 0}, s1[3] = {0, 0, 0};
            for (int y = ry0 + 30; y < ry0 + rh0 - 30; ++y)
                for (int x = rx0 + 30; x < rx0 + rw0 - 30; ++x)
                    for (int c = 0; c < 3; ++c) {
                        s0[c] += before.px(x, y)[c];
                        s1[c] += g.px(x, y)[c];
                    }
            wbRect = std::max(std::fabs((s1[0] / s1[1]) / (s0[0] / s0[1]) - 1.0),
                              std::fabs((s1[2] / s1[1]) / (s0[2] / s0[1]) - 1.0));
            wbCore = 0.0;
            for (const auto& c : clipped) {
                const float* q = g.px(c.first, c.second);
                wbCore = std::max(wbCore, std::max(std::fabs((double)q[0] / q[1] - 1.0),
                                                   std::fabs((double)q[2] / q[1] - 1.0)));
            }
        }
        // (e) the lower 40 % a landscape at 0.2 x the sky (it hides the stars there): every pixel
        // short of a clipped core - sky, stars, the dark ground - gets exactly the gains.
        {
            ImageF g = S;
            std::normal_distribution<float> gl(0.0f, 0.0002f);
            const int top = (int)(0.6 * h);
            for (int y = top; y < h; ++y)
                for (int x = 0; x < w; ++x)
                    for (int c = 0; c < 3; ++c) g.px(x, y)[c] = 0.2f * sky + gl(rw);
            std::vector<std::pair<double, double>> above;
            for (const auto& s : at)
                if (s.second < top - 14) above.push_back(s);
            const ImageF before = g;
            apply_sky_white_balance(g, gR, gB, pointsOf(before, above));
            wbDark = fullGains(before, g);
            for (int y = 0; y < top; ++y)
                for (int x = 0; x < w; ++x) {
                    const float* q = before.px(x, y);
                    if (q[1] > sky + 0.01f && std::max(q[0], std::max(q[1], q[2])) < 0.75f) ++darkStarPx;
                }
        }
        // (f) fewer than 10 sky points: the output of gains 1, to the bit
        {
            ImageF a = S, b = S;
            PostParams pa, pb;
            pa.version = pb.version = 2;
            pa.wbR = gR;
            pa.wbB = gB;
            pa.skyPts.assign(ptsS.begin(), ptsS.begin() + 9 * 3);
            postprocess_v2(a, pa, quiet);
            postprocess_v2(b, pb, quiet);
            wbFew = 0.0;
            for (size_t i = 0; i < a.d.size(); ++i)
                wbFew = std::max(wbFew, (double)std::fabs(a.d[i] - b.d[i]));
        }
        // (g) a Milky Way core: a region 600 px wide at 3 x the sky with 20 of the sky's stars in it
        {
            const int mx0 = 212, my0 = 184, mx1 = 812, my1 = 584;
            ImageF g = skyOf([=](int x, int y) {
                return x >= mx0 && x < mx1 && y >= my0 && y < my1 ? 3.0f * sky : sky;
            });
            std::uniform_real_distribution<double> ix(mx0 + 20.0, mx1 - 21.0), iy(my0 + 20.0, my1 - 21.0);
            std::vector<std::pair<double, double>> st;
            while ((int)st.size() < 20) st.push_back({ix(rw), iy(rw)});
            while ((int)st.size() < 150) {
                const double x = px(rw), y = py(rw);
                if (x > mx0 - 20 && x < mx1 + 20 && y > my0 - 20 && y < my1 + 20) continue;
                st.push_back({x, y});
            }
            for (const auto& s : st) addStar(g, s.first, s.second, pk(rw), 2.0, false);
            mwPts = 0;
            for (const auto& s : st)
                if (s.first >= mx0 && s.first < mx1 && s.second >= my0 && s.second < my1) ++mwPts;
            const ImageF before = g;
            apply_sky_white_balance(g, gR, gB, pointsOf(before, st));
            wbCoreMW = fullGains(before, g);
        }
        // (h) the sky twice as bright at the bottom as at the top, stars all over
        {
            ImageF g = skyOf([=](int, int y) { return sky * (1.0f + (float)y / (float)(h - 1)); });
            std::vector<std::pair<double, double>> st;
            for (int i = 0; i < 150; ++i) {
                st.push_back({px(rw), py(rw)});
                addStar(g, st.back().first, st.back().second, pk(rw), 2.0, false);
            }
            const ImageF before = g;
            apply_sky_white_balance(g, gR, gB, pointsOf(before, st));
            wbGrad = fullGains(before, g);
        }
        // (i) only the stars
        {
            ImageF g = skyOf([sky](int, int) { return sky; });
            // A town's glow 350 px across, bluer than the sky.
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    const double d2 = (x - 500.0) * (x - 500.0) + (y - 300.0) * (y - 300.0);
                    const float bump = (float)std::exp(-d2 / (2.0 * 150.0 * 150.0));
                    float* q = g.px(x, y);
                    q[0] += 0.6f * sky * bump;
                    q[1] += 1.0f * sky * bump;
                    q[2] += 1.5f * sky * bump;
                }
            // The lit edge of a roof: 6 px thick, 4 x the sky, yellow, across the frame on a slant.
            for (int x = 60; x < 960; ++x) {
                const int yc = 470 - (x - 60) / 12;
                for (int y = yc - 3; y < yc + 3; ++y) {
                    float* q = g.px(x, y);
                    q[0] = 5.0f * sky;
                    q[1] = 4.0f * sky;
                    q[2] = 2.5f * sky;
                }
            }
            // A window too dim for the lit mask: 60 px, 1.5 x the sky.
            for (int y = 150; y < 210; ++y)
                for (int x = 800; x < 860; ++x) {
                    float* q = g.px(x, y);
                    q[0] = 1.8f * sky;
                    q[1] = 1.5f * sky;
                    q[2] = 1.2f * sky;
                }
            // Stars over the upper part, clear of the roof and the window; lamps below, shaped
            // like stars, with no star of the sky within 150 px.
            std::vector<std::pair<double, double>> st, lamps;
            std::uniform_real_distribution<double> sx(20.0, w - 21.0), sy(20.0, 380.0);
            while ((int)st.size() < 150) {
                const double x = sx(rw), y = sy(rw);
                if (x > 770 && x < 890 && y > 120 && y < 240) continue;
                st.push_back({x, y});
                addStar(g, x, y, pk(rw), 2.0, false);
            }
            for (int i = 0; i < 24; ++i) {
                lamps.push_back({40.0 + 40.0 * i, 640.0 + 30.0 * (i % 3)});
                addStar(g, lamps.back().first, lamps.back().second, 0.1, 2.0, false);
            }
            ImageF a = g, plain = g;
            PostParams pa, pc;
            pa.version = pc.version = 2;
            pa.wbR = gR;
            pa.wbB = gB;
            pa.skyPts = pointsOf(g, st);
            postprocess_v2(a, pa, quiet);
            postprocess_v2(plain, pc, quiet);
            // Blocks of 16 px with no star within 14 px: their mean, per channel, against no gains.
            wbOnly = 0.0;
            wbOnlyBlocks = 0;
            for (int by = 0; by + 16 <= h; by += 16)
                for (int bx = 0; bx + 16 <= w; bx += 16) {
                    bool star = false;
                    for (const auto& s : st)
                        star = star || (s.first > bx - 14 && s.first < bx + 30 && s.second > by - 14 && s.second < by + 30);
                    if (star) continue;
                    double ma[3] = {0, 0, 0}, mp[3] = {0, 0, 0};
                    for (int y = by; y < by + 16; ++y)
                        for (int x = bx; x < bx + 16; ++x)
                            for (int c = 0; c < 3; ++c) {
                                ma[c] += a.px(x, y)[c];
                                mp[c] += plain.px(x, y)[c];
                            }
                    for (int c = 0; c < 3; ++c) {
                        const double d = std::fabs(ma[c] - mp[c]) / 256.0;
                        if (d > wbOnly) {
                            wbOnly = d;
                            wbOnlyX = bx;
                            wbOnlyY = by;
                        }
                    }
                    ++wbOnlyBlocks;
                }
            // And the stars did take the gains: red against green at their cores, with and without.
            double ra = 0.0, rp = 0.0;
            int nCore = 0;
            for (const auto& s : st) {
                const int x = (int)std::lround(s.first), y = (int)std::lround(s.second);
                const float* qa = a.px(x, y);
                const float* qp = plain.px(x, y);
                if (qp[1] < 0.2f || qp[1] > 0.9f || qa[1] <= 0.0f) continue;  // faint, or clipped
                ra += qa[0] / qa[1];
                rp += qp[0] / qp[1];
                ++nCore;
            }
            wbStarsMoved = nCore > 0 && rp > 0.0 ? ra / rp : 0.0;
        }
        add_log(log, "wb gains: (i) only the stars: starless blocks against no gains %.2g (%d blocks, the "
                "worst at %d, %d), the stars' red over green x %.2f",
                wbOnly, wbOnlyBlocks, wbOnlyX, wbOnlyY, wbStarsMoved);
        add_log(log, "wb gains: (a) gains 1 diff %.2g; (b) stars against premultiplied %.2g, background "
                "against no gains %.2g (gain weights %.2g); "
                "(c) starless area colour %.3f%%; (d) clipped core %.3f%%; (e) over a dark landscape %.2g "
                "(%d star pixels); (f) 9 sky points diff %.2g; (g) Milky Way core with %d stars %.2g; "
                "(h) sky doubling %.2g",
                wbDiffA, wbDiffB, wbBg, stepDiff, 100.0 * wbRect, 100.0 * wbCore, wbDark, darkStarPx, wbFew,
                mwPts, wbCoreMW, wbGrad);
    }
    const bool c10 = wbDiffA == 0.0 && wbDiffB < 0.04 && wbBg < 0.008 && wbRect < 0.01 && wbCore < 0.01 &&
                     wbDark < 1e-5 && darkStarPx > 0 && wbFew == 0.0 && mwPts >= 20 &&
                     wbCoreMW < 1e-5 && wbGrad < 1e-5 &&
                     wbOnly < 0.004 && wbOnlyBlocks > 1000 && wbStarsMoved > 1.25;
    add_log(log, "check10 wb gains (%s)", c10 ? "PASS" : "FAIL");

    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - tStart)
                        .count();
    add_log(log, "elapsed %lld ms", (long long)ms);

    const bool pass = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && c9 && c10;
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
    checks += ",";
    // Value: % suppressed (the false-site counts, limit 10 each, are in the log).
    checks += check_json("hot_pixels_auto", c8, autoPct, 95.0);
    checks += ",";
    // Value: % of the true stars sky-verified (lamps and colour in the log).
    checks += check_json("sky_stars", c9, 100.0 * skyFrac, 90.0);
    checks += ",";
    // Value: the stars' largest difference from the premultiplied picture's (the rest in the log).
    checks += check_json("wb_gains", c10, wbDiffB, 0.04);
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

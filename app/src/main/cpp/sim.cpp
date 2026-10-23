// sim.cpp — a synthetic night sky for the emulator, whose camera shows no stars. Debug builds
// arm it from adb (SkySim.kt); every frame a session takes is then replaced by one of these before
// the engine sees it, so the whole capture loop - probe, star focus, lights, darks, the autopilot -
// runs as it would under a real sky. The star field is fixed (seeded) and drifts like a sky on an
// untracked tripod; clouds dim it, a focus error blurs it, dawn brightens it.

#include "sim.h"

#include <cmath>
#include <mutex>
#include <random>
#include <vector>

#include "common.h"

namespace starcam {
namespace {

struct SimStar {
    double x = 0, y = 0;  // RAW px at drift 0
    double peak = 0;      // counts above the sky, at the sharpest focus
};

// The same field every frame, every session: positions and brightnesses from a fixed seed.
std::vector<SimStar> field(int w, int h, int n) {
    static std::mutex mu;
    static std::vector<SimStar> stars;
    static int fw = 0, fh = 0, fn = 0;
    std::lock_guard<std::mutex> lk(mu);
    if (fw != w || fh != h || fn != n) {
        std::mt19937 rng(0xC0FFEEu);
        std::uniform_real_distribution<double> u(0.0, 1.0);
        stars.clear();
        stars.reserve(n);
        for (int i = 0; i < n; ++i) {
            SimStar s;
            s.x = 12.0 + u(rng) * (w - 24.0);
            s.y = 12.0 + u(rng) * (h - 24.0);
            // A few bright stars, many faint: brightness falls off like a real field's.
            s.peak = std::pow(u(rng), 4.0);
            stars.push_back(s);
        }
        fw = w;
        fh = h;
        fn = n;
    }
    return stars;
}

}  // namespace

bool simulate_sky(uint16_t* out, int w, int h, int strideElems, const SimParams& p) {
    if (!out || w < 16 || h < 16 || strideElems < w) return false;
    const float range = std::max(1.0f, p.white - p.black);
    std::vector<float> img((size_t)w * h, 0.0f);

    if (!p.dark) {
        // Sky: a gentle gradient (light pollution low down), lifted by cloud reflecting it.
        const float sky = p.sky * range * (1.0f + 1.5f * clampf(p.cloud, 0.0f, 1.0f));
        parallel_for(0, h, [&](int y) {
            float* row = &img[(size_t)y * w];
            const float g = sky * (0.85f + 0.3f * (float)y / (float)h);
            for (int x = 0; x < w; ++x) row[x] = g;
        });

        // Stars: a Gaussian PSF whose width grows with the focus error; the light it spreads
        // out keeps its total, so blurred stars also get fainter and the faint ones vanish.
        const double sigma0 = 1.25;
        const double sigma = std::sqrt(sigma0 * sigma0 + (double)p.blurPx * p.blurPx);
        const double spread = (sigma0 * sigma0) / (sigma * sigma);
        const double light = 1.0 - clampf(p.cloud, 0.0f, 1.0f);
        const double twoSig2 = 2.0 * sigma * sigma;
        const int reach = (int)std::ceil(4.0 * sigma);
        // The drift: a slow turn about the centre and a slide, as on a tripod that does not track.
        const double cx = 0.5 * (w - 1), cy = 0.5 * (h - 1);
        const double th = 0.004 * p.drift * M_PI / 180.0;
        const double ct = std::cos(th), st = std::sin(th);
        const double tx = 0.7 * p.drift, ty = -0.45 * p.drift;
        for (const SimStar& s : field(w, h, p.stars)) {
            const double dx = s.x - cx, dy = s.y - cy;
            const double sx = cx + ct * dx - st * dy + tx;
            const double sy = cy + st * dx + ct * dy + ty;
            if (sx < -8 || sy < -8 || sx > w + 8 || sy > h + 8) continue;
            const double amp = (0.03 + 1.6 * s.peak) * range * spread * light;
            if (amp < 0.5) continue;
            const int x0 = clampi((int)std::floor(sx) - reach, 0, w - 1);
            const int x1 = clampi((int)std::floor(sx) + reach, 0, w - 1);
            const int y0 = clampi((int)std::floor(sy) - reach, 0, h - 1);
            const int y1 = clampi((int)std::floor(sy) + reach, 0, h - 1);
            for (int y = y0; y <= y1; ++y) {
                float* row = &img[(size_t)y * w];
                const double ddy = (double)y - sy;
                for (int x = x0; x <= x1; ++x) {
                    const double ddx = (double)x - sx;
                    row[x] += (float)(amp * std::exp(-(ddx * ddx + ddy * ddy) / twoSig2));
                }
            }
        }
    }

    // Noise: read noise everywhere, photon noise on the signal; then the hot pixels, which sit
    // in the same places in every frame, lights and darks alike (the darks must catch them).
    const float darkCurrent = 2.0f;
    parallel_for(0, h, [&](int y) {
        std::mt19937 rng(p.seed * 2654435761u + (uint32_t)y * 40503u + 17u);
        std::normal_distribution<float> g(0.0f, 1.0f);
        const float* src = &img[(size_t)y * w];
        uint16_t* dst = out + (size_t)y * strideElems;
        for (int x = 0; x < w; ++x) {
            const float sig = src[x] + darkCurrent;
            const float n = 3.0f * g(rng) + 0.6f * std::sqrt(std::max(0.0f, sig)) * g(rng);
            const float v = clampf(p.black + sig + n, 0.0f, p.white);
            dst[x] = (uint16_t)(v + 0.5f);
        }
    });
    std::mt19937 hot(0x5EEDu);
    std::uniform_int_distribution<int> hx(0, w - 1), hy(0, h - 1);
    for (int i = 0; i < 160; ++i) {
        const int x = hx(hot), y = hy(hot);
        uint16_t& v = out[(size_t)y * strideElems + x];
        v = (uint16_t)std::min<float>(p.white, (float)v + 0.12f * range);
    }
    return true;
}

}  // namespace starcam

// Synthetic phone-astro scene: what a 20-frame stack from a 24 mm-equivalent phone camera on a
// tripod looks like in linear RGB before post-processing. Light pollution brightening towards the
// horizon and one side, residual vignetting, a Milky Way band with dust lanes rising from the
// horizon, coloured stars, a dark landscape with trees and two house lights, and stack noise that
// is correlated over a couple of pixels like demosaiced data. Numbers are chosen to resemble the
// engine's real output (sky background ~6% of white), not measured from a particular phone.

#include "scene.h"

#include <cmath>

#include "common.h"

namespace lab {
namespace {

inline uint32_t hash2(int x, int y, uint32_t seed) {
    uint32_t h = (uint32_t)x * 374761393u + (uint32_t)y * 668265263u + seed * 2246822519u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}

inline float hashf(int x, int y, uint32_t seed) {
    return (float)(hash2(x, y, seed) & 0xFFFFFFu) / 16777215.0f;
}

float value_noise(float x, float y, uint32_t seed) {
    const int xi = (int)std::floor(x), yi = (int)std::floor(y);
    const float fx = x - (float)xi, fy = y - (float)yi;
    const float sx = fx * fx * (3.0f - 2.0f * fx), sy = fy * fy * (3.0f - 2.0f * fy);
    const float a = hashf(xi, yi, seed), b = hashf(xi + 1, yi, seed);
    const float c = hashf(xi, yi + 1, seed), d = hashf(xi + 1, yi + 1, seed);
    const float top = a + (b - a) * sx, bottom = c + (d - c) * sx;
    return top + (bottom - top) * sy;
}

// Fractal value noise in [0, 1].
float fbm(float x, float y, int octaves, uint32_t seed) {
    float s = 0.0f, amp = 0.5f, f = 1.0f, norm = 0.0f;
    for (int i = 0; i < octaves; ++i) {
        s += amp * value_noise(x * f, y * f, seed + 101u * (uint32_t)i);
        norm += amp;
        amp *= 0.5f;
        f *= 2.0f;
    }
    return s / norm;
}

inline float smoothstep(float e0, float e1, float x) {
    float t = starcam::clampf((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed * 0x9E3779B97F4A7C15ull + 1) {}
    uint64_t next() {
        s ^= s << 13;
        s ^= s >> 7;
        s ^= s << 17;
        return s;
    }
    float uniform() { return (float)((next() >> 11) * (1.0 / 9007199254740992.0)); }
    float gauss() {
        float u1 = std::max(uniform(), 1e-12f), u2 = uniform();
        return std::sqrt(-2.0f * std::log(u1)) * std::cos(6.2831853f * u2);
    }
};

// Star colour from B-V: Ballesteros' temperature, then a black body sampled at three
// wavelengths and normalised so 6500 K is white.
void bv_to_rgb(float bv, float out[3]) {
    const double t = 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62));
    auto planck = [](double lambdaNm, double temp) {
        const double l = lambdaNm * 1e-9;
        return 1.0 / (std::pow(l, 5.0) * (std::exp(0.0143877735 / (l * temp)) - 1.0));
    };
    const double lam[3] = {600.0, 540.0, 460.0};
    double v[3], m = 0.0;
    for (int c = 0; c < 3; ++c) {
        v[c] = planck(lam[c], t) / planck(lam[c], 6500.0);
        m = std::max(m, v[c]);
    }
    for (int c = 0; c < 3; ++c) out[c] = (float)(v[c] / m);
}

}  // namespace

float Scene::mwDistance(float x, float y) const {
    const float dx = mwX1 - mwX0, dy = mwY1 - mwY0;
    const float len = std::sqrt(dx * dx + dy * dy);
    if (len <= 0.0f) return 1e9f;
    return std::fabs((x - mwX0) * dy - (y - mwY0) * dx) / len;
}

Scene render_scene(const SceneOptions& o) {
    Scene s;
    s.w = o.w;
    s.h = o.h;
    const int W = o.w, H = o.h;
    s.rgb.assign((size_t)W * H * 3, 0.0f);
    s.starLight.assign((size_t)W * H, 0.0f);
    s.horizonY.assign((size_t)W, (float)H + 10.0f);
    s.hasForeground = o.foreground;
    s.hasMilkyWay = o.milkyWay;

    // ---- horizon: rolling hills plus patches of jagged tree tops
    if (o.foreground) {
        for (int x = 0; x < W; ++x) {
            const float fx = (float)x;
            const float hills = 0.07f * (float)H * fbm(fx / 900.0f, 0.5f, 4, o.seed + 11);
            const float treePatch = smoothstep(0.50f, 0.65f, fbm(fx / 400.0f, 9.1f, 2, o.seed + 23));
            const float trees = 0.035f * (float)H * fbm(fx / 14.0f, 3.3f, 3, o.seed + 31) * treePatch;
            s.horizonY[x] = 0.74f * (float)H - hills - trees;
        }
    }

    // ---- Milky Way: rising from the lower left, brighter core near the horizon
    s.mwX0 = 0.20f * (float)W;
    s.mwY0 = 1.05f * (float)H;
    s.mwX1 = 0.80f * (float)W;
    s.mwY1 = -0.05f * (float)H;
    s.mwSigma = 0.09f * (float)W;

    const float sky0[3] = {0.050f, 0.052f, 0.056f};
    const float lpCol[3] = {1.0f, 0.72f, 0.42f};
    const float mwCol[3] = {1.0f, 0.93f, 0.80f};
    const float groundCol[3] = {1.0f, 0.86f, 0.72f};
    const float horizonMean = 0.72f * (float)H;
    const float halfDiag = 0.5f * std::sqrt((float)W * W + (float)H * H);

    starcam::parallel_for(0, H, [&](int y) {
        const float fy = (float)y;
        for (int x = 0; x < W; ++x) {
            const float fx = (float)x;
            float* q = &s.rgb[((size_t)y * W + x) * 3];
            float v[3];
            if (s.ground(x, y)) {
                const float below = fy - s.horizonY[x];
                const float tex = 0.6f + 0.8f * fbm(fx / 60.0f, fy / 25.0f, 5, o.seed + 41);
                const float glow = 1.0f + 0.5f * std::exp(-below / (0.05f * (float)H));
                for (int c = 0; c < 3; ++c) v[c] = 0.010f * tex * glow * groundCol[c];
            } else {
                for (int c = 0; c < 3; ++c) v[c] = sky0[c];
                if (o.gradient) {
                    const float t = std::max(0.0f, (horizonMean - fy) / (0.30f * (float)H));
                    const float side = 0.75f + 0.5f * (fx / (float)W);
                    const float lp = 0.045f * std::exp(-t) * side;
                    for (int c = 0; c < 3; ++c) v[c] += lp * lpCol[c];
                }
                if (o.milkyWay) {
                    const float d = s.mwDistance(fx, fy);
                    // Position along the band, 0 at the lower end.
                    const float ax = s.mwX1 - s.mwX0, ay = s.mwY1 - s.mwY0;
                    const float along = ((fx - s.mwX0) * ax + (fy - s.mwY0) * ay) / (ax * ax + ay * ay);
                    float mw = 0.030f * std::exp(-0.5f * (d / s.mwSigma) * (d / s.mwSigma));
                    mw *= 1.0f + 0.8f * std::exp(-((along - 0.25f) / 0.15f) * ((along - 0.25f) / 0.15f));
                    mw *= 0.7f + 0.6f * fbm(fx / 120.0f, fy / 120.0f, 4, o.seed + 53);
                    const float lane = d / (0.5f * s.mwSigma);
                    const float dust =
                        smoothstep(0.52f, 0.72f, fbm(fx / 350.0f, fy / 350.0f, 5, o.seed + 67)) *
                        std::exp(-0.5f * lane * lane);
                    mw *= 1.0f - 0.75f * dust;
                    for (int c = 0; c < 3; ++c) v[c] += mw * mwCol[c];
                }
            }
            for (int c = 0; c < 3; ++c) q[c] = v[c];
        }
    });

    // ---- stars: power-law brightness, more of them along the band, none behind the ground
    Rng rng(o.seed);
    const float psfSigma = 1.25f;
    const int nField = (int)(40000.0 * W * H / (4000.0 * 3000.0));
    const int nBand = o.milkyWay ? nField / 2 : 0;
    s.stars.reserve((size_t)(nField + nBand));
    for (int i = 0; i < nField + nBand; ++i) {
        float x, y;
        if (i < nField) {
            x = rng.uniform() * (float)W;
            y = rng.uniform() * (float)H;
        } else {
            // Gaussian scatter around the band's centre line.
            const float t = rng.uniform();
            x = s.mwX0 + t * (s.mwX1 - s.mwX0);
            y = s.mwY0 + t * (s.mwY1 - s.mwY0);
            const float dx = s.mwY1 - s.mwY0, dy = -(s.mwX1 - s.mwX0);
            const float len = std::sqrt(dx * dx + dy * dy);
            const float off = rng.gauss() * 0.6f * s.mwSigma;
            x += off * dx / len;
            y += off * dy / len;
        }
        if (x < 2 || y < 2 || x >= W - 2 || y >= H - 2) continue;
        if (s.ground((int)x, (int)y)) continue;
        TruthStar st;
        st.x = x;
        st.y = y;
        st.peak = std::min(3.0f, 0.0012f * std::pow(std::max(rng.uniform(), 1e-6f), -1.0f / 1.1f));
        const float pick = rng.uniform();
        float bv = pick < 0.70f ? 0.75f + 0.35f * rng.gauss()
                 : pick < 0.85f ? 1.50f + 0.25f * rng.gauss() : 0.0f + 0.2f * rng.gauss();
        st.bv = starcam::clampf(bv, -0.3f, 2.0f);
        bv_to_rgb(st.bv, st.rgb);
        s.stars.push_back(st);
    }
    for (const TruthStar& st : s.stars) {
        const bool bright = st.peak > 0.2f;
        const int r = bright ? 14 : 5;
        const int x0 = std::max(0, (int)st.x - r), x1 = std::min(W - 1, (int)st.x + r);
        const int y0 = std::max(0, (int)st.y - r), y1 = std::min(H - 1, (int)st.y + r);
        for (int y = y0; y <= y1; ++y) {
            for (int x = x0; x <= x1; ++x) {
                if (s.ground(x, y)) continue;
                const float dx = (float)x - st.x, dy = (float)y - st.y;
                const float r2 = dx * dx + dy * dy;
                float a = st.peak * std::exp(-0.5f * r2 / (psfSigma * psfSigma));
                if (bright) a += 0.02f * st.peak * std::exp(-0.5f * r2 / 16.0f);  // optics halo
                if (a < 1e-6f) continue;
                float* q = &s.rgb[((size_t)y * W + x) * 3];
                for (int c = 0; c < 3; ++c) q[c] += a * st.rgb[c];
                s.starLight[(size_t)y * W + x] += a;
            }
        }
    }

    // ---- two house lights on the ground
    if (o.foreground) {
        const float lx[2] = {0.35f * (float)W, 0.62f * (float)W};
        for (int k = 0; k < 2; ++k) {
            const int cx = (int)lx[k];
            const float cy = s.horizonY[cx] + 30.0f + 15.0f * (float)k;
            for (int y = (int)cy - 6; y <= (int)cy + 6; ++y) {
                for (int x = cx - 6; x <= cx + 6; ++x) {
                    if (x < 0 || y < 0 || x >= W || y >= H || !s.ground(x, y)) continue;
                    const float r2 = (float)((x - cx) * (x - cx)) + ((float)y - cy) * ((float)y - cy);
                    const float a = 0.6f * std::exp(-0.5f * r2 / 2.0f);
                    float* q = &s.rgb[((size_t)y * W + x) * 3];
                    q[0] += a;
                    q[1] += 0.8f * a;
                    q[2] += 0.5f * a;
                }
            }
        }
    }

    // ---- residual vignetting after the lens shading correction
    starcam::parallel_for(0, H, [&](int y) {
        for (int x = 0; x < W; ++x) {
            const float dx = (float)x - 0.5f * (float)W, dy = (float)y - 0.5f * (float)H;
            const float r2 = (dx * dx + dy * dy) / (halfDiag * halfDiag);
            const float v = 1.0f - 0.10f * r2;
            float* q = &s.rgb[((size_t)y * W + x) * 3];
            for (int c = 0; c < 3; ++c) q[c] *= v;
        }
    });

    // ---- noise: shot + read, correlated over ~2 px like demosaiced data, R/B noisier than G
    const float kShot = 1.4e-5f * o.noise * o.noise, read2 = 1.6e-7f * o.noise * o.noise;
    s.noiseSigma = std::sqrt(kShot * 0.07f + read2);
    {
        std::vector<float> n((size_t)W * H * 3), t((size_t)W * H * 3);
        starcam::parallel_for(0, H, [&](int y) {
            Rng r(o.seed * 7919ull + (uint64_t)y * 104729ull + 3);
            for (size_t i = (size_t)y * W * 3; i < (size_t)(y + 1) * W * 3; ++i) n[i] = r.gauss();
        });
        // [1 2 1]/4 in x then y keeps 0.375 of the sigma; divide that back out.
        starcam::parallel_for(0, H, [&](int y) {
            for (int x = 0; x < W; ++x) {
                const int xl = std::max(0, x - 1), xr = std::min(W - 1, x + 1);
                for (int c = 0; c < 3; ++c) {
                    t[((size_t)y * W + x) * 3 + c] =
                        0.25f * n[((size_t)y * W + xl) * 3 + c] + 0.5f * n[((size_t)y * W + x) * 3 + c] +
                        0.25f * n[((size_t)y * W + xr) * 3 + c];
                }
            }
        });
        starcam::parallel_for(0, H, [&](int y) {
            const int yu = std::max(0, y - 1), yd = std::min(H - 1, y + 1);
            for (int x = 0; x < W; ++x) {
                float* q = &s.rgb[((size_t)y * W + x) * 3];
                for (int c = 0; c < 3; ++c) {
                    const float g = (0.25f * t[((size_t)yu * W + x) * 3 + c] +
                                     0.5f * t[((size_t)y * W + x) * 3 + c] +
                                     0.25f * t[((size_t)yd * W + x) * 3 + c]) / 0.375f;
                    const float sig = std::sqrt(kShot * std::max(q[c], 0.0f) + read2) *
                                      (c == 1 ? 1.0f : 1.3f);
                    q[c] = std::min(1.0f, q[c] + g * sig);
                }
            }
        });
    }
    return s;
}

}  // namespace lab

// postlab — runs the engine's post-processing (app/src/main/cpp/post.cpp) outside the app, on a
// synthetic scene with known ground truth or on a session's master_linear16.tiff, and reports how
// each variant treats the sky, the Milky Way, the ground, star colour and noise. Built with the NDK
// for the x86_64 emulator by run.ps1; see DEBUGGING.md.
//
//   postlab scene <outdir> [variants] [--nofg] [--nomw] [--nograd] [--mwflat] [--dome] [--noise X]
//                 [--seed N]
//   postlab tiff <master_linear16.tiff> <outdir> [variants]
//
// variants: comma list of classic,v2 (default both), preview / preview_old (the live-stack preview
// now / until 0.4.0). key=value pairs override PostParams fields.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "common.h"
#include "post.h"
#include "preview.h"
#include "scene.h"

using starcam::ImageF;

namespace {

bool write_bmp(const std::string& path, const ImageF& img, int x0, int y0, int cw, int chh, int step) {
    const int ow = cw / step, oh = chh / step;
    if (ow <= 0 || oh <= 0) return false;
    const int rowBytes = (ow * 3 + 3) & ~3;
    const uint32_t dataSize = (uint32_t)rowBytes * (uint32_t)oh;
    uint8_t hdr[54] = {'B', 'M'};
    auto put32 = [&hdr](int off, uint32_t v) {
        for (int i = 0; i < 4; ++i) hdr[off + i] = (uint8_t)(v >> (8 * i));
    };
    put32(2, 54 + dataSize);
    put32(10, 54);
    put32(14, 40);
    put32(18, (uint32_t)ow);
    put32(22, (uint32_t)oh);
    hdr[26] = 1;
    hdr[28] = 24;
    put32(34, dataSize);
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    std::fwrite(hdr, 1, 54, f);
    std::vector<uint8_t> row((size_t)rowBytes, 0);
    for (int oy = oh - 1; oy >= 0; --oy) {
        for (int ox = 0; ox < ow; ++ox) {
            float acc[3] = {0, 0, 0};
            for (int sy = 0; sy < step; ++sy) {
                for (int sx = 0; sx < step; ++sx) {
                    const float* q = img.px(x0 + ox * step + sx, y0 + oy * step + sy);
                    for (int c = 0; c < 3; ++c) acc[c] += q[c];
                }
            }
            for (int c = 0; c < 3; ++c) {
                const float v = starcam::clampf(acc[c] / (float)(step * step), 0.0f, 1.0f);
                row[(size_t)ox * 3 + (2 - c)] = (uint8_t)std::lround(v * 255.0f);
            }
        }
        std::fwrite(row.data(), 1, row.size(), f);
    }
    return std::fclose(f) == 0;
}

// Reads the engine's own uncompressed 16-bit RGB TIFF (tiffout.cpp) back into linear floats.
bool read_tiff16(const std::string& path, ImageF* out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> b((size_t)size);
    const bool ok = std::fread(b.data(), 1, b.size(), f) == b.size();
    std::fclose(f);
    if (!ok || size < 8 || b[0] != 'I') return false;
    auto u16 = [&b](size_t o) { return (uint32_t)b[o] | ((uint32_t)b[o + 1] << 8); };
    auto u32 = [&u16](size_t o) { return u16(o) | (u16(o + 2) << 16); };
    const size_t ifd = u32(4);
    const uint32_t n = u16(ifd);
    uint32_t w = 0, h = 0, strip = 0;
    for (uint32_t i = 0; i < n; ++i) {
        const size_t e = ifd + 2 + 12 * (size_t)i;
        const uint32_t tag = u16(e), type = u16(e + 2);
        const uint32_t v = type == 3 ? u16(e + 8) : u32(e + 8);
        if (tag == 256) w = v;
        if (tag == 257) h = v;
        if (tag == 273) strip = v;
    }
    if (!w || !h || (size_t)strip + (size_t)w * h * 6 > b.size()) return false;
    out->init((int)w, (int)h, 3);
    for (size_t i = 0; i < (size_t)w * h * 3; ++i) out->d[i] = (float)u16(strip + 2 * i) / 65535.0f;
    return true;
}

struct Metrics {
    float skyMed = 0, skyFlat = 0, mwContrast = 0, groundMed = 0, horizonDelta = 0;
    float mwLow = 0, mwDark = 0;  // the band's foot (lowest quarter above the horizon), its darkest tenth
    float rimBright = 0, rimDark = 0;
    float redSat = 0, blueSat = 0, hueOk = 0, lumaNoise = 0, chromaNoise = 0;
    int nRed = 0, nBlue = 0;
};

inline float luma(const float* q) { return 0.2126f * q[0] + 0.7152f * q[1] + 0.0722f * q[2]; }

float median(std::vector<float> v) { return starcam::median_of(v); }

float mad_sigma(std::vector<float> v) {
    if (v.empty()) return 0;
    const float m = starcam::median_of(v);
    for (float& x : v) x = std::fabs(x - m);
    return 1.4826f * starcam::median_of(v);
}

Metrics measure(const ImageF& img, const lab::Scene& s) {
    Metrics m;
    const int W = img.w, H = img.h;
    auto cleanSky = [&](int x, int y) {
        if (s.hasForeground && y > s.horizonY[x] - 60.0f) return false;
        if (s.starLight[(size_t)y * W + x] > 0.3f * s.noiseSigma) return false;
        if (s.hasMilkyWay && s.mwDistance((float)x, (float)y) < 2.5f * s.mwSigma) return false;
        return true;
    };
    std::vector<float> skyBlocks, mwBlocks, mwLowBlocks, nearHorizon, groundV, v;
    const int B = 64;
    for (int by = 0; by + B <= H; by += B) {
        for (int bx = 0; bx + B <= W; bx += B) {
            v.clear();
            for (int y = by; y < by + B; ++y)
                for (int x = bx; x < bx + B; ++x)
                    if (cleanSky(x, y)) v.push_back(luma(img.px(x, y)));
            if (v.size() > (size_t)(0.7f * B * B)) skyBlocks.push_back(median(v));
            const float cx = (float)bx + 0.5f * B, cy = (float)by + 0.5f * B;
            if (s.hasMilkyWay && s.mwDistance(cx, cy) < 0.5f * s.mwSigma &&
                (!s.hasForeground || (float)(by + B) < s.horizonY[(int)cx] - 60.0f)) {
                v.clear();
                for (int y = by; y < by + B; ++y)
                    for (int x = bx; x < bx + B; ++x)
                        if (s.starLight[(size_t)y * W + x] < 0.3f * s.noiseSigma)
                            v.push_back(luma(img.px(x, y)));
                if (v.size() > (size_t)(0.5f * B * B)) {
                    const float med = median(v);
                    mwBlocks.push_back(med);
                    const float foot = s.hasForeground ? s.horizonY[(int)cx] : (float)H;
                    if (foot - (float)(by + B) < 0.25f * (float)H) mwLowBlocks.push_back(med);
                }
            }
        }
    }
    if (!skyBlocks.empty()) {
        std::vector<float> sb = skyBlocks;
        m.skyMed = median(sb);
        m.skyFlat = starcam::percentile_of(sb, 0.95f) - starcam::percentile_of(sb, 0.05f);
    }
    if (!mwBlocks.empty()) {
        m.mwContrast = median(mwBlocks) - m.skyMed;
        m.mwDark = starcam::percentile_of(mwBlocks, 0.10f) - m.skyMed;
    }
    if (!mwLowBlocks.empty()) m.mwLow = median(mwLowBlocks) - m.skyMed;
    if (s.hasForeground) {
        for (int y = 0; y < H; y += 2) {
            for (int x = 0; x < W; x += 2) {
                if (y > s.horizonY[x] + 25.0f) groundV.push_back(luma(img.px(x, y)));
                else if (y < s.horizonY[x] - 60.0f && y > s.horizonY[x] - 220.0f && cleanSky(x, y))
                    nearHorizon.push_back(luma(img.px(x, y)));
            }
        }
        if (!groundV.empty()) m.groundMed = median(groundV);
        if (!nearHorizon.empty()) m.horizonDelta = median(nearHorizon) - m.skyMed;
        // Outline along the silhouette: per column, the brightest (darkest) pixel within a few
        // rows of the true horizon against the same window 55 rows higher in plain sky.
        std::vector<float> bright, dark;
        for (int x = 0; x < W; x += 2) {
            const int hz = (int)std::floor(s.horizonY[x]);
            if (hz < 70 || hz > H - 4) continue;
            float maxA = 0, minA = 1e9f, maxB = 0, minB = 1e9f;
            for (int y = hz - 5; y <= hz + 2; ++y) maxA = std::max(maxA, luma(img.px(x, y)));
            for (int y = hz - 5; y <= hz - 1; ++y) minA = std::min(minA, luma(img.px(x, y)));
            for (int y = hz - 60; y <= hz - 53; ++y) maxB = std::max(maxB, luma(img.px(x, y)));
            for (int y = hz - 60; y <= hz - 56; ++y) minB = std::min(minB, luma(img.px(x, y)));
            bright.push_back(maxA - maxB);
            dark.push_back(minB - minA);
        }
        if (!bright.empty()) {
            m.rimBright = median(bright);
            m.rimDark = median(dark);
        }
    }
    // Star colour: unsaturated, well-exposed stars that are clearly red or clearly blue.
    float redSum = 0, blueSum = 0;
    int ok = 0;
    for (const lab::TruthStar& st : s.stars) {
        if (st.peak < 0.02f || st.peak > 0.45f) continue;
        const bool red = st.bv > 1.3f, blue = st.bv < 0.2f;
        if (!red && !blue) continue;
        const int cx = (int)std::lround(st.x), cy = (int)std::lround(st.y);
        if (cx < 1 || cy < 1 || cx >= W - 1 || cy >= H - 1) continue;
        float a[3] = {0, 0, 0};
        for (int y = cy - 1; y <= cy + 1; ++y)
            for (int x = cx - 1; x <= cx + 1; ++x)
                for (int c = 0; c < 3; ++c) a[c] += img.px(x, y)[c] / 9.0f;
        const float mx = std::max(a[0], std::max(a[1], a[2]));
        const float mn = std::min(a[0], std::min(a[1], a[2]));
        const float sat = mx > 1e-4f ? (mx - mn) / mx : 0.0f;
        if (red) { redSum += sat; ++m.nRed; if (a[0] > a[2]) ++ok; }
        else { blueSum += sat; ++m.nBlue; if (a[2] > a[0]) ++ok; }
    }
    if (m.nRed) m.redSat = redSum / (float)m.nRed;
    if (m.nBlue) m.blueSat = blueSum / (float)m.nBlue;
    if (m.nRed + m.nBlue) m.hueOk = (float)ok / (float)(m.nRed + m.nBlue);
    // Pixel-scale noise in clean sky: residual from a 5x5 mean.
    std::vector<float> rl, rc;
    for (int y = 3; y < H - 3; y += 3) {
        for (int x = 3; x < W - 3; x += 3) {
            if (!cleanSky(x, y)) continue;
            float ml = 0, mr = 0, mb = 0;
            for (int yy = y - 2; yy <= y + 2; ++yy) {
                for (int xx = x - 2; xx <= x + 2; ++xx) {
                    const float* q = img.px(xx, yy);
                    ml += luma(q);
                    mr += q[0] - q[1];
                    mb += q[2] - q[1];
                }
            }
            const float* q = img.px(x, y);
            rl.push_back(luma(q) - ml / 25.0f);
            rc.push_back((q[0] - q[1]) - mr / 25.0f);
            rc.push_back((q[2] - q[1]) - mb / 25.0f);
        }
    }
    m.lumaNoise = mad_sigma(rl);
    m.chromaNoise = mad_sigma(rc);
    return m;
}

// The live preview as it was until 0.4.0 (engine.cpp getPreview), for comparison: per-channel
// asinh(200x) from the 10th percentile of the luma, nearest-neighbour.
bool old_preview(const ImageF& src, uint32_t* out, int outW, int outH) {
    const int sw = src.w, sh = src.h;
    std::vector<float> samples;
    const size_t total = (size_t)sw * sh, step = std::max<size_t>(1, total / 20000);
    for (size_t i = 0; i < total; i += step) {
        const float* p = &src.d[i * 3];
        samples.push_back((p[0] + p[1] + p[2]) / 3.0f);
    }
    const float black = starcam::percentile_of(samples, 0.10f);
    const float inv = 1.0f / std::asinh(200.0f);
    for (int y = 0; y < outH; ++y) {
        for (int x = 0; x < outW; ++x) {
            const float* p = src.px(std::min(sw - 1, (int)((int64_t)x * sw / outW)),
                                    std::min(sh - 1, (int)((int64_t)y * sh / outH)));
            uint32_t px = 0xFF000000u;
            for (int c = 0; c < 3; ++c) {
                const float v = starcam::clampf(std::asinh(200.0f * std::max(0.0f, p[c] - black)) * inv, 0.0f, 1.0f);
                px |= (uint32_t)(uint8_t)(v * 255.0f + 0.5f) << (16 - 8 * c);
            }
            out[(size_t)y * outW + x] = px;
        }
    }
    return true;
}

starcam::PostParams params_for(const std::string& variant, const std::vector<std::string>& kv) {
    starcam::PostParams p;  // the engine's defaults are the classic look
    if (variant == "v2") p.version = 2;
    for (const std::string& a : kv) {
        const size_t eq = a.find('=');
        if (eq == std::string::npos) continue;
        const std::string k = a.substr(0, eq);
        const float v = std::strtof(a.c_str() + eq + 1, nullptr);
        if (k == "stretch") p.stretchStrength = (int)v;
        else if (k == "sat") p.saturation = v;
        else if (k == "grad") p.gradientRemoval = v != 0;
        else if (k == "chroma") p.chromaDenoise = v != 0;
        else if (k == "sharpen") p.starSharpen = v != 0;
        else if (k == "style") p.style = (int)v;
        else if (k == "version") p.version = (int)v;
        else if (k == "sky") p.skyLevel = v;
        else if (k == "ground") p.groundLevel = v;
        else if (k == "gap") p.contrastGap = v;
        else if (k == "gs") p.gradientStrength = v;
        else if (k == "nr") p.noiseReduction = v;
        else if (k == "starc") p.starColor = v;
        else if (k == "down") p.downEdge = (int)v;
        else if (k == "detail") p.detail = v;
        else if (k == "smaller") p.starReduction = v;
        else if (k == "vig") p.vignette = v;
        else if (k == "warm") p.warmth = v;
        else if (k == "tint") p.tint = v;
    }
    return p;
}

void outputs(const ImageF& img, const std::string& dir, const std::string& name) {
    const int W = img.w, H = img.h;
    write_bmp(dir + "/" + name + "_small.bmp", img, 0, 0, W, H, 4);
    auto crop = [&](const char* tag, float fx, float fy) {
        const int cw = std::min(800, W), ch = std::min(600, H);
        const int x0 = starcam::clampi((int)(fx * W) - cw / 2, 0, W - cw);
        const int y0 = starcam::clampi((int)(fy * H) - ch / 2, 0, H - ch);
        write_bmp(dir + "/" + name + "_crop_" + tag + ".bmp", img, x0, y0, cw, ch, 1);
    };
    crop("horizon", 0.30f, 0.68f);
    crop("band", 0.62f, 0.25f);
    crop("sky", 0.12f, 0.15f);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: postlab scene <outdir> [...] | postlab tiff <in> <outdir> [...]\n");
        return 2;
    }
    const std::string cmd = argv[1];
    lab::SceneOptions so;
    std::string outDir, tiffIn, variants = "classic,v2";
    std::vector<std::string> kv;
    bool rot90 = false;  // turn the scene a quarter clockwise: the ground ends up on the left
    int i = 2;
    if (cmd == "tiff") tiffIn = argv[i++];
    if (i >= argc) return 2;
    outDir = argv[i++];
    for (; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--nofg") so.foreground = false;
        else if (a == "--nomw") so.milkyWay = false;
        else if (a == "--nograd") so.gradient = false;
        else if (a == "--mwflat") so.milkyWayFlat = true;
        else if (a == "--dome") so.dome = true;
        else if (a == "--noise" && i + 1 < argc) so.noise = std::strtof(argv[++i], nullptr);
        else if (a == "--seed" && i + 1 < argc) so.seed = (uint32_t)std::atoi(argv[++i]);
        else if (a == "--rot90") rot90 = true;
        else if (a.find('=') != std::string::npos) kv.push_back(a);
        else variants = a;
    }

    ImageF base;
    lab::Scene scene;
    if (cmd == "scene") {
        scene = lab::render_scene(so);
        base.w = scene.w;
        base.h = scene.h;
        base.ch = 3;
        base.d = scene.rgb;
        std::printf("scene %dx%d stars=%zu noise=%.5f\n", scene.w, scene.h, scene.stars.size(),
                    scene.noiseSigma);
        if (rot90) {
            ImageF r;
            r.init(base.h, base.w, 3);
            for (int y = 0; y < base.h; ++y)
                for (int x = 0; x < base.w; ++x)
                    for (int c = 0; c < 3; ++c) r.px(base.h - 1 - y, x)[c] = base.px(x, y)[c];
            base = std::move(r);
        }
    } else if (!read_tiff16(tiffIn, &base)) {
        std::fprintf(stderr, "cannot read %s\n", tiffIn.c_str());
        return 1;
    }

    size_t start = 0;
    while (start <= variants.size()) {
        size_t comma = variants.find(',', start);
        if (comma == std::string::npos) comma = variants.size();
        const std::string v = variants.substr(start, comma - start);
        start = comma + 1;
        if (v.empty()) continue;
        if (v == "preview" || v == "preview_old") {
            // The capture screen's live-stack preview of the same data, at the 1/4 size.
            const int pw = base.w / 4, ph = base.h / 4;
            std::vector<uint32_t> argb((size_t)pw * ph);
            const auto t0 = std::chrono::steady_clock::now();
            const bool ok = v == "preview" ? starcam::render_preview(base, nullptr, 0, argb.data(), pw, ph)
                                           : old_preview(base, argb.data(), pw, ph);
            const long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now() - t0).count();
            ImageF shown;
            shown.init(pw, ph, 3);
            for (size_t i = 0; i < argb.size(); ++i)
                for (int c = 0; c < 3; ++c) shown.d[i * 3 + c] = (float)((argb[i] >> (16 - 8 * c)) & 0xFF) / 255.0f;
            write_bmp(outDir + "/" + v + ".bmp", shown, 0, 0, pw, ph, 1);
            std::printf("%-11s %4ld ms  %s\n", v.c_str(), ms, ok ? "" : "FAILED");
            continue;
        }
        ImageF img = base;
        const auto t0 = std::chrono::steady_clock::now();
        starcam::postprocess(img, params_for(v, kv), nullptr);
        const long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0).count();
        outputs(img, outDir, v);
        if (cmd == "scene") {
            if (rot90) {  // measured the way it was rendered: turned back
                ImageF back;
                back.init(scene.w, scene.h, 3);
                for (int y = 0; y < scene.h; ++y)
                    for (int x = 0; x < scene.w; ++x)
                        for (int c = 0; c < 3; ++c) back.px(x, y)[c] = img.px(scene.h - 1 - y, x)[c];
                img = std::move(back);
            }
            const Metrics m = measure(img, scene);
            std::printf("%-8s %5ld ms  sky %.3f flat %.4f  mw %+.4f (foot %+.4f dark %+.4f)  ground %.3f  "
                        "horizon %+.4f  rim %+.3f/%+.3f  starSat red %.3f blue %.3f hue %.2f (n=%d/%d)  "
                        "noise luma %.4f chroma %.4f\n",
                        v.c_str(), ms, m.skyMed, m.skyFlat, m.mwContrast, m.mwLow, m.mwDark, m.groundMed,
                        m.horizonDelta, m.rimBright, m.rimDark, m.redSat, m.blueSat, m.hueOk, m.nRed,
                        m.nBlue, m.lumaNoise, m.chromaNoise);
        } else {
            std::printf("%-8s %5ld ms\n", v.c_str(), ms);
        }
    }
    return 0;
}

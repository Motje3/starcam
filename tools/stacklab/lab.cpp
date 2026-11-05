// stacklab — runs the engine's alignment and stacking (app/src/main/cpp) outside the app, over the
// cached frames a real session kept (cache/frame_*.sraw), and measures how well the frames line up
// across the whole frame. Built with the NDK for the x86_64 emulator by run.ps1; see DEBUGGING.md.
//
//   stacklab align <cacheDir> <outDir>
//       each frame against the first, the way the capture aligns them (incremental, the previous
//       frame's transform as the guess): matched stars, inliers, residual, and per star where the
//       transform puts it against where it is (align_<n>.csv), to see where in the frame it fits.
//   stacklab stack <cacheDir> <outDir> <options.json> <rotation> [<phone master tiff>]
//       the whole re-stack through EngineSession, as the app's re-stack runs it, then the same
//       stars measured in the stack and in the reference frame stacked onto itself (the same
//       processing, no alignment): how much of their light stays in their peak, by region, says
//       what the alignment did to them. Given the master TIFF the phone made of the same frames,
//       that is measured too. <rotation> is the turn in the options, which every TIFF is written
//       with. Writes sheet.bmp (and sheet_phone.bmp): the brightest star of each region, frame
//       left, stack right.

#include <dirent.h>
#include <sys/stat.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include "align.h"
#include "bayer.h"
#include "common.h"
#include "engine.h"
#include "sraw.h"
#include "stars.h"

using namespace starcam;

namespace {

std::vector<std::string> list_frames(const std::string& dir) {
    std::vector<std::string> out;
    DIR* d = opendir(dir.c_str());
    if (!d) return out;
    while (dirent* e = readdir(d)) {
        const std::string n = e->d_name;
        if (n.rfind("frame_", 0) == 0 && n.size() > 5 && n.substr(n.size() - 5) == ".sraw")
            out.push_back(dir + "/" + n);
    }
    closedir(d);
    std::sort(out.begin(), out.end());
    return out;
}

SensorInfo sensor_of(const SrawHeader& h) {
    SensorInfo si;
    si.width = (int)h.width;
    si.height = (int)h.height;
    si.cfa = h.cfa;
    si.whiteLevel = h.whiteLevel;
    for (int i = 0; i < 4; ++i) si.blackLevel[i] = h.blackLevel[i];
    return si;
}

struct Frame {
    std::string path;
    SrawHeader hdr;
    std::vector<Star> stars;
    std::vector<float> ground;  // per star: the median of a ring 6-10 px around it (luma)
    SkyStats sky;
};

bool load_stars(const std::string& path, Frame* f) {
    SrawReader r;
    if (!r.open(path)) return false;
    f->path = path;
    f->hdr = r.header();
    ImageF luma;
    const SensorInfo si = sensor_of(f->hdr);
    superpixel_luma(r.data(), si.width, si.height, si.width, si, f->hdr.wbGain, &luma);
    detect_stars(luma, DetectParams(), &f->stars, &f->sky);
    f->ground.clear();
    std::vector<float> ring;
    for (const Star& s : f->stars) {
        ring.clear();
        const int x0 = (int)std::lround(s.x), y0 = (int)std::lround(s.y);
        for (int y = y0 - 10; y <= y0 + 10; ++y) {
            for (int x = x0 - 10; x <= x0 + 10; ++x) {
                if (x < 0 || y < 0 || x >= luma.w || y >= luma.h) continue;
                const double d = std::hypot(x - s.x, y - s.y);
                if (d >= 6.0 && d <= 10.0) ring.push_back(luma.d[(size_t)y * luma.w + x]);
            }
        }
        f->ground.push_back(median_of(ring));
    }
    return true;
}

// The nearest star to (x, y) within r, or -1.
int nearest(const std::vector<Star>& s, double x, double y, double r) {
    int best = -1;
    double bd = r * r;
    for (size_t i = 0; i < s.size(); ++i) {
        const double dx = s[i].x - x, dy = s[i].y - y, d2 = dx * dx + dy * dy;
        if (d2 <= bd) {
            bd = d2;
            best = (int)i;
        }
    }
    return best;
}

int run_align(const std::string& cacheDir, const std::string& outDir) {
    const std::vector<std::string> paths = list_frames(cacheDir);
    if (paths.size() < 2) {
        fprintf(stderr, "need >= 2 frames in %s\n", cacheDir.c_str());
        return 2;
    }
    std::vector<Frame> frames(paths.size());
    for (size_t i = 0; i < paths.size(); ++i) {
        if (!load_stars(paths[i], &frames[i])) {
            fprintf(stderr, "cannot read %s\n", paths[i].c_str());
            return 2;
        }
    }
    const Frame& ref = frames[0];
    const double t0 = (double)ref.hdr.timestampNs;
    printf("frame  t(s)  stars  pairs/inl  resid  dx      dy      rot     static\n");
    Transform prev;
    AlignParams ap;
    for (size_t i = 0; i < frames.size(); ++i) {
        const Frame& f = frames[i];
        Transform T;
        int inl = (int)f.stars.size();
        float res = 0;
        bool ok = true;
        if (i > 0) {
            const AlignResult ar = align_frame(ref.stars, f.stars, prev, ap);
            // Each start on its own: from the guess, and guess-free.
            const AlignResult g = align_stars(ref.stars, f.stars, prev, ap);
            const AlignResult b = align_bootstrap(ref.stars, f.stars, ap);
            printf("      guided %d/%d (%.3f)  guess-free %d/%d (%.3f) shift %.1f %.1f\n", g.inliers,
                   g.coverage, g.residual, b.inliers, b.coverage, b.residual, b.T.shiftX(), b.T.shiftY());
            ok = ar.ok;
            if (ok) {
                T = ar.T;
                prev = ar.T;
            } else {
                T = prev;
            }
            inl = ar.inliers;
            res = ar.residual;
        }
        // Per reference star: where T puts it, the nearest star there, and whether a star sits
        // on its very pixel in this frame (a fixed light on the ground does; a star has moved).
        std::vector<int> order(ref.stars.size());
        for (size_t k = 0; k < order.size(); ++k) order[k] = (int)k;
        char name[64];
        snprintf(name, sizeof(name), "/align_%02zu.csv", i);
        FILE* csv = fopen((outDir + name).c_str(), "w");
        if (csv) fprintf(csv, "rx,ry,rflux,rfwhm,px,py,cx,cy,ex,ey,static,cflux\n");
        int fixedCount = 0;
        for (int k : order) {
            const Star& rs = ref.stars[(size_t)k];
            double px, py;
            T.apply(rs.x, rs.y, &px, &py);
            const int c = nearest(f.stars, px, py, 12.0);
            const int same = nearest(f.stars, rs.x, rs.y, 1.0);
            if (same >= 0) ++fixedCount;
            if (csv) {
                if (c >= 0) {
                    const Star& cs = f.stars[(size_t)c];
                    fprintf(csv, "%.2f,%.2f,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.3f,%.3f,%d,%.1f\n", rs.x,
                            rs.y, rs.flux, rs.fwhm, px, py, cs.x, cs.y, cs.x - px, cs.y - py,
                            same >= 0 ? 1 : 0, cs.flux);
                } else {
                    fprintf(csv, "%.2f,%.2f,%.1f,%.2f,%.2f,%.2f,,,,,%d,\n", rs.x, rs.y, rs.flux,
                            rs.fwhm, px, py, same >= 0 ? 1 : 0);
                }
            }
        }
        if (csv) fclose(csv);
        printf("%5zu %5.1f %6zu %4d %s %6.3f %7.2f %7.2f %7.3f %4d\n", i,
               ((double)f.hdr.timestampNs - t0) / 1e9, f.stars.size(), inl, ok ? "ok " : "BAD", res,
               T.shiftX(), T.shiftY(), T.rotationDeg(), fixedCount);
    }
    // Every frame's stars, for plotting.
    for (size_t i = 0; i < frames.size(); ++i) {
        char name[64];
        snprintf(name, sizeof(name), "/stars_%02zu.csv", i);
        FILE* csv = fopen((outDir + name).c_str(), "w");
        if (!csv) continue;
        fprintf(csv, "x,y,flux,fwhm,area,ring\n");
        for (size_t k = 0; k < frames[i].stars.size(); ++k) {
            const Star& s = frames[i].stars[k];
            fprintf(csv, "%.2f,%.2f,%.5f,%.2f,%d,%.5f\n", s.x, s.y, s.flux, s.fwhm, s.area,
                    frames[i].ground[k]);
        }
        fclose(csv);
    }
    return 0;
}

// ---------------------------------------------------------------------------------------------
// stack

std::string read_file(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return "";
    std::string s;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) s.append(buf, n);
    fclose(f);
    return s;
}

// Reads the engine's own uncompressed 16-bit RGB TIFF (tiffout.cpp) back into linear floats.
bool read_tiff16(const std::string& path, ImageF* out) {
    std::string s = read_file(path);
    const std::vector<uint8_t> b(s.begin(), s.end());
    if (b.size() < 8 || b[0] != 'I') return false;
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

// Half-res luma of a stacked RGB image, as measure_stack_fwhm makes it.
void half_luma(const ImageF& rgb, ImageF* luma) {
    luma->init(rgb.w / 2, rgb.h / 2, 1);
    for (int y = 0; y < luma->h; ++y) {
        for (int x = 0; x < luma->w; ++x) {
            float s = 0;
            for (int dy = 0; dy < 2; ++dy)
                for (int dx = 0; dx < 2; ++dx) {
                    const float* p = rgb.px(2 * x + dx, 2 * y + dy);
                    s += p[0] + p[1] + p[2];
                }
            luma->d[(size_t)y * luma->w + x] = s / 12.0f;
        }
    }
}

// Runs the engine over `paths` (as the app's re-stack feeds them) into workDir; the master TIFF.
bool engine_stack(const std::vector<std::string>& paths, const std::string& workDir,
                  const std::string& options, ImageF* tiff, std::string* result) {
    mkdir(workDir.c_str(), 0755);
    SrawReader first;
    if (!first.open(paths[0])) return false;
    EngineConfig cfg;
    cfg.sessionDir = workDir;
    cfg.mode = SessionMode::ASTRO;
    cfg.sensor = sensor_of(first.header());
    cfg.keepCache = true;
    cfg.livePreview = false;
    std::unique_ptr<EngineSession> e(EngineSession::create(cfg));
    if (!e) return false;
    for (size_t i = 0; i < paths.size(); ++i) {
        SrawReader r;
        if (!r.open(paths[i])) continue;
        const SrawHeader& h = r.header();
        FrameMeta m;
        m.index = (int)i;
        m.exposureNs = (int64_t)h.exposureNs;
        m.iso = (int32_t)h.iso;
        m.timestampNs = (int64_t)h.timestampNs;
        for (int k = 0; k < 4; ++k) m.wbGain[k] = h.wbGain[k];
        for (int k = 0; k < 9; ++k) m.ccm[k] = h.ccm[k];
        m.hasBlackLevel = true;
        for (int k = 0; k < 4; ++k) m.blackLevel[k] = h.blackLevel[k];
        m.sourcePath = paths[i];
        printf("  add %zu: %s\n", i, e->addFrame(r.data(), (int)h.width, (int)h.height, (int)h.width, m).c_str());
    }
    *result = e->finalizeStack(options, [](const char*, int) {});
    return read_tiff16(workDir + "/out/master_linear16.tiff", tiff);
}

struct Region {
    std::vector<float> single, stack, phone;
};

// A star at full-res (cx, cy): its peak (the brightest pixel within 2 px of the centre) and its
// light (everything within 6 px), both above the median of a ring 8-12 px out; snr is the light
// against the ring's noise over the aperture.
struct Conc {
    float peak = 0, flux = 0, snr = 0;
    bool ok = false;
};

Conc concentration(const ImageF& rgb, double cx, double cy) {
    Conc c;
    const int R = 12;
    const int x0 = (int)std::lround(cx), y0 = (int)std::lround(cy);
    if (x0 - R < 0 || y0 - R < 0 || x0 + R >= rgb.w || y0 + R >= rgb.h) return c;
    auto lum = [&rgb](int x, int y) {
        const float* p = rgb.px(x, y);
        return p[0] + p[1] + p[2];
    };
    std::vector<float> ring;
    for (int y = y0 - R; y <= y0 + R; ++y) {
        for (int x = x0 - R; x <= x0 + R; ++x) {
            const double d = std::hypot(x - cx, y - cy);
            if (d >= 8.0 && d <= 12.0) ring.push_back(lum(x, y));
        }
    }
    std::vector<float> tmp = ring;
    const float bg = median_of(tmp);
    for (float& v : tmp) v = std::fabs(v - bg);
    const float noise = 1.4826f * median_of(tmp);
    float peak = -1e9f, flux = 0.0f;
    int n = 0;
    for (int y = y0 - 6; y <= y0 + 6; ++y) {
        for (int x = x0 - 6; x <= x0 + 6; ++x) {
            const double d = std::hypot(x - cx, y - cy);
            if (d > 6.0) continue;
            const float v = lum(x, y) - bg;
            flux += v;
            ++n;
            if (d <= 2.0) peak = std::max(peak, v);
        }
    }
    c.peak = peak;
    c.flux = flux;
    c.snr = noise > 0.0f ? flux / (noise * std::sqrt((float)n)) : 0.0f;
    c.ok = flux > 0.0f && peak > 0.0f;
    return c;
}

// Contact sheet: per 3 x 3 region, the brightest star matched in both, crops side by side
// (single left, stack right), 3x enlarged, the same asinh stretch for both.
void write_sheet(const std::string& path, const ImageF& a, const ImageF& b,
                 const std::vector<std::pair<int, int>>& centres) {
    const int C = 40, Z = 3, cell = C * Z, gap = 6;
    const int W = 3 * (2 * cell + gap) + 2 * gap * 2, H = 3 * (cell + gap);
    std::vector<uint8_t> img((size_t)W * H * 3, 30);
    auto stretch = [](float v) {
        const float t = std::asinh(std::max(0.0f, v) * 60.0f) / std::asinh(60.0f);
        return (uint8_t)std::lround(clampf(t, 0.0f, 1.0f) * 255.0f);
    };
    for (size_t r = 0; r < centres.size() && r < 9; ++r) {
        const int gx = (int)r % 3, gy = (int)r / 3;
        const int ox = gx * (2 * cell + gap + gap * 2), oy = gy * (cell + gap);
        for (int s = 0; s < 2; ++s) {
            const ImageF& src = s == 0 ? a : b;
            const int cx = centres[r].first, cy = centres[r].second;
            if (cx < 0) continue;
            for (int y = 0; y < cell; ++y) {
                for (int x = 0; x < cell; ++x) {
                    const int sx = clampi(cx - C / 2 + x / Z, 0, src.w - 1);
                    const int sy = clampi(cy - C / 2 + y / Z, 0, src.h - 1);
                    const float* p = src.px(sx, sy);
                    uint8_t* q = &img[(((size_t)(oy + y)) * W + ox + s * (cell + gap) + x) * 3];
                    for (int c = 0; c < 3; ++c) q[2 - c] = stretch(p[c]);
                }
            }
        }
    }
    const int rowBytes = (W * 3 + 3) & ~3;
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return;
    uint8_t hdr[54] = {'B', 'M'};
    auto put32 = [&hdr](int off, uint32_t v) {
        for (int i = 0; i < 4; ++i) hdr[off + i] = (uint8_t)(v >> (8 * i));
    };
    put32(2, 54 + (uint32_t)rowBytes * H);
    put32(10, 54);
    put32(14, 40);
    put32(18, (uint32_t)W);
    put32(22, (uint32_t)H);
    hdr[26] = 1;
    hdr[28] = 24;
    put32(34, (uint32_t)rowBytes * H);
    fwrite(hdr, 1, 54, f);
    std::vector<uint8_t> row((size_t)rowBytes, 0);
    for (int y = H - 1; y >= 0; --y) {
        memcpy(row.data(), &img[(size_t)y * W * 3], (size_t)W * 3);
        fwrite(row.data(), 1, row.size(), f);
    }
    fclose(f);
}

// A master TIFF written turned (rotation clockwise, as the app writes them) back in the sensor's
// orientation (w x h), so it lines up with frames and with a stack made here.
bool unrotate(const ImageF& turned, int rotation, int w, int h, ImageF* out) {
    int ow, oh;
    rotated_size(rotation, w, h, &ow, &oh);
    if (turned.w != ow || turned.h != oh) return false;
    out->init(w, h, 3);
    for (int oy = 0; oy < oh; ++oy) {
        for (int ox = 0; ox < ow; ++ox) {
            int sx, sy;
            rotated_source(rotation, w, h, ox, oy, &sx, &sy);
            const float* p = turned.px(ox, oy);
            float* q = out->px(sx, sy);
            q[0] = p[0];
            q[1] = p[1];
            q[2] = p[2];
        }
    }
    return true;
}

// `rotation` is the turn in the options (the session's own, so the post-processing knows where
// the ground is): every master TIFF is written turned, and measured turned back.
int run_stack(const std::string& cacheDir, const std::string& outDir, const std::string& optPath,
              int rotation, const std::string& phoneTiff) {
    const std::vector<std::string> paths = list_frames(cacheDir);
    if (paths.size() < 2) {
        fprintf(stderr, "need >= 2 frames in %s\n", cacheDir.c_str());
        return 2;
    }
    const std::string options = read_file(optPath);
    printf("options: %s\n", options.c_str());
    int sensorW = 0, sensorH = 0;
    {
        SrawReader r;
        if (!r.open(paths[0])) return 2;
        sensorW = (int)r.header().width;
        sensorH = (int)r.header().height;
    }
    auto upright = [&](const ImageF& turned, ImageF* out) {
        return unrotate(turned, rotation, sensorW, sensorH, out);
    };

    ImageF stack, turned;
    std::string result;
    printf("stack of %zu frames:\n", paths.size());
    if (!engine_stack(paths, outDir + "/stack", options, &turned, &result) || !upright(turned, &stack)) {
        fprintf(stderr, "stack failed: %s\n", result.c_str());
        return 3;
    }
    printf("result: %s\n", result.c_str());
    printf("report: %s\n", read_file(outDir + "/stack/out/stack_report.json").c_str());

    // The reference the stack ended up in: the frame whose transform is the identity.
    int refIdx = 0;
    {
        const std::string rep = read_file(outDir + "/stack/out/stack_report.json");
        // frames are listed in order; the reference has dx 0, dy 0 and rotDeg 0 exactly.
        size_t pos = 0;
        while ((pos = rep.find("\"index\":", pos)) != std::string::npos) {
            const size_t end = rep.find('}', pos);
            const std::string obj = rep.substr(pos, end - pos);
            if (obj.find("\"dx\":0,") != std::string::npos && obj.find("\"dy\":0,") != std::string::npos &&
                obj.find("\"rotDeg\":0") != std::string::npos) {
                refIdx = std::atoi(rep.c_str() + pos + 8);
                break;
            }
            pos = end;
        }
    }
    printf("stack reference: frame %d\n", refIdx);

    // The same processing without alignment: the reference frame stacked onto itself.
    ImageF single;
    std::string r2;
    printf("baseline (frame %d twice):\n", refIdx);
    const std::vector<std::string> twice = {paths[(size_t)refIdx], paths[(size_t)refIdx]};
    if (!engine_stack(twice, outDir + "/single", options, &turned, &r2) || !upright(turned, &single)) {
        fprintf(stderr, "baseline failed: %s\n", r2.c_str());
        return 3;
    }
    // The stack the phone made of the same frames, when given: measured the same way.
    ImageF phone;
    if (!phoneTiff.empty() && (!read_tiff16(phoneTiff, &turned) || !upright(turned, &phone))) {
        fprintf(stderr, "cannot use %s\n", phoneTiff.c_str());
        phone = ImageF();
    }

    ImageF ls;
    half_luma(single, &ls);
    DetectParams dp;
    dp.maxStars = 3000;
    std::vector<Star> ss;
    SkyStats k1;
    detect_stars(ls, dp, &ss, &k1);

    // Every clear star of the frame, measured at the same spot in both pictures (the stack is in
    // the reference's geometry): how much of its light sits in its peak. Misalignment smears a
    // star's peak away, not its light, so the stack/frame ratio is 1 for a perfect stack (a
    // little under: the warp interpolates). A width from the pixels above the noise would not do:
    // the stack's lower noise lets in more of every star's wings, and it reads ~20 % wider.
    Region reg[9];
    std::vector<std::pair<int, int>> centres(9, {-1, -1});
    std::vector<float> bestFlux(9, -1.0f);
    int used = 0;
    const bool withPhone = !phone.empty();
    for (const Star& s : ss) {
        if (s.area >= 60) continue;  // saturated, or a light on the ground
        const double cx = 2.0 * s.x + 0.5, cy = 2.0 * s.y + 0.5;
        const Conc a = concentration(single, cx, cy), b = concentration(stack, cx, cy);
        if (!a.ok || !b.ok || a.snr < 20.0f) continue;
        Conc c;
        if (withPhone) {
            c = concentration(phone, cx, cy);
            if (!c.ok) continue;
        }
        const int gx = clampi((int)(3 * s.x / ls.w), 0, 2), gy = clampi((int)(3 * s.y / ls.h), 0, 2);
        const int r = gy * 3 + gx;
        ++used;
        reg[r].single.push_back(a.peak / a.flux);
        reg[r].stack.push_back(b.peak / b.flux);
        if (withPhone) reg[r].phone.push_back(c.peak / c.flux);
        if (s.flux > bestFlux[(size_t)r]) {
            bestFlux[(size_t)r] = s.flux;
            centres[(size_t)r] = {(int)std::lround(cx), (int)std::lround(cy)};
        }
    }
    printf("%d clear stars of %zu detected\n", used, ss.size());
    printf("peak share of a star's light, median: the frame, this stack%s; stack/frame\n",
           withPhone ? ", the phone's stack" : "");
    const char* names[9] = {"top-left", "top", "top-right", "left", "centre", "right",
                            "bottom-left", "bottom", "bottom-right"};
    std::vector<float> all, allPhone;
    for (int r = 0; r < 9; ++r) {
        if (reg[r].single.empty()) {
            printf("%-12s %3d\n", names[r], 0);
            continue;
        }
        std::vector<float> ratio, ratioPhone;
        for (size_t k = 0; k < reg[r].single.size(); ++k) {
            ratio.push_back(reg[r].stack[k] / reg[r].single[k]);
            if (withPhone) ratioPhone.push_back(reg[r].phone[k] / reg[r].single[k]);
        }
        all.insert(all.end(), ratio.begin(), ratio.end());
        allPhone.insert(allPhone.end(), ratioPhone.begin(), ratioPhone.end());
        std::vector<float> a = reg[r].single, b = reg[r].stack;
        printf("%-12s %3zu  frame %5.3f  stack %5.3f  %5.2f", names[r], a.size(), median_of(a),
               median_of(b), median_of(ratio));
        if (withPhone) {
            std::vector<float> c = reg[r].phone;
            printf("   phone %5.3f  %5.2f", median_of(c), median_of(ratioPhone));
        }
        printf("\n");
    }
    printf("%-12s %3zu                            %5.2f", "all", all.size(), median_of(all));
    if (withPhone) printf("                 %5.2f", median_of(allPhone));
    printf("\n");
    write_sheet(outDir + "/sheet.bmp", single, stack, centres);
    if (withPhone) write_sheet(outDir + "/sheet_phone.bmp", single, phone, centres);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 4 && std::string(argv[1]) == "align") return run_align(argv[2], argv[3]);
    if (argc >= 6 && std::string(argv[1]) == "stack") {
        return run_stack(argv[2], argv[3], argv[4], std::atoi(argv[5]), argc >= 7 ? argv[6] : "");
    }
    fprintf(stderr,
            "usage: stacklab align <cacheDir> <outDir>\n"
            "       stacklab stack <cacheDir> <outDir> <options.json> <rotation> [<phone master tiff>]\n");
    return 1;
}

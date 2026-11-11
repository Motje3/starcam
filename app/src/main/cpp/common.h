// StarCam native engine — shared types & utilities.
// This header is the single source of truth for cross-module types. Do not redefine these elsewhere.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>
#include <algorithm>
#include <functional>
#include <thread>
#include <atomic>

#ifdef __ANDROID__
#include <android/log.h>
#define SLOG(...) __android_log_print(ANDROID_LOG_INFO, "StarCamEngine", __VA_ARGS__)
#define SLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "StarCamEngine", __VA_ARGS__)
#else
#define SLOG(...) do { printf(__VA_ARGS__); printf("\n"); } while (0)
#define SLOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

namespace starcam {

// ---------- Bayer / sensor ----------

// CFA layout of the 2x2 tile at even row, even column.
enum class CFA : uint32_t { RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3 };

// Per-position values (black level, WB gain) are indexed by raster position within the 2x2 tile:
// idx = (row % 2) * 2 + (col % 2). Kotlin side converts Camera2's RggbChannelVector / black level
// pattern into this raster order before passing them down.
struct SensorInfo {
    int width = 0;          // full RAW width in pixels
    int height = 0;
    CFA cfa = CFA::RGGB;
    uint32_t whiteLevel = 1023;
    float blackLevel[4] = {64, 64, 64, 64};  // raster order within 2x2 tile
};

// Which color sits at 2x2 raster position idx for a given CFA. 0=R, 1=G, 2=B.
inline int cfaColorAt(CFA cfa, int idx) {
    static const int tbl[4][4] = {
        {0, 1, 1, 2},  // RGGB
        {1, 0, 2, 1},  // GRBG
        {1, 2, 0, 1},  // GBRG
        {2, 1, 1, 0},  // BGGR
    };
    return tbl[static_cast<int>(cfa)][idx & 3];
}

// ---------- Lens shading ----------

// Vignetting correction map, from Camera2's STATISTICS_LENS_SHADING_CORRECTION_MAP, reduced to
// RGB (the two green channels averaged) on the Kotlin side. Low resolution (typically ~17x13) and
// bilinearly interpolated: lens falloff has no high-frequency content.
//
// Gains are >= 1.0 and normalized so the brightest point of the frame is 1.0, so multiplying a
// linear image by them flattens the lens's own falloff. RAW_SENSOR frames are never shading-
// corrected by the device, which is exactly why the map is published.
//
// Empty means "no correction available". Every consumer must treat that as a silent no-op — a
// phone that does not publish a usable map must behave exactly as it did before this existed.
struct ShadingMap {
    int cols = 0, rows = 0;
    std::vector<float> gain;   // rows*cols*3, [r,g,b] per grid node, row-major
    float maxGain = 1.0f;      // diagnostic: the largest gain in the map

    bool empty() const {
        return cols < 2 || rows < 2 || gain.size() != (size_t)cols * (size_t)rows * 3;
    }
};

// ---------- Images ----------

// Planar-interleaved float image, channel-interleaved: d[(y*w + x)*ch + c].
struct ImageF {
    int w = 0, h = 0, ch = 1;
    std::vector<float> d;
    void init(int W, int H, int C) { w = W; h = H; ch = C; d.assign((size_t)W * H * C, 0.0f); }
    float* px(int x, int y) { return &d[((size_t)y * w + x) * ch]; }
    const float* px(int x, int y) const { return &d[((size_t)y * w + x) * ch]; }
    bool empty() const { return d.empty(); }
};

struct Image16 {
    int w = 0, h = 0;
    std::vector<uint16_t> d;
    void init(int W, int H) { w = W; h = H; d.assign((size_t)W * H, 0); }
    bool empty() const { return d.empty(); }
};

// ---------- Geometry ----------

// Projective transform (homography) mapping reference coords -> this frame's coords (or as
// documented per use): a 3x3 matrix m, row-major, acting on (x, y, 1), kept with m[8] = 1:
//   w  = m[6]*x + m[7]*y + m[8]
//   x' = (m[0]*x + m[1]*y + m[2]) / w
//   y' = (m[3]*x + m[4]*y + m[5]) / w
// Two frames of the sky through a fixed lens differ by a turn of the view - the sky turning, or
// the phone settling on its mount - and a turned view is exactly a homography. A similarity
// (shift and rotation, m[6] = m[7] = 0) fits one only near the middle: on the field sessions of
// 2026-10-31 it was pixels off toward the edges of the wide phone lens within two minutes.
struct Transform {
    double m[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    // x' = a*x - b*y + tx, y' = b*x + a*y + ty: rotation and scale by (a, b), then the shift.
    static Transform similarity(double a, double b, double tx, double ty) {
        Transform t;
        t.m[0] = a;
        t.m[1] = -b;
        t.m[2] = tx;
        t.m[3] = b;
        t.m[4] = a;
        t.m[5] = ty;
        return t;
    }

    void apply(double x, double y, double* ox, double* oy) const {
        const double iw = 1.0 / (m[6] * x + m[7] * y + m[8]);
        *ox = (m[0] * x + m[1] * y + m[2]) * iw;
        *oy = (m[3] * x + m[4] * y + m[5]) * iw;
    }
    Transform inverse() const {
        const double c00 = m[4] * m[8] - m[5] * m[7], c01 = m[2] * m[7] - m[1] * m[8],
                     c02 = m[1] * m[5] - m[2] * m[4];
        const double c10 = m[5] * m[6] - m[3] * m[8], c11 = m[0] * m[8] - m[2] * m[6],
                     c12 = m[2] * m[3] - m[0] * m[5];
        const double c20 = m[3] * m[7] - m[4] * m[6], c21 = m[1] * m[6] - m[0] * m[7],
                     c22 = m[0] * m[4] - m[1] * m[3];
        const double det = m[0] * c00 + m[1] * c10 + m[2] * c20;
        // A degenerate transform has no inverse; identity keeps every caller's math finite.
        if (!(std::fabs(det) > 1e-12) || !std::isfinite(det)) return Transform{};
        Transform r;
        const double q[9] = {c00, c01, c02, c10, c11, c12, c20, c21, c22};
        for (int i = 0; i < 9; ++i) r.m[i] = q[i] / det;
        return r.normalized();
    }
    // The same mapping in RAW coordinates, for a transform between luma (2x2 superpixel) coords.
    // Superpixel u covers RAW 2u..2u+1 and is centred on RAW 2u + 0.5, so with S: x = 2u + 0.5
    // the RAW transform is S T S^-1. Doubling the shift alone left rotated frames off by
    // (I - A) (0.5, 0.5): about 0.37 RAW px at 30 degrees, nothing for a pure shift.
    Transform lumaToRaw() const {
        Transform s, sInv;
        s.m[0] = 2.0;
        s.m[2] = 0.5;
        s.m[4] = 2.0;
        s.m[5] = 0.5;
        sInv.m[0] = 0.5;
        sInv.m[2] = -0.25;
        sInv.m[4] = 0.5;
        sInv.m[5] = -0.25;
        return s.compose(compose(sInv));
    }
    // Composition: result applies `first`, then `this`.
    Transform compose(const Transform& first) const {
        Transform r;
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) {
                r.m[i * 3 + j] = m[i * 3] * first.m[j] + m[i * 3 + 1] * first.m[3 + j] +
                                 m[i * 3 + 2] * first.m[6 + j];
            }
        }
        return r.normalized();
    }
    // Where the origin goes: the shift, for a similarity.
    double shiftX() const { return m[2] / m[8]; }
    double shiftY() const { return m[5] / m[8]; }
    // The turn of the linear part at the origin (the whole frame's turn, for a similarity).
    double rotationDeg() const {
        const double j00 = m[0] - m[2] * m[6], j01 = m[1] - m[2] * m[7];
        const double j10 = m[3] - m[5] * m[6], j11 = m[4] - m[5] * m[7];
        return std::atan2(j10 - j01, j00 + j11) * 180.0 / M_PI;
    }
    bool isIdentity() const {
        static const double id[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        for (int i = 0; i < 9; ++i)
            if (m[i] != id[i]) return false;
        return true;
    }

private:
    Transform normalized() const {
        Transform r = *this;
        if (std::fabs(m[8]) > 1e-12 && std::isfinite(m[8]))
            for (double& v : r.m) v /= m[8];
        return r;
    }
};

// ---------- Stars / frames ----------

struct Star {
    float x = 0, y = 0;      // in luma (half-res) coordinates unless stated otherwise
    float flux = 0;          // background-subtracted integrated flux
    float fwhm = 0;          // from second moments, px
    int area = 0;
};

struct FrameMeta {
    int index = 0;
    int64_t exposureNs = 0;
    int32_t iso = 0;
    int64_t timestampNs = 0;
    float wbGain[4] = {1, 1, 1, 1};  // raster order within 2x2 tile
    float ccm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // row-major, WB'd sensor RGB -> linear sRGB
    float gyroRms = 0;
    // Per-frame dynamic black level (SENSOR_DYNAMIC_BLACK_LEVEL): the sensor's black point
    // drifts upward as it warms over a session. Raster order; used instead of the static
    // characteristics value when present.
    bool hasBlackLevel = false;
    float blackLevel[4] = {0, 0, 0, 0};
    // Re-stack only: the frame's existing cache file, used as is instead of writing a new one.
    std::string sourcePath;
};

struct FrameRecord {
    FrameMeta meta;
    std::string srawPath;
    std::vector<Star> stars;       // luma coords
    Transform T;                   // maps REFERENCE luma coords -> THIS frame's luma coords
    bool aligned = false;
    bool rejected = false;
    std::string rejectReason;
    int inliers = 0;
    float residual = 0;            // px, luma scale
    float medianFwhm = 0;
    float bgFrac = 0;              // sky background as fraction of white level (post-black-sub)
    float noiseSigma = 0;          // robust background noise sigma from detection (normalized units)
    // Stacking weight (relative, ~[0.25, 1]): computed at finalize from noise (1/sigma^2) and
    // FWHM so hazier/softer frames contribute proportionally less instead of equally.
    float weight = 1.0f;
};

// ---------- Small math helpers ----------

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// floor / ceil of a coordinate, clamped to [lo, hi] BEFORE the int conversion: converting an
// out-of-range double is undefined (arm64 saturates, and the int math after it can overflow).
// NaN maps to lo.
inline int floor_clamp(double v, int lo, int hi) {
    if (!(v > (double)lo)) return lo;
    if (v >= (double)hi) return hi;
    return (int)std::floor(v);
}
inline int ceil_clamp(double v, int lo, int hi) {
    if (!(v > (double)lo)) return lo;
    if (v >= (double)hi) return hi;
    return (int)std::ceil(v);
}

// NOTE: these reorder `v` in place.
inline float median_of(std::vector<float>& v) {
    if (v.empty()) return 0.0f;
    size_t m = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + m, v.end());
    return v[m];
}
inline float percentile_of(std::vector<float>& v, float p) {
    if (v.empty()) return 0.0f;
    size_t k = (size_t)clampf(p * (v.size() - 1), 0.0f, (float)(v.size() - 1));
    std::nth_element(v.begin(), v.begin() + k, v.end());
    return v[k];
}

// ---------- Parallelism ----------

// Simple work-stealing-free parallel for. `fn` MUST NOT throw (exceptions would terminate).
// Uses hardware_concurrency - 2 threads (min 1). Safe to nest only if inner call is serial.
inline void parallel_for(int begin, int end, const std::function<void(int)>& fn) {
    int n = end - begin;
    if (n <= 0) return;
    unsigned hw = std::max(1u, std::thread::hardware_concurrency());
    unsigned nt = std::min<unsigned>(hw > 2 ? hw - 2 : 1, (unsigned)n);
    if (nt <= 1) {
        for (int i = begin; i < end; ++i) fn(i);
        return;
    }
    std::atomic<int> next(begin);
    std::vector<std::thread> ts;
    ts.reserve(nt);
    auto worker = [&next, end, &fn] {
        int i;
        while ((i = next.fetch_add(1)) < end) fn(i);
    };
    for (unsigned t = 0; t < nt; ++t) {
        // Under memory pressure (50 MP stacks) a thread may fail to start. Destroying the vector
        // with joinable threads in it would std::terminate the app; the threads that did start
        // take all the work from the shared counter instead.
        try {
            ts.emplace_back(worker);
        } catch (...) {
            break;
        }
    }
    if (ts.empty()) worker();
    for (auto& th : ts) th.join();
}

using ProgressFn = std::function<void(const char* stage, int percent)>;
// finalizeStack's stars hook: given out/stars.json's text, returns a flat JSON answer ("" for none).
using StarsFn = std::function<std::string(const std::string& starsJson)>;

// ---------- Output rotation ----------

// Photos are stacked in sensor orientation and turned upright only when written. rotation is the
// clockwise turn (0, 90, 180, 270) that makes a w x h image upright; these give the written size
// and, for an output pixel, the source pixel it comes from.
inline int normalize_rotation(int r) {
    r = ((r % 360) + 360) % 360;
    return (r + 45) / 90 * 90 % 360;
}
inline void rotated_size(int rotation, int w, int h, int* ow, int* oh) {
    const bool swap = rotation == 90 || rotation == 270;
    *ow = swap ? h : w;
    *oh = swap ? w : h;
}
inline void rotated_source(int rotation, int w, int h, int ox, int oy, int* sx, int* sy) {
    switch (rotation) {
        case 90: *sx = oy; *sy = h - 1 - ox; break;
        case 180: *sx = w - 1 - ox; *sy = h - 1 - oy; break;
        case 270: *sx = w - 1 - oy; *sy = ox; break;
        default: *sx = ox; *sy = oy; break;
    }
}
// The other way, for a point: where source position (sx, sy) of the w x h image lands once turned
// upright. Pixel centres at integers, so it holds for positions between pixels too.
inline void rotated_point(int rotation, int w, int h, double sx, double sy, double* ox, double* oy) {
    switch (rotation) {
        case 90: *ox = (double)(h - 1) - sy; *oy = sx; break;
        case 180: *ox = (double)(w - 1) - sx; *oy = (double)(h - 1) - sy; break;
        case 270: *ox = sy; *oy = (double)(w - 1) - sx; break;
        default: *ox = sx; *oy = sy; break;
    }
}
// The image edge that faces the ground once turned upright: 0 bottom, 1 left, 2 top, 3 right.
inline int down_edge_for_rotation(int rotation) {
    switch (rotation) {
        case 90: return 3;
        case 180: return 2;
        case 270: return 1;
        default: return 0;
    }
}

}  // namespace starcam

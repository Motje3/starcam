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

// Similarity transform mapping reference coords -> this frame's coords (or as documented per use):
//   x' = a*x - b*y + tx
//   y' = b*x + a*y + ty
struct Transform {
    double a = 1.0, b = 0.0, tx = 0.0, ty = 0.0;

    void apply(double x, double y, double* ox, double* oy) const {
        *ox = a * x - b * y + tx;
        *oy = b * x + a * y + ty;
    }
    Transform inverse() const {
        double d2 = a * a + b * b;
        // A degenerate transform has no inverse; identity keeps every caller's math finite.
        if (!(d2 > 1e-12) || !std::isfinite(d2)) return Transform{};
        Transform r;
        r.a = a / d2;
        r.b = -b / d2;
        r.tx = -(r.a * tx - r.b * ty);
        r.ty = -(r.b * tx + r.a * ty);
        return r;
    }
    // Same rotation, translation scaled by s (for moving between luma (half) and raw (full) coords).
    Transform scaledTranslation(double s) const { return Transform{a, b, tx * s, ty * s}; }
    // Composition: result applies `first`, then `this`.
    Transform compose(const Transform& first) const {
        Transform r;
        r.a = a * first.a - b * first.b;
        r.b = b * first.a + a * first.b;
        r.tx = a * first.tx - b * first.ty + tx;
        r.ty = b * first.tx + a * first.ty + ty;
        return r;
    }
    double rotationDeg() const { return std::atan2(b, a) * 180.0 / M_PI; }
    bool isIdentity() const { return a == 1.0 && b == 0.0 && tx == 0.0 && ty == 0.0; }
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

}  // namespace starcam

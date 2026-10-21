// StarCam native engine — tile-based warp-on-the-fly sigma-clip stacking. PLAN.md §6.4.
//
// Contract (stack.h): stacks all frames with rejected==false into outRGB at RAW resolution.
// frames[i].T maps reference LUMA coords -> frame i LUMA coords; scaled x2 to RAW coords via
// Transform::scaledTranslation(2.0). Tiles are processed in batches via parallel_for; `progress`
// is invoked ONLY from the calling thread between batches. abortFlag is checked between batches
// and inside the tile lambda. Returns false on abort or if no active frames.

#include "stack.h"

#include <memory>

#include "bayer.h"
#include "sraw.h"

namespace starcam {

namespace {

struct MeanSd {
    double mean = 0.0;
    double sd = 0.0;
};

MeanSd mean_sd(const std::vector<float>& v) {
    MeanSd r;
    if (v.empty()) return r;
    double s = 0.0;
    for (float f : v) s += (double)f;
    r.mean = s / (double)v.size();
    double q = 0.0;
    for (float f : v) {
        double d = (double)f - r.mean;
        q += d * d;
    }
    r.sd = std::sqrt(q / (double)v.size());
    return r;
}

// ---------------------------------------------------------------------------
// Winsorized sigma clipping (Huber-style, as used by Siril/PixInsight — the
// recommended rejection for small stacks, ~8..40 subs). Plain mean/sd clipping
// estimates its statistics FROM the outliers it is trying to reject, so a single
// bright satellite inflates sigma enough to partially hide itself at n=8..20.
// Here the estimates come from a winsorized (clamped) copy instead:
//   1. start from median and 1.4826*MAD,
//   2. iterate: clamp samples to m +/- 1.5*sigma, recompute mean and 1.134*sd
//      (1.134 is Huber's consistency correction for 1.5-sigma winsorization),
//   3. reject samples outside m +/- k*sigma of the final robust estimates.
// Scratch buffers are supplied by the caller (hot loop; no allocations here).
// ---------------------------------------------------------------------------
struct WinsorStats {
    double center = 0.0;
    double sigma = 0.0;
};

WinsorStats winsorized_stats(const std::vector<float>& v, std::vector<float>& scratch) {
    WinsorStats ws;
    const size_t n = v.size();
    scratch = v;
    // Median + MAD seed.
    std::nth_element(scratch.begin(), scratch.begin() + n / 2, scratch.end());
    const double med = scratch[n / 2];
    for (size_t i = 0; i < n; ++i) scratch[i] = std::fabs(v[i] - (float)med);
    std::nth_element(scratch.begin(), scratch.begin() + n / 2, scratch.end());
    double sigma = 1.4826 * (double)scratch[n / 2];
    double center = med;
    if (!(sigma > 0.0)) {
        // Degenerate MAD (e.g. >50% identical values): fall back to plain sd as scale seed.
        const MeanSd ms = mean_sd(v);
        sigma = ms.sd;
        if (!(sigma > 0.0)) {
            ws.center = med;
            ws.sigma = 0.0;
            return ws;
        }
    }
    for (int it = 0; it < 3; ++it) {
        const double lo = center - 1.5 * sigma;
        const double hi = center + 1.5 * sigma;
        double s = 0.0;
        for (size_t i = 0; i < n; ++i) {
            double x = (double)v[i];
            if (x < lo) x = lo;
            else if (x > hi) x = hi;
            scratch[i] = (float)x;
            s += x;
        }
        const double m = s / (double)n;
        double q = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double d = (double)scratch[i] - m;
            q += d * d;
        }
        const double sd = 1.134 * std::sqrt(q / (double)n);
        // Converged?
        const bool done = std::fabs(sd - sigma) < 0.0005 * sigma;
        center = m;
        sigma = sd;
        if (done || !(sigma > 0.0)) break;
    }
    ws.center = center;
    ws.sigma = sigma;
    return ws;
}

// ---------------------------------------------------------------------------
// Lanczos-3 resampling with anti-ringing clamping. Bilinear softens every frame
// before it is summed (measured: 2.2 px stars stacked to 2.33 px); a windowed-sinc
// kernel preserves the PSF (Siril's move to clamped Lanczos bought ~8% FWHM).
// The kernel is precomputed into a phase LUT so the hot loop does no sin() calls.
// Anti-ringing: the result is clamped to the min/max of the 4x4 core neighbourhood,
// which kills the negative-lobe halos around bright stars.
// ---------------------------------------------------------------------------
constexpr int kLanczosA = 3;          // 6-tap kernel
constexpr int kLanczosPhases = 1024;  // LUT resolution over one pixel of phase

struct LanczosLut {
    // For phase f in [0,1): weights for taps at offsets -2,-1,0,+1,+2,+3 from floor(x).
    float w[kLanczosPhases][6];
    LanczosLut() {
        for (int p = 0; p < kLanczosPhases; ++p) {
            const double f = (double)p / (double)kLanczosPhases;
            double sum = 0.0;
            for (int t = 0; t < 6; ++t) {
                const double x = (double)(t - 2) - f;  // tap position relative to sample point
                double v;
                const double ax = std::fabs(x);
                if (ax < 1e-9) {
                    v = 1.0;
                } else if (ax >= (double)kLanczosA) {
                    v = 0.0;
                } else {
                    const double pix = M_PI * x;
                    v = ((double)kLanczosA * std::sin(pix) * std::sin(pix / (double)kLanczosA)) /
                        (pix * pix);
                }
                w[p][t] = (float)v;
                sum += v;
            }
            // Normalize so flat fields stay flat.
            if (sum != 0.0) {
                for (int t = 0; t < 6; ++t) w[p][t] = (float)((double)w[p][t] / sum);
            }
        }
    }
};

const LanczosLut& lanczos_lut() {
    static const LanczosLut lut;  // thread-safe magic static
    return lut;
}

bool stack_frames_impl(const std::vector<FrameRecord>& frames, const SensorInfo& si,
                       const Image16& masterDark, const std::vector<uint32_t>& hotIdx,
                       const StackParams& p, ImageF* outRGB, const ProgressFn& progress,
                       const std::atomic<bool>* abortFlag, float* outRejectionPct) {
    if (outRejectionPct) *outRejectionPct = 0.0f;
    if (!outRGB) {
        SLOGE("stack_frames: null output image");
        return false;
    }
    if (si.width <= 0 || si.height <= 0) {
        SLOGE("stack_frames: bad sensor dims %dx%d", si.width, si.height);
        return false;
    }
    if (frames.empty()) {
        SLOGE("stack_frames: no frames");
        return false;
    }

    const int fw = si.width;
    const int fh = si.height;

    // Collect active frames and open one SrawReader per frame up front (alive whole function).
    std::vector<int> act;
    std::vector<std::unique_ptr<SrawReader>> readers;
    act.reserve(frames.size());
    readers.reserve(frames.size());
    for (int i = 0; i < (int)frames.size(); ++i) {
        if (frames[i].rejected) continue;
        auto rd = std::make_unique<SrawReader>();
        if (frames[i].srawPath.empty() || !rd->open(frames[i].srawPath)) {
            SLOGE("stack_frames: cannot open sraw for frame %d (%s)", i,
                  frames[i].srawPath.c_str());
            continue;
        }
        if ((int)rd->header().width != fw || (int)rd->header().height != fh) {
            SLOGE("stack_frames: frame %d is %ux%u, expected %dx%d — skipping", i,
                  rd->header().width, rd->header().height, fw, fh);
            continue;
        }
        act.push_back(i);
        readers.push_back(std::move(rd));
    }
    const int nAct = (int)act.size();
    if (nAct == 0) {
        SLOGE("stack_frames: no active (unrejected, readable) frames");
        return false;
    }

    // Precompute per-frame transforms at RAW scale: reference RAW coords -> frame RAW coords.
    std::vector<Transform> tf((size_t)nAct);
    for (int k = 0; k < nAct; ++k) {
        tf[k] = p.identityTransforms ? Transform{} : frames[act[k]].T.scaledTranslation(2.0);
    }

    // Per-frame stacking weights, normalized so the best frame is 1.0. The floor keeps every
    // accepted frame contributing something — rejection, not weighting, is the outlier tool.
    std::vector<float> frameW((size_t)nAct, 1.0f);
    {
        float mx = 0.0f;
        for (int k = 0; k < nAct; ++k) mx = std::max(mx, frames[act[k]].weight);
        if (mx > 0.0f) {
            for (int k = 0; k < nAct; ++k) {
                frameW[k] = clampf(frames[act[k]].weight / mx, 0.05f, 1.0f);
            }
        }
    }

    const bool useDark = !masterDark.empty() && masterDark.w == fw && masterDark.h == fh;
    if (!masterDark.empty() && !useDark) {
        SLOGE("stack_frames: master dark %dx%d mismatches sensor %dx%d — ignoring dark",
              masterDark.w, masterDark.h, fw, fh);
    }
    const bool useHot = p.hotPixelFix && !hotIdx.empty();

    // Each worker thread allocates its own per-tile sample stack of
    // nAct * ts*ts * (3 floats + 1 validity byte) = nAct * ts*ts * 13 bytes. That scales with BOTH
    // frame count and thread count, so a fixed 512 px tile costs ~126 MB per thread at 40 frames
    // (~800 MB across 6 workers) and far more for a long METEOR session. Shrink the tile until the
    // per-thread working set fits a fixed budget; correctness is unaffected, only tile overhead.
    int ts = p.tileSize > 0 ? p.tileSize : 512;
    {
        const unsigned hw = std::max(1u, std::thread::hardware_concurrency());
        const unsigned nt = hw > 2 ? hw - 2 : 1;
        const size_t kBudgetPerThread = 48u << 20;  // 48 MB
        const size_t kTotalBudget = 512u << 20;     // 512 MB across all workers
        const size_t budget = std::min(kBudgetPerThread, kTotalBudget / nt);
        while (ts > 64) {
            const size_t perTile = (size_t)nAct * (size_t)ts * (size_t)ts * 13u;
            if (perTile <= budget) break;
            ts /= 2;
        }
        if (ts != (p.tileSize > 0 ? p.tileSize : 512)) {
            SLOG("stack_frames: tile size reduced to %d for %d frames on %u threads (%.0f MB/thread)",
                 ts, nAct, nt, (double)((size_t)nAct * (size_t)ts * (size_t)ts * 13u) / 1048576.0);
        }
    }
    const int tilesX = (fw + ts - 1) / ts;
    const int tilesY = (fh + ts - 1) / ts;
    const int totalTiles = tilesX * tilesY;

    outRGB->init(fw, fh, 3);

    std::atomic<uint64_t> gValid(0);
    std::atomic<uint64_t> gClipped(0);
    std::atomic<bool> hadError(false);

    auto aborted = [abortFlag]() { return abortFlag != nullptr && abortFlag->load(); };

    // Guard against pathological transforms blowing up the per-tile source region.
    const size_t maxRegionArea = (size_t)(ts + 64) * (size_t)(ts + 64) * 4;

    auto processTile = [&](int tIdx) {
        if (aborted() || hadError.load()) return;
        const int txi = tIdx % tilesX;
        const int tyi = tIdx / tilesX;
        const int ox0 = txi * ts;
        const int oy0 = tyi * ts;
        const int tw = std::min(ts, fw - ox0);
        const int th = std::min(ts, fh - oy0);
        const size_t tilePixels = (size_t)tw * (size_t)th;

        // Per-tile sample stack: [frame][tilePixels*3] + validity per frame/pixel.
        std::vector<float> samples((size_t)nAct * tilePixels * 3);
        std::vector<uint8_t> valid((size_t)nAct * tilePixels, 0);
        std::vector<uint16_t> regionU16;
        ImageF regionRGB;

        for (int k = 0; k < nAct; ++k) {
            if (aborted()) return;
            const Transform& T = tf[k];

            // Map tile corners + center (ref RAW -> frame RAW), bbox, pad 8 px, snap origin to even.
            const double cxs[5] = {(double)ox0, (double)(ox0 + tw), (double)ox0,
                                   (double)(ox0 + tw), ox0 + 0.5 * tw};
            const double cys[5] = {(double)oy0, (double)oy0, (double)(oy0 + th),
                                   (double)(oy0 + th), oy0 + 0.5 * th};
            double minX = 1e30, minY = 1e30, maxX = -1e30, maxY = -1e30;
            for (int c = 0; c < 5; ++c) {
                double mx, my;
                T.apply(cxs[c], cys[c], &mx, &my);
                minX = std::min(minX, mx);
                maxX = std::max(maxX, mx);
                minY = std::min(minY, my);
                maxY = std::max(maxY, my);
            }
            // Pad 8 px and clamp just outside the frame while still in doubles. Converting a
            // coordinate past the int range is undefined: a degenerate transform once turned
            // into a tiny region near x = y = 2^31 that passed the area guard below and read
            // unmapped memory. A box that misses the frame entirely is skipped here.
            if (!(maxX + 8.0 >= 0.0) || !(maxY + 8.0 >= 0.0) ||
                !(minX - 8.0 <= (double)(fw - 1)) || !(minY - 8.0 <= (double)(fh - 1))) {
                continue;  // frame does not cover this tile (or the transform is not finite)
            }
            int sx0 = floor_clamp(minX - 8.0, -2, fw);
            int sy0 = floor_clamp(minY - 8.0, -2, fh);
            const int sx1 = ceil_clamp(maxX + 8.0, -2, fw + 1);  // inclusive
            const int sy1 = ceil_clamp(maxY + 8.0, -2, fh + 1);
            if (sx0 & 1) --sx0;  // snap DOWN to even: CFA phase preserved
            if (sy0 & 1) --sy0;
            const int rx0 = std::max(sx0, 0);          // stays even (0 is even)
            const int ry0 = std::max(sy0, 0);
            const int rx1 = std::min(sx1 + 1, fw);     // exclusive
            const int ry1 = std::min(sy1 + 1, fh);
            const int rw = rx1 - rx0;
            const int rh = ry1 - ry0;
            if (rw <= 0 || rh <= 0) continue;  // frame does not cover this tile
            if ((size_t)rw * (size_t)rh > maxRegionArea) {
                SLOGE("stack_frames: tile %d frame %d degenerate region %dx%d — skipping",
                      tIdx, act[k], rw, rh);
                continue;
            }

            copy_region_u16(readers[k]->data(), fw, fh, rx0, ry0, rw, rh, &regionU16);
            // Per-frame black level: the sraw header carries the frame's own (dynamic) black,
            // which drifts upward as the sensor warms across a session.
            SensorInfo siK = si;
            for (int b = 0; b < 4; ++b) siK.blackLevel[b] = readers[k]->header().blackLevel[b];
            if (useDark) {
                subtract_dark_region(regionU16, rw, rh, rx0, ry0, masterDark, siK.blackLevel,
                                     p.darkPedestal);
            }
            if (useHot) {
                fix_hot_pixels_region(regionU16, rw, rh, rx0, ry0, fw, fh, hotIdx);
            }
            demosaic_region_malvar(regionU16.data(), rw, rh, siK, readers[k]->header().wbGain,
                                   &regionRGB);
            // Vignetting lives in frame coordinates, so it has to come off before the warp.
            if (p.shading != nullptr) {
                apply_shading_region(regionRGB, rx0, ry0, fw, fh, *p.shading);
            }

            const LanczosLut& lut = lanczos_lut();
            float* smp = &samples[(size_t)k * tilePixels * 3];
            uint8_t* vld = &valid[(size_t)k * tilePixels];
            for (int oy = 0; oy < th; ++oy) {
                double sx, sy;
                T.apply((double)ox0, (double)(oy0 + oy), &sx, &sy);
                for (int ox = 0; ox < tw; ++ox, sx += T.a, sy += T.b) {
                    // Written as "inside" tests so a NaN coordinate counts as outside.
                    if (!(sx >= 0.0 && sy >= 0.0 && sx <= (double)(fw - 1) && sy <= (double)(fh - 1))) {
                        continue;  // outside frame -> invalid sample
                    }
                    const double fx = sx - rx0;
                    const double fy = sy - ry0;
                    if (!(fx >= 0.0 && fy >= 0.0 && fx <= (double)(rw - 1) && fy <= (double)(rh - 1))) {
                        continue;  // outside fetched region -> invalid sample
                    }
                    int ix = (int)fx;  // fx >= 0, truncation == floor
                    int iy = (int)fy;
                    if (ix > rw - 1) ix = rw - 1;  // paranoia vs fp edge cases
                    if (iy > rh - 1) iy = rh - 1;

                    // Lanczos-3 phase weights from the LUT (no trig in the hot loop).
                    int phx = (int)((fx - (double)ix) * kLanczosPhases);
                    int phy = (int)((fy - (double)iy) * kLanczosPhases);
                    phx = clampi(phx, 0, kLanczosPhases - 1);
                    phy = clampi(phy, 0, kLanczosPhases - 1);
                    const float* wx6 = lut.w[phx];
                    const float* wy6 = lut.w[phy];

                    float acc[3] = {0.0f, 0.0f, 0.0f};
                    for (int ty = 0; ty < 6; ++ty) {
                        const int ryy = clampi(iy + ty - 2, 0, rh - 1);
                        const float wy = wy6[ty];
                        if (wy == 0.0f) continue;
                        const float* row = regionRGB.px(0, ryy);
                        float rr = 0.0f, rg = 0.0f, rb = 0.0f;
                        for (int tx = 0; tx < 6; ++tx) {
                            const float wx = wx6[tx];
                            if (wx == 0.0f) continue;
                            const int rxx = clampi(ix + tx - 2, 0, rw - 1);
                            const float* q = row + (size_t)rxx * 3;
                            rr += wx * q[0];
                            rg += wx * q[1];
                            rb += wx * q[2];
                        }
                        acc[0] += wy * rr;
                        acc[1] += wy * rg;
                        acc[2] += wy * rb;
                    }

                    // Anti-ringing clamp: bound the result to the min/max of the 4x4 core
                    // neighbourhood. Tight enough to kill the negative-lobe halos Lanczos draws
                    // around bright stars, loose enough not to flatten legitimate reconstruction
                    // (a 2x2 bound would demote the kernel back toward bilinear).
                    float* dst = smp + ((size_t)oy * (size_t)tw + (size_t)ox) * 3;
                    float lo[3], hi[3];
                    {
                        const int cx0 = clampi(ix - 1, 0, rw - 1);
                        const int cy0 = clampi(iy - 1, 0, rh - 1);
                        const int cx1 = clampi(ix + 2, 0, rw - 1);
                        const int cy1 = clampi(iy + 2, 0, rh - 1);
                        const float* first = regionRGB.px(cx0, cy0);
                        lo[0] = hi[0] = first[0];
                        lo[1] = hi[1] = first[1];
                        lo[2] = hi[2] = first[2];
                        for (int cy = cy0; cy <= cy1; ++cy) {
                            const float* q = regionRGB.px(cx0, cy);
                            for (int cx = cx0; cx <= cx1; ++cx, q += 3) {
                                for (int c = 0; c < 3; ++c) {
                                    lo[c] = std::min(lo[c], q[c]);
                                    hi[c] = std::max(hi[c], q[c]);
                                }
                            }
                        }
                    }
                    for (int c = 0; c < 3; ++c) dst[c] = clampf(acc[c], lo[c], hi[c]);
                    vld[(size_t)oy * (size_t)tw + (size_t)ox] = 1;
                }
            }
        }

        // Combine per pixel-channel: winsorized sigma clipping (see winsorized_stats above),
        // then a frame-quality-WEIGHTED mean of the survivors. Weighted median for tiny counts.
        uint64_t localValid = 0;
        uint64_t localClipped = 0;
        std::vector<float> vals;
        std::vector<float> wts;      // frame weight of each entry in vals
        std::vector<float> scratch;  // winsorized_stats workspace
        vals.reserve((size_t)nAct);
        wts.reserve((size_t)nAct);
        scratch.reserve((size_t)nAct);
        for (size_t pix = 0; pix < tilePixels; ++pix) {
            const int ox = (int)(pix % (size_t)tw);
            const int oy = (int)(pix / (size_t)tw);
            float* outPx = outRGB->px(ox0 + ox, oy0 + oy);
            for (int c = 0; c < 3; ++c) {
                vals.clear();
                wts.clear();
                for (int k = 0; k < nAct; ++k) {
                    if (valid[(size_t)k * tilePixels + pix]) {
                        vals.push_back(samples[((size_t)k * tilePixels + pix) * 3 + c]);
                        wts.push_back(frameW[k]);
                    }
                }
                const size_t n = vals.size();
                localValid += n;
                if (n == 0) {
                    outPx[c] = 0.0f;
                    continue;
                }
                if (n < 5) {
                    // Too few samples for meaningful statistics: plain median (robust, unbiased
                    // enough at n<=4, and rejection would be guesswork anyway).
                    scratch = vals;
                    outPx[c] = median_of(scratch);
                    continue;
                }
                const WinsorStats ws = winsorized_stats(vals, scratch);
                double sum = 0.0, wsum = 0.0;
                size_t kept = 0;
                if (ws.sigma > 0.0) {
                    const double thr = (double)p.sigmaFinal * ws.sigma;
                    for (size_t q = 0; q < n; ++q) {
                        if (std::fabs((double)vals[q] - ws.center) <= thr) {
                            sum += (double)wts[q] * (double)vals[q];
                            wsum += (double)wts[q];
                            ++kept;
                        }
                    }
                }
                if (kept >= 3 && wsum > 0.0) {
                    localClipped += n - kept;
                    outPx[c] = (float)(sum / wsum);
                } else {
                    // Everything (or nearly) rejected: the statistics collapsed, trust the median.
                    scratch = vals;
                    outPx[c] = median_of(scratch);
                }
            }
        }
        gValid.fetch_add(localValid);
        gClipped.fetch_add(localClipped);
    };

    // The parallel_for lambda must not throw: catch everything inside the worker.
    const std::function<void(int)> tileFn = [&](int tIdx) {
        try {
            processTile(tIdx);
        } catch (...) {
            hadError.store(true);
            SLOGE("stack_frames: exception while processing tile %d", tIdx);
        }
    };

    const unsigned hw = std::max(1u, std::thread::hardware_concurrency());
    const int nThreads = (int)(hw > 2 ? hw - 2 : 1);
    const int batchSize = std::max(1, 4 * nThreads);

    SLOG("stack_frames: %d frames, %dx%d, %d tiles of %dx%d, %d threads, dark=%d hot=%d",
         nAct, fw, fh, totalTiles, ts, ts, nThreads, useDark ? 1 : 0, useHot ? 1 : 0);
    if (progress) progress("stack", 0);

    for (int b = 0; b < totalTiles; b += batchSize) {
        if (aborted()) {
            SLOG("stack_frames: aborted at tile %d/%d", b, totalTiles);
            return false;
        }
        const int e = std::min(b + batchSize, totalTiles);
        parallel_for(b, e, tileFn);
        if (hadError.load()) {
            SLOGE("stack_frames: tile batch [%d,%d) failed", b, e);
            return false;
        }
        if (progress) progress("stack", (int)((int64_t)e * 100 / (int64_t)totalTiles));
        if (aborted()) {
            SLOG("stack_frames: aborted after tile %d/%d", e, totalTiles);
            return false;
        }
    }

    // Optional CCM: WB'd sensor RGB -> linear sRGB using the reference frame's matrix.
    if (p.applyCCM) {
        if (p.refIndex < 0 || p.refIndex >= (int)frames.size()) {
            SLOGE("stack_frames: refIndex %d out of range (%d frames) — skipping CCM",
                  p.refIndex, (int)frames.size());
        } else {
            const float* m = frames[p.refIndex].meta.ccm;  // row-major 3x3
            ImageF* img = outRGB;
            parallel_for(0, fh, [img, fw, m](int y) {
                float* row = img->px(0, y);
                for (int x = 0; x < fw; ++x, row += 3) {
                    const float r = row[0];
                    const float g = row[1];
                    const float b = row[2];
                    const float nr = m[0] * r + m[1] * g + m[2] * b;
                    const float ng = m[3] * r + m[4] * g + m[5] * b;
                    const float nb = m[6] * r + m[7] * g + m[8] * b;
                    row[0] = nr < 0.0f ? 0.0f : nr;
                    row[1] = ng < 0.0f ? 0.0f : ng;
                    row[2] = nb < 0.0f ? 0.0f : nb;
                }
            });
        }
    }

    const uint64_t v = gValid.load();
    const uint64_t cl = gClipped.load();
    if (outRejectionPct) {
        *outRejectionPct = v > 0 ? (float)(100.0 * (double)cl / (double)v) : 0.0f;
    }
    if (progress) progress("stack", 100);
    SLOG("stack_frames: done, %.2f%% of samples clipped",
         v > 0 ? 100.0 * (double)cl / (double)v : 0.0);
    return true;
}

}  // namespace

bool stack_frames(const std::vector<FrameRecord>& frames, const SensorInfo& si,
                  const Image16& masterDark, const std::vector<uint32_t>& hotIdx,
                  const StackParams& p, ImageF* outRGB, const ProgressFn& progress,
                  const std::atomic<bool>* abortFlag, float* outRejectionPct) {
    try {
        return stack_frames_impl(frames, si, masterDark, hotIdx, p, outRGB, progress, abortFlag,
                                 outRejectionPct);
    } catch (...) {
        SLOGE("stack_frames: unhandled exception");
        return false;
    }
}

}  // namespace starcam

// Tile-based warp-on-the-fly sigma-clip stacking. See PLAN.md §6.4.
#pragma once

#include "common.h"

namespace starcam {

struct StackParams {
    float sigmaFirst = 3.5f;   // legacy (pre-winsorized two-pass); kept for config compatibility
    float sigmaFinal = 2.8f;   // rejection threshold k: reject beyond winsorized center +/- k*sigma
    int tileSize = 512;
    bool hotPixelFix = true;
    bool applyCCM = true;      // convert sensor RGB -> linear sRGB using reference frame's CCM
    int refIndex = 0;          // index into `frames` of the reference frame
    bool identityTransforms = false;  // foreground pass: ignore frames[i].T, use identity
    // Optional lens-shading (vignetting) correction, applied per frame after demosaic and before
    // warping. nullptr or an empty map = off, which must behave exactly as before it existed.
    const ShadingMap* shading = nullptr;
    // Optional per-CFA-position pedestal of the master dark (see subtract_dark_region). nullptr =
    // subtract the dark's excess over its own black level ([darkBlack]).
    const float* darkPedestal = nullptr;
    // The black level the master dark was shot at (raster order); nullptr = assume each light
    // frame's own black.
    const float* darkBlack = nullptr;
};

// Stacks all frames with rejected==false into `outRGB` (RAW resolution, 3ch, linear, [0,1]-ish).
// frames[i].T maps reference LUMA coords -> frame i LUMA coords; internally converted to RAW coords
// (Transform::lumaToRaw).
// Per tile & frame: snap source region to even coords, copy_region_u16, subtract_dark_region (if
// masterDark non-empty), fix_hot_pixels_region (if hotPixelFix), demosaic_region_malvar, bilinear
// sample at warped coords. Combine across frames per pixel-channel with 2-iteration sigma clip
// (mean/sigma; reject > sigmaFirst then > sigmaFinal; final mean of survivors; median if < 3 left).
// Tiles are parallelized via parallel_for. `progress` is invoked from worker threads through an
// atomic tile counter — implementation must funnel calls so `progress` itself is only invoked from
// the calling thread (poll pattern) OR guarantee the callback is thread-safe; we require the
// SIMPLER contract: implementation calls `progress` only from the calling thread between tile
// batches. `abortFlag` checked per tile; on abort returns false.
// outRejectionPct: percentage of samples clipped (diagnostic).
bool stack_frames(const std::vector<FrameRecord>& frames, const SensorInfo& si,
                  const Image16& masterDark, const std::vector<uint32_t>& hotIdx,
                  const StackParams& p, ImageF* outRGB, const ProgressFn& progress,
                  const std::atomic<bool>* abortFlag, float* outRejectionPct);

}  // namespace starcam

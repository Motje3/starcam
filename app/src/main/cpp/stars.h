// Star detection on half-res luma images. See PLAN.md §6.1 for the algorithm and parameters.
#pragma once

#include "common.h"

namespace starcam {

struct DetectParams {
    float sigmaK = 4.5f;    // detection threshold = bg + sigmaK * noise
    int minArea = 2;
    int maxArea = 120;
    float maxAspect = 2.5f; // reject elongated blobs (satellites, edges)
    int maxStars = 300;     // keep brightest N
    int gridCells = 48;     // background grid resolution (gridCells x gridCells)
};

struct SkyStats {
    float bgMedian = 0;     // median background level (normalized units)
    float noiseSigma = 0;   // robust noise sigma via MAD * 1.4826
};

// Detects stars in `luma` (linear, normalized). Results sorted by flux descending, capped at
// maxStars. Centroids are flux-weighted, FWHM from second moments: 2.355*sqrt((mxx+myy)/2).
// Background/noise surfaces are the bilinear interpolation of per-cell median / MAD estimates.
void detect_stars(const ImageF& luma, const DetectParams& p, std::vector<Star>* out,
                  SkyStats* stats);

}  // namespace starcam

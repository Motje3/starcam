// Star-pattern alignment. All coordinates are LUMA (half-res) coordinates.
// The estimated Transform maps REFERENCE frame coords -> CURRENT frame coords, i.e. for an output
// (reference-geometry) pixel p, sample the current frame at T.apply(p). See PLAN.md §6.3.
#pragma once

#include "common.h"

namespace starcam {

struct AlignParams {
    float matchRadius = 12.0f;  // px, NN match radius around predicted position
    float fluxRatioMin = 0.4f;
    float fluxRatioMax = 2.5f;
    int ransacIters = 400;
    float inlierPx = 1.5f;
    int minInliers = 8;
    int bootstrapStars = 25;    // brightest N used for triangle voting
    // Two frames of the same sky through the same lens differ in scale by far less than 1 %.
    // A model further than this from scale 1 is a false match and is never accepted.
    float maxScaleDev = 0.05f;
};

struct AlignResult {
    Transform T;
    int inliers = 0;
    float residual = 0;  // RMS of inlier residuals, px
    bool ok = false;
};

// Incremental alignment: predict each reference star via initGuess, NN-match within matchRadius
// with flux-ratio gate, then RANSAC (2-point minimal similarity) + least-squares refit on inliers.
AlignResult align_stars(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                        const Transform& initGuess, const AlignParams& p);

// Guess-free bootstrap: triangle-similarity voting over the brightest `bootstrapStars` stars
// (invariant = sorted side-length ratios, 1% tolerance), then the same RANSAC/refit.
AlignResult align_bootstrap(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                            const AlignParams& p);

}  // namespace starcam

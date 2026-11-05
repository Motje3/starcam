// Star-pattern alignment. All coordinates are LUMA (half-res) coordinates.
// The estimated Transform maps REFERENCE frame coords -> CURRENT frame coords, i.e. for an output
// (reference-geometry) pixel p, sample the current frame at T.apply(p). See PLAN.md §6.3.
#pragma once

#include "common.h"

namespace starcam {

struct AlignParams {
    float matchRadius = 12.0f;  // px, putative-match radius around the predicted position
    float fluxRatioMin = 0.4f;
    float fluxRatioMax = 2.5f;
    int ransacIters = 400;
    // The similarity that seeds the fit holds near the middle only (a turned view bends away from
    // it toward the edges), so its consensus is counted loosely.
    float seedInlierPx = 4.0f;
    float inlierPx = 1.5f;      // a star within this of where the transform puts it fits
    int minInliers = 8;
    int bootstrapStars = 25;    // brightest N used for triangle voting
    // Two frames of the same sky through the same lens differ in scale by far less than 1 %.
    // A model further than this from scale 1 is a false match and is never accepted.
    float maxScaleDev = 0.05f;
    int refStars = 300;         // brightest reference stars the transform is fitted and counted on
};

struct AlignResult {
    Transform T;
    int inliers = 0;     // reference stars (of the brightest refStars) that fit within inlierPx
    float residual = 0;  // RMS of inlier residuals, px
    // How much of the frame the fit holds over: the inliers on an 8 x 8 grid, at most 2 a cell.
    // Of two motions in one frame - the stars, and lights on the ground moving differently - the
    // one spread over more of it is the sky's, even where the lights outnumber the stars.
    int coverage = 0;
    bool ok = false;
};

// From a guess (the previous frame's transform): predicted-position matching with a flux-ratio
// gate, a RANSAC similarity (2-point minimal) as the seed, then a homography refined over every
// reference star with ever tighter matching. The guess itself is refined too; the better wins.
AlignResult align_stars(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                        const Transform& initGuess, const AlignParams& p);

// Guess-free: the shift most stars agree on (a vote over every pair's displacement), and
// triangle-similarity voting over the brightest `bootstrapStars` stars (invariant = sorted
// side-length ratios, 1% tolerance), each seeding the same RANSAC and refinement.
AlignResult align_bootstrap(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                            const AlignParams& p);

// Both of the above; the one that fits clearly more stars wins (the guided one on a tie). A phone
// shifting on its mount between frames moves the stars past any match radius, which only the
// guess-free start survives. What the engine aligns every frame with.
AlignResult align_frame(const std::vector<Star>& refStars, const std::vector<Star>& curStars,
                        const Transform& initGuess, const AlignParams& p);

}  // namespace starcam

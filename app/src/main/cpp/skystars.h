// Sky-verified stars: the reference frame's stars that followed the sky from frame to frame,
// measured on the linear stack. finalizeStack writes them as out/stars.json for the plate solver,
// which otherwise had only the finished JPEG's blobs to go on - lit windows and lamps among them.
// The engine only measures; the reader decides which entries to trust.
#pragma once

#include "common.h"

namespace starcam {

struct SkyStar {
    float x = 0, y = 0;     // the reference frame's luma coordinates
    int area = 0;           // detection area (luma px); 60 and more is blown out
    int tried = 0;          // other frames the sky's motion put it inside of (4 px from the edge)
    int seen = 0;           // of those, frames with a star of their own within 1.5 px of there
    int staticTried = 0;    // frames whose transform moved it more than 3 px
    int staticSeen = 0;     // of those, frames with a star still on its unmoved spot
    bool isStatic = false;  // better explained by something that stands still in the frame
    bool sky = false;       // followed the sky
    // Measured on the linear stack (measure_stars). r, g, b, flux: sums over the aperture of
    // (linear value, white = 1) x pixels of the full-resolution stack, the annulus median taken off.
    bool measured = false;  // false: the aperture or annulus left the picture; all zero
    double r = 0, g = 0, b = 0;
    double flux = 0;        // (r + 2g + b) / 4
    float peak = 0;         // the brightest value in the aperture, any channel
    float snr = 0;          // g / (annulus robust sigma x sqrt(aperture pixels))
    bool sat = false;       // peak >= 0.9
    float bgRGB[3] = {0, 0, 0};  // annulus median per channel
    // Its own width in RAW px of the stack: effective_fwhm on the stack's half-res luma at the
    // star, times 2. 0 when it cannot be measured (too near the edge, not a star's shape).
    float fwhm = 0;
};

struct StarPhotometry {
    float fwhm = 0;                      // the median star width the aperture was sized by, RAW px
    float radius = 0;                    // aperture radius, RAW px
    float annulusIn = 0, annulusOut = 0;
    float skyBg[3] = {0, 0, 0};          // median over the sky-verified stars of their annulus medians
};

// A star's width from how much of its light sits in its brightest pixel: for a Gaussian,
// peak = flux / (2 pi sigma^2), so FWHM = 2.355 sqrt(flux / (2 pi peak)). It reads a star the
// same in one frame and in a stack of many. A width from the pixels above the noise does not:
// the stack's lower noise lets more of each star's wings in, and by it the phone's stacks of
// 2026-10-31 read twice as wide as their frames (1.7 -> 3.3 px) however well aligned.
// (x, y) in luma coords; 0 when the star is too near the edge or not a star.
float effective_fwhm(const ImageF& luma, float x, float y);

// A stack's (or any RAW-resolution RGB image's) luma at half resolution, the superpixel grid the
// stars were detected on: each 2 x 2 block's mean of r, g and b.
void stack_luma_half(const ImageF& rgb, ImageF* luma);

// For every star of the reference frame (frames[refPos].stars): put it through each other frame's
// transform (not rejected, aligned); inside that frame by 4 px it counts as tried there, and as seen
// when that frame's own list has a star within 1.5 px. Where the transform moved it more than 3 px,
// a star within 1.5 px of its unmoved position is a static hit. Sky-verified: tried >= 1, seen >=
// (tried >= 3 ? ceil(0.6 tried) : tried), and not static (static hits >= 0.6 of at least 2 static
// tries, and more of them than sightings). lumaW, lumaH: the frames' luma size.
std::vector<SkyStar> verify_sky_stars(const std::vector<FrameRecord>& frames, int refPos,
                                      int lumaW, int lumaH);

// Aperture photometry on the linear stack (RAW resolution, after the CCM, before anything is
// composited onto it), at each star's RAW position (2x + 0.5, 2y + 0.5). Each star's width first
// (effective_fwhm on stackLumaHalf); the aperture radius is 1.4 x the median width of the brightest
// sky-verified stars that are not blown out (all stars when too few of those, fallbackFwhmRaw when
// none can be measured), clamped to 6..14 px, the annulus from radius + 4 to radius + 10.
void measure_stars(const ImageF& stackRGB, const ImageF& stackLumaHalf, float fallbackFwhmRaw,
                   std::vector<SkyStar>* stars, StarPhotometry* phot);

// Whether a star marks the sky for the stars' white balance (sky_lit_mask's points): it followed
// the sky, was measured, the sky really carried it along (more than 3 px, in a frame at least:
// where the sky hardly moved, a lamp follows it as well as a star does) and it has a star's width,
// 0.6 to 1.7 times the stars' median (when it is the phone that moves, a lamp moves with the sky
// too: on 2026-10-31 the brightest "star" that followed it was 11.5 px wide, against 4.8).
// finalizeStack takes its sky points by this, and stars.json carries it as "pt" for the studio.
bool sky_point(const SkyStar& s, const StarPhotometry& phot);

// out/stars.json. Positions on the finished upright picture's pixel grid (that of final_rgb.raw:
// the stack turned by `rotation`), x right, y down, pixel centres at integers; every star, the
// brightest first (measured ones by flux, then the rest). Per star, beside its light: "seen" of
// "tried" frames, and "moved": in how many frames the sky had carried it more than 3 px from its
// place. With "moved" 0 the sky hardly moved at all and "sky" says little: a lamp passes too.
// "pt": sky_point above - the stars whose x, y and bg are the look's "skyStars".
std::string stars_json(const std::vector<SkyStar>& stars, const StarPhotometry& phot, int stackW,
                       int stackH, int rotation, int framesUsed);

}  // namespace starcam

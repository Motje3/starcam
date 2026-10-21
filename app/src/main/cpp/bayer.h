// Bayer-domain operations: superpixel extraction, Malvar-He-Cutler demosaic, dark/hot-pixel repair.
// All outputs are LINEAR, black-subtracted, WB-gain-applied, normalized so whiteLevel-black -> 1.0.
#pragma once

#include "common.h"

namespace starcam {

// Half-res luma from 2x2 superpixels: (R + G1 + G2 + B) * 0.25 after black/WB/normalize.
// Output size (w/2, h/2, 1).
void superpixel_luma(const uint16_t* bayer, int w, int h, int strideElems, const SensorInfo& si,
                     const float wbGain[4], ImageF* outLuma);

// Half-res RGB from 2x2 superpixels: R, (G1+G2)/2, B (positions resolved via si.cfa).
// Output size (w/2, h/2, 3).
void superpixel_rgb(const uint16_t* bayer, int w, int h, int strideElems, const SensorInfo& si,
                    const float wbGain[4], ImageF* outRgb);

// Copies a region out of a packed full frame with edge clamping.
// PRECONDITION: x0 and y0 MUST be even (CFA phase preserved); rw, rh any size.
void copy_region_u16(const uint16_t* bayer, int w, int h, int x0, int y0, int rw, int rh,
                     std::vector<uint16_t>* out);

// In-place on a copied region: subtract the master dark's excess over black level.
// region pixel (x,y) corresponds to full-frame (x0+x, y0+y). masterDark is full-res.
// result = clamp(pixel - (dark - blackAtPosition), blackAtPosition, 65535).
void subtract_dark_region(std::vector<uint16_t>& region, int rw, int rh, int x0, int y0,
                          const Image16& masterDark, const float blackLevel[4]);

// In-place on a copied region: replace listed hot pixels (full-frame indices y*fullW+x) that fall
// inside the region with the mean of the nearest same-CFA-position neighbors (±2 px).
void fix_hot_pixels_region(std::vector<uint16_t>& region, int rw, int rh, int x0, int y0, int fullW,
                           int fullH, const std::vector<uint32_t>& hotIdx);

// In-place on a demosaiced region: multiply by the lens shading gains so a flat sky reads flat.
//
// Applied in FRAME coordinates, after demosaic and before any warp: vignetting is fixed to the
// lens, not to the sky. Region pixel (x,y) is full-frame (x0+x, y0+y); the map spans the whole
// frame in normalized coordinates, so it works for binned and full-res RAW alike.
//
// No-op when [sm] is empty, which is the normal state on a device that publishes no usable map.
void apply_shading_region(ImageF& rgb, int x0, int y0, int fullW, int fullH, const ShadingMap& sm);

// Malvar-He-Cutler 5x5 linear demosaic of a packed region buffer (as produced by copy_region_u16
// with even x0/y0, so the region's CFA phase equals si.cfa). Output (rw, rh, 3) linear sensor RGB,
// black-subtracted, WB-applied, normalized to [0, ~1]. Border pixels use clamped taps.
void demosaic_region_malvar(const uint16_t* region, int rw, int rh, const SensorInfo& si,
                            const float wbGain[4], ImageF* outRgb);

}  // namespace starcam

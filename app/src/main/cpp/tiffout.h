// Minimal uncompressed 16-bit RGB TIFF writer (little-endian, single strip per row block).
#pragma once

#include "common.h"

namespace starcam {

// Input clamped to [0,1] and scaled to 0..65535, turned upright by `rotation` (clockwise degrees,
// see rotated_source). Returns false on IO failure.
bool write_tiff16(const std::string& path, const ImageF& rgb, int rotation = 0);

// The display-referred photo as raw interleaved u8 RGB (clamp(v*255)), turned upright the same
// way; Kotlin's MediaSaver encodes it to JPEG.
bool write_rgb8(const std::string& path, const ImageF& rgb, int rotation);

// Reads back a TIFF in the layout write_tiff16 produces (little-endian, uncompressed, 16-bit RGB,
// chunky, one strip) into linear [0,1] floats. Returns false for anything else.
bool read_tiff16(const std::string& path, ImageF* out);

}  // namespace starcam

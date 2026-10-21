// Minimal uncompressed 16-bit RGB TIFF writer (little-endian, single strip per row block).
#pragma once

#include "common.h"

namespace starcam {

// Input clamped to [0,1] and scaled to 0..65535. Returns false on IO failure.
bool write_tiff16(const std::string& path, const ImageF& rgb);

}  // namespace starcam

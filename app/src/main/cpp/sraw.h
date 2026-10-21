// StarCam raw cache format (.sraw): fixed 160-byte little-endian header + packed uint16 Bayer.
// Header layout (offsets in bytes):
//   0  magic "SRAW" (4 bytes)
//   4  u32 version (=1)
//   8  u32 width
//  12  u32 height
//  16  u32 cfa (starcam::CFA)
//  20  u32 whiteLevel
//  24  f32 blackLevel[4]   (raster order in 2x2 tile)
//  40  f32 wbGain[4]       (raster order in 2x2 tile)
//  56  f32 ccm[9]          (row-major, WB'd sensor RGB -> linear sRGB)
//  92  u64 exposureNs
// 100  u32 iso
// 104  u64 timestampNs
// 112  reserved zeros to 160
// 160  uint16 bayer[width*height], row-major, no padding
#pragma once

#include "common.h"

namespace starcam {

struct SrawHeader {
    uint32_t width = 0, height = 0;
    CFA cfa = CFA::RGGB;
    uint32_t whiteLevel = 1023;
    float blackLevel[4] = {0, 0, 0, 0};
    float wbGain[4] = {1, 1, 1, 1};
    float ccm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    uint64_t exposureNs = 0;
    uint32_t iso = 0;
    uint64_t timestampNs = 0;
};

// Writes header + pixel data. `strideElems` = elements per source row (>= w). Returns false on IO error.
bool sraw_write(const std::string& path, const SrawHeader& hdr, const uint16_t* bayer,
                int strideElems);

// mmap-backed reader. Keep alive while pixel pointers are in use.
class SrawReader {
public:
    SrawReader() = default;
    ~SrawReader() { close(); }
    SrawReader(const SrawReader&) = delete;
    SrawReader& operator=(const SrawReader&) = delete;

    bool open(const std::string& path);
    void close();
    bool isOpen() const { return map_ != nullptr; }
    const SrawHeader& header() const { return hdr_; }
    // Packed w*h pixel data.
    const uint16_t* data() const { return pixels_; }
    const uint16_t* row(int y) const { return pixels_ + (size_t)y * hdr_.width; }

private:
    int fd_ = -1;
    size_t mapLen_ = 0;
    void* map_ = nullptr;
    const uint16_t* pixels_ = nullptr;
    SrawHeader hdr_;
};

constexpr size_t kSrawHeaderSize = 160;

}  // namespace starcam

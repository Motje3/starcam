// StarCam — minimal little-endian uncompressed 16-bit RGB TIFF writer.
// Layout: [8-byte header][pixel data (single strip)][IFD][BitsPerSample value area].
// No dependencies beyond the C/C++ standard library.

#include "tiffout.h"

#include <cstdio>

namespace starcam {

namespace {

// TIFF field types.
constexpr uint16_t kTypeShort = 3;  // 16-bit unsigned
constexpr uint16_t kTypeLong = 4;   // 32-bit unsigned

inline void put_u16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back((uint8_t)(v & 0xFFu));
    b.push_back((uint8_t)((v >> 8) & 0xFFu));
}

inline void put_u32(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back((uint8_t)(v & 0xFFu));
    b.push_back((uint8_t)((v >> 8) & 0xFFu));
    b.push_back((uint8_t)((v >> 16) & 0xFFu));
    b.push_back((uint8_t)((v >> 24) & 0xFFu));
}

// One 12-byte IFD entry: tag, type, count, value/offset.
// For inline SHORT values (count 1) the TIFF spec wants the value left-justified in the
// 4-byte field; in a little-endian file that is simply the value in the low bytes, which
// is exactly what put_u32 of a value < 65536 produces.
inline void put_entry(std::vector<uint8_t>& b, uint16_t tag, uint16_t type,
                      uint32_t count, uint32_t value) {
    put_u16(b, tag);
    put_u16(b, type);
    put_u32(b, count);
    put_u32(b, value);
}

inline bool fwrite_all(FILE* f, const void* p, size_t n) {
    return std::fwrite(p, 1, n, f) == n;
}

bool write_tiff16_impl(const std::string& path, const ImageF& rgb) {
    if (rgb.empty() || rgb.w <= 0 || rgb.h <= 0) {
        SLOGE("write_tiff16: empty image for %s", path.c_str());
        return false;
    }
    if (rgb.ch != 3) {
        SLOGE("write_tiff16: expected 3-channel image, got ch=%d", rgb.ch);
        return false;
    }
    const uint32_t w = (uint32_t)rgb.w;
    const uint32_t h = (uint32_t)rgb.h;
    if (rgb.d.size() < (size_t)w * h * 3u) {
        SLOGE("write_tiff16: image buffer too small (%zu < %zu)",
              rgb.d.size(), (size_t)w * h * 3u);
        return false;
    }

    // ---- Offset arithmetic (all fixed before any byte is written) ----
    // header: 8 bytes ("II", 42, IFD offset)
    // pixels: at offset 8, w*h*3 samples * 2 bytes
    // IFD:    after pixels, aligned to an even offset (pixelBytes is always even,
    //         but keep the alignment defensive)
    // bps:    3 SHORTs ([16,16,16]) immediately after the IFD's next-IFD pointer
    const uint64_t pixelOffset = 8ull;
    const uint64_t pixelBytes = (uint64_t)w * (uint64_t)h * 6ull;
    uint64_t ifdOffset = pixelOffset + pixelBytes;
    uint32_t padBytes = 0;
    if (ifdOffset & 1ull) {  // TIFF values must sit at even offsets
        ifdOffset += 1ull;
        padBytes = 1;
    }
    const uint32_t numEntries = 10;
    const uint64_t ifdBytes = 2ull + (uint64_t)numEntries * 12ull + 4ull;  // count + entries + next-IFD ptr = 126
    const uint64_t bpsOffset = ifdOffset + ifdBytes;
    const uint64_t totalBytes = bpsOffset + 6ull;  // + 3 SHORTs
    if (totalBytes > 0xFFFFFFFFull) {
        SLOGE("write_tiff16: image too large for 32-bit TIFF offsets (%llu bytes)",
              (unsigned long long)totalBytes);
        return false;
    }

    // ---- Build all non-pixel bytes up front (vector ops may allocate; do them
    //      before fopen so no throwing operation runs while the file is open) ----
    std::vector<uint8_t> header;
    header.reserve(8);
    header.push_back('I');
    header.push_back('I');
    put_u16(header, 42);
    put_u32(header, (uint32_t)ifdOffset);

    std::vector<uint8_t> ifd;
    ifd.reserve((size_t)ifdBytes + 6);
    put_u16(ifd, (uint16_t)numEntries);
    // Entries in strictly ascending tag order (required by the TIFF spec).
    put_entry(ifd, 256, kTypeLong, 1, w);                        // ImageWidth
    put_entry(ifd, 257, kTypeLong, 1, h);                        // ImageLength
    put_entry(ifd, 258, kTypeShort, 3, (uint32_t)bpsOffset);     // BitsPerSample -> [16,16,16]
    put_entry(ifd, 259, kTypeShort, 1, 1);                       // Compression = none
    put_entry(ifd, 262, kTypeShort, 1, 2);                       // Photometric = RGB
    put_entry(ifd, 273, kTypeLong, 1, (uint32_t)pixelOffset);    // StripOffsets (single strip)
    put_entry(ifd, 277, kTypeShort, 1, 3);                       // SamplesPerPixel
    put_entry(ifd, 278, kTypeLong, 1, h);                        // RowsPerStrip = whole image
    put_entry(ifd, 279, kTypeLong, 1, (uint32_t)pixelBytes);     // StripByteCounts
    put_entry(ifd, 284, kTypeShort, 1, 1);                       // PlanarConfig = chunky
    put_u32(ifd, 0);                                             // next IFD: none
    // BitsPerSample external value area, contiguous right after the IFD.
    put_u16(ifd, 16);
    put_u16(ifd, 16);
    put_u16(ifd, 16);

    // Row buffer: w*3 samples, 2 bytes each, emitted explicitly little-endian.
    std::vector<uint8_t> row((size_t)w * 6u);

    // ---- Write ----
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) {
        SLOGE("write_tiff16: fopen failed for %s", path.c_str());
        return false;
    }

    bool ok = fwrite_all(f, header.data(), header.size());

    if (ok) {
        const size_t rowFloats = (size_t)w * 3u;
        for (uint32_t y = 0; y < h && ok; ++y) {
            const float* src = &rgb.d[(size_t)y * rowFloats];
            uint8_t* dst = row.data();
            for (size_t i = 0; i < rowFloats; ++i) {
                float c = src[i];
                if (!(c >= 0.0f)) {
                    c = 0.0f;  // catches negatives and NaN
                } else if (c > 1.0f) {
                    c = 1.0f;
                }
                uint32_t v = (uint32_t)(c * 65535.0f + 0.5f);
                if (v > 65535u) v = 65535u;
                dst[0] = (uint8_t)(v & 0xFFu);
                dst[1] = (uint8_t)((v >> 8) & 0xFFu);
                dst += 2;
            }
            ok = fwrite_all(f, row.data(), row.size());
        }
    }

    if (ok && padBytes) {
        const uint8_t zero = 0;
        ok = fwrite_all(f, &zero, 1);
    }

    if (ok) ok = fwrite_all(f, ifd.data(), ifd.size());

    if (std::fclose(f) != 0) {
        SLOGE("write_tiff16: fclose failed for %s", path.c_str());
        ok = false;
    }

    if (!ok) {
        SLOGE("write_tiff16: write failed for %s", path.c_str());
        return false;
    }

    SLOG("write_tiff16: wrote %ux%u (%llu bytes) to %s", w, h,
         (unsigned long long)totalBytes, path.c_str());
    return true;
}

}  // namespace

bool write_tiff16(const std::string& path, const ImageF& rgb) {
    // No exceptions may escape: all allocation happens in std::vector, which can throw.
    try {
        return write_tiff16_impl(path, rgb);
    } catch (...) {
        SLOGE("write_tiff16: unexpected exception for %s", path.c_str());
        return false;
    }
}

}  // namespace starcam

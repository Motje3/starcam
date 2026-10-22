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

bool write_tiff16_impl(const std::string& path, const ImageF& rgb, int rotation) {
    if (rgb.empty() || rgb.w <= 0 || rgb.h <= 0) {
        SLOGE("write_tiff16: empty image for %s", path.c_str());
        return false;
    }
    if (rgb.ch != 3) {
        SLOGE("write_tiff16: expected 3-channel image, got ch=%d", rgb.ch);
        return false;
    }
    if (rgb.d.size() < (size_t)rgb.w * rgb.h * 3u) {
        SLOGE("write_tiff16: image buffer too small (%zu < %zu)",
              rgb.d.size(), (size_t)rgb.w * rgb.h * 3u);
        return false;
    }
    int ow, oh;
    rotated_size(rotation, rgb.w, rgb.h, &ow, &oh);
    const uint32_t w = (uint32_t)ow;
    const uint32_t h = (uint32_t)oh;

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
        for (uint32_t y = 0; y < h && ok; ++y) {
            uint8_t* dst = row.data();
            for (size_t i = 0; i < (size_t)w * 3u; ++i) {
                int sx, sy;
                rotated_source(rotation, rgb.w, rgb.h, (int)(i / 3), (int)y, &sx, &sy);
                float c = rgb.px(sx, sy)[i % 3];
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

// u8 clamp(v*255) interleaved RGB, plain fwrite, turned upright by `rotation` on the way out.
bool write_rgb8(const std::string& path, const ImageF& rgb, int rotation) {
    if (rgb.empty() || rgb.ch != 3 || rgb.w <= 0 || rgb.h <= 0) return false;
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) {
        SLOGE("write_rgb8: cannot open %s", path.c_str());
        return false;
    }
    int ow, oh;
    rotated_size(rotation, rgb.w, rgb.h, &ow, &oh);
    std::vector<uint8_t> row((size_t)ow * 3);
    bool ok = true;
    for (int y = 0; y < oh && ok; ++y) {
        for (int x = 0; x < ow; ++x) {
            int sx, sy;
            rotated_source(rotation, rgb.w, rgb.h, x, y, &sx, &sy);
            const float* src = rgb.px(sx, sy);
            for (int c = 0; c < 3; ++c) {
                float v = clampf(src[c], 0.0f, 1.0f);
                row[(size_t)x * 3 + c] = (uint8_t)(v * 255.0f + 0.5f);
            }
        }
        ok = fwrite(row.data(), 1, row.size(), f) == row.size();
    }
    if (fclose(f) != 0) ok = false;
    if (!ok) SLOGE("write_rgb8: write failed for %s", path.c_str());
    return ok;
}

bool read_tiff16(const std::string& path, ImageF* out) {
    try {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (!f) return false;
        auto readAt = [f](uint64_t off, void* dst, size_t n) {
            return std::fseek(f, (long)off, SEEK_SET) == 0 && std::fread(dst, 1, n, f) == n;
        };
        uint8_t hdr[8];
        bool ok = readAt(0, hdr, 8) && hdr[0] == 'I' && hdr[1] == 'I' && hdr[2] == 42;
        const uint32_t ifd = (uint32_t)hdr[4] | ((uint32_t)hdr[5] << 8) | ((uint32_t)hdr[6] << 16) |
                             ((uint32_t)hdr[7] << 24);
        uint8_t cnt[2];
        ok = ok && readAt(ifd, cnt, 2);
        const uint32_t n = ok ? ((uint32_t)cnt[0] | ((uint32_t)cnt[1] << 8)) : 0;
        uint32_t w = 0, h = 0, strip = 0, spp = 0, bps = 16, comp = 1;
        std::vector<uint8_t> e((size_t)n * 12);
        ok = ok && n > 0 && n < 64 && readAt(ifd + 2, e.data(), e.size());
        for (uint32_t i = 0; ok && i < n; ++i) {
            const uint8_t* p = &e[(size_t)i * 12];
            const uint32_t tag = (uint32_t)p[0] | ((uint32_t)p[1] << 8);
            const uint32_t type = (uint32_t)p[2] | ((uint32_t)p[3] << 8);
            const uint32_t v = type == kTypeShort ? ((uint32_t)p[8] | ((uint32_t)p[9] << 8))
                                                  : ((uint32_t)p[8] | ((uint32_t)p[9] << 8) |
                                                     ((uint32_t)p[10] << 16) | ((uint32_t)p[11] << 24));
            if (tag == 256) w = v;
            else if (tag == 257) h = v;
            else if (tag == 259) comp = v;
            else if (tag == 273) strip = v;
            else if (tag == 277) spp = v;
        }
        ok = ok && w > 0 && h > 0 && spp == 3 && comp == 1 && bps == 16 && w < 20000 && h < 20000;
        if (ok) {
            out->init((int)w, (int)h, 3);
            std::vector<uint8_t> row((size_t)w * 6);
            for (uint32_t y = 0; ok && y < h; ++y) {
                ok = readAt(strip + (uint64_t)y * w * 6, row.data(), row.size());
                float* d = &out->d[(size_t)y * w * 3];
                for (size_t i = 0; ok && i < (size_t)w * 3; ++i)
                    d[i] = (float)((uint32_t)row[2 * i] | ((uint32_t)row[2 * i + 1] << 8)) / 65535.0f;
            }
        }
        std::fclose(f);
        if (!ok) {
            out->d.clear();
            SLOGE("read_tiff16: not a StarCam master: %s", path.c_str());
        }
        return ok;
    } catch (...) {
        SLOGE("read_tiff16: exception for %s", path.c_str());
        out->d.clear();
        return false;
    }
}

bool write_tiff16(const std::string& path, const ImageF& rgb, int rotation) {
    // No exceptions may escape: all allocation happens in std::vector, which can throw.
    try {
        return write_tiff16_impl(path, rgb, normalize_rotation(rotation));
    } catch (...) {
        SLOGE("write_tiff16: unexpected exception for %s", path.c_str());
        return false;
    }
}

}  // namespace starcam

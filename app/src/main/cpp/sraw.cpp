#include "sraw.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace starcam {

namespace {
void put_u32(uint8_t* p, uint32_t v) { memcpy(p, &v, 4); }
void put_u64(uint8_t* p, uint64_t v) { memcpy(p, &v, 8); }
void put_f32(uint8_t* p, const float* v, int n) { memcpy(p, v, 4 * (size_t)n); }
uint32_t get_u32(const uint8_t* p) { uint32_t v; memcpy(&v, p, 4); return v; }
uint64_t get_u64(const uint8_t* p) { uint64_t v; memcpy(&v, p, 8); return v; }
}  // namespace

bool sraw_write(const std::string& path, const SrawHeader& hdr, const uint16_t* bayer,
                int strideElems) {
    if (hdr.width == 0 || hdr.height == 0 || strideElems < (int)hdr.width) return false;
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) {
        SLOGE("sraw_write: cannot open %s", path.c_str());
        return false;
    }
    uint8_t h[kSrawHeaderSize];
    memset(h, 0, sizeof(h));
    memcpy(h, "SRAW", 4);
    put_u32(h + 4, 1);
    put_u32(h + 8, hdr.width);
    put_u32(h + 12, hdr.height);
    put_u32(h + 16, (uint32_t)hdr.cfa);
    put_u32(h + 20, hdr.whiteLevel);
    put_f32(h + 24, hdr.blackLevel, 4);
    put_f32(h + 40, hdr.wbGain, 4);
    put_f32(h + 56, hdr.ccm, 9);
    put_u64(h + 92, hdr.exposureNs);
    put_u32(h + 100, hdr.iso);
    put_u64(h + 104, hdr.timestampNs);
    bool ok = fwrite(h, 1, kSrawHeaderSize, f) == kSrawHeaderSize;
    for (uint32_t y = 0; ok && y < hdr.height; ++y) {
        ok = fwrite(bayer + (size_t)y * strideElems, 2, hdr.width, f) == hdr.width;
    }
    if (fclose(f) != 0) ok = false;
    if (!ok) SLOGE("sraw_write: write failed for %s", path.c_str());
    return ok;
}

bool SrawReader::open(const std::string& path) {
    close();
    fd_ = ::open(path.c_str(), O_RDONLY);
    if (fd_ < 0) {
        SLOGE("SrawReader: cannot open %s", path.c_str());
        return false;
    }
    struct stat st;
    if (fstat(fd_, &st) != 0 || (size_t)st.st_size < kSrawHeaderSize) {
        close();
        return false;
    }
    mapLen_ = (size_t)st.st_size;
    map_ = mmap(nullptr, mapLen_, PROT_READ, MAP_PRIVATE, fd_, 0);
    if (map_ == MAP_FAILED) {
        map_ = nullptr;
        close();
        return false;
    }
    const uint8_t* h = (const uint8_t*)map_;
    if (memcmp(h, "SRAW", 4) != 0 || get_u32(h + 4) != 1) {
        SLOGE("SrawReader: bad magic/version in %s", path.c_str());
        close();
        return false;
    }
    hdr_.width = get_u32(h + 8);
    hdr_.height = get_u32(h + 12);
    hdr_.cfa = (CFA)get_u32(h + 16);
    hdr_.whiteLevel = get_u32(h + 20);
    memcpy(hdr_.blackLevel, h + 24, 16);
    memcpy(hdr_.wbGain, h + 40, 16);
    memcpy(hdr_.ccm, h + 56, 36);
    hdr_.exposureNs = get_u64(h + 92);
    hdr_.iso = get_u32(h + 100);
    hdr_.timestampNs = get_u64(h + 104);
    size_t need = kSrawHeaderSize + (size_t)hdr_.width * hdr_.height * 2;
    if (mapLen_ < need) {
        SLOGE("SrawReader: truncated file %s", path.c_str());
        close();
        return false;
    }
    pixels_ = (const uint16_t*)(h + kSrawHeaderSize);
    return true;
}

void SrawReader::close() {
    if (map_) munmap(map_, mapLen_);
    map_ = nullptr;
    pixels_ = nullptr;
    mapLen_ = 0;
    if (fd_ >= 0) ::close(fd_);
    fd_ = -1;
}

}  // namespace starcam

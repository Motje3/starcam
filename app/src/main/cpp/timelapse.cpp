// timelapse.cpp — see timelapse.h.

#include "timelapse.h"

#include <algorithm>
#include <mutex>
#include <vector>

#include "bayer.h"
#include "common.h"
#include "preview.h"
#include "sraw.h"

namespace starcam {
namespace {

// The master dark, kept between frames: a video develops a hundred frames against the same one.
std::mutex gDarkMu;
std::string gDarkPath;
Image16 gDark;
float gDarkPedestal[4] = {0, 0, 0, 0};
float gDarkBlack[4] = {0, 0, 0, 0};

bool loadDark(const std::string& path, int w, int h) {
    if (path == gDarkPath && gDark.w == w && gDark.h == h) return !gDark.empty();
    gDarkPath = path;
    gDark = Image16();
    SrawReader d;
    if (!d.open(path) || (int)d.header().width != w || (int)d.header().height != h) return false;
    gDark.w = w;
    gDark.h = h;
    gDark.d.assign(d.data(), d.data() + (size_t)w * h);
    dark_pedestal(gDark, gDarkPedestal);
    for (int i = 0; i < 4; ++i) gDarkBlack[i] = d.header().blackLevel[i];
    return true;
}

// Without a dark: a pixel standing far above all its neighbours in one colour is a hot pixel (a
// star at half resolution always spills into the pixels around it).
void quiet_hot_pixels(ImageF& rgb) {
    const int w = rgb.w, h = rgb.h;
    ImageF src = rgb;
    parallel_for(1, h - 1, [&](int y) {
        for (int x = 1; x < w - 1; ++x) {
            for (int c = 0; c < 3; ++c) {
                const float v = src.px(x, y)[c];
                float m = -1e9f;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx)
                        if (dx || dy) m = std::max(m, src.px(x + dx, y + dy)[c]);
                if (v > 4.0f * std::max(m, 0.0f) + 0.01f) rgb.px(x, y)[c] = m;
            }
        }
    });
}

}  // namespace

bool develop_frame(const std::string& srawPath, const std::string& darkPath, int rotation,
                   uint32_t* argbOut, int outW, int outH) {
    if (!argbOut || outW <= 0 || outH <= 0) return false;
    SrawReader r;
    if (!r.open(srawPath)) return false;
    const SrawHeader& hdr = r.header();
    const int w = (int)hdr.width, h = (int)hdr.height;
    if (w < 4 || h < 4) return false;
    SensorInfo si;
    si.width = w;
    si.height = h;
    si.cfa = hdr.cfa;
    si.whiteLevel = hdr.whiteLevel;
    for (int i = 0; i < 4; ++i) si.blackLevel[i] = hdr.blackLevel[i];

    const uint16_t* bayer = r.data();
    std::vector<uint16_t> region;
    bool darkDone = false;
    if (!darkPath.empty()) {
        std::lock_guard<std::mutex> lk(gDarkMu);
        if (loadDark(darkPath, w, h)) {
            copy_region_u16(bayer, w, h, 0, 0, w, h, &region);
            // Only the dark's structure (hot pixels, glow): its level was another moment's.
            subtract_dark_region(region, w, h, 0, 0, gDark, hdr.blackLevel, gDarkPedestal, gDarkBlack);
            bayer = region.data();
            darkDone = true;
        }
    }
    ImageF rgb;
    superpixel_rgb(bayer, w, h, w, si, hdr.wbGain, &rgb);
    if (!darkDone) quiet_hot_pixels(rgb);
    return render_preview(rgb, nullptr, rotation, argbOut, outW, outH);
}

void develop_frame_release() {
    std::lock_guard<std::mutex> lk(gDarkMu);
    gDarkPath.clear();
    gDark = Image16();
}

}  // namespace starcam

// Editing studio (studio.h): the linear master developed again with the user's settings.

#include "studio.h"

#include "json_mini.h"
#include "tiffout.h"

namespace starcam {

namespace {

// [src] box-averaged down by the whole factor that brings its long edge to at most [edge].
ImageF box_down(const ImageF& src, int edge) {
    const int longEdge = std::max(src.w, src.h);
    const int f = std::max(1, (longEdge + std::max(64, edge) - 1) / std::max(64, edge));
    const int w = std::max(1, src.w / f), h = std::max(1, src.h / f);
    ImageF out;
    out.init(w, h, 3);
    const float inv = 1.0f / (float)(f * f);
    parallel_for(0, h, [&](int y) {
        for (int x = 0; x < w; ++x) {
            float acc[3] = {0, 0, 0};
            for (int yy = 0; yy < f; ++yy) {
                const float* r = src.px(x * f, y * f + yy);
                for (int xx = 0; xx < f; ++xx)
                    for (int c = 0; c < 3; ++c) acc[c] += r[xx * 3 + c];
            }
            float* o = out.px(x, y);
            for (int c = 0; c < 3; ++c) o[c] = acc[c] * inv;
        }
    });
    return out;
}

}  // namespace

Studio* Studio::open(const std::string& tiffPath, int previewLongEdge) {
    try {
        ImageF full;
        if (!read_tiff16(tiffPath, &full)) return nullptr;
        auto* s = new Studio();
        s->path_ = tiffPath;
        s->w_ = full.w;
        s->h_ = full.h;
        // Box-average down by a whole factor: cheap, and it keeps the preview's noise honest
        // (averaged, not aliased).
        s->preview_ = box_down(full, previewLongEdge);
        s->thumb_ = box_down(s->preview_, kThumbEdge);
        SLOG("studio: opened %s (%dx%d, preview %dx%d, thumb %dx%d)", tiffPath.c_str(), full.w, full.h,
             s->preview_.w, s->preview_.h, s->thumb_.w, s->thumb_.h);
        return s;
    } catch (...) {
        SLOGE("studio: open failed for %s", tiffPath.c_str());
        return nullptr;
    }
}

std::string Studio::info() const {
    jsonmini::Builder b;
    b.add("width", w_).add("height", h_).add("previewWidth", preview_.w).add("previewHeight", preview_.h);
    b.add("thumbWidth", thumb_.w).add("thumbHeight", thumb_.h);
    return b.build();
}

bool Studio::renderPreview(const PostParams& p, int rotation, uint32_t* argb, int outW, int outH) {
    return render(preview_, p, rotation, argb, outW, outH);
}

bool Studio::renderThumb(const PostParams& p, int rotation, uint32_t* argb, int outW, int outH) {
    return render(thumb_, p, rotation, argb, outW, outH);
}

bool Studio::render(const ImageF& src, const PostParams& p, int rotation, uint32_t* argb, int outW, int outH) {
    try {
        std::lock_guard<std::mutex> lk(mu_);
        int rw, rh;
        rotated_size(rotation, src.w, src.h, &rw, &rh);
        if (!argb || outW != rw || outH != rh) return false;
        ImageF img = src;
        postprocess(img, p, nullptr);
        parallel_for(0, outH, [&](int y) {
            uint32_t* dst = argb + (size_t)y * outW;
            for (int x = 0; x < outW; ++x) {
                int sx, sy;
                rotated_source(rotation, img.w, img.h, x, y, &sx, &sy);
                const float* q = img.px(sx, sy);
                uint32_t px = 0xFF000000u;
                for (int c = 0; c < 3; ++c)
                    px |= (uint32_t)(uint8_t)(clampf(q[c], 0.0f, 1.0f) * 255.0f + 0.5f) << (16 - 8 * c);
                dst[x] = px;
            }
        });
        return true;
    } catch (...) {
        SLOGE("studio: preview failed");
        return false;
    }
}

std::string Studio::exportFull(const PostParams& p, int rotation, const std::string& outPath) {
    auto fail = [](const char* why) {
        jsonmini::Builder b;
        b.add("ok", false).add("error", std::string(why)).add("width", 0).add("height", 0);
        return b.build();
    };
    try {
        std::lock_guard<std::mutex> lk(mu_);
        ImageF full;
        if (!read_tiff16(path_, &full)) return fail("cannot read the master");
        postprocess(full, p, nullptr);
        if (!write_rgb8(outPath, full, rotation)) return fail("cannot write the photo");
        int ow, oh;
        rotated_size(rotation, full.w, full.h, &ow, &oh);
        jsonmini::Builder b;
        b.add("ok", true).add("error", "").add("width", ow).add("height", oh);
        return b.build();
    } catch (...) {
        return fail("out of memory");
    }
}

}  // namespace starcam

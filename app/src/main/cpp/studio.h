// Editing studio: develops a session's linear master (out/master_linear16.tiff) again with the
// v2 pipeline and the user's own settings, small while the sliders move and at full size when
// saved. The master itself is never written to.
#pragma once

#include <mutex>
#include <string>

#include "common.h"
#include "post.h"

namespace starcam {

class Studio {
public:
    // nullptr when the master cannot be read. The preview is the master scaled so its long edge
    // is at most previewLongEdge.
    static Studio* open(const std::string& tiffPath, int previewLongEdge);

    // {"width","height","previewWidth","previewHeight","thumbWidth","thumbHeight"} of the master
    // as stored.
    std::string info() const;

    // Develops the preview with p and writes it, turned by `rotation`, as ARGB ints into
    // argb (outW x outH, which must be the turned preview size).
    bool renderPreview(const PostParams& p, int rotation, uint32_t* argb, int outW, int outH);

    // The same from the thumbnail (long edge at most kThumbEdge): the looks' small pictures.
    bool renderThumb(const PostParams& p, int rotation, uint32_t* argb, int outW, int outH);

    static constexpr int kThumbEdge = 320;

    // Develops the full-size master with p and writes interleaved RGB8, turned by `rotation`,
    // to outPath. Returns {"ok","error","width","height"}.
    std::string exportFull(const PostParams& p, int rotation, const std::string& outPath);

private:
    bool render(const ImageF& src, const PostParams& p, int rotation, uint32_t* argb, int outW, int outH);

    std::mutex mu_;
    std::string path_;
    int w_ = 0, h_ = 0;
    ImageF preview_;  // linear
    ImageF thumb_;    // linear, the preview scaled down again
};

}  // namespace starcam

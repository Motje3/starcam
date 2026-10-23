// EngineSession — the native session orchestrator behind the JNI bridge. See PLAN.md §4, §7.
#pragma once

#include <memory>
#include <mutex>

#include "common.h"
#include "meteor.h"
#include "sraw.h"
#include "trails.h"

namespace starcam {

enum class SessionMode : int { ASTRO = 0, TRAILS = 1, METEOR = 2, PRO = 3 };

struct EngineConfig {
    std::string sessionDir;   // absolute path; engine writes cache/ and out/ beneath it
    SessionMode mode = SessionMode::ASTRO;
    SensorInfo sensor;
    bool keepCache = false;   // keep .sraw files after successful finalize
    bool livePreview = true;
    int rotation = 0;         // clockwise turn that makes the photo upright (live preview)
};

class EngineSession {
public:
    static EngineSession* create(const EngineConfig& cfg);  // nullptr on failure (dirs not creatable)
    ~EngineSession();

    // Bayer buffer from ImageReader (strideElems = rowStrideBytes/2). Copies/persists everything it
    // needs before returning; the caller may recycle the buffer immediately.
    // Does: write cache/frame_NNNN.sraw, superpixel luma, detect stars, align vs reference
    // (incremental, previous frame's T as guess; bootstrap fallback), update live preview & mode
    // accumulators. First frame becomes the provisional reference (refined in finalize).
    // Returns per-frame stats JSON:
    // {"index":i,"saved":bool,"starCount":n,"medianFwhm":f,"bgFrac":f,"dx":f,"dy":f,"rotDeg":f,
    //  "inliers":n,"residual":f,"aligned":bool,"streaks":n}
    std::string addFrame(const uint16_t* bayer, int w, int h, int strideElems,
                         const FrameMeta& meta);

    // Measures a frame against the reference without keeping it: nothing written, nothing
    // accumulated (the autopilot's cloud checks). Cloud dims the stars below the alignment's
    // flux gate, so the matched count falls even when hot pixels keep the star count up.
    // Returns {"starCount":n,"medianFwhm":f,"bgFrac":f,"inliers":n,"aligned":bool}
    std::string checkFrame(const uint16_t* bayer, int w, int h, int strideElems,
                           const FrameMeta& meta);

    // Dark frames: accumulated into a running mean (masterDark); no alignment, not stacked.
    // Returns {"index":i,"darkCount":n,"meanLevel":f}
    std::string addDark(const uint16_t* bayer, int w, int h, int strideElems, const FrameMeta& meta);

    // Drops every dark added so far: the stack then runs without a master dark, exactly as if
    // none had been shot (too few usable darks are worse than none - they add their own noise).
    void discardDarks();

    // Marks a frame rejected before finalize (e.g. Kotlin's gyro said the exposure was bumped).
    void rejectFrame(int index, const std::string& reason);

    // Scales the current live stack (or trails composite) into the ARGB8888 buffer (outW x outH,
    // row-major, premultiplied not required — fully opaque). Quick asinh display stretch.
    // Returns false if no frame has been added yet.
    bool getPreview(uint32_t* argbOut, int outW, int outH);

    // Runs the full pipeline per PLAN.md §4 for the session's mode. optionsJson = flat StackConfig
    // (see StackConfig.kt). Writes out/final_rgb.raw (u8 RGB, W*H*3), out/master_linear16.tiff,
    // out/stack_report.json. Returns result JSON:
    // {"ok":bool,"error":"","width":w,"height":h,"finalRgbPath":"...","tiffPath":"...",
    //  "framesUsed":n,"framesTotal":n,"rejected":"idx:reason;idx:reason","rejectionPct":f,
    //  "fwhmRef":f,"fwhmStack":f,"elapsedMs":n,"meteorCount":n}
    // `progress` is called on the calling thread only, stages: "prepare","align","stack",
    // "foreground","post","write".
    std::string finalizeStack(const std::string& optionsJson, const ProgressFn& progress);

    void abort();  // makes finalizeStack return early with ok=false,error="aborted"

private:
    EngineSession() = default;

    EngineConfig cfg_;
    std::mutex mu_;                     // guards everything below (addFrame vs getPreview vs finalize)
    std::vector<FrameRecord> frames_;
    std::vector<Star> refStars_;        // provisional reference = first good frame
    int refIndex_ = -1;
    Transform prevT_;                   // last successful alignment (incremental guess)

    // Dark accumulation (full-res running sum) and count, plus the sum of each dark's own black
    // level (raster order): the master dark is subtracted relative to the baseline it was shot at.
    std::vector<float> darkSum_;
    int darkCount_ = 0;
    double darkBlackSum_[4] = {0, 0, 0, 0};

    // Live preview: running sum/count of warped superpixel RGB in reference geometry.
    ImageF liveSum_;                    // (w/2, h/2, 3)
    ImageF liveCnt_;                    // (w/2, h/2, 1)

    TrailsAccumulator trailsPreview_;   // half-res, TRAILS/METEOR preview
    MeteorDetector meteor_;

    std::atomic<bool> abort_{false};
    int frameCounter_ = 0;
    int darkCounter_ = 0;
};

// Stateless analysis for autofocus/probe frames:
// returns {"bgFrac":f,"starCount":n,"medianFwhm":f,"noiseSigma":f}
std::string analyze_frame(const uint16_t* bayer, int w, int h, int strideElems,
                          const SensorInfo& si, const float wbGain[4]);

}  // namespace starcam

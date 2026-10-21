// End-to-end synthetic validation of the whole pipeline. See PLAN.md §6.8.
#pragma once

#include <string>

namespace starcam {

// Synthesizes 16 RGGB frames (2048x1536) with known transforms, noise, hot pixels, a satellite
// streak, vignetting and a gradient; writes them as .sraw into tmpDir; runs the REAL pipeline
// (detect/align/stack/post); asserts alignment residual, hot-pixel removal, streak suppression and
// stacked star FWHM. Returns a JSON report: {"pass":bool, "checks":[...], "log":"..."} where each
// check is {"name","pass","value","limit"} (built with jsonmini::Builder + addJson for the array).
// Cleans up its temp files afterwards.
std::string run_selftest(const std::string& tmpDir);

}  // namespace starcam

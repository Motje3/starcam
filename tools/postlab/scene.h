// Synthetic night-sky scenes for judging the post pipeline (see lab.cpp).
#pragma once

#include <cstdint>
#include <vector>

namespace lab {

struct SceneOptions {
    int w = 4000, h = 3000;
    uint32_t seed = 7;
    bool foreground = true;   // dark landscape below an irregular horizon
    bool milkyWay = true;     // diagonal band with dust lanes
    bool milkyWayFlat = false; // the band lying along the horizon instead of rising from it
    bool gradient = true;     // light pollution towards the horizon and one side
    bool dome = false;        // a town's light dome: a glow over one part of the horizon only
    float noise = 1.0f;       // 1 = a 20-frame stack of 20 s ISO 1600 phone frames
};

struct TruthStar {
    float x, y, peak;  // centre (px) and peak linear brightness before noise
    float rgb[3];      // colour, max component 1
    float bv;          // B-V colour index it was drawn from
};

// Linear RGB, white = 1, channel-interleaved like the engine's ImageF, plus what is known about
// every pixel so the metrics can tell sky from ground and stars from background.
struct Scene {
    int w = 0, h = 0;
    std::vector<float> rgb;
    std::vector<float> horizonY;   // per column; ground is y > horizonY[x]
    std::vector<float> starLight;  // per pixel: star luminance before noise
    std::vector<TruthStar> stars;
    float noiseSigma = 0;          // background noise sigma (linear)
    // Milky Way centre line through (x0,y0)-(x1,y1) with Gaussian width sigma (px).
    float mwX0 = 0, mwY0 = 0, mwX1 = 0, mwY1 = 0, mwSigma = 0;
    bool hasMilkyWay = false, hasForeground = false;

    bool ground(int x, int y) const { return hasForeground && y > horizonY[x]; }
    float mwDistance(float x, float y) const;
};

Scene render_scene(const SceneOptions& o);

}  // namespace lab

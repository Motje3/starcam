// skystars.cpp — the reference frame's stars checked against the sky's own motion, and measured on
// the linear stack. See skystars.h.
//
// A star moves with the sky from frame to frame; a lit window, a lamp or a hot pixel stays where
// it is in the frame (or moves with the camera, not with the sky). Alignment already found the
// sky's motion, so each reference star is simply looked for where that motion puts it.

#include "skystars.h"

#include "json_mini.h"

namespace starcam {
namespace {

constexpr double kEdgePx = 4.0;      // luma px: a prediction this near the edge is not looked for
constexpr double kMatchPx = 1.5;     // luma px: a star this near the prediction is the same star
constexpr double kStaticMovePx = 3.0;  // luma px: frames moving it further than this test its old spot
constexpr int kBlownArea = 60;       // detection area (luma px) from which a star is blown out
constexpr int kWidthStars = 50;      // brightest stars the aperture's width is taken from

bool any_within(const std::vector<Star>& s, double x, double y, double r) {
    const double r2 = r * r;
    for (const Star& t : s) {
        const double dx = (double)t.x - x, dy = (double)t.y - y;
        if (dx * dx + dy * dy <= r2) return true;
    }
    return false;
}

double round2(double v) { return std::round(v * 100.0) / 100.0; }

}  // namespace

float effective_fwhm(const ImageF& luma, float x, float y) {
    const int R = 9;
    const int x0 = (int)std::lround(x), y0 = (int)std::lround(y);
    if (x0 - R < 0 || y0 - R < 0 || x0 + R >= luma.w || y0 + R >= luma.h) return 0.0f;
    std::vector<float> ring;
    ring.reserve(256);
    for (int py = y0 - R; py <= y0 + R; ++py) {
        for (int px = x0 - R; px <= x0 + R; ++px) {
            const float d = std::hypot((float)px - x, (float)py - y);
            if (d >= 6.0f && d <= 9.0f) ring.push_back(luma.d[(size_t)py * luma.w + px]);
        }
    }
    const float bg = median_of(ring);
    float peak = 0.0f, flux = 0.0f;
    for (int py = y0 - 5; py <= y0 + 5; ++py) {
        for (int px = x0 - 5; px <= x0 + 5; ++px) {
            const float d = std::hypot((float)px - x, (float)py - y);
            if (d > 5.0f) continue;
            const float v = luma.d[(size_t)py * luma.w + px] - bg;
            flux += v;
            if (d <= 1.5f) peak = std::max(peak, v);
        }
    }
    if (!(peak > 0.0f) || !(flux > peak)) return 0.0f;
    return 2.355f * std::sqrt(flux / (2.0f * (float)M_PI * peak));
}

void stack_luma_half(const ImageF& rgb, ImageF* luma) {
    if (luma == nullptr) return;
    if (rgb.empty() || rgb.ch != 3 || rgb.w < 2 || rgb.h < 2) {
        *luma = ImageF();
        return;
    }
    luma->init(rgb.w / 2, rgb.h / 2, 1);
    const int lw = luma->w;
    parallel_for(0, luma->h, [&](int y) {
        for (int x = 0; x < lw; ++x) {
            float s = 0.0f;
            for (int dy = 0; dy < 2; ++dy) {
                for (int dx = 0; dx < 2; ++dx) {
                    const float* p = rgb.px(2 * x + dx, 2 * y + dy);
                    s += p[0] + p[1] + p[2];
                }
            }
            luma->d[(size_t)y * lw + x] = s * (1.0f / 12.0f);
        }
    });
}

std::vector<SkyStar> verify_sky_stars(const std::vector<FrameRecord>& frames, int refPos,
                                      int lumaW, int lumaH) {
    std::vector<SkyStar> out;
    if (refPos < 0 || refPos >= (int)frames.size()) return out;
    const std::vector<Star>& ref = frames[(size_t)refPos].stars;
    out.resize(ref.size());
    for (size_t k = 0; k < ref.size(); ++k) {
        out[k].x = ref[k].x;
        out[k].y = ref[k].y;
        out[k].area = ref[k].area;
    }
    const double xMax = (double)(lumaW - 1) - kEdgePx, yMax = (double)(lumaH - 1) - kEdgePx;
    for (int i = 0; i < (int)frames.size(); ++i) {
        const FrameRecord& fr = frames[(size_t)i];
        if (i == refPos || fr.rejected || !fr.aligned) continue;
        for (SkyStar& s : out) {
            double px, py;
            fr.T.apply((double)s.x, (double)s.y, &px, &py);
            // Written as "inside" tests so a NaN position counts as outside.
            if (px >= kEdgePx && py >= kEdgePx && px <= xMax && py <= yMax) {
                ++s.tried;
                if (any_within(fr.stars, px, py, kMatchPx)) ++s.seen;
            }
            const double moved = std::hypot(px - (double)s.x, py - (double)s.y);
            if (moved > kStaticMovePx) {
                ++s.staticTried;
                if (any_within(fr.stars, (double)s.x, (double)s.y, kMatchPx)) ++s.staticSeen;
            }
        }
    }
    for (SkyStar& s : out) {
        // ceil(0.6 tried) and "0.6 of the static tries" in integers: 0.6 * 5 is not 3 in floats.
        const int need = s.tried >= 3 ? (3 * s.tried + 4) / 5 : s.tried;
        s.isStatic = s.staticTried >= 2 && 5 * s.staticSeen >= 3 * s.staticTried &&
                     s.staticSeen > s.seen;
        s.sky = s.tried >= 1 && s.seen >= need && !s.isStatic;
    }
    return out;
}

void measure_stars(const ImageF& stackRGB, const ImageF& stackLumaHalf, float fallbackFwhmRaw,
                   std::vector<SkyStar>* stars, StarPhotometry* phot) {
    if (stars == nullptr || phot == nullptr) return;
    *phot = StarPhotometry();
    // Each star's own width, and the median of the brightest clear ones (sky-verified when there
    // are enough: lamps and windows have widths of their own).
    std::vector<float> widths, widthsAll;
    for (SkyStar& s : *stars) {
        s.fwhm = stackLumaHalf.empty() ? 0.0f : 2.0f * effective_fwhm(stackLumaHalf, s.x, s.y);
        if (s.fwhm > 0.0f && s.area < kBlownArea) {
            if (s.sky && (int)widths.size() < kWidthStars) widths.push_back(s.fwhm);
            if ((int)widthsAll.size() < kWidthStars) widthsAll.push_back(s.fwhm);
        }
    }
    float fwhm = widths.size() >= 5 ? median_of(widths) : median_of(widthsAll);
    if (!(fwhm > 0.0f)) fwhm = fallbackFwhmRaw;
    if (!(fwhm > 0.0f) || !std::isfinite(fwhm)) fwhm = 6.0f;
    const float R = clampf(1.4f * fwhm, 6.0f, 14.0f), Rin = R + 4.0f, Rout = R + 10.0f;
    phot->fwhm = fwhm;
    phot->radius = R;
    phot->annulusIn = Rin;
    phot->annulusOut = Rout;
    if (stackRGB.empty() || stackRGB.ch != 3) return;

    const double R2 = (double)R * R, in2 = (double)Rin * Rin, out2 = (double)Rout * Rout;
    std::vector<float> ann[3];
    for (SkyStar& s : *stars) {
        const double cx = 2.0 * (double)s.x + 0.5, cy = 2.0 * (double)s.y + 0.5;
        const int x0 = floor_clamp(cx - Rout, -1, stackRGB.w), x1 = ceil_clamp(cx + Rout, -1, stackRGB.w);
        const int y0 = floor_clamp(cy - Rout, -1, stackRGB.h), y1 = ceil_clamp(cy + Rout, -1, stackRGB.h);
        if (x0 < 0 || y0 < 0 || x1 >= stackRGB.w || y1 >= stackRGB.h) continue;  // left the picture
        double sum[3] = {0.0, 0.0, 0.0};
        int nAp = 0;
        float peak = 0.0f;
        for (int c = 0; c < 3; ++c) ann[c].clear();
        for (int py = y0; py <= y1; ++py) {
            const double dy2 = ((double)py - cy) * ((double)py - cy);
            for (int px = x0; px <= x1; ++px) {
                const double d2 = ((double)px - cx) * ((double)px - cx) + dy2;
                const float* p = stackRGB.px(px, py);
                if (d2 <= R2) {
                    for (int c = 0; c < 3; ++c) sum[c] += (double)p[c];
                    peak = std::max(peak, std::max(p[0], std::max(p[1], p[2])));
                    ++nAp;
                } else if (d2 >= in2 && d2 <= out2) {
                    for (int c = 0; c < 3; ++c) ann[c].push_back(p[c]);
                }
            }
        }
        if (nAp == 0 || ann[1].size() < 16) continue;
        float bg[3];
        for (int c = 0; c < 3; ++c) bg[c] = median_of(ann[c]);
        // The annulus's own noise, from its green: the spread a flux is measured against.
        for (float& v : ann[1]) v = std::fabs(v - bg[1]);
        const float sigma = 1.4826f * median_of(ann[1]);
        s.r = sum[0] - (double)bg[0] * nAp;
        s.g = sum[1] - (double)bg[1] * nAp;
        s.b = sum[2] - (double)bg[2] * nAp;
        s.flux = (s.r + 2.0 * s.g + s.b) * 0.25;
        s.peak = peak;
        s.sat = peak >= 0.9f;
        s.snr = sigma > 0.0f ? (float)(s.g / ((double)sigma * std::sqrt((double)nAp))) : 0.0f;
        for (int c = 0; c < 3; ++c) s.bgRGB[c] = bg[c];
        s.measured = true;
    }
    for (int c = 0; c < 3; ++c) {
        std::vector<float> v;
        for (const SkyStar& s : *stars)
            if (s.sky && s.measured) v.push_back(s.bgRGB[c]);
        phot->skyBg[c] = median_of(v);
    }
}

std::string stars_json(const std::vector<SkyStar>& stars, const StarPhotometry& phot, int stackW,
                       int stackH, int rotation, int framesUsed) {
    std::vector<size_t> order(stars.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::stable_sort(order.begin(), order.end(), [&stars](size_t a, size_t b) {
        if (stars[a].measured != stars[b].measured) return stars[a].measured;
        return stars[a].flux > stars[b].flux;
    });
    std::string arr = "[";
    for (size_t k = 0; k < order.size(); ++k) {
        const SkyStar& s = stars[order[k]];
        double ox, oy;
        rotated_point(rotation, stackW, stackH, 2.0 * (double)s.x + 0.5, 2.0 * (double)s.y + 0.5,
                      &ox, &oy);
        const double bgL = 0.25 * ((double)s.bgRGB[0] + 2.0 * (double)s.bgRGB[1] + (double)s.bgRGB[2]);
        jsonmini::Builder sb;
        sb.add("x", round2(ox))
            .add("y", round2(oy))
            .add("flux", s.flux)
            .add("r", s.r)
            .add("g", s.g)
            .add("b", s.b)
            .add("peak", (double)s.peak)
            .add("snr", (double)s.snr)
            .add("fwhm", round2((double)s.fwhm))
            .add("bg", bgL)
            .add("seen", s.seen)
            .add("tried", s.tried)
            .add("moved", s.staticTried)
            .add("sat", s.sat)
            .add("sky", s.sky);
        if (k) arr += ",";
        arr += sb.build();
    }
    arr += "]";
    int outW, outH;
    rotated_size(rotation, stackW, stackH, &outW, &outH);
    jsonmini::Builder b;
    b.add("version", 1)
        .add("width", outW)
        .add("height", outH)
        .add("rotation", rotation)
        .add("frames", framesUsed)
        .add("fwhm", round2((double)phot.fwhm))
        .add("aperture", (double)phot.radius)
        .add("annulus", std::vector<double>{(double)phot.annulusIn, (double)phot.annulusOut})
        .add("skyBg", std::vector<double>{(double)phot.skyBg[0], (double)phot.skyBg[1],
                                          (double)phot.skyBg[2]})
        .addJson("stars", arr);
    return b.build();
}

}  // namespace starcam

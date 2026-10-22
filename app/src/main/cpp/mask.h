// Sky/ground separation and composite (beta). See PLAN.md §6.5.
#pragma once

#include "common.h"

namespace starcam {

// skyStack: star-aligned stack; groundStack: identity-aligned stack. Same size, 3ch, linear.
// Builds a sky mask on skyStack (1/8-res luma -> Otsu -> flood fill from top edge -> morphological
// close(5) -> upsample -> feather by featherPx), normalizes exposure between the two inputs on the
// mask boundary band, then blends: out = mask*sky + (1-mask)*ground.
// outMaskOptional (nullable): receives the feathered mask (1ch) for diagnostics.
// downEdge: the image edge that faces the ground (0 bottom, 1 left, 2 top, 3 right); the sky is
// seeded from the opposite edge, so a photo shot with the phone upright (sensor image on its side)
// composites too. Defaults to a landscape-held phone.
void composite_foreground(const ImageF& skyStack, const ImageF& groundStack, float featherPx,
                          ImageF* outComposite, ImageF* outMaskOptional, int downEdge = 0);

}  // namespace starcam

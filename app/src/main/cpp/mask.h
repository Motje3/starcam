// Sky/ground separation and composite (beta). See PLAN.md §6.5.
#pragma once

#include "common.h"

namespace starcam {

// skyStack: star-aligned stack; groundStack: identity-aligned stack. Same size, 3ch, linear.
// Builds a sky mask on skyStack (1/8-res luma -> Otsu -> flood fill from top edge -> morphological
// close(5) -> upsample -> feather by featherPx), normalizes exposure between the two inputs on the
// mask boundary band, then blends: out = mask*sky + (1-mask)*ground.
// outMaskOptional (nullable): receives the feathered mask (1ch) for diagnostics.
void composite_foreground(const ImageF& skyStack, const ImageF& groundStack, float featherPx,
                          ImageF* outComposite, ImageF* outMaskOptional);

}  // namespace starcam

# Manga Test Image Factory — Design Notes

## Purpose
Provide deterministic, inspectable manga-style test images so inpainting behavior can be verified without depending on device screenshots.

## Image families
1. White bubble on gray background.
2. Colored bubble on dark background.
3. Complex background with thin lines near text.
4. English text with multiple short lines.
5. Japanese colored page with illustration line near text.
6. Korean page with accent-colored bubble and decorative border.

## What each image provides
- Bitmap with ink drawn in expected text rectangles.
- Expected bubble rectangles.
- Expected "preserved regions" outside text that should remain identical after inpainting.
- Multiple text line rectangles so multi-line mask behavior can be checked.

## Validation rules
- Text pixels inside mask area should be removed or strongly reduced.
- Pixels outside mask should remain identical.
- Nearby illustration/border lines should remain intact if not covered by mask.

## Notes
- This is still instrumentation-level proof, not device screenshot proof.
- Once device is available, these families should be compared against real manga samples.

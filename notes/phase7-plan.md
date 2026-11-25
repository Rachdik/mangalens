# PHASE 7 WORK PLAN

## Current focus
1. Inpainting: reusable test-image factory for Japanese / Korean / English manga samples, and verification that Reader uses inpainted result instead of overlay fallback.
2. Advanced Settings: complete the seven additional color settings with HEX input, live preview, persist, and consistent theming across Reader/Floating.

## Constraints
- No device or emulator with system image is currently usable.
- Proof must come from unit-level/image-processing-level checks, build, and internal contracts, not from "it built".
- Do not finish only because build succeeded.

## Inpainting work
- Add deterministic bitmap generators that represent common manga cases:
  - white bubble background
  - colored bubble background
  - background with screentone/lines
  - text crossing an illustration line
- Keep each generator deterministic and inspectable so we can assert "before" and "after" properties without relying on device screenshots.
- Continue to assert:
  - original text pixels are gone in mask area
  - pixels outside mask are unchanged
  - illustration/border pixels near text remain intact
  - mask does not swallow unrelated nearby text unnecessarily
- Add tests that exercise MangaPageTranslator mask creation + BasicInpaintingEngine together, including multi-region text on same image.
- Confirm Reader path contract:
  - processImageFile sets inpaintedDataUrl when inpaintingEnabled and image is single-slice
  - result bubbles use inpainted coordinates
  - ReaderActivity uses inpaintedDataUrl to replace image src before overlay
- Add a clear fallback guarantee: when inpaintingEnabled is false, pipeline must not produce inpaintedDataUrl and overlay path must still work.

## Advanced Settings work
- Add 7 color settings with a shared theme/color store so Reader and Floating read the same values:
  - Primary Color
  - Background Color
  - Surface Color
  - Text Color
  - Secondary Text Color
  - Floating Button Color
  - Overlay Background Color
- Each setting should support:
  - visual color picker
  - HEX input
  - live preview
  - apply
  - reset to default
  - persistence across process restart
- Keep existing accent picker behavior intact.
- Ensure theme consumers read colors from the same source instead of hardcoded duplicates.

## Validation rules
- Any new feature must compile and must not weaken existing checks.
- New tests must check real behavior, not fake success.
- Document status honestly: PASS only if verified, UNTESTED if not verifiable without device, PARTIAL if implemented but not yet fully validated.

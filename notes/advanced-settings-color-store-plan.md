# Advanced Color Settings — Implementation Plan

## Goal
Complete seven additional app color settings with picker, HEX input, live preview, apply, reset, persistence, and consistent theming across Reader and Floating.

## Colors to add
1. Primary Color
2. Background Color
3. Surface Color
4. Text Color
5. Secondary Text Color
6. Floating Button Color
7. Overlay Background Color

## Storage approach
- Use a shared color preferences store so Reader, Floating, and app UI read the same values.
- Persist as hex strings for portability and clear debugging.
- Keep accent and mode in AppTheme/ThemeStore as they already exist.

## UX behavior
- Each color has a visual picker with live preview.
- HEX input is also available.
- Apply runs immediately and persists.
- Reset restores a sensible default for that role.
- Preview reflects applied value in the same settings screen.

## Consistency rules
- Reader must read colors from the same store.
- Floating bubble/overlay colors must read from the same store.
- Avoid duplicating hardcoded colors per Activity.

## Validation
- Confirm build passes.
- Add quick checks that stored values round-trip and default values exist.
- Do not claim visual success without device screenshots later.

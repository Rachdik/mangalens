Phase 7 in-progress note.

Done so far:
- Wrote deterministic manga-style test image factory and instrumentation suite for inpainting.
- Added AppColorStore + AppColorValues for advanced color settings.
- Kept AppTheme intact for accent/mode.

Still pending:
- Run instrumentation suite once device/emulator or suitable test harness is available.
- Wire advanced color roles into Settings UI.
- Propagate new colors into Reader and Floating consumers.
- Confirm persistence and default values.

Constraints:
- No device / emulator with system image right now.
- Proof must come from build + internal checks, not from visual claim.

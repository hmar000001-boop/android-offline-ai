> HISTORICAL R1.5.2 REVIEW — this describes the old policy that existed before R6. It is not the current implementation. R6 safety limits are documented in README.md, SECURITY.md and MODEL_GOVERNANCE.md.

# 1.5.2 - fixes from the deep code review and the full-screen-blur incident

Not validated: no Gradle build, unit test or emulator run happened when these changes were written. Run the workflow and a physical-device pass (`DEVICE_VALIDATION.md`) before relying on them.

## Root causes of the whole-screen blur
1. Every person detected by the specialist profiles was `UNCERTAIN` and "redact uncertain" is on by default, so a portrait, video call or full-screen photo covered the whole window.
2. The Gemma prompt asks for `[0,0,1000,1000]` for full-screen media and nothing capped coverage.
3. The nine-tile ViT fallback unions to the entire screen.
4. There was no emergency exit, and the overlay can sit on top of the notification shade.

## Changes
| Area | Change |
|---|---|
| Coverage | `CoveragePolicy`: unconfirmed regions covering 70% of the window or more are dropped; unconfirmed regions are trimmed until union coverage is under 85%. Confirmed swimwear/suggestive/explicit may still fill the window. |
| Dwell | Whole-window coverage for 30 s pauses protection with a notification. |
| People | New `PERSON_LOW_RISK` decision for low-scoring people, covered only with the new strict switch. `UNCERTAIN` now needs an ensemble score of at least 0.25 (uncalibrated). |
| Escape | Ongoing notification with Stop; Quick Settings tile; masks cleared while the shade/lock screen is active or no app window is active; paused alert when protection stops for a technical reason. |
| Blur | Blur radius and pixel block scale with region size (`RedactionStrength`). |
| Gemma | More than 24 regions keeps the 24 most sensitive instead of pausing protection; up to 64 accepted. Prompt marks on-screen text as untrusted. Profile text says it is experimental and steerable. |
| Downloads | GitHub wildcard hosts removed. `scripts/verify-model-pins.py` and a manual CI job re-download every artifact and compare SHA-256. |
| Release | `release` build type with R8, shrinker and optional env-based signing; separate CI job. Debug APK artifact is renamed NOT-FOR-DAILY-USE. |
| Diagnostics | Events-and-counts-only on-device log with Copy/Clear buttons. Abandoned frame files are also removed when the service binds. |
| Docs | `SECURITY.md` failure section rewritten; hotfix document corrected. |

## Known remaining limits
- Masks still disappear on scroll and window change until the next analysis.
- The Gemma profile can be steered by on-screen text.
- Model provenance cannot be proven by a hash; two classifiers come from individual community accounts.
- The 0.25 / 0.82 thresholds, the 70% / 85% coverage limits and the 30 s dwell are not calibrated on a real device.
- The system-panel detection assumes the shade is a focused `TYPE_SYSTEM` window covering 40% or more of the display; this needs device confirmation.
- Release R8 keep rules are conservative but untested against the real runtimes.
- 2026-11-03 CI: the unit test that asserted rejection of more than 24 regions was rewritten for the new keep-24-most-sensitive behavior.

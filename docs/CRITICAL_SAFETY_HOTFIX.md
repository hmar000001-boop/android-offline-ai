# Critical safety hotfix — protection startup and overlay lifecycle

## Incident root causes confirmed in source

The previous release had several paths that could leave an entire app window covered:

1. `protection_enabled` defaulted to `true`. `MainActivity.beginProtection()` persisted `true` before opening Android Accessibility settings. The service then started automatically from `onServiceConnected()` and `MainActivity.onResume()`.
2. `startProtection()`, model loading, first analysis, window changes, scrolling and every content-change event could call `coverMasks()`, which replaced localized masks with a full-window opaque bitmap.
3. Model initialization and repeated model/capture failures deliberately retained that opaque cover. After three model failures the circuit breaker stopped inference but left the cover in place.
4. The specialist detector could return a full-screen region for a global NSFW score or more than 24 detected people.
5. The overlay renderer promoted an unmappable individual box into a full-window cover.

## Changes in this hotfix

- Protection now defaults OFF. A one-time preference migration forcibly disables protection when upgrading from the old lifecycle.
- Enabling Accessibility does not start protection. Returning from Android settings does not start it. The user must return to ScreenVeil and tap **Start protection** after enabling Accessibility.
- Model downloads never start protection automatically.
- Rebinding the Accessibility service starts in the OFF state.
- Startup and model loading do not draw an opaque cover.
- Window, scroll and content changes clear only localized masks and debounce a fresh screenshot. They never add a global cover.
- Model initialization errors immediately pause protection and clear the overlay.
- Capture errors preserve only the last localized masks while retrying; after five consecutive errors protection pauses and the overlay clears.
- Three consecutive inference errors pause protection and clear the overlay.
- Overlay rendering failure pauses protection rather than showing an opaque fallback.
- Invalid/unmappable local boxes are never promoted to a full-window mask. If a requested redaction cannot be rendered, protection pauses and the overlay is cleared.
- The specialist pipeline no longer turns a whole-screen NSFW score or crowded-person result into a global mask. It limits per-person analysis and reports truncation.
- Service interruption, unbind and shutdown disable the persisted protection flag and clear the overlay.

## Deliberate safety/usability trade-off

When protection starts, during screen reflow, or after a technical failure, content may temporarily be visible without a redaction while the next analysis runs. This is intentional: ScreenVeil must not lock the device or obscure unrelated UI because of a model or capture failure. The UI reports errors and pauses after bounded retries. The app does not claim uninterrupted frame-by-frame protection.

## Release status

Version 1.5.1 / versionCode 7 (upgrade over the previous 1.5.0 / versionCode 6 package). This document records source changes only. Android Gradle build, instrumentation tests, emulator validation and physical-device validation have not yet been run for this hotfix.

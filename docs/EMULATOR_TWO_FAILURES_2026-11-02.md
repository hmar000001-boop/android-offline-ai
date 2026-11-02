# Emulator follow-up: debounce and FLAG_SECURE — 2026-11-02

## Observed API 34 result

The previous revision compiled both APKs and executed all 18 instrumentation tests. Sixteen passed. Two failed:

- `repeatedContentChangeEventsAreDebouncedToOneCapture`: the test saw its injected frame counter increment, then slept 350 ms and expected the overlay to have returned.
- `androidSecureWindowScreenshotDenialPausesWithoutOverlay`: the test did not observe the secure-window-specific pause within 20 seconds.

## Debounce failure analysis and correction

The frame-provider counter increments before `dispatchFrame`, inference, overlay preparation, and the main-thread overlay commit. It is therefore a capture-start signal, not a pipeline-completion signal. Sleeping for a fixed 350 ms did not establish that inference and the overlay commit had finished. The test now waits for all of the following: a second detector inspection, active protection, one redacted region, and the redaction window. It also waits for the initial screenshot/inference/render pipeline to become idle, then removes the periodic capture callback so a periodic frame cannot be miscounted as a second event-storm capture.

This is a test synchronization correction. It does not weaken the requirement that the mask is restored after a valid analysis.

## Secure-window failure analysis and correction

Android's `AccessibilityService` API defines `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` as error code `6`, meaning the target window contains secure content protected by `WindowManager.LayoutParams.FLAG_SECURE`.

The previous implementation routed this permanent platform refusal through the generic capture-failure retry counter. The new behavior handles this specific error immediately: protection is stopped, the persisted ON flag is reset, callbacks are removed, and the localized overlay is cleared. Transient screenshot failures still use the bounded retry path.

The secure fixture setup is also more deterministic:

- Keep launching the fixture through ActivityManager because it lives in the instrumentation APK, not in the target app process; wait for its Activity class to appear in the current-focus window entry.
- The fixture computes whether its runtime `WindowManager.LayoutParams` actually contains `FLAG_SECURE` after applying the intent extra. It exposes a confirmation marker in its own accessibility text only when that bit is present, and the test verifies that marker before starting capture.
- After the service binds, dispatch a foreground window-state event for the fixture so the service's own-app exclusion guard cannot mistake the test runner's previous foreground package for the target.
- If the test still times out, report the protection status, current focused window, foreground package, selected target window ID/bounds, screenshot request/success/failure counts, last callback failure and mask generation.

The diagnostic fields are observational only; they do not participate in release-build control flow. The test hook that disables periodic capture remains guarded by `BuildConfig.DEBUG`.

## Validation status

- Source-level checks: completed for the packaged revision (XML/YAML parsing, shell syntax, archive integrity, test-definition count and targeted source assertions).
- Android Gradle compilation: not run in this environment (no configured Android SDK/Gradle distribution).
- API 34 and API 36 execution: required in GitHub Actions.
- No APK installation or real-device validation is authorized by this result.

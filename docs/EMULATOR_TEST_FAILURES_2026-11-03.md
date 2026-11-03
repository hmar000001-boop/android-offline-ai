# Emulator instrumentation follow-up — 2026-11-03

This change addresses two failures observed after the instrumentation suite successfully compiled and ran 18 tests on the API 36 emulator.

## 1. `repeatedModelFailuresStopAndClearAnExistingOverlay`

The prior fake detector returned one valid result and threw on every later call. The service can request another screenshot due to real Accessibility events or its capture schedule. That second inference could fail before the test thread observed the first overlay, making the test race its own setup.

The detector now keeps returning the valid localized result until the test has observed an active status with one redacted region and the overlay window. Only then does the test enable the injected failures and explicitly request another screenshot. The wait failure now reports detector calls, capture count, protection status, and service diagnostics. This makes the test validate the intended sequence: valid overlay first, repeated failures second, safe stop and overlay removal last.

## 2. `androidSecureWindowScreenshotDenialPausesWithoutOverlay`

The instrumentation APK keeps its fixture Activity across test cases. Merely launching the same component with a different `secure` extra does not guarantee that `onCreate()` runs on a reused Activity instance. The previous fixture only applied `FLAG_SECURE` in `onCreate()`, so a focused reused Activity could publish flags from the earlier non-secure launch.

Fixture launch now clears and recreates its task using `--activity-clear-task --activity-new-task`, and the manifest uses `singleTop`. The Java fixture also handles `onNewIntent()`, re-applies or clears `FLAG_SECURE` from the new Intent, rebuilds its visible content, and republishes the actual Window flags. The test still requires the runtime report to prove that `FLAG_SECURE` is set before it expects Android's screenshot API to reject capture.

## Verification status

Static checks performed on the source tree: fixture references and Java declarations inspected; shell scripts syntax-checked; test source inspected for the updated injection ordering; ZIP integrity checked.

Not run in this environment: Gradle compilation, Android instrumentation, API 34/API 36 emulator tests, and real Galaxy S24 FE tests. CI must confirm those. The emulator-console warning in the supplied log did not prevent the emulator from running tests; the two reported failures were test assertions, not emulator startup failures.

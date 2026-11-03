# API 34/36 explicit Stop instrumentation failure — source analysis and fix

## Observed failure

Both API 34 and API 36 runs fail on the same test:

`ProtectionLifecycleInstrumentedTest.explicitStopWhileLocalizedOverlayIsVisibleRemovesItImmediately`

The test runner then reports `Instrumentation run failed due to Process crashed`. The accompanying excerpt does not include the fatal Java exception, native signal, tombstone, or full test assertion stack. Therefore the exact crash signature is not proven from the supplied excerpt. The emulator-console warning is not itself the cause shown by this log: the emulator starts the test APK and executes tests before the failure.

## Reuse of the previous R5 test correction

The earlier R5 correction made `repeatedModelFailuresStopAndClearAnExistingOverlay` deterministic by keeping the fake detector successful until the test had observed a fully active state with one redacted region and an overlay. Only then did it inject failures. This avoids ambient periodic/framework-triggered work racing the test's observation.

The R6 explicit Stop test previously waited only for the WindowManager title to appear. That proves a window was registered, but not that model analysis completed, the expected redaction state was published, a frame was submitted for rendering, or the asynchronous capture/inference pipeline was quiescent.

The R6 test now waits for all of the following before Stop:

- `state.active` and `state.modelReady`;
- exactly one redacted region and a non-null end-to-end duration;
- the `ScreenVeil redaction` WindowManager entry;
- an overlay frame-commit signal from `ViewTreeObserver.registerFrameCommitCallback()` on hardware rendering (with a two-animation-callback fallback).

It then enters the existing isolated accessibility-event test mode, waits for capture/inference and known Handler callbacks to become idle, and posts a main-thread barrier before requesting Stop. This follows the established R5 determinism pattern instead of relying on a fixed sleep.

## Production lifecycle defect corrected

The R6 `OverlayController` previously recycled the bitmap displayed by its `ImageView` after an arbitrary 200 ms delay. `clear()` removed the overlay and then scheduled that recycle; replacing the bitmap also scheduled a delayed recycle of the previous frame.

That delay is not a valid guarantee that Android's View/HWUI renderer has stopped referring to the bitmap. Android's `Bitmap.recycle()` reference explicitly says to avoid calling it on bitmaps used by a View or otherwise referenced elsewhere. It also explains that hardware-accelerated drawing can cache draw commands, and that a draw pass after invalidation is needed before the bitmap is known to be unused. See the Android API reference: https://developer.android.com/reference/android/graphics/Bitmap and ViewTreeObserver frame-commit API: https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable).

The fix is intentionally conservative:

- Do not explicitly recycle a bitmap after it has been supplied to an `ImageView`.
- On clear, detach the drawable, remove the overlay view, and allow Java/Android rendering references to drain; ordinary garbage collection reclaims the backing memory when safe.
- On replacement, let the old view drawable become unreachable without calling `Bitmap.recycle()` on it.
- Keep explicit recycling only for intermediates used synchronously in software bitmap composition, and a prepared bitmap rejected before it is ever supplied to a View (for example, missing overlay permission).
- In the service's `!shown` failure branch, do not recycle the prepared bitmap. `showPrepared()` may return false after the bitmap has already been assigned to a View and the later WindowManager update failed. The controller detaches the view; the service must not race HWUI by recycling the bitmap again.
- Add a monotonically increasing frame-commit generation. A late callback for a previous frame or a cleared overlay cannot mark a newer/removed overlay as committed.

This is a lifetime fix; it does not relax the redaction policy, alter a coverage limit, or create a rectangular fallback.

## Failure diagnostics added

If `connectedDebugAndroidTest` fails, `scripts/run-emulator-tests.sh` now saves the following under `build/emulator-failure-diagnostics/` before the emulator runner tears the emulator down:

- all-buffer and crash-buffer logcat;
- ActivityManager process exit information for target and instrumentation packages;
- DropBox app and native crash records;
- activity/process, WindowManager, accessibility, memory and PID dumps.

The workflow uploads this directory as part of the API-specific emulator report artifact. These diagnostics are necessary because the supplied summary does not contain the actual fatal signal/exception.

## Scope and validation boundary

No model, model pin, Gradle dependency, coverage threshold, timeout, redaction category, or release setting was changed. The change is limited to overlay bitmap lifetime, stale frame-commit suppression, deterministic explicit-Stop test synchronization, and CI failure diagnostics.

Static source and script checks can be run locally. This environment does not have an Android SDK or configured Gradle installation, so API 34/API 36 instrumentation execution is not claimed here. The next CI run should either pass the corrected lifecycle test or upload the crash artifacts needed to identify a different remaining failure.

# ScreenVeil delivery validation — 2026-11-01

## Source changes in this revision

- Window changes, scrolling and all window-content-change events invalidate old geometry and replace the previous mask with an opaque cover over the active application window until a fresh analysis is committed.
- Gemma model initialization failures no longer silently turn protection off. While the service remains connected and the overlay can be displayed, an opaque cover is retained; the user must explicitly Stop → Start protection to retry initialization.
- Invalid structured output, inference exceptions, screenshot-buffer conversion errors, non-rate-limit screenshot failures and overlay commit failures use an opaque fallback and bounded retry instead of calling `pauseProtection()`.
- After three consecutive model/structured-output failures, a model circuit breaker stops repeated expensive inference attempts, closes the failed engine, retains the opaque screen cover and requires the user to stop/restart protection to retry initialization. This avoids an endless hot failure loop without revealing the underlying app.
- An accessibility interruption no longer acts like an explicit user stop; it retains an opaque cover while the service remains connected.
- The 1×1 opaque-cover bitmap allocation is guarded so allocation/rendering failures are reported as critical rather than escaping the fallback method.
- Android screenshot rate-limit responses retain the last validated overlay and use a single retry callback.
- The overlay fallback uses a 1×1 opaque bitmap stretched to the target window, avoiding a full-resolution bitmap allocation on the UI thread.
- The model response parser accepts up to 24 regions instead of 8, and the constrained-generation output budget is increased to 1,024 tokens. Excessive/malformed output is rejected and routed to the opaque fallback rather than truncated.
- UI status and privacy notes explain that an opaque cover may temporarily hide the app during transitions or errors.
- The status panel now exposes both model inference duration and capture-request-to-overlay-commit duration to support real device latency measurements.
- Added parser unit cases for more than eight regions and for the 24-region cap.

## Checks actually executed in this environment

- The source ZIP and working tree were inspected.
- `MaskGeometry.kt` was compiled with the installed Kotlin compiler against a minimal `NormalizedBox` stub. Six geometry smoke assertions passed: full-frame mapping, independent axis scaling, outward rounding, degenerate-box rejection, invalid-dimension rejection and an additional bounds check.
- The `DetectionResponseParser` Kotlin fragment passed a compile/type-check against minimal `org.json` API stubs. This is only a syntax/type check; it does not exercise JSON parsing behavior.
- The GitHub Actions YAML parsed successfully, all five Android XML files are well-formed, the Gradle bootstrap script passes `bash -n`, and 18 JUnit test methods are present in source.
- The Android manifest and XML resources were checked for well-formedness; no XML resources were changed in this revision.
- Source inspection confirms automatic inference-failure calls to `pauseProtection()` have been removed. The explicit three-consecutive-model-failure circuit breaker retains an opaque cover instead of pausing protection.
- The pinned build configuration was checked against the official Kotlin/Android Gradle compatibility tables: Kotlin Gradle Plugin 2.4.21 supports AGP 8.5.2–9.3.1 and Gradle 7.6.3–9.7.0; AGP 8.13 requires Gradle 8.13 or newer. The configured AGP 8.13.2 / Kotlin 2.4.21 / Gradle 8.14.6 combination is within those published ranges.

## Checks not executed — do not treat as passed

- `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` was attempted in this environment. The pinned Gradle bootstrap could not resolve `services.gradle.org` (`curl: (6) Could not resolve host`), so the Gradle build never started. Android SDK Platform 36 / Build Tools 36.0.0 are also not installed here.
- Consequently, the full 18 JUnit tests, Android Lint and APK assembly have not been executed in this environment. The new parser tests are included in source but remain unrun until Gradle dependencies can be resolved.
- No emulator or physical Galaxy S24 FE was available. Screenshot capture, overlay coordinates, One UI lifecycle, LiteRT-LM GPU/CPU fallback, latency, memory, battery and thermals remain device-unvalidated.
- No representative image/video dataset has been run. There is no measured false-negative/false-positive rate, box IoU, swimwear recall, or real-time/video guarantee.
- Only Gemma 4 E2B is an approved integrated model. ScreenParser, NSFWJS, Electblake and NudeNet remain unintegrated candidates; no multi-model selector or fast specialized pipeline is included. They must not be shown as operational choices until adapters, pinned artifacts, licenses and device benchmarks pass.

## Remaining release gates

1. Run `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` on a networked machine with JDK 21 and Android SDK Platform 36 / Build Tools 36.0.0; fix every compiler, test and lint issue.
2. Install and exercise the resulting APK on a Galaxy S24 FE, including window changes, scrolls, split-screen, secure windows, service lifecycle, overlay alignment and forced failure cases.
3. Benchmark Gemma initialization/inference on GPU and CPU, peak memory, battery and thermal behavior during sustained use.
4. Evaluate a labelled lawful dataset for media-region recall, swimwear recall, suggestive/explicit recall, false positives and bounding-box IoU. Valid model false negatives remain possible even when the opaque failure fallback works correctly.
5. Integrate a specialized model pipeline only after model artifact provenance, licensing, input preprocessing, Android runtime compatibility and S24 FE performance are verified.

## Release classification

This archive is an updated experimental source build, not a verified release APK or production-ready safety filter. The opaque fallback handles technical errors and notified layout transitions; it cannot guarantee detection of visually missed media or content that changes without a timely accessibility event. Do not claim continuous real-time or complete swimwear/adult-content detection without physical-device and dataset evidence.

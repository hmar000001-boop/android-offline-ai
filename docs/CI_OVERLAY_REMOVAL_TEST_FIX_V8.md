# ScreenVeil R6 — overlay-removal instrumentation test fix V8

## CI result addressed

The supplied API 34 run executed all 29 instrumentation tests. The two failures were in
`repeatedModelFailuresStopAndClearAnExistingOverlay` and
`repeatedCaptureFailuresStopAndClearAnExistingOverlay`. Both had already observed the
expected circuit-breaker stop state, but then immediately queried `dumpsys window windows`
once and found the `ScreenVeil redaction` title still present.

## Analysis

`stopForSafety()` runs on the main thread and calls `overlay?.clear()` before publishing the
inactive `ProtectionStatus`. `OverlayController.clear()` detaches the displayed drawable and
requests `WindowManager.removeView(view)`. The test's status observation and its separate
WindowManager dump are not guaranteed to be a single atomic observation. The supplied log does
not include the final WindowManager dump at failure time, so it cannot prove whether the window
was awaiting teardown or was genuinely retained.

V8 preserves that distinction instead of assuming either outcome.

## Changes

Only these paths differ from V7:

1. `app/src/androidTest/java/dev/screenveil/app/capture/ProtectionLifecycleInstrumentedTest.kt`
   - The two repeated-failure tests call `awaitRedactionWindowRemoved()` after the safety-stop
     condition and persisted preference check.
   - The helper polls the actual WindowManager dump for up to 8 seconds.
   - If the title remains, the test still fails and includes ProtectionStatus, service test
     diagnostics, and the matching WindowManager line from the final dump.
   - The opaque-cover assertions remain. The tests still require that the localized overlay
     disappear; no failure is ignored or converted into success.
2. `docs/EMULATOR_TEST_COVERAGE.md` — records the V8 observation rule.
3. `docs/EMULATOR_TEST_FAILURES_2026-11-03.md` — records the API 34 failure and V8 response.
4. `docs/CI_OVERLAY_REMOVAL_TEST_FIX_V8.md` — this report.

No production Kotlin, overlay code, watchdog logic, Gradle configuration, CI workflow, model
asset, permission logic, safety threshold, or app manifest changed.

## Validation performed

- Diff against V7: exactly the four paths listed above changed; no production source changed.
- Bash syntax: every project shell script and `gradlew` passed `bash -n`.
- Android XML: all 7 project XML files parsed successfully.
- GitHub Actions YAML: the workflow YAML parsed successfully.
- Model catalog metadata: all 5 pinned entries passed `scripts/verify-model-pins.py`.
- Kotlin parser diagnostic scan: no syntax/parse diagnostics were found in the modified
  instrumentation test. A standalone compile could not resolve Android, AndroidX, project,
  and instrumentation dependencies because their Gradle classpath is not present here.
- ZIP integrity and SHA-256: verified after packaging.

## Not verified

This environment has no Android SDK or `adb`; no Android Gradle compilation or live emulator
run was performed. GitHub Actions must rerun API 34 and API 36. If either test still fails,
the new assertion message will provide the actual remaining WindowManager evidence. The V8
package is not evidence that the API 34/API 36 emulator runs pass.

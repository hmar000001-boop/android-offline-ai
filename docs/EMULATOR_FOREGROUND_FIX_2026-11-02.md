# Emulator fixture foreground detection correction — 2026-11-02

## Failure analysis

The 2026-11-02 18:24 API 34 run compiled both APKs, then 11 instrumentation tests failed at the same `await()` line while calling `launchFixtureWindow()`. The remaining tests that did not launch the fixture completed. This failure distribution identifies one shared test-helper regression rather than 11 independent ScreenVeil lifecycle defects.

The helper previously waited for `mCurrentFocus` in `dumpsys window windows`. That field is not a stable contract for this diagnostic output. Android 13/14 builds can print window entries as `Window #...` and omit `mCurrentFocus` from the `windows` subsection. Treating absence of that line as proof that the fixture Activity did not launch caused every fixture-dependent test to stop before its scenario ran.

## Correction

- Start the fixture using `am start -W` so ActivityManager waits for the launch result.
- Assert that ActivityManager returned `Status: ok`.
- Wait for the fixture component in `topResumedActivity`, `mResumedActivity`, or `mFocusedActivity` from `dumpsys activity activities`.
- On failure, include the start output and resumed/focused ActivityTaskManager records.
- Replace the secure-window failure diagnostic's use of `mCurrentFocus` with the same ActivityTaskManager diagnostics.

The production AccessibilityService and screenshot behavior are unchanged in this correction. The previous debounce and secure-window behavior changes are preserved.

## Validation status

Static source checks and archive integrity can be verified locally. Android Gradle compilation and API 34/API 36 execution still require GitHub Actions; no emulator result is claimed by this document.

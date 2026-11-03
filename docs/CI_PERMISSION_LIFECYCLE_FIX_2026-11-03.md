# ScreenVeil R6 — instrumentation process-crash correction

Date: 2026-11-03

## Root cause identified from the source and CI sequence

`ProtectionLifecycleInstrumentedTest.setUp()` granted `POST_NOTIFICATIONS` with `UiAutomation` when the permission was initially denied. Its `@After` then revoked that runtime permission to restore the original state.

Android's runtime-permission documentation states that when a runtime permission is revoked, processes running under the calling UID are killed. The app target and its instrumentation share the target application UID in this instrumentation configuration. Consequently, revoking `POST_NOTIFICATIONS` during `@After` can terminate the instrumentation process itself. This directly matches a run that completes one test and then reports `Instrumentation run failed due to Process crashed`.

API 34's long-running job is consistent with the same teardown/process-termination race, although the supplied log excerpt does not include its final process exit record. The repeated emulator-console warning is not treated as the cause: the emulator had booted and Gradle had launched instrumentation.

## Changes

1. Removed `UiAutomation.revokeRuntimePermission()` from test teardown.
2. Removed the in-process `UiAutomation.grantRuntimePermission()` setup path.
3. Added a strict setup assertion that `POST_NOTIFICATIONS` is already granted before each test.
4. Updated `scripts/run-emulator-tests.sh` to grant and verify `POST_NOTIFICATIONS` with `adb shell pm grant` / `pm check-permission` after the diagnostic script installs both APKs and before `connectedDebugAndroidTest` starts.
5. The CI emulator is disposable, so the permission is deliberately not restored from inside instrumentation.

No production application code, model, detector threshold, coverage limit, overlay rendering policy, or Gradle dependency was changed.

## Validation performed in this environment

- Source inspection confirms no `grantRuntimePermission` or `revokeRuntimePermission` calls remain in the instrumentation test class.
- Shell script syntax validation passed.
- Android XML and GitHub Actions workflow YAML validation passed.
- ZIP archive integrity and SHA-256 verification passed.

A full Gradle build and emulator run were not available in this environment. The next API 34 and API 36 CI run is the required confirmation.

## References

- Android Open Source Project, Runtime permissions: https://source.android.com/docs/core/permissions/runtime_perms
- Android `UiAutomation` API: https://developer.android.com/reference/android/app/UiAutomation

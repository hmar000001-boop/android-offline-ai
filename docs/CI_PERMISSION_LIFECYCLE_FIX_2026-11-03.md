# ScreenVeil R6 — instrumentation permission preflight correction

Date: 2026-11-03

## What the latest CI output proves

The latest CI run did **not** start AndroidJUnitRunner. The target and instrumentation APKs were installed, and the independent test ContentProvider returned a Bundle. The script then printed `Pre-granting POST_NOTIFICATIONS outside instrumentation...` and exited with code 255.

Inspection of the exact script showed the cause: it invoked `adb shell pm check-permission`. `check-permission` is not a `pm` subcommand. Android exposes this check through `dumpsys package check-permission <permission> <package> [<user>]`. That command prints the integer result from `PackageManager`: `0` means granted; `-1` means denied. The invalid `pm check-permission` invocation explains the exit 255 and means this CI run provides no new evidence about the instrumentation lifecycle tests.

## Separate earlier hypothesis: runtime-permission mutation during tests

An earlier version of `ProtectionLifecycleInstrumentedTest` granted `POST_NOTIFICATIONS` through `UiAutomation` in setup and revoked it in teardown. Android documents that revoking runtime permissions can kill processes associated with the affected UID. That was a credible explanation for the prior `Instrumentation run failed due to Process crashed` symptom, but it has **not yet been confirmed** by a successful run after removing in-process permission changes.

The test now requires the permission to be granted before instrumentation begins and does not grant or revoke it itself. The CI emulator is disposable, so the host grants the permission once and leaves it granted until the emulator is discarded.

## Changes in this correction

1. Added `scripts/pregrant-notification-permission.sh`.
2. Updated `scripts/run-emulator-tests.sh` to call the helper after the target/test APKs are installed and before `connectedDebugAndroidTest` starts.
3. The helper grants explicitly for Android user 0 using `adb shell pm grant --user 0 PACKAGE PERMISSION`.
4. It verifies using `adb shell dumpsys package check-permission PERMISSION PACKAGE 0` and accepts only result `0`.
5. It captures and prints raw command output and exit status when the grant or verification fails, rather than hiding stderr or exiting without identifying which command failed.

No production application source, model, detector threshold, coverage limit, overlay rendering policy, or Gradle dependency was changed.

## Validation performed for this archive

- Shell syntax validation for every shell script: passed.
- Host-side fake-ADB test of successful grant + verification: passed.
- Host-side fake-ADB test that a denied result (`-1`) is rejected: passed.
- Model catalog metadata validation for all five artifacts: passed. No model weights were downloaded by this check.
- All 7 Android XML files parsed: passed.
- GitHub Actions workflow YAML parsed: passed.
- Instrumentation source scan confirms no `UiAutomation.grantRuntimePermission` or `UiAutomation.revokeRuntimePermission` calls remain in the lifecycle test.
- ZIP integrity and SHA-256 are checked during packaging.

A full Gradle build and Android API 34/API 36 emulator run were not available in this environment. The next CI run must first pass this permission preflight and then execute all 29 instrumentation tests. Only that run can confirm whether the earlier instrumentation-process crash is resolved and whether the explicit-Stop test has a separate failure.

## References

- Android Open Source Project, `PackageManagerService` source (documents `dumpsys package check-permission <permission> <package> [<user>]` and returns the PackageManager permission result): https://android.googlesource.com/platform/frameworks/base/+/3eef5a5/services/core/java/com/android/server/pm/PackageManagerService.java
- Android Open Source Project, `PackageManagerShellCommand` source (documents `pm grant [--user USER_ID] PACKAGE PERMISSION`): https://android.googlesource.com/platform/frameworks/base/+/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java
- Android runtime permissions: https://source.android.com/docs/core/permissions/runtime_perms

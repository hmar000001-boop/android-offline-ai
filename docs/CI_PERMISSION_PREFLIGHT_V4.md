> **Superseded by V5:** the API 34 run after V4 showed `adb install -r -g` success and `POST_NOTIFICATIONS: granted=true` in the detailed User 0 package dump, but the separate `dumpsys package check-permission` query returned `-1`. V4 incorrectly treated that contradictory one-line query as a hard failure. V5 records both views as diagnostics and leaves the decisive post-AGP-install check to the instrumentation target context.

# ScreenVeil R6 — CI instrumentation permission fix V4

Date: 2026-11-03
Base: ScreenVeil 1.6.0 R6 instrumentation-permission-fix V3

## Executive summary

The supplied latest CI excerpt failed **before AndroidJUnitRunner was started**. It completed APK installation and the ContentProvider diagnostic, then exited with code 255 while printing an older pre-grant message. That log does not show the V4 source marker, so it cannot establish that the exact prior ZIP was used.

V4 makes the grant part of the **Android Gradle Plugin's own APK installation options**, not just a separate ADB command that a subsequent Gradle install could undo. The host-side ADB preflight remains as an early verification, and the test's `@Before` permission assertion remains the final check from the target app context.

This is a CI/test-install configuration change. No moderation behavior, overlay logic, detection model, segmentation behavior, safety limit, or production Kotlin logic was changed.

## Root-cause hypotheses and evidence

### A. In-process permission mutation

The earlier instrumentation test version granted and revoked `POST_NOTIFICATIONS` using `UiAutomation`. Runtime-permission revocation can terminate processes belonging to the affected UID. That is a credible explanation for the earlier `Instrumentation run failed due to Process crashed` symptoms. The current test source contains no `grantRuntimePermission` or `revokeRuntimePermission` calls. It asserts `POST_NOTIFICATIONS` is already granted and its `@After` does not revoke it.

This mechanism is plausible and worth preventing, but the supplied recent pre-grant failure did not execute AndroidJUnitRunner, so it does not prove or disprove the original test-process crash hypothesis.

### B. V2/V3 host preflight command/provenance problems

V2 used an invalid `pm check-permission` command. The check belongs to `dumpsys package check-permission` and returns a numeric result (`0` granted, `-1` denied). V3 corrected the command, but the user's next log printed an older `Pre-granting POST_NOTIFICATIONS outside instrumentation...` message that was not emitted by the V3 script. This indicates the GitHub repository likely did not run the exact V3 files, or it contained mixed/stale workflow/script contents.

V4 prints a unique source marker and SHA-256 hashes so the next log can establish which source was actually executed.

### C. A manual grant alone can be undone by AGP

A separate `adb install -r -g` before instrumentation is not sufficient if the connected-test task installs the target APK again without `-g`. AGP's connected instrumentation task itself manages installation. Excluding `installDebug` tasks does not disable the installs performed internally by the connected test runner.

V4 configures AGP `Installation.installOptions` with `-g` when, and only when, the emulator script exports `SCREENVEIL_CI_GRANT_RUNTIME_PERMISSIONS=1`. AGP 8.13.2 exposes `CommonExtension.installation`; its `DeviceProviderInstrumentTestTask.CreationAction` takes the global `Installation` options and sets them on the connected instrumentation task. This is the supported point at which to pass the install flag.

## Selected layered solution

1. `scripts/run-emulator-tests.sh` exports `SCREENVEIL_CI_GRANT_RUNTIME_PERMISSIONS=1` **before** invoking the diagnostic script. That diagnostic script itself runs Gradle APK installation tasks, so the flag must already be present.
2. `app/build.gradle.kts` conditionally calls `installation { installOptions.add("-g") }`. The Gradle file prints `SCREENVEIL_GRADLE_INSTALL_OPTIONS=-g`; the runner refuses to launch tests unless that marker appears in the diagnostic log.
3. `scripts/install-test-app-with-grants.sh` verifies the target APK application ID, performs `adb install -r -g`, verifies the package path and checks `POST_NOTIFICATIONS` using `dumpsys package check-permission` for user 0. It also confirms the instrumentation APK is installed.
4. `connectedDebugAndroidTest` runs normally. V4 does not exclude AGP's install tasks; AGP's own installation uses `-g` too.
5. The instrumentation `@Before` checks `context.checkSelfPermission(POST_NOTIFICATIONS)` in `InstrumentationRegistry.getInstrumentation().targetContext`. If permission is missing, the test fails immediately with an explicit message rather than mutating permissions from inside the test process.

## Alternatives compared

| Option | Assessment |
|---|---|
| Grant/revoke through `UiAutomation` in `@Before`/`@After` | Rejected. Permission revocation can kill the target UID process and interfere with the test runner. |
| Only `adb shell pm grant` before tests | Valid host-side method, but later APK installation may reset permission state. Not sufficient alone. |
| Only `adb install -r -g` before tests | Grants at that install, but AGP's connected test task may install again. Not sufficient alone. |
| Only AGP `installation.installOptions += -g` | Controls AGP's connected-test install, but lacks an early host-side preflight and clear permission diagnostics. |
| **ADB preflight + AGP install option + in-process assertion** | **Selected.** Covers manual preflight, AGP-managed test installation, and the final state observed by the actual target app. |
| Fake notification permission/notifier in lifecycle tests | Rejected for this integration test. It would no longer exercise the real notification Stop prerequisite. A fake is appropriate only for isolated unit tests. |
| Direct `adb shell am instrument` | Kept as a diagnostic fallback only; it bypasses AGP's normal report/result collection. |

## Exact V4 provenance markers expected in CI

The next API 34 and API 36 log must contain:

- `SCREENVEIL_CI_CHECKOUT_SHA=...`
- `SCREENVEIL_CI_SOURCE_MARKER=R6_V4_AGP_INSTALL_GRANT`
- `SCREENVEIL_CI_GRANT_RUNTIME_PERMISSIONS=1`
- `SCREENVEIL_GRADLE_INSTALL_OPTIONS=-g`
- `SCREENVEIL_AGP_INSTALL_GRANT_HOOK=CONFIRMED`
- `SCREENVEIL_CI_PERMISSION_SETUP_RESULT=PASS`

A run missing the V4 marker/hashes is not a V4 run; inspect repository checkout/workflow changes before interpreting its result.

## Files changed from V3

- `app/build.gradle.kts`: environment-gated AGP installation option `-g`; no APK runtime behavior change.
- `scripts/run-emulator-tests.sh`: enables the flag before all Gradle calls, requires the AGP configuration marker, runs the standard connected test install sequence, adds source provenance and richer failure diagnostics.
- `scripts/install-test-app-with-grants.sh`: explicit target APK ADB install/permission preflight.
- `.github/workflows/android.yml`: prints checked-out commit and script hashes; uploads permission/preflight diagnostics.
- `ProtectionLifecycleInstrumentedTest.kt`: explanatory comment updated only; test behavior unchanged.
- `docs/CI_PERMISSION_PREFLIGHT_V4.md`: records the analysis and chosen design.
- Removed the older `pregrant-notification-permission.sh` path to avoid stale competing implementations.

No production Kotlin behavior, instrumentation test behavior, manifest permission declaration, Gradle dependency, model artifact, detector, overlay renderer, segmentation algorithm, coverage limit, or moderation policy changed from V3.

## Verification performed in this environment

- `bash -n` passed for every `scripts/*.sh` file.
- `scripts/verify-model-pins.py` metadata validation passed for all five catalog artifacts. This was metadata validation only, not a full model download/hash verification.
- All 7 Android XML files parsed successfully.
- GitHub Actions YAML parsed successfully.
- Static scan found no `UiAutomation.grantRuntimePermission` or `revokeRuntimePermission` calls in instrumentation source.
- A mocked ADB/apkanalyzer test passed both the permission-granted path and the permission-denied fail-closed path of the host preflight helper.
- ZIP integrity and SHA-256 are checked after packaging.

## Not verified here

This environment has no configured Android SDK or installed Gradle executable. Therefore V4 has **not** been Gradle-compiled, run through `connectedDebugAndroidTest`, or executed on API 34/API 36. The selected mechanism is supported by AGP 8.13.2's documented API and task wiring, but only the next real CI run can confirm that the workflow is using this exact ZIP and that instrumentation now runs to completion.

If V4 reaches instrumentation and still crashes, the logs will have moved past the permission-preflight stage. Use `screenveil-emulator-api-34-reports` / `screenveil-emulator-api-36-reports`, particularly `logcat-crash-buffer.txt`, `target-exit-info.txt`, `test-exit-info.txt`, DropBox crash reports, `notification-permission-state.txt`, and the JUnit HTML report, to identify the actual process and exception.

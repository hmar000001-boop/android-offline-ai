# ScreenVeil R6 — API 34 permission-preflight false-negative correction V5

Date: 2026-11-03
Base: R6 CI permission-install V4

## What the supplied API 34 log proves

The V4 preflight stopped before AndroidJUnitRunner. However, the failure is no longer an install/grant failure:

- `adb install -r -g app/build/outputs/apk/debug/app-debug.apk` returned `Success`.
- The target package was installed and its APK path was present.
- The detailed `dumpsys package dev.screenveil.app` output, under `User 0` -> `runtime permissions`, explicitly reported `android.permission.POST_NOTIFICATIONS: granted=true`.
- The separate `dumpsys package check-permission ... 0` query returned `-1` in the same run.

Those two PackageManager views contradict each other. The V4 script treated the one-line query as authoritative and exited, despite the detailed per-user permission state showing the grant. That was an incorrect fail-fast condition in our preflight script.

The log does not show an AndroidJUnitRunner launch. Therefore this run provides no evidence about whether the prior instrumentation process crash is fixed or still present.

## V5 correction

`scripts/install-test-app-with-grants.sh` now:

1. Keeps APK application-ID verification.
2. Installs the exact target APK using `adb install -r -g`.
3. Confirms the package installation path.
4. Reads `dumpsys package <package>` and parses the explicit `User <id>` -> `runtime permissions` entry for the requested permission.
5. Fails if the detailed per-user entry is absent or says `granted=false`.
6. Records the one-line `dumpsys package check-permission` output as a diagnostic only when it disagrees with the detailed record; it no longer aborts on that contradictory output.
7. Retains the final `targetContext.checkSelfPermission(POST_NOTIFICATIONS)` assertion in the instrumentation `@Before`. This is the decisive check after AGP performs its own connected-test APK installation.

The CI diagnostic artifact renames the one-line query capture to `notification-permission-check-query-diagnostic.txt` to make clear that it is not the authoritative grant check.

## Why this is safer than simply ignoring permission state

V5 does not blindly continue after `adb install -g`. It requires a concrete per-user `granted=true` record from the package's runtime-permission section. Then the target app context independently checks the permission after the connected-test runner has installed the APK. If AGP's own install loses the grant, the test fails at `@Before` with a clear assertion before lifecycle assertions begin.

## What has not been proven

- The supplied log is API 34 only; it does not establish API 36 behavior.
- The instrumentation suite was not started in that run.
- The earlier instrumentation process crash remains unresolved until the suite actually executes beyond its first tests and finishes.
- No production moderation, overlay, segmentation, model, threshold or runtime code was changed.
- No full Gradle or emulator run was available in this environment.

## V5 provenance markers expected in CI

- `SCREENVEIL_CI_SOURCE_MARKER=R6_V5_PACKAGE_DUMP_DIAGNOSTIC`
- `SCREENVEIL_CI_PERMISSION_SETUP=R6_V5_ADB_INSTALL_GRANT`
- `SCREENVEIL_AGP_INSTALL_GRANT_HOOK=CONFIRMED`
- `PACKAGE_DUMP_RUNTIME_PERMISSION=GRANTED` (diagnostic from the initial host install; may say NOT_CONFIRMED without stopping)
- `PACKAGE_CHECK_PERMISSION_QUERY=DISAGREES_OR_DENIED output=-1` may appear; it is explicitly diagnostic only.
- `SCREENVEIL_CI_PERMISSION_FINAL_CHECK=INSTRUMENTATION_TARGET_CONTEXT`
- `SCREENVEIL_CI_PERMISSION_INSTALL_PREFLIGHT=PASS`

The instrumentation `@Before` must then pass its target-context permission assertion. That is the decisive proof the permission survived AGP's test installation.

## Validation performed for V5

- Shell syntax checks for all scripts.
- Parser simulation against the exact `User 0` runtime-permission excerpt supplied by the user (`granted=true`).
- Parser rejection simulation for `granted=false`, missing permission, and a different user ID.
- Mocked end-to-end host preflight where `adb install -r -g` succeeds, the detailed User 0 dump reports `granted=true`, and the separate check-permission query returns `-1`; the script correctly proceeds and records the disagreement.
- Android XML parsing, GitHub Actions YAML parsing and model catalog metadata validation.
- ZIP integrity and SHA-256 verification.

V5 is a correction to CI preflight verification, not evidence that the Android instrumentation tests pass.

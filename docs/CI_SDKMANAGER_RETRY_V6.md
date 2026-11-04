# ScreenVeil R6 — Android SDK download retry correction V6

Date: 2026-11-03
Base: R6 CI permission-preflight V5

## Failure being addressed

The supplied API 36 workflow log failed in the `Install compile and emulator SDK components` step while `sdkmanager` was downloading `system-images;android-36;google_apis;x86_64` (`x86_64-36_r07.zip`). SDK Manager then reported `Error on ZipFile unknown archive` and exited before Gradle emulator setup or AndroidJUnitRunner.

This is an SDK package acquisition/unpacking failure. The log does not establish whether the downloaded archive was truncated, corrupted in transit, or otherwise malformed. It is not evidence of a ScreenVeil Kotlin, overlay, permission, or instrumentation-test defect.

A closely matching Android CI incident was reported in DeviceFarmer/stf PR #1051 on October 23, 2026. Its maintainer reported the same SDK Manager ZIP error before emulator startup and moved the SDK installation ahead of the emulator action, retrying the install while clearing SDK Manager temporary directories between attempts. That implementation also notes that the archive checksum was valid at the origin and that the failure was consistent with a bad download. See: https://github.com/DeviceFarmer/stf/pull/1051

## V6 changes

### Shared retry helper

Added `scripts/install-android-sdk-with-retries.sh`.

- Installs the requested SDK package paths through the SDK Manager from the active `ANDROID_HOME` / `ANDROID_SDK_ROOT`.
- Uses stable channel `0`, matching the workflow and emulator action's default stable channel.
- Deduplicates package arguments while preserving order.
- Retries up to three attempts by default, with 15-second then 30-second backoff. The attempt count can be restricted to 1–5 with `SCREENVEIL_SDKMANAGER_MAX_ATTEMPTS`.
- Before every attempt, clears only `$SDK_ROOT/.temp` and `$SDK_ROOT/.downloadIntermediates`, the SDK Manager temporary download/staging directories. It does not delete installed platforms, build-tools, platform-tools, emulator binaries, system images, AVDs, or Gradle caches.
- Writes one log per attempt (`sdkmanager-install-attempt-N.log`). A final failure remains a hard failure; retries do not turn failed SDK installation into success.

### All SDK installation steps now use the helper

The debug build, release build, and emulator matrix each use the shared retry helper. `setup-android@v4` is configured with `packages: ''` so it prepares command-line tools and accepts licenses without separately installing `platform-tools` outside the retry helper. The helper then installs `platform-tools` with the other SDK packages. The API 36 emulator leg adds the compile platform only once; the helper also defensively deduplicates package paths.

### Preinstall the emulator action's exact requirements

The workflow pins `reactivecircus/android-emulator-runner` to `v2.38.0` and preinstalls its SDK dependencies before invoking the action:

- `build-tools;37.0.0`
- `platform-tools`
- `platforms;android-${matrix.api-level}`
- `emulator`
- `system-images;android-${matrix.api-level};google_apis;x86_64`

The emulator job additionally retains the app's own compile platform and build-tools (`platforms;android-36`, `build-tools;36.0.0`). This matters because v2.38.0's `sdk-installer.js` pins Build Tools **37.0.0** and invokes SDK Manager itself for Build Tools, platform-tools, the API platform, emulator, and system image. Preinstalling those packages under retry avoids leaving the action's internal one-shot SDK Manager calls to perform the large downloads without retry. Source inspected: https://github.com/ReactiveCircus/android-emulator-runner/blob/v2.38.0/lib/sdk-installer.js

## Scope

No production Kotlin/Java source, app manifest, model artifact, model pin, Gradle dependency, accessibility behavior, permission logic, or instrumentation test was changed. The change is limited to CI SDK acquisition and workflow pinning/diagnostic logs.

## Validation performed for this source package

- `bash -n` for every shell script.
- Mocked SDK Manager test: first attempt fails, helper clears temporary directories and retries, second attempt succeeds.
- Mocked SDK Manager test: all attempts fail; helper returns non-zero and preserves each attempt log.
- Confirmed package arguments are de-duplicated and `--channel=0` / SDK root are passed to the stub.
- GitHub Actions workflow YAML parsing.
- ZIP integrity and SHA-256 verification.

## What remains unproven

No live download from `dl.google.com`, full Gradle build, API 34/API 36 emulator boot, or Android instrumentation run was performed in this environment. V6 addresses the observed pre-emulator SDK download failure; it does not prove the download will succeed on the next GitHub-hosted runner, and it does not claim that the prior instrumentation lifecycle crash is resolved.

# Delivery validation — balanced model approval update

## Changes applied to this source package

- Replaced the `TrustedModel` name with `ApprovedModelArtifact` so a matching hash is not represented as a safety certification.
- Reduced the selectable specialist catalog to three pretrained artifacts: Google EfficientDet-Lite0, Yahoo OpenNSFW ONNX and ViT-Tiny NSFW ONNX. Gemma 4 remains a separate optional profile.
- Removed Electblake's five-class clothing classifier from all selectable profiles. The app also removes its legacy downloaded artifact and marker on startup/upgrade.
- Replaced the MediaPipe Tasks Vision runtime dependency with standalone TensorFlow Lite Task Vision `0.4.4` for person detection. This avoids the MediaPipe Tasks usage-metrics collection described in its documentation; the app's selected policy allows network access only for model downloads.
- Restricted downloads to the fixed catalog, HTTPS, manually checked redirect hosts, byte-size bounds and exact SHA-256 verification. A model is moved into its final private-storage location only after all checks pass.
- Updated model profiles, Compose UI descriptions, security notes, third-party notices, model governance and device-test instructions to remove the old four-specialist-model/clothing-classifier claims.
- Added catalog-policy tests for the admitted artifact IDs, HTTPS/hash/size metadata, specialist independence from Gemma and the separate optional Gemma profile.
- Updated app version to `1.3.0` / version code `4`.

## Admitted models and profile composition

- Balanced: EfficientDet-Lite0 + OpenNSFW + ViT-Tiny NSFW (three artifacts, no Gemma).
- Fast: EfficientDet-Lite0 + OpenNSFW (two artifacts, no Gemma).
- Gemma vision: Gemma 4 E2B only (optional, separate download).

There is no approved swimwear-specific model in this version. The specialist pipeline masks non-explicit person boxes as `UNCERTAIN` when the user's conservative uncertain-redaction setting is enabled. This can over-mask ordinary people and does not establish swimwear detection accuracy.

## Checks performed in this environment

- Source/docs grep confirms the Electblake clothing classifier is absent from the active catalog, selectable profiles and runtime implementation. Historical references may remain only in exclusion/migration documentation.
- Kotlin source inspection confirms `SpecializedScreenModerationDetector` imports standalone TensorFlow Lite Task Vision rather than MediaPipe Tasks.
- The manifest documents `INTERNET` for user-initiated downloads; source search finds the model installer as the sole application network client. This is a source-level check, not a runtime packet capture.
- Android XML files are parsed and checked for well-formedness.
- GitHub Actions YAML is parsed and the Gradle bootstrap script is checked with `bash -n`.
- ZIP integrity is checked after packaging and the final SHA-256 is recorded.

## Checks not executed — do not treat as passed

- The full Android Gradle command `./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug` has not been completed in this environment. No Android SDK platform is installed, no system Gradle is available, and the pinned Gradle distribution previously failed to resolve because DNS for `services.gradle.org` was unavailable.
- Android `compileDebugKotlin`, Gradle unit tests, Lint and APK assembly have not been confirmed for this exact revision.
- The ONNX and TFLite artifacts have not all been downloaded by an Android device and initialized on the S24 FE in this delivery.
- No Galaxy S24 FE latency, memory, battery, thermal, overlay or accessibility test has been run.
- There is no independent security audit of every model binary or complete resolved-dependency SBOM.
- There is no representative ScreenVeil dataset or measured false-negative/false-positive result. No model has been calibrated for the product's swimwear requirement.

## Remaining release gates

1. Run GitHub Actions or the documented Gradle command on a networked machine with JDK 21 and Android SDK Platform 36 / Build Tools 36.0.0. Resolve any compile, test and lint failures.
2. Download all three Balanced model artifacts on an Android device. Verify the byte-size checks and full SHA-256 re-hash before initialization. Separately test the optional Gemma download if desired.
3. Run image fixtures through both ONNX classifiers and the TFLite person detector; confirm tensor names, dimensions, output order and preprocessing.
4. Install on the Galaxy S24 FE. Benchmark P50/P95 capture-to-overlay time, peak RAM/PSS, native heap, battery and thermal stability for at least 30 minutes per profile.
5. Evaluate lawful representative image/video samples and report per-category precision/recall, false negatives, false positives, person-detection recall, region coverage and box IoU. Include swimwear categories; state clearly that current models do not have a dedicated swimwear label.
6. Generate a complete release SBOM and review all transitive runtime licenses and security advisories.

## Release classification

This is an updated experimental source package under the balanced model-approval policy. It is **not** a verified Android APK and **not** production-ready protection. Do not claim that all models are 100% safe, secure, trusted or accurate. The specialist profiles do not use Gemma, but neither provides validated swimwear-specific classification or guaranteed real-time video redaction.


## Follow-up — detector-box geometry and pending Swimwear-aware profile

- Preserved Google EfficientDet-Lite0 in both selectable specialist profiles (Balanced and Fast).
- Added a visible `SWIMWEAR_AWARE` profile entry for Google's SSD MobileNet V2 / Open Images V4 candidate. The entry is intentionally disabled and is not in the fixed download catalog because the reviewed Google checkpoint is a SavedModel, not a pinned Android TFLite artifact. The profile has no required artifact list, is rejected by `ModelProfile.fromStored`, and cannot reach model initialization. No checksum or converted artifact was fabricated.
- Preserved the separate optional Gemma profile.
- Fixed the integer-division defect in `SpecializedScreenModerationDetector`: the four person-box coordinates now pass through `DetectionBoxNormalizer.fromPixelBounds`, which converts with floating-point arithmetic, clips to screenshot dimensions and rounds outwards.
- Added regression tests for fractional/non-square coordinates, clipping, invalid boxes, and the full normalized-box-to-overlay mapping.
- Bumped Android app metadata to version `1.4.0` / version code `5`.

### Candidate model source and remaining admission work

Google's Kaggle-published SSD MobileNet V2 Open Images V4 model documents 600 output categories, detection boxes/class names/scores, Apache-2.0, and an overall Open Images V4 mAP of 0.34. Those are publisher/model-card details, not a ScreenVeil swimwear recall measurement. The Google checkpoint must still be exported to TFLite without training/fine-tuning, receive the correct label metadata or a tested custom Interpreter adapter, be compared against the SavedModel on fixed fixtures, be hashed after export, and run locally on the Galaxy S24 FE.

### Verification for this follow-up

- Source inspection confirms the old `(int / int) * 1000f` expressions are removed from the person-box conversion path.
- The pure normalization helper and `MaskGeometry` mapping have dedicated regression tests; those tests are included in the Gradle unit-test suite but have not been run with Gradle in this environment.
- Full Android Kotlin compilation, Gradle unit tests, lint and APK assembly remain unverified for this revision.
- No actual Google Open Images TFLite artifact is included or downloadable in this revision, and no device inference or model-performance claim is made.


## CI follow-up — 2026-11-02

The user's GitHub Actions log reached `:app:compileDebugKotlin` and reported one Kotlin diagnostic: `ObjectDetector.createFromFileAndOptions` expected a `String` model path but received a `File`. Corrected the call to pass `personFile.absolutePath`. This is a source-level correction; the replacement archive still requires a fresh GitHub Actions run for full Gradle, unit-test, lint, and APK verification.


## Critical usability/safety hotfix (1.5.1 / versionCode 7)

Source now defaults protection OFF, requires an explicit Start tap after Accessibility is enabled, removes global opaque fallback paths, bounds capture/model retries, pauses and clears overlays after runtime failures, and disables specialist whole-screen scoring. See `CRITICAL_SAFETY_HOTFIX.md`. At the initial hotfix packaging stage, the emulator suite had not yet run. The suite was subsequently added and expanded as documented below; no successful Android build or emulator result is claimed until GitHub Actions completes.


## 1.5.1 emulator lifecycle and protection-path tests expanded

Expanded the Android instrumentation suite to 18 tests. In addition to default-OFF, migration, Accessibility binding and missing-model safe-stop, the suite now tests: synthetic detector success through the real service and WindowManager; Android's real `takeScreenshotOfWindow()` API; a 30-event content-change storm; three consecutive model failures with an existing mask; five capture failures with an existing mask; Android screenshot denial for a real `FLAG_SECURE` window; renderer failure; Stop while a mask is visible; Accessibility interruption; service disable/unbind while a mask is visible; localized pixel-level bitmap output; and ALLOW-only output with no overlay. A debug-only injection seam provides deterministic fake detector/frame/renderer behavior; release builds do not consult it. See `EMULATOR_TEST_COVERAGE.md`. The expanded suite is source-complete but has not yet been executed; API 34 and API 36 GitHub Actions results are still required.


## Emulator runner shell correction (2026-11-02)

The first API 34/API 36 run booted the emulators but failed before Gradle started because the
runner executes its script through `/bin/sh`, which does not support `set -o pipefail`. The
emulator command now uses POSIX-compatible status capture and log printing. Emulator tests remain
unverified until the workflow is rerun successfully. See `EMULATOR_CI_SHELL_FIX.md`.

## Emulator-runner multiline script parser correction

The API 34/API 36 runner boots were successful, but the second command attempt failed before
Gradle because `reactivecircus/android-emulator-runner@v2` splits its `script` input on newlines
and executes every non-empty line separately. The workflow now invokes the checked-in Bash
script with a single-line `script: bash scripts/run-emulator-tests.sh`. The Bash script captures
the Gradle and `tee` exit statuses, and the workflow prints/uploads `emulator-tests.log` even
when the emulator test step fails. The script was syntax-checked and exercised with fake Gradle
commands returning both 0 and 23; both exit statuses were propagated correctly. Actual Android
instrumentation execution remains pending.


## 1.5.1 expanded protection test suite — 2026-11-02

Added debug-only dependency seams in `ScreenVeilTestHooks.kt` for detector, frame provider and renderer injection. The service uses them only under `BuildConfig.DEBUG`. Added a separate fixture Activity in the androidTest APK, including a `FLAG_SECURE` mode, so instrumentation can capture a window outside ScreenVeil's own package. Added 12 additional instrumentation scenarios, bringing the suite to 17. Static XML/YAML/shell/ZIP checks remain distinct from execution: the new tests have not yet run on Android emulators. The environment used to author this archive has no Android SDK or Gradle distribution available, so a GitHub Actions run is required for Kotlin/Android compilation and test results.


## Test fixture API constant correction — 2026-11-02

Both API 34 and API 36 runs compiled the production app but failed while compiling the instrumentation
source because `View.ACCESSIBILITY_EVENT_TYPE_WINDOW_CONTENT_CHANGED` does not exist. Replaced it with
`AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED`. The next CI run must confirm test APK compilation
and execute the instrumentation suite; no pass is claimed for the current run.


## Accessibility instrumentation settings-write correction — 2026-11-02

The first API 36 execution compiled both APKs and started all 17 tests, but 12 service-dependent tests
failed in the common `enableScreenVeilAccessibility()` setup assertion. Source inspection found that
the test built commands such as `settings put secure enabled_accessibility_services 'value'` and passed
them to `UiAutomation.executeShellCommand()`. AOSP's `UiAutomationConnection` implementation launches
the command with `Runtime.getRuntime().exec(command)`, not an interactive shell; single quotes are
therefore not removed as shell syntax and can become part of the stored setting. The same pattern was
also present in test teardown and the service-disable test.

The instrumentation now writes non-null secure settings through `Settings.Secure.putString()` while
`UiAutomation` adopts only `WRITE_SECURE_SETTINGS`, then drops that identity in `finally`. Null settings
are deleted through an unquoted, whitespace-tokenized `settings delete secure <key>` command. Every
write is read back and checked. Test and production service-component matching now parse each value
with `ComponentName.unflattenFromString()` rather than relying on one textual flattening format. Added
a regression test for short/full component forms and quote rejection, bringing the suite to 18 tests.

This is a source correction only. The current environment has no Android SDK installation or
configured Android emulator, so Kotlin compilation and API 34/API 36 execution of this revision have
not been performed here. The next CI run must establish whether the test service binds and whether the
remaining screenshot, inference-seam, overlay and cleanup assertions pass. The prior run did not
exercise those flows because all service-dependent tests stopped at setup.

Source reference: [AOSP UiAutomationConnection](https://android.googlesource.com/platform/frameworks/base/+/fc2be9c0d2beddfbff420d64c0af3d287327a2ee/core/java/android/app/UiAutomationConnection.java)
shows `Runtime.getRuntime().exec(command)`. Android's [UiAutomation API](https://developer.android.com/reference/android/app/UiAutomation)
documents scoped shell-permission adoption; [Settings.Secure](https://developer.android.com/reference/android/provider/Settings.Secure)
documents secure-setting writes and their normal-app restriction.

## 2026-11-02 emulator follow-up

The API 34 run that followed the Accessibility-settings correction compiled both APKs and ran all 18 instrumentation tests; 16 passed and 2 exposed a debounce-test synchronization issue and an unverified FLAG_SECURE fixture/capture path. The analysis and corrections are documented in `EMULATOR_TWO_FAILURES_2026-11-02.md`. The corrected source has not yet been executed on an Android emulator; API 34 and API 36 GitHub Actions runs remain required.

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

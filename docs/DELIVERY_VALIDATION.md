# ScreenVeil delivery validation — 2026-11-02

## Multi-model functionality added

- Added a default **Balanced multi-model · offline** profile that does not use Gemma.
- Added a **Fast specialized · offline** profile for lower model storage / compute cost.
- Kept **Gemma 4 vision** as an independent optional profile.
- Balanced specialist profile now uses four existing pretrained artifacts: Google EfficientDet-Lite0 person detection, Electblake EfficientNetV2-S clothing classification, Yahoo OpenNSFW ResNet-50 and Marqo-derived ViT-Tiny NSFW.
- Added pinned model source revisions/URLs, byte-size floors and SHA-256 digests to the trusted catalog; existing generic downloader stores weights privately and verifies the full file before loading.
- Added ViT-Tiny ONNX inference with documented 384×384 RGB NCHW preprocessing and `[NSFW, safe]` logit interpretation.
- Added a nine-tile overlapping ViT scan when the Balanced profile finds no person boxes; positive tiles are masked as whole regions.
- The Fast profile skips the ViT classifier and tile pass.
- Changed the five-class clothing mapping so `denim_shorts`, `maid`, `nurse`, unknown labels and low-confidence predictions are `UNCERTAIN`, never `ALLOW`. `swim_bikini` and `lingerie` have explicit thresholds.
- Added unit tests for clothing label interpretation, threshold boundaries and non-finite confidence/scores.
- Corrected profile switching: previous detector runtime is closed and the newly selected profile is initialized on the serialized inference worker.
- Updated app version to 1.1.0 and refreshed model governance, third-party notices and device-validation documentation.

## Checks executed in this environment

- Source tree and model catalog reviewed after changes.
- Six geometry smoke assertions pass using the installed Kotlin compiler and minimal type stub.
- Specialist decision policy compiled independently with Kotlin; a standalone harness executed 9 assertions (positive swimwear, below threshold, lingerie, no-safe mapping, non-finite confidence and NSFW threshold semantics).
- Model catalog/profile compiled independently with Kotlin; a standalone harness executed 5 assertions (four-model Balanced profile without Gemma, three-model Fast profile without Gemma, separate Gemma profile and default fallback).
- All main/test Kotlin files were passed through the compiler parser; the standalone compile stops on unresolved Android/Compose/runtime symbols because the Android dependency classpath is unavailable, with no Kotlin syntax diagnostics.
- All five Android XML files are well-formed.
- GitHub Actions YAML parsed successfully.
- Gradle bootstrap shell script passes `bash -n`.
- All 28 JUnit test methods are present in source.
- ZIP integrity and SHA-256 are checked at packaging time.

The standalone policy harness is not the Gradle JUnit suite. It verifies pure mapping logic only.

## Checks not executed — do not treat as passed

- Android Gradle `clean testDebugUnitTest lintDebug assembleDebug` cannot be run in this environment: Android SDK Platform 36 / Build Tools 36.0.0 are unavailable and the pinned Gradle distribution could not be resolved because DNS for `services.gradle.org` failed.
- The Android-specific ONNX / MediaPipe code has not been compiled or run in an Android build environment during this delivery.
- No emulator or Galaxy S24 FE was available. Installation, model downloads through Android networking, ONNX input binding compatibility, MediaPipe delegate behavior, overlay alignment, battery, peak memory, latency and thermal stability are not device-verified.
- The four-model classifier combination has not been calibrated against a labelled ScreenVeil dataset. The thresholds are heuristics. There are no measured per-category recall/precision, false-negative, false-positive or box-IoU values.
- Model download artifact hashes are pinned in source based on upstream artifact metadata; the actual device download-and-hash path must still pass in CI/device testing.

## Remaining release gates

1. Run the GitHub Actions workflow or the documented Gradle command on a networked machine with JDK 21 and Android SDK Platform 36 / Build Tools 36.0.0. Resolve all compile, test and lint failures.
2. Install on the Galaxy S24 FE and verify model downloads and SHA-256 checks for all four specialist files. Verify each ONNX model's input/output names, dimensions and successful first inference; verify MediaPipe person detection.
3. Benchmark Fast versus Balanced profiles: initialization, P50/P95 capture-to-overlay latency, peak RAM/PSS, sustained battery drain and temperature for at least 30 minutes.
4. Evaluate lawful representative data for one-piece swimsuits, bikinis, swim trunks, rash guards, sports swimwear, lingerie, ordinary beach scenes, partially visible/small people, multiple people, images without a person, drawings, screenshots, collages and video frames.
5. Report per-category recall/precision, false negatives, false positives, media-region coverage and box IoU. Tune thresholds only against a held-out validation set and preserve the raw results.
6. Generate a complete release SBOM and verify all upstream license notices before redistribution.

## Release classification

This archive is a multi-model experimental source build, **not** a verified Android APK and **not** production-ready protection. Three non-Gemma pretrained models are used in the Fast profile; the default Balanced profile uses four non-Gemma pretrained artifacts (one detector plus three classifiers). Gemma remains optional. Physical Android build and target-device/dataset validation remain mandatory; do not claim complete swimwear detection or guaranteed real-time video redaction.


## 2026-11-02 Kotlin compile follow-up

- Corrected three ONNX Runtime output reads: `OrtSession.Result.get(String)` returns `Optional<OnnxValue>`, so the code now unwraps it with `orElseThrow` before accessing the tensor value.
- Added `--stacktrace`, `tee build-output.log`, and artifact upload for the full Gradle log and Gradle problems report to the Android GitHub Actions workflow.
- Verified the corrected Optional-unwrapping expression independently with `kotlinc` against a minimal Java stub matching the official ONNX Runtime API signature.
- Full Android build remains unverified in this environment because the Gradle distribution cannot be downloaded (DNS failure for `services.gradle.org`) and no Android SDK platform is installed.

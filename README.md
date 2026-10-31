# FilterBridge Lab 0.2.2 — local media picker and hardened filtering foundation

FilterBridge Lab is an Android engineering prototype for a privacy-first, on-device social-content filtering product. This version replaces Share-sheet-first testing with direct in-app photo/video selection.

## What the app does now

- Opens Android's system Photo Picker from separate **Choose photo** and **Choose video** actions.
- Uses AndroidX Activity `1.13.0` Activity Result contracts. The Photo Picker gives access only to media selected by the user and automatically falls back to `ACTION_OPEN_DOCUMENT` where the Photo Picker is unavailable.
- Validates content URIs and MIME types, reads optional display-name/size metadata, and checks that the selected URI can be opened.
- Does not request broad photo/video library permissions and does not declare `INTERNET`.
- Decodes image previews off the UI thread, bounds the preview to a maximum 1600-pixel edge, and applies EXIF orientation.
- Previews videos with Android `VideoView` and samples at most six representative frames during local preflight. Sampled frames are immediately discarded.
- Includes global filtering level, activation/hide confidence thresholds, per-category overrides, and user-defined category IDs. Settings are stored locally in `SharedPreferences`.
- Adds explicit analysed-category coverage. A specialist detector that only covers nudity cannot make content appear fully verified while the user also has other categories enabled. Positive detections still trigger the selected action, but the decision states that coverage is incomplete.
- Adds normalized spatial boxes and video time ranges to detection results, so future detectors can describe where and when redaction is needed.
- Runtime exceptions and null detector results become `FAILED` / `UNVERIFIED`, not an empty successful result.
- Preserves the platform-neutral Java filtering core. Android-specific `Uri`, bitmap, and video classes stay outside `filter-core`.
- Retains the old Share-intent parser as a reusable adapter, but Share-sheet handling is no longer the primary demo path or registered in the app manifest.

## Important: AI classification is not connected

This build is **not yet a functioning content-safety detector**. `NotConfiguredAnalyzer` intentionally returns `NOT_ANALYZED`, and the policy engine consequently returns `UNVERIFIED`. It never labels imported content safe.

For images, the app creates a bounded local preview. For videos, it attempts to extract and immediately discard up to six representative frames on API 27+ when source dimensions are available; otherwise it safely abstains. Neither operation classifies content. There is no on-device model file in this ZIP, no real nudity/suggestive-content/music/profanity/violence detector, no automatic blur/hide rendering, and no real-time video filtering.

A production detector must be selected only after checking its model-card license, supported labels, training/evaluation data, false-positive/false-negative rates, language coverage, quantization, and performance on target phones. Generic object recognition is not a substitute for those specialist detectors. Until that selection and benchmark work is complete, the app stays explicitly `UNVERIFIED`. See [MODEL_SELECTION.md](MODEL_SELECTION.md) for the current candidate review and the privacy/licensing reason no model is bundled.

The deterministic policy layer remains separate from perception: AI components produce observations; the user-configured policy decides whether an observation should warn, blur, or hide. The system does not make religious rulings.

## Project modules

| Module | Responsibility |
|---|---|
| `filter-core` | Pure Java content contracts, detector API, category IDs, coverage-aware filtering policy, spatial detections and deterministic policy engine |
| `android-ingest` | Android content-URI adapter, media picker repository, bounded image preview decoder and video frame sampler |
| `demo-app` | Android UI, Photo Picker launchers, preview, analysis status, local filtering settings and lifecycle coordination |

## Build versions

- Android Gradle Plugin: `9.5.0`
- Gradle distribution: `9.6.0`
- Android compile SDK: `36`
- Android target SDK: `36`
- Android min SDK: `26`
- Java source/target: `17`
- AndroidX Activity: `1.13.0` (stable)
- AndroidX ExifInterface: `1.4.2`

The project includes the Gradle distribution properties but not the generated Gradle Wrapper JAR/scripts. GitHub Actions installs Gradle `9.6.0` directly, so CI does not require wrapper files. For a local command-line build, generate the wrapper using the command below or use an installed Gradle `9.6.0`. To generate a wrapper locally, install Gradle `9.6.0` and run:

```bash
gradle wrapper --gradle-version 9.6.0 --distribution-type bin
```

Then build with:

```bash
./gradlew clean :filter-core:test :demo-app:lintDebug :demo-app:assembleDebug
```

## GitHub Actions

`.github/workflows/android-ci.yml` runs on pushes, pull requests, and manual dispatch. It installs JDK 17, Android SDK Platform 36, Build Tools 36.0.0 and Gradle 9.6.0; runs core tests and Android lint; builds the debug APK; and uploads the APK plus build metadata. Open the repository's **Actions** tab, select the successful run, and download `filterbridge-lab-debug-apk-*` from **Artifacts**.

The debug APK is for device testing only, not a Play Store release. The CI job has not been executed against your GitHub repository by this project generator; the first successful Actions run is the authoritative Android build verification.

## Quick device test

For the shortest walkthrough, see [QUICK_START.md](QUICK_START.md).


1. Install the newest `filterbridge-lab-debug-apk-*` artifact.
2. Open **FilterBridge Lab**.
3. Tap **Choose photo**, select one photo, and check its preview/name/type/size/dimensions.
4. Tap **Analyze on device**. The expected result is `NOT_ANALYZED` / `UNVERIFIED` with an explicit message that no classifier is installed.
5. Tap **Choose video**, select a short video, press play to confirm preview, then tap **Analyze on device**. The app should report duration and the number of locally extracted frame samples (up to six), followed by `UNVERIFIED`.
6. Open **Filtering settings**. Change the global level and thresholds, add a custom category such as `smoking`, set a category override, save, close and reopen settings to confirm persistence.
7. Tap **Clear**. The preview and selected URI reference are released and the home state returns.
8. Enable Airplane mode and repeat photo selection/preview. The app should not require a network connection.

## Privacy and resource handling

- No Internet permission is declared.
- No `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or legacy external-storage permission is declared.
- The system Photo Picker grants access to selected content instead of the whole media library.
- No media file is copied into app storage by the importer.
- Image decoding, metadata reads, and video frame sampling run on a dedicated background executor.
- Image previews are bounded to avoid decoding a full-resolution image into the UI heap.
- Video sampling is capped at six frames; each bitmap is recycled immediately after sampling. API 27+ uses platform-scaled frame extraction when source dimensions are available. API 26, or files with unknown dimensions, are deliberately skipped by the sampler rather than risking an unbounded full-resolution frame decode; in those cases the reported sample count may be zero.
- Persistable URI read permission is requested when supported and released when the selection is cleared or replaced. Unsupported transient grants are handled without crashing.
- No local media history or analysis history is stored. User filtering preferences alone are persisted.

## Known limitations before production release

1. No trained content-classification model is included or integrated.
2. No production blur/hide renderer is connected to model detections.
3. Video frame sampling is a preflight only; it does not cover every frame and is not real-time protection.
4. Audio extraction, speech recognition, music classification, and OCR are not implemented.
5. Threshold defaults are placeholders and need calibration against representative evaluation sets.
6. Device tests are still required for Samsung/Android versions, cloud-backed picker providers, unusually large images/videos, permission revocation, process death, thermal load, and battery use.
7. This is an engineering test build, not a production security or content-safety guarantee.

## Architecture rule

Platform adapters may depend on `filter-core`; `filter-core` must not depend on Android APIs or any social platform. Future Facebook, Instagram, YouTube, share, import, and companion-mode integrations must normalize content into the same core contracts.

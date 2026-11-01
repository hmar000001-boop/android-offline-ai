# ScreenVeil

ScreenVeil is an experimental Android-wide, on-device screen protection application. It uses Android `MediaProjection` to capture a user-authorized display, runs Gemma 4 E2B through Google's LiteRT-LM Kotlin runtime, asks the model to identify visible image/video regions and their moderation categories, and displays redacted copies of those regions in a non-interactive overlay.

**This is an experimental research application, not a certified content-safety product.** A general-purpose vision-language model is not a calibrated NSFW classifier or a deterministic object detector. Its classifications and box coordinates can be wrong. Do not rely on ScreenVeil as the sole child-safety, workplace-safety or other high-assurance control.

## Product policy implemented

- Inference and redaction are offline after the model has been installed.
- All detected swimwear is redacted, including ordinary beach and sports swimwear.
- Suggestive media, sexualized poses, explicit nudity and sexual activity are redacted.
- Ordinary clothing and media classified as safe are allowed.
- Uncertain media is redacted by default; this conservative behavior can be disabled in Settings.
- Redaction is configurable: Gaussian-like blur, pixelation, or opaque cover.
- The analysis interval and redaction strength are configurable.

## Selected model

| Property | Value |
|---|---|
| Model | Gemma 4 E2B Instruct, LiteRT-LM build |
| Repository | `litert-community/gemma-4-E2B-it-litert-lm` |
| Artifact | `gemma-4-E2B-it.litertlm` |
| Immutable source commit | `6e5c4f1e395deb959c494953478fa5cec4b8008f` |
| Repository license | Apache-2.0 |
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| Exact artifact size | 2,588,147,712 bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |

The model is **not bundled in the APK**. The user explicitly starts its download. It is stored in private application storage and is accepted only when its exact size threshold and pinned SHA-256 match. The download URL is pinned to the immutable Hugging Face commit, not the moving `main` branch. The hash was checked against the Hugging Face file page for that commit. The source repository identifies the model artifact as Apache-2.0.

Allow at least **2.9 GB of free internal storage** before downloading. The model itself is about 2.59 GB decimal; extra free space is needed during download and verification.

Only this one model is approved in the current build. Model runtime integration is behind `ScreenContentDetector`, and model metadata is isolated in `GemmaModelCatalog`, so another model adapter/catalog entry can be added later. This build intentionally does **not** accept arbitrary model files or unsigned external model packages: a checksum alone proves file integrity, not publisher authenticity. New selectable models must first have their source, signed/pinned artifact, license and LiteRT compatibility audited and then be added to the trusted catalogue. The Settings screen reports the currently approved model.

## What the app does

1. The user explicitly grants Android screen-capture consent and selects **Entire screen**.
2. A foreground service receives frames through `MediaProjection` and `ImageReader`.
3. A single LiteRT-LM worker sends sampled frames to Gemma 4 and requests normalized media-region rectangles plus one of `allow`, `swimwear`, `suggestive`, `explicit`, or `uncertain`.
4. The parser validates the model's JSON, region count, media type and normalized coordinates. Malformed responses are not treated as safe.
5. The deterministic policy applies the user's settings independently of the model.
6. A redaction worker crops each selected media rectangle, applies the chosen effect and displays it using a non-touchable, region-sized `TYPE_APPLICATION_OVERLAY` window. Separate region-sized windows avoid creating one invisible full-screen overlay that could obscure touches everywhere. Android may still block taps directly over a redacted media rectangle.
7. While a detected video region remains active, the app refreshes the redacted patch from newer captured frames so the patch does not remain a frozen screenshot. A region is re-classified periodically; the box can still become stale after scrolling or a layout change.

Gemma itself is currently responsible for both media-region discovery and content classification. There is no separate trained screen-layout/object-detection model in this version. This is a deliberate experimental choice and is one of the largest accuracy risks.

## Build the APK

Requirements:

- JDK 21
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Internet access for the first Gradle/dependency resolution

On macOS/Linux:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

On Windows:

```bat
gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

`gradlew` and `gradlew.bat` are small bootstrap scripts that fetch the pinned Gradle 9.6.0 distribution and verify its SHA-256. This repository does not include the binary Gradle Wrapper JAR. GitHub Actions installs the same Gradle version using `gradle/actions/setup-gradle`.

The APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This is a debug-signed APK for controlled device testing, not a Play Store release. No production signing key is included.

## GitHub Actions

`.github/workflows/android.yml` runs on pushes to `main`, pull requests and manual dispatch. It installs Temurin JDK 21, Android SDK 36, Build Tools 36.0.0 and Gradle 9.6.0, then runs:

```bash
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

It uploads the debug APK and test/lint reports as workflow artifacts. The workflow uses read-only repository permissions and does not require secrets for a debug build. Push this project to a GitHub repository and the workflow will run; this session has not pushed to a repository or executed a GitHub Actions run.

## First run

1. Install the debug APK.
2. Open ScreenVeil and tap **Download and verify Gemma 4**. This downloads around 2.59 GB. Use a reliable connection and ensure at least 2.9 GB free.
3. Grant **Display over other apps** permission when Android requests it.
4. Tap **Start protection**.
5. In Android's screen-capture dialog, select **Entire screen** to monitor other applications, then approve capture. Android 14+ may also offer app-only sharing; selecting a single app does not monitor the rest of the device.
6. Keep the foreground-service notification available. It contains a Stop action.

The app does not start capture automatically at boot. Capture stops when the user stops it, Android revokes the projection, overlay permission is removed, or repeated inference/parsing errors occur.

## Permissions and privacy

- `INTERNET`: used only for the explicitly requested model download. Inference and redaction do not make network requests.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`: keep the user-visible capture service running.
- `POST_NOTIFICATIONS`: request the persistent notification permission on Android 13+.
- `SYSTEM_ALERT_WINDOW`: display redaction overlays above other applications.

No Accessibility Service is used. The app does not inspect other apps' private view hierarchies. Captured frames are handled in memory; a temporary JPEG is written to the private app cache for LiteRT-LM input and deleted after inference. The app contains no analytics, ads, account system, remote-command channel, boot receiver or screenshot-upload component. `allowBackup=false` is configured.

## Android constraints and limitations

1. **No first-frame guarantee.** Inference takes time. Content may be visible before its region has been identified and redacted.
2. **Model quality.** Gemma is a general-purpose multimodal model, not an adult-content specialist trained for this policy. The all-swimwear rule is expressed in the prompt and still may be missed. No reliable accuracy claim is made.
3. **Box accuracy.** Coordinates are generated by the model, not an object detector with validated localization metrics. The app validates their numeric range, but valid coordinates can still point to the wrong place.
4. **Screen transitions.** The model re-classifies at the configured interval. During that interval, an old box can be refreshed over the new content at that same position. Boxes expire after a bounded time, but fast scrolling can still cause false or missed masks.
5. **Video.** Existing redaction patches are refreshed from new frames every 250 ms while their region is active. This is not a frame-perfect video filter; inference is periodic and the overlay may lag.
6. **Protected windows.** Android may exclude `FLAG_SECURE` or otherwise protected content from MediaProjection. ScreenVeil does not bypass those restrictions.
7. **Overlay behavior.** Application overlays are below critical system windows and the OS/OEM can change their visibility or position. Each redaction is a separate region-sized window; Android may block taps within the redacted rectangle. Samsung One UI behavior must be checked on the target device.
8. **Performance.** The model is 2.59 GB on disk. There are no S24 FE measurements for latency, peak RAM, battery, thermal behavior or sustained inference in this project. GPU initialization is attempted first; CPU is the fallback. CPU inference may be too slow for practical continuous protection.
9. **Not a child-safety filter.** The app may miss content, produce false positives or be unable to capture protected material. Do not treat it as a reliable safeguard for children or other high-risk uses.

## Architecture

```text
MediaProjection + ImageReader (latest-frame policy)
    -> ScreenContentDetector interface
        -> Gemma 4 E2B / LiteRT-LM
            -> media-region rectangles + content categories
    -> strict response parser
    -> deterministic ModerationPolicy
    -> bitmap crop + configurable redaction
    -> secure, non-touchable WindowManager overlay
```

`ScreenContentDetector` isolates model runtime integration. `GemmaModelCatalog` contains the audited model descriptor and immutable artifact reference. `ModerationPolicy` owns user policy. `OverlayController` owns mask placement and bitmap lifetime. `ScreenProtectionService` owns one MediaProjection session and bounded worker queues.

## Tests and validation status

Unit tests cover the deterministic policy and model-response parser. This execution environment has JDK 21 but no Android SDK, Gradle installation or network/DNS access, so an Android APK build and Gradle tests could not be executed here. The GitHub Actions workflow is configured for a network-enabled runner, but it has not yet run. The application has not been installed or device-tested on the Galaxy S24 FE.

Before considering it usable, follow `docs/DEVICE_VALIDATION.md` and test the model on a properly licensed, adult-only evaluation set. Measure false negatives and false positives separately for swimwear, suggestive content, explicit content, ordinary clothing, artwork and medical imagery. Record sustained temperature, battery and memory at the chosen frame interval.

## Official references

- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android 14 MediaProjection behavior changes](https://developer.android.com/about/versions/14/behavior-changes-14)
- [Android overlay permission](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW)
- [LiteRT-LM Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)
- [Gemma 4 LiteRT-LM artifact](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm)
- [LiteRT-LM Android artifact](https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android)

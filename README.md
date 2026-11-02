# ScreenVeil

ScreenVeil is an experimental Android-wide, fully on-device screen-content redaction app. It uses an explicitly enabled Android `AccessibilityService` to observe window-change events and request screenshots of the active application window. It does **not** use `MediaProjection`, initiate a screen-sharing session, display the Android screen-recording consent dialog, or require `SYSTEM_ALERT_WINDOW`.

The first setup requires the user to enable ScreenVeil once in Android Accessibility settings. Android controls that approval; the application cannot silently enable its own accessibility service. After approval, the enabled service starts protection automatically whenever the saved protection setting is on and the verified model is installed.

**This is an experimental research application, not a certified content-safety product.** Gemma 4 E2B is a general-purpose vision-language model, not a calibrated adult-content classifier or a deterministic object detector. Its classifications and box coordinates can be wrong. Do not rely on ScreenVeil as the sole child-safety, workplace-safety or other high-assurance control.

## Product policy

- Inference and redaction are offline after the user installs the model.
- All detected swimwear is redacted, including ordinary beach and sports swimwear.
- Suggestive media, sexualized poses, explicit nudity and sexual activity are redacted.
- Ordinary clothing and media classified as safe are allowed.
- Uncertain media is redacted by default; this conservative behavior can be disabled in Settings.
- Redaction is configurable: Gaussian-like blur, pixelation, or opaque cover.
- Redaction strength and screenshot analysis interval are configurable. The status panel reports both Gemma inference time and capture-to-overlay end-to-end time for device benchmarking.

The policy is deterministic after classification. For example, a region classified as `swimwear` is always redacted; the model cannot override that choice.

## Platform and capture design

- **Minimum Android version: Android 14 (API 34).** This is required for `AccessibilityService.takeScreenshotOfWindow()`.
- The service declares `android:canTakeScreenshot="true"` and `android:canRetrieveWindowContent="true"` in its accessibility metadata, and requests `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` so it can obtain an active application window ID and its screen bounds.
- It uses `takeScreenshotOfWindow(windowId, executor, callback)`. Android documents this API for capturing a window underneath an accessibility overlay without capturing the overlay's pixels.
- The service reads accessibility event type/package metadata and active window IDs/bounds only. It does **not** query `AccessibilityNodeInfo`, read text/content descriptions, inspect UI labels, click controls, perform gestures, or automate other applications.
- Redaction uses one `TYPE_ACCESSIBILITY_OVERLAY` window containing a single composed transparent bitmap for every redacted region. The window is non-focusable and non-touchable; no separate draw-over-other-apps permission is requested.
- The screenshot API may refuse protected/secure windows and may return a screenshot rate-limit error. ScreenVeil does not bypass these restrictions.

### What “automatic” means

1. Install ScreenVeil and open it.
2. Install Gemma 4 using the explicit download button.
3. Tap **Enable protection**. Android Accessibility settings open.
4. Find ScreenVeil under Installed apps and manually enable it.
5. Android binds the service. If protection is still enabled in ScreenVeil's saved preference and the verified model exists, it initializes Gemma and starts inspecting app-window screenshots. No screen-recording dialog is shown.
6. Use the Stop button in ScreenVeil to pause monitoring. The Android accessibility service remains enabled, but no screenshots are requested while protection is paused.

The service's enablement is managed by Android. If the user disables it in Android settings, it stops. If Android or the device vendor kills/restarts the service, Android may rebind it; the saved preference is respected on reconnection.

## Selected model and provenance

| Property | Value |
|---|---|
| Model | Gemma 4 E2B Instruct, LiteRT-LM build |
| Repository | `litert-community/gemma-4-E2B-it-litert-lm` |
| Artifact | `gemma-4-E2B-it.litertlm` |
| Immutable source commit | `6e5c4f1e395deb959c494953478fa5cec4b8008f` |
| Repository license | Apache-2.0 (as declared by model repository; verify notices at redistribution time) |
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| Exact artifact size | 2,588,147,712 bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |

The model is **not bundled in the APK**. The user explicitly starts its download. It is stored in private application storage and accepted only when its exact expected size and pinned SHA-256 match. The download URL points to the immutable Hugging Face commit, not the moving `main` branch.

Allow at least **2.9 GB of free internal storage** before downloading. The model itself is about 2.59 GB decimal; extra free space is needed during download and verification.

Only this model is approved and selectable in the current build. The `ScreenContentDetector` interface is the runtime extension point, but additional model profiles are not exposed until their Android adapter, immutable artifact, checksum, provenance and license have been verified. This build intentionally does **not** accept arbitrary model files or unsigned external model packages: a checksum alone proves file integrity, not publisher authenticity. The candidate review is documented in [`docs/MODEL_GOVERNANCE.md`](docs/MODEL_GOVERNANCE.md).

## Gemma structured output

The application does not depend on a prompt-only “please return JSON” instruction. It calls LiteRT-LM 0.18.0 `ResponseFormat.json(schema)` with a constrained schema, disables thinking, and disables speculative decoding before the engine is created and in the conversation configuration. The latter avoids a reported Gemma 4 E2B constrained-decoding defect involving speculative/MTP decoding in LiteRT-LM. The model response is still parsed and validated in Kotlin; malformed output is an error, never a safe/empty decision.

See [`docs/GEMMA_STRUCTURED_OUTPUT.md`](docs/GEMMA_STRUCTURED_OUTPUT.md).

## Processing pipeline

1. Accessibility window-change, scroll and content-change events schedule a debounced capture request. A periodic refresh is also used while protection is active.
2. ScreenVeil selects the active `TYPE_APPLICATION` accessibility window and copies its ID and bounds. It does not read the window's view hierarchy.
3. `takeScreenshotOfWindow()` returns a `HardwareBuffer`. ScreenVeil copies the pixels to a CPU bitmap and closes the buffer in all callback paths.
4. A single worker sends a downscaled screenshot to Gemma 4 and requests media rectangles normalized to 0–1000, media type and one decision: `allow`, `swimwear`, `suggestive`, `explicit`, or `uncertain`.
5. The Kotlin parser validates the JSON structure, exact fields, category, media type, coordinate types/ranges, rectangle ordering and minimum dimensions.
6. The deterministic policy chooses which returned regions need redaction.
7. The redaction worker crops each selected region, applies the configured effect and composites every patch into one transparent bitmap sized to the captured application window. A single accessibility overlay view swaps this bitmap as one update.
8. On a window switch, scroll, any window-content-change event, invalid model response, capture failure or inference failure, ScreenVeil replaces detailed masks with a one-pixel opaque bitmap stretched over the active app window. It keeps that cover until a valid fresh analysis can replace it. After three consecutive model/structured-output failures, it stops expensive retry inference, leaves the cover in place and requires an explicit stop/restart to retry Gemma initialization. This intentionally trades temporary visibility/usability for privacy; touch events pass through because the overlay is non-touchable.
9. Every capture carries a screen-generation token and monotonically increasing sequence. Results from an earlier window/scroll/content change are discarded, and an older frame cannot overwrite a newer overlay. While inference is busy, at most one follow-up screenshot is requested; there is no unbounded frame queue. Existing valid redactions may be refreshed from a newer screenshot while inference runs.

Gemma currently performs both media-region discovery and content classification. There is no separate trained screen-layout/object-detection model. This is an important accuracy limitation: a VLM can miss an image, fail to return a tight box, or misclassify a beach photograph. `takeScreenshotOfWindow`, constrained JSON and the opaque error fallback improve the capture/failure path; they do not protect against a syntactically valid but visually incorrect `allow` or empty-regions response. Detection false negatives remain an open release gate.

## Latency, video and Android limitations

This is not a screen recorder and is not a 30-FPS redaction pipeline. Android may reject screenshot calls made too frequently; ScreenVeil conservatively spaces requests with a 750 ms local minimum, retries Android rate-limit failures, and uses event-triggered/debounced captures plus periodic refresh. Gemma inference on a 2.6 GB multimodal model also takes time and can be expensive on memory, battery and thermals. During notified window/scroll/content changes the opaque cover stays visible until a fresh analysis completes. Content that changes without a useful accessibility event can still be visible until the next periodic sample. Fast-moving video is sampled occasionally and cannot be guaranteed frame-by-frame.

Android can refuse capture for `FLAG_SECURE` or other protected content. System surfaces, DRM-protected video, secure banking screens and some application windows may be unavailable. ScreenVeil must not attempt to bypass these restrictions.

The selected API is Android 14+ (`takeScreenshotOfWindow`, API 34). Lower Android releases are not supported by this build. Device-vendor behavior, especially Samsung One UI window IDs, overlay coordinates, background lifecycle and screenshot throttling, must be verified on the target Galaxy S24 FE.

## Build the APK

Requirements:

- JDK 21
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Internet access for first Gradle/dependency resolution

On macOS/Linux:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

On Windows:

```bat
gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

`gradlew` and `gradlew.bat` bootstrap the pinned Gradle 8.14.6 distribution and verify its SHA-256. The repository does not include the binary Gradle Wrapper JAR. GitHub Actions installs the same Gradle version using `gradle/actions/setup-gradle`.

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This is a debug-signed APK for controlled device testing, not a Play Store release. No production signing key is included.

## GitHub Actions

`.github/workflows/android.yml` runs on pushes to `main`, pull requests and manual dispatch. It installs Temurin JDK 21, Android SDK 36, Build Tools 36.0.0 and Gradle 8.14.6, then runs:

```bash
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

It uploads the debug APK and test/lint reports as workflow artifacts. It uses read-only repository permissions and requires no secrets for a debug build. This source archive has not been pushed to a GitHub repository or run by GitHub Actions in this delivery. Local Android compilation also requires an installed Android SDK Platform 36 and Build Tools 36.0.0.

## Privacy and permissions

- `INTERNET`: used only when the user explicitly downloads the pinned Gemma model. Inference and redaction do not make network requests.
- The service is declared with `android.permission.BIND_ACCESSIBILITY_SERVICE`, which prevents ordinary third-party apps from binding to it. The user must enable it explicitly in Android settings.
- `canRetrieveWindowContent=true` is required to obtain interactive application-window IDs and bounds. The code does not call node-tree APIs or read other apps' text/content descriptions; inspect the source to verify this claim.
- `canTakeScreenshot=true` is required by Android's accessibility screenshot API.
- No `MediaProjection`, foreground capture service, screen-recording session, `SYSTEM_ALERT_WINDOW`, notification permission or boot receiver is used.
- Screenshots are processed locally. A temporary JPEG is written to the private app cache for LiteRT-LM input and deleted after inference; stale temporary frame files are removed at next app launch.
- There is no analytics, ads, account system, remote-command channel or screenshot-upload component. `allowBackup=false` is configured.

Accessibility access is powerful. Review `SECURITY.md` before enabling it. ScreenVeil intentionally uses this capability only for screenshot-based visual moderation.

## Source layout

- `capture/ScreenProtectionAccessibilityService.kt`: event handling, active-window screenshot requests, throttling, worker orchestration and service lifecycle.
- `model/ScreenModerationDetector.kt`: LiteRT-LM / Gemma integration and constrained-output validation.
- `model/GemmaModelCatalog.kt` and `ModelInstaller.kt`: one trusted, immutable model artifact with size and SHA-256 checks.
- `policy/ModerationPolicy.kt`: the user-defined redaction policy.
- `overlay/BitmapRedactor.kt` and `OverlayController.kt`: bitmap redaction and accessibility overlay placement.
- `AppPreferences.kt`: local settings.

## Validation status

A successful GitHub build proves only that the Android source compiles and tests pass. It does not prove detection quality, bounding-box accuracy, screenshot frequency, battery suitability, thermal suitability or compatibility with every application. These require a separate device-testing phase on the Galaxy S24 FE with lawfully obtained, properly licensed evaluation images and videos. Follow [`docs/DEVICE_VALIDATION.md`](docs/DEVICE_VALIDATION.md). See [`docs/DELIVERY_VALIDATION.md`](docs/DELIVERY_VALIDATION.md) for the checks actually executed for this source delivery and the checks still outstanding.

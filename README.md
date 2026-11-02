# ScreenVeil

ScreenVeil is an experimental Android-wide screen-content redaction app. It inspects screenshots of the active application window through an explicitly enabled Android `AccessibilityService`, then applies blur, pixelation or an opaque cover to detected regions. It does not use `MediaProjection`, a screen-recording session or `SYSTEM_ALERT_WINDOW`.

**ScreenVeil is experimental software, not a certified safety product.** Models can miss content or produce false positives. It cannot guarantee frame-by-frame video protection, detection of every swimsuit, or protection of Android secure/DRM windows. Do not use it as the sole child-safety, workplace-safety or other high-assurance control.

## Model approval policy

This project uses a **balanced model-approval policy**:

- accept established research/open-source projects and named publishers when the artifact, license declaration, source revision and runtime are sufficiently documented;
- pin an exact artifact SHA-256 and enforce download-size bounds;
- permit internet access only for explicit, user-initiated downloads from the fixed catalog and approved HTTPS hosts;
- run all inference locally; screenshots and inference results are not uploaded;
- do not allow arbitrary model URLs, user-imported model files, training or fine-tuning;
- distinguish artifact integrity from model safety, publisher trust and detection accuracy. A matching hash proves only that the bytes match the catalog.

The catalog intentionally excludes candidates whose licensing, artifact provenance, runtime compatibility or offline behavior do not meet this policy. See [`docs/MODEL_GOVERNANCE.md`](docs/MODEL_GOVERNANCE.md).

## Approved model catalog

The default **Balanced multi-model · offline** profile uses three pretrained artifacts and does not require Gemma:

| Model | Responsibility | Runtime | Declared license / provenance | Important limitation |
|---|---|---|---|---|
| Google EfficientDet-Lite0 | Locate people in a screenshot | Standalone TensorFlow Lite Task Vision | Apache-2.0 model; model bytes pinned | Person detector only; it does not classify clothing or swimwear |
| Yahoo OpenNSFW ResNet-50 ONNX | General binary NSFW score | ONNX Runtime Android | Original Yahoo weights BSD-2-Clause; third-party ONNX conversion has separate notices | Old general pornographic-content classifier; not a swimwear classifier |
| ViT-Tiny NSFW 384 ONNX | Independent general NSFW score and tiled fallback | ONNX Runtime Android | Apache-2.0 declared by the ONNX repository; source/conversion evidence documented | Repository-declared license and self-reported evaluation; not independently security-audited |

Two specialist profiles are selectable:

- **Balanced multi-model · offline (default):** EfficientDet-Lite0 + Yahoo OpenNSFW + ViT-Tiny NSFW. The ViT also scans up to nine overlapping screen tiles if no person is detected. Approximately 60 MB of model artifacts based on catalog bounds.
- **Fast specialized · offline:** EfficientDet-Lite0 + Yahoo OpenNSFW. Approximately 38 MB. It omits the second classifier and tile fallback.

**Gemma 4 E2B Instruct is an optional, separate profile.** It is a general-purpose vision model with a roughly 2.6 GB download; neither specialist profile depends on it. The official Gemma license is Apache-2.0, while the Android LiteRT-LM packaging is separately pinned and documented.

A fourth profile entry, **Swimwear-aware · Google Open Images**, is now shown as pending but is deliberately disabled. It is intended to use Google's SSD MobileNet V2 trained on Open Images V4, whose 600-class outputs include `Swimwear` and `Person`. Google's published checkpoint declares Apache-2.0, but the reviewed artifact is a SavedModel, not a hash-pinned Android TFLite model. The app will not download or execute an unconverted/unverified model. Conversion, output/label validation, exact SHA-256 pinning and S24 FE testing are required before it can be enabled. The existing EfficientDet-Lite0 profiles are preserved and unchanged except for the coordinate-normalization correction.

Until that candidate passes admission, no currently approved model provides validated swimwear-specific detection. In the specialist pipeline, a person region that is not positively classified as NSFW remains `UNCERTAIN`; the app's default conservative policy redacts uncertain regions. This is not equivalent to correctly recognizing every swimsuit, swim trunk, rash guard or sports swimsuit, and it can over-redact ordinary people. The two NSFW classifiers do not provide a swimwear label. See [`docs/MODEL_GOVERNANCE.md`](docs/MODEL_GOVERNANCE.md).

## Model download and offline behavior

- Model downloads happen only after the user selects **Download and verify**.
- Every catalog entry has a fixed HTTPS URL, approved host, SHA-256 and lower/upper byte bounds.
- Redirects are followed manually; every redirect hop must remain HTTPS and on an approved hosting domain.
- Downloaded bytes are streamed to a temporary file, bounded by the catalog maximum, hashed, and renamed into private app storage only after a full hash match.
- Inference is local. The only application code that opens a network connection is `ModelInstaller`.
- The standalone TensorFlow Lite Task Vision runtime is used for person detection; MediaPipe Tasks Vision is not a runtime dependency.
- No analytics, telemetry, remote inference or model marketplace is implemented.

A model can still be inaccurate or malicious despite a valid hash. The catalog is not an independent security certification. A complete dependency SBOM and physical-device test are release gates.

## Interpretation and protection limits

- OpenNSFW and ViT-Tiny NSFW are general NSFW classifiers, not clothing classifiers. Their agreement does not prove that an image is safe.
- In the specialist path, a person box that is not positively flagged is returned as `UNCERTAIN`. With **Redact uncertain content** enabled (the conservative default), that box is covered. This favors missed-allow reduction at the cost of substantial false-positive masking.
- If no person boxes are detected, Balanced scans up to nine overlapping tiles with ViT. If one or more persons are detected, the tile fallback is not run; unrelated media outside those boxes can be missed.
- Thresholds are engineering starting values, not calibrated performance results. No independent ScreenVeil dataset has established per-category precision, recall or false-negative rates.
- The current approved catalog has no swimwear-specific model. The app must not claim reliable swimwear detection.

## Product policy

- Explicit and detected NSFW content is intended to be redacted.
- Uncertain person regions are redacted by default.
- The user can choose blur, pixelation or opaque cover, change intensity and tune the screenshot interval.
- The policy layer is deterministic after model output, but it cannot correct a model that misses a region or incorrectly labels content as safe.

## Platform and capture design

- Minimum Android version: Android 14 (API 34), required by `AccessibilityService.takeScreenshotOfWindow()`.
- Android requires the user to enable ScreenVeil manually in Accessibility settings.
- The service uses `takeScreenshotOfWindow(windowId, executor, callback)` and accessibility event metadata plus window IDs/bounds. It does not read view nodes, app text, content descriptions, click controls or perform gestures.
- One `TYPE_ACCESSIBILITY_OVERLAY` window contains all composed redaction regions. It is non-focusable and non-touchable.
- Android may refuse secure/DRM window captures or rate-limit screenshots. ScreenVeil does not bypass those restrictions.

## Protection and failure behavior

1. Window, scroll and content-change events invalidate stale geometry.
2. The previous detailed overlay is replaced by an opaque cover while a fresh capture is analysed.
3. A single worker runs the selected inference pipeline; results with an older generation/sequence are discarded.
4. Invalid outputs, capture errors and inference failures use an opaque fallback when Android allows the overlay to be drawn.
5. After repeated model errors, expensive inference attempts stop but the fallback cover remains until the user explicitly stops/restarts protection.
6. The app reports model inference time separately from capture-request-to-overlay-commit time.

Opaque fallback reduces exposure during technical failures but cannot protect content that a model incorrectly classifies as safe or fails to locate. The service samples screenshots; it is not a 30-FPS video filter. The default screenshot interval is 1.5 seconds, with a configurable minimum of 0.75 seconds. Content can change between samples and may be briefly visible before the app receives a useful accessibility event and applies the cover.

## Build

Pinned versions are in `settings.gradle.kts` and `app/build.gradle.kts`. CI uses JDK 21, Android SDK Platform 36 / Build Tools 36.0.0 and Gradle 8.14.6. Run:

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

The GitHub Actions workflow builds a debug APK and uploads unit-test / lint reports plus the full Gradle log after failure. The local wrapper script checks the pinned Gradle distribution SHA-256; the binary wrapper JAR is not included.

## Verification status

The source archive includes tests for model-catalog policy, profiles, parser validation, pixel-to-normalized detector-box conversion, screenshot-to-overlay geometry and moderation policy. See [`docs/DELIVERY_VALIDATION.md`](docs/DELIVERY_VALIDATION.md) for checks performed in this environment and checks still outstanding. A successful Android Gradle build, Android test suite, model inference on device, and Galaxy S24 FE accuracy/performance measurements are required before any production/release claim.

## Privacy and permissions

- `INTERNET` is used only for explicit model downloads from the fixed catalog. Inference is local after installation.
- Model files and temporary frames are stored in private app storage/cache; temporary frames are deleted after inference.
- The user controls the Accessibility permission and protection start/stop state.
- No analytics, telemetry or screenshot-upload endpoint is implemented.

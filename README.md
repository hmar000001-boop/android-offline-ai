# ScreenVeil

ScreenVeil is an experimental Android-wide screen-content redaction app. It inspects screenshots of the active application window through an explicitly enabled Android `AccessibilityService`, then applies blur, pixelation or an opaque cover to detected regions. It does not use `MediaProjection`, a screen-recording session or `SYSTEM_ALERT_WINDOW`.

**ScreenVeil is an experimental research build, not a certified safety product.** Its classifiers can miss content or produce false positives. It cannot guarantee frame-by-frame video protection, detection of every swimsuit, or protection of Android secure/DRM windows. Do not use it as the sole child-safety, workplace-safety or other high-assurance control.

## Local model profiles

The default profile is **Balanced multi-model · offline**. It does not use Gemma. It combines four existing pretrained artifacts:

| Model | Purpose | Runtime | Model license / pinned artifact |
|---|---|---|---|
| Google EfficientDet-Lite0 | Locate people in the screenshot | MediaPipe Tasks Vision | Apache-2.0; SHA-256 pinned in `SpecializedModelCatalog` |
| Electblake Clothing Type EfficientNetV2-S | Classify person-centred clothing crops; includes `swim_bikini` and `lingerie` | ONNX Runtime | MIT classifier / Apache-2.0 base; immutable HF revision and SHA-256 pinned |
| Yahoo OpenNSFW ResNet-50 | Independent general pornographic-content score | ONNX Runtime | Yahoo weights BSD-2-Clause; immutable release and SHA-256 pinned |
| Marqo-derived ViT-Tiny NSFW | Second independent NSFW score; nine overlapping screen tiles when no person is detected | ONNX Runtime | Apache-2.0 repository; immutable HF revision and SHA-256 pinned |

The combined specialist download is approximately **142 MB** (the exact downloaded bytes are enforced by SHA-256). The inference path is local after download; it does not upload screenshots or send model-inference requests to a server.

Two alternate profiles are available:

- **Fast specialized · offline**: EfficientDet-Lite0 + Electblake clothing classifier + Yahoo OpenNSFW. It omits the ViT and tile scan to reduce model storage and compute.
- **Gemma 4 vision**: separate optional LiteRT-LM / Gemma 4 E2B multimodal path. Its model is approximately 2.59 GB and is not required by either specialist profile.

No model is trained, fine-tuned, quantized or built from scratch in ScreenVeil. No user-supplied model import is allowed. Downloads are initiated by the user and every artifact is size-checked and SHA-256 verified before use. Details and exclusions are in [`docs/MODEL_GOVERNANCE.md`](docs/MODEL_GOVERNANCE.md) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

### Important interpretation limits

- The clothing model has only five labels: `denim_shorts`, `lingerie`, `maid`, `nurse`, `swim_bikini`. It has no general `ordinary clothing` / `other` class. Therefore **none** of `denim_shorts`, `maid` or `nurse` is treated as proof that the person is safe. Those outputs are `uncertain` and are redacted by default.
- The clothing model's `swim_bikini` class does not establish coverage of one-piece swimsuits, swim trunks, rash guards, every sports swimsuit, or small/occluded people.
- Yahoo OpenNSFW and the ViT classifier are binary/general NSFW classifiers, not dedicated swimsuit detectors. Their scores are combined conservatively; they do not establish semantic certainty.
- In the Balanced profile, if EfficientDet returns no person boxes, the ViT inspects up to nine overlapping tiles and masks positive tiles as a whole. This is a fallback, not guaranteed discovery of every image/media item.
- Thresholds are conservative starting heuristics. They have not been calibrated on a representative ScreenVeil dataset or measured on a Galaxy S24 FE.

## Product policy

- All detected swimwear is intended to be redacted, including ordinary beach and sports swimwear.
- Suggestive media, explicit nudity and sexual activity are redacted.
- Ordinary content is allowed only when the selected model can make a supported decision; uncertain person crops are redacted by default.
- The redaction policy is deterministic after classification. A model score cannot override the `SWIMWEAR`, `SUGGESTIVE` or `EXPLICIT` redaction decisions.
- The user can choose blur, pixelation or opaque cover, change intensity and tune the screenshot interval.

## Platform and capture design

- Minimum Android version: Android 14 (API 34), required by `AccessibilityService.takeScreenshotOfWindow()`.
- Android requires the user to enable ScreenVeil manually in Accessibility settings.
- The service uses `takeScreenshotOfWindow(windowId, executor, callback)` and reads accessibility event metadata plus window IDs/bounds. It does not read view nodes, app text, content descriptions, click controls or perform gestures.
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
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

The GitHub Actions workflow builds a debug APK and uploads unit-test / lint reports. The local `gradlew` script bootstraps the pinned distribution and checks its SHA-256; the binary wrapper JAR is not included.

## Verification status

The source archive includes unit tests for parser validation, geometry, moderation policy and conservative clothing-classifier interpretation. Local static/smoke checks are documented in [`docs/DELIVERY_VALIDATION.md`](docs/DELIVERY_VALIDATION.md). The Android Gradle build, Android test suite and Galaxy S24 FE performance/accuracy measurements must be run in a networked Android build environment and on the actual device before any production/release claim.

## Privacy and permissions

- `INTERNET` is used only for explicit model downloads. Inference and redaction are on-device after installation.
- Model files and temporary frames are stored in private app storage/cache and temporary frames are deleted after inference.
- The user controls the Accessibility permission and protection start/stop state.
- No analytics or screenshot-upload endpoint is implemented.

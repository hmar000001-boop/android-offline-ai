# ScreenVeil 1.6.0 (R6)

ScreenVeil is an experimental Android-wide, offline screen-content redaction application. After the user explicitly enables its Android `AccessibilityService` and taps **Start protection**, it captures the active application window with Android's `takeScreenshotOfWindow()` API, performs local model inference, and draws a non-focusable, non-touchable accessibility overlay over selected pixels. It does not use `MediaProjection` or `SYSTEM_ALERT_WINDOW`.

**This is experimental software, not a certified safety product.** Model accuracy, segmentation quality, latency, battery and thermal behavior have not yet been validated on the Samsung Galaxy S24 FE. Do not treat it as a high-assurance child-safety control or assume it catches every explicit image, swimsuit, video frame or application layout.

## R6 safety contract

The safety policy is independent of model labels and confidence:

- Maximum final redaction mask for one detection region: **35% of the application window**.
- Maximum union of all rendered masks: **45% of the application window**.
- These limits apply to every decision, including `EXPLICIT`, `SUGGESTIVE`, `SWIMWEAR` and `UNCERTAIN`. No category bypass exists.
- A mask that exceeds a hard limit is rejected and protection stops; it is not silently shrunk to an arbitrary rectangle.
- If combined visible mask coverage remains at or above **35% for 3 seconds**, an independent scheduled watchdog stops protection and clears the overlay.
- Maximum protection session during this test period: **5 minutes**. Protection then switches OFF and removes the overlay.
- Screenshot callback deadline: **5 seconds**. Inference deadline: **8 seconds**. A deadline expiry stops protection, invalidates late results and poisons the current process; after a hard worker timeout, force-stop ScreenVeil from Android App Info before trying again.
- Missing, malformed or failed body segmentation stops protection. There is **no rectangular fallback**.
- Start is refused unless Android notifications and the ongoing Stop notification channel are available. The Quick Settings tile is a separate Stop-only action; the user should add it before testing.

The watchdog callbacks run on Android's main looper, separately from the screenshot and inference worker executors. No application-level timer can guarantee recovery if Android's main process/looper itself is completely unresponsive; Android process/service teardown remains the system-level fallback.

## How redaction works

Detection and redaction localization are separate operations. EfficientDet/OpenNSFW/ViT/Gemma decide which candidate regions should be redacted. A separately pinned Google Selfie Multiclass segmentation model then classifies screenshot pixels. R6 selects only:

| Segmentation class | Class ID | R6 rendering |
|---|---:|---|
| Background | 0 | Visible |
| Hair | 1 | Visible |
| Body skin | 2 | Redacted when inside a selected detection region |
| Face skin | 3 | Visible |
| Clothes | 4 | Redacted when inside a selected detection region |
| Accessories / others | 5 | Visible |

The renderer applies the selected blur, pixelation or opaque effect only to selected body-skin/clothing pixels. The remainder of each detection rectangle is transparent. A small mask dilation is applied to reduce boundary leakage, and coverage is checked on the resulting rendered bitmap. The 256×256 segmenter is coarse and designed for person/selfie segmentation; S24 FE alignment and quality still require real-device validation.

Segmentation is a localization model, **not a swimwear classifier**. It cannot decide whether a garment is a bikini, one-piece swimsuit, swim trunks, lingerie or ordinary clothing. The current approved catalog still has no validated swimwear-specific detector.

## Approved model catalog

All artifacts are user-initiated downloads from fixed HTTPS URLs. The app verifies byte-size bounds and exact SHA-256 before a model can be loaded. Hash verification proves byte identity with this catalog; it does not prove accuracy or model safety.

| Model | Responsibility | Runtime | Important limitation |
|---|---|---|---|
| Google EfficientDet-Lite0 | Person/object localization | TensorFlow Lite Task Vision | Not an NSFW or clothing classifier |
| Yahoo OpenNSFW ResNet-50 | General binary NSFW score | ONNX Runtime | Not a swimsuit classifier; old general classifier |
| ViT-Tiny NSFW 384 | Second general NSFW score and Balanced tile fallback | ONNX Runtime | Not a swimsuit classifier; publisher-reported metrics only |
| Google Selfie Multiclass 256×256 | Pixel classes for body skin, face, clothes, hair, accessories and background | TensorFlow Lite Task Vision | Not a content classifier; coarse mask; quality must be measured on device |
| Gemma 4 E2B Instruct | Optional multimodal screenshot analysis | LiteRT-LM | Experimental, large download, prompt-injection exposure; prefer a specialist profile |

The segmentation model is pinned to Google's versioned `float32/1` artifact, Apache-2.0 as declared by its Google model card. See [`docs/MODEL_GOVERNANCE.md`](docs/MODEL_GOVERNANCE.md) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for full artifact URLs, hashes, bounds and provenance.

### Profiles

- **Balanced multi-model · offline (default):** EfficientDet-Lite0 + Yahoo OpenNSFW + ViT-Tiny NSFW + Selfie Multiclass segmentation. Approximately **76–93 MB** based on the approved artifact bounds. If no people are detected, ViT may scan up to nine overlapping screen tiles; the final overlay still intersects those candidate regions with the body/clothing segmentation mask and must pass the same hard coverage gate.
- **Fast specialized · offline:** EfficientDet-Lite0 + Yahoo OpenNSFW + Selfie Multiclass segmentation. Approximately **54–63 MB**. No ViT tile fallback.
- **Gemma 4 vision · optional:** Gemma 4 E2B plus Selfie Multiclass segmentation. About **2.6 GB plus 16–17 MB**. Text visible in screenshots can steer a language model; this profile remains experimental.
- **Swimwear-aware · Google Open Images:** disabled and not downloadable until an Android artifact, label map, runtime contract, license and exact hash are verified.

A wider model review dated 2026-11-03 also identified an experimental swimwear-labelled RT-DETRv2 detector and a five-class clothing ONNX classifier. Neither is approved for safety decisions: their training/evaluation limitations, missing neutral/reject semantics, and Android validation gaps are documented in [`docs/MODEL_CANDIDATE_REVIEW_2026-11-03.md`](docs/MODEL_CANDIDATE_REVIEW_2026-11-03.md). The active catalog and R6 runtime are intentionally unchanged. A second research addendum audits the Marqo ONNX classifier already integrated in R6 and compares further image-safety candidates (Freepik EVA02, Falconsai, TostAI, Blue-Eye and ShieldGemma 2) plus a product-photo swimwear model (Genlook); it recommends evaluation only and does not change the runtime. See [`docs/MODEL_CANDIDATE_RESEARCH_ADDENDUM_2026-11-03.md`](docs/MODEL_CANDIDATE_RESEARCH_ADDENDUM_2026-11-03.md).

These are approximate catalog downloads, not guarantees about on-disk storage or RAM. Gemma needs substantially more memory than specialist profiles.

## Conservative policy and known limits

- OpenNSFW and ViT are general NSFW classifiers. They do not recognize swimwear as a semantic class.
- The two classifiers do not constitute an independent accuracy guarantee; current ensemble decisions and thresholds are engineering settings, not calibrated product metrics.
- With **Redact uncertain content** enabled, uncertain person candidates can be selected for redaction; low-risk person regions are selected only when the strict setting is enabled. False positives on ordinary people remain possible.
- Balanced tile detections do not imply the entire tile is blurred: only intersecting body-skin/clothing pixels inside approved candidate regions are rendered.
- A false negative or content outside the candidate regions can remain visible. ScreenVeil samples screenshots; it is not a continuous frame-by-frame video filter.
- Segmentation failure is fail-open: protection stops and masks are cleared rather than reverting to a large rectangle. This preserves device usability but content can be visible while protection is stopped or awaiting a fresh analysis.
- Android may refuse screenshots of secure/DRM windows. ScreenVeil does not bypass these restrictions.

## Capture, privacy and permissions

- Minimum Android version: Android 14 / API 34.
- The user enables Accessibility manually and separately taps Start. Accessibility binding alone never starts protection.
- The service uses active-window IDs/bounds and Accessibility event metadata only to schedule screenshot capture. It does not read accessibility view nodes, app text, content descriptions, click controls or perform gestures.
- The overlay is a single `TYPE_ACCESSIBILITY_OVERLAY` window with `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCHABLE`; it does not intercept taps.
- Screenshots, segmentation masks and inference remain on-device. The only application network access is explicit model download from the fixed catalog. Download hosts still receive ordinary network metadata such as IP address and request time.
- No telemetry, screenshot upload, remote inference, arbitrary model URL or model marketplace is implemented.

## Emergency Stop

1. **Notification Stop:** required to start protection. If notification permission is revoked or the active notification channel is disabled, Start is refused.
2. **Quick Settings Stop tile:** add ScreenVeil to Quick Settings before testing. It only stops; when protection is OFF, it opens the app and does not start protection.
3. **Android system fallback:** disable ScreenVeil under Settings → Accessibility, or force-stop it from Android App Info.

The notification shade/system panel is detected so stale masks are cleared while a large system panel is active. Validate this on Samsung One UI before relying on it.

## Build and CI

Pinned toolchain versions are in the Gradle settings/build files. Typical local validation command:

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

The GitHub Actions workflow builds a debug APK for testing and a separate R8 release APK. Release signing is conditional on repository signing secrets; without them the release artifact is unsigned. Emulator lifecycle tests run on API 34 and API 36. The manual `verify-model-pins` job downloads and verifies all five model artifacts, including the large optional Gemma file.

R6 defines **58 unit tests** and **28 lifecycle instrumentation tests plus 1 real-model segmenter plumbing test (29 total)** in source. These are not claimed as passing until the R6 revision itself completes GitHub Actions. Instrumentation uses synthetic detector/segmenter outputs for deterministic lifecycle tests; it does not validate real model accuracy.

## Galaxy S24 FE validation is still required

Before daily use, validate real model initialization, body mask alignment, false positives/misses, capture-to-overlay latency, memory, heat, battery, notification Stop, Quick Settings Stop, 5-minute auto-stop, coverage watchdog, screenshot timeout and inference timeout on the actual device. Follow [`docs/DEVICE_VALIDATION.md`](docs/DEVICE_VALIDATION.md). This source release is not a certification that those tests passed.

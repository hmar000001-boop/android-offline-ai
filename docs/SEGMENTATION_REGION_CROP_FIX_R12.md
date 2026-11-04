# ScreenVeil 1.6.8 (R12) — per-region segmentation correction

## Baseline and scope

R12 is an incremental patch to the complete R11 source tree. It does not replace the detector, segmentation model artifact, LiteRT runtime, classifiers, policy, overlay architecture, or UI. The pinned Google Selfie Multiclass TFLite model and its URL, size bounds, SHA-256, license, six category IDs, RGB float32 normalization (`pixel / 127.5 - 1`), and 256×256 model dimensions are unchanged.

## Why this patch was made

The Samsung SM-S721W report showed that the person detector and segmentation model initialized successfully. A detailed trace for cycle 6 showed zero person boxes and therefore skipped segmentation. A later cycle found a redaction region, but the renderer could not find selected body/clothing pixels inside that region and correctly stopped. The one-shot detailed trace had already been consumed by cycle 6, so the original report did not include per-region mask-intersection counts or the nested rendering exception.

R11 segmented the entire 1080×2340 screenshot into one 256×256 mask and reused that full-frame mask for every flagged detection. For a tall phone screenshot, aspect-preserving letterboxing can reduce the whole frame to roughly 118 model pixels in width. A small person or an image within a feed can consequently occupy very few model pixels, and a valid person detection may not intersect the low-resolution selected body/clothing mask.

## Minimal code changes

1. `HumanBodySegmenter.kt` adds a bounded-source-region overload while retaining the old full-frame method for existing test doubles and callers. The production TFLite implementation crops the supplied screenshot region, aspect-preservingly letterboxes that crop into 256×256, and runs the same pinned model.
2. `BodySegmentationMask` records the crop's position and size within the original screenshot. `protectsFramePixel` maps crop-local model pixels back into the correct global screenshot coordinates. A geometry-only intersection counter supports diagnostics and preflight validation.
3. `ScreenProtectionAccessibilityService.kt` maps each policy-approved detector box to screenshot pixels, expands only the segmentation context by 10% (clipped to the frame), segments that crop, and requires selected body/clothing pixels to intersect the original unexpanded detector box. The original detector box remains the only area passed to the renderer.
4. `OverlayController.kt` retains fail-stop behavior but includes the underlying exception class/message in the local safety diagnostic. It does not add a rectangular fallback.
5. `BodySegmentationMaskTest.kt` adds crop-to-frame mapping coverage. The real-model instrumentation test runs the pinned model on the same synthetic person crop used by the existing smoke test and verifies that the returned mask intersects that crop.
6. The app version increments to 1.6.8 / versionCode 21.

## Safety properties retained

- Body-skin and clothes categories only (IDs 2 and 4); face, hair, accessories and background remain transparent.
- Crop context padding is never redacted: the mask is intersected with the original detector rectangle.
- No rectangular fallback. A zero-intersection, invalid, non-finite or unmappable mask still stops protection and clears the overlay.
- The 35% per-region and 45% combined coverage limits, 35%/3-second coverage watchdog, five-minute session watchdog, five-second screenshot deadline, eight-second inference deadline, and stop controls are unchanged.
- The same per-frame 24-region cap remains in force. No detections are silently dropped to meet a compute budget.
- Inference remains local on LiteRT CPU. No additional dependency, telemetry path, network request or model download is added by this patch.

## Validation status

- Source comparison: R12 begins from the R11 source archive; the changes are limited to the files listed above plus this documentation and version string.
- Local Kotlin compiler check with Android/LiteRT API stubs: `HumanBodySegmenter.kt` compiles and the crop-to-frame coordinate/intersection checks pass. This is a syntax/geometry check, not an Android build.
- `./gradlew testDebugUnitTest` could not start because this environment could not resolve `services.gradle.org` to download the pinned Gradle 8.14.6 distribution.
- R12 has not yet passed GitHub Actions, Android API 34/API 36 emulator instrumentation, or physical Samsung inference. The real-model instrumented crop test must pass in CI before installing R12.

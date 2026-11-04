# Emulator test coverage — ScreenVeil 1.6.3 (R8)

The R7 instrumentation suite is configured for `connectedDebugAndroidTest` on Android API 34 and API 36. The suite downloads the exact pinned EfficientDet and segmentation models needed by its two real-runtime smoke tests; detector and lifecycle cases inject deterministic outputs. A debug-only test seam provides synthetic detector, screenshot and segmentation implementations for those deterministic tests. The Android AccessibilityService, watchdog scheduling, screenshot flow, inference worker, real `OverlayController`/`WindowManager` and service lifecycle remain active where each test requires them.

## 28 lifecycle instrumentation tests plus 2 real-model runtime smoke tests and 1 box-order contract test (31 total)

| Test | What it exercises |
|---|---|
| `rawTfliteDetectionPostProcessBoxOrderMapsYminXminYmaxXmaxCorrectly` | Deterministically checks raw TFLite `[ymin,xmin,ymax,xmax]` mapping to pixel bounds. |
| `pinnedEfficientDetLite0RunsAndMatchesDetectionPostProcessContract` | Downloads/verifies the exact EfficientDet-Lite0 model, executes LiteRT standalone CPU inference and validates the raw four-output tensor contract, including zero-based raw class IDs and the Android person-class index (0), without the COCO-evaluation offset. This is runtime plumbing, not person-detection accuracy. |
| `accessibilityComponentMatchingAcceptsAndroidFlattenedFormsOnly` | Parses Android flattened component names and rejects shell-quote characters. |
| `cleanInstallDefaultsProtectionToOff` | Fresh install defaults to OFF. |
| `previousReleaseOnFlagIsMigratedToOff` | Legacy persisted ON is migrated to OFF. |
| `enablingAccessibilityDoesNotStartProtectionOrCreateOverlay` | Accessibility binding alone does not start protection or create an overlay. |
| `startWithoutVerifiedModelsStopsSafelyAndLeavesNoOverlay` | Start without all required verified models is rejected. |
| `successfulSyntheticInferenceCreatesOnlyLocalizedAccessibilityOverlay` | Synthetic detector and segmentation output pass through service workers and real localized overlay. |
| `androidWindowScreenshotApiDeliversAFrameToTheDetector` | Android's real `takeScreenshotOfWindow()` callback delivers a fixture frame to the test detector. |
| `repeatedContentChangeEventsAreDebouncedToOneCapture` | A 30-event content-change burst coalesces through the production event handler. |
| `repeatedModelFailuresStopAndClearAnExistingOverlay` | Three consecutive injected model failures stop protection and clear an existing overlay. |
| `repeatedCaptureFailuresStopAndClearAnExistingOverlay` | Five capture failures stop protection and clear an existing overlay. |
| `androidSecureWindowScreenshotDenialPausesWithoutOverlay` | A real `FLAG_SECURE` fixture causes Android screenshot denial; protection pauses without a fallback overlay. |
| `overlayRenderingFailureStopsProtectionAndClearsRenderer` | Renderer failure stops protection and clears renderer state. |
| `explicitStopWhileLocalizedOverlayIsVisibleRemovesItImmediately` | Waits for completed model status, one redacted region and a committed overlay frame; isolates ambient accessibility events, waits for pipeline quiescence, then verifies explicit Stop clears the overlay. |
| `accessibilityInterruptionStopsAndRemovesActiveOverlay` | Android `onInterrupt()` stops protection and removes the overlay. |
| `disablingAccessibilityWhileOverlayIsVisibleRemovesTheOverlay` | Service disable/unbind clears a visible overlay. |
| `fiveMinuteSessionWatchdogStopsAndClearsTheOverlayWithoutBeingExtendedByStart` | Debug-shortened session timer calls the same safety stop and removes the overlay; a repeated Start request cannot extend the original deadline; production setting is 300,000 ms. |
| `sustainedCoverageWatchdogStopsAndClearsTheOverlay` | Sustained high coverage triggers the independent scheduled watchdog and clears the overlay. |
| `inferenceDeadlineStopsProtectionAndPoisonsCurrentProcess` | Waits until the injected detector is executing, then verifies the inference watchdog stop reason and process-wide poison latch. The fake detector is interruptible; this does not simulate a JNI call that ignores interruption. |
| `inferenceDeadlineStillStopsAfterWindowGenerationChanges` | A window-generation change cannot consume the deadline for a still-hung inference worker and verifies process-wide poison. |
| `screenshotDeadlineStopsProtectionWhenFrameProviderStalls` | Waits until the injected frame provider is executing, then verifies the screenshot watchdog stop reason and process-wide poison latch. The separate `androidWindowScreenshotApiDeliversAFrameToTheDetector` test covers the real Android screenshot API plumbing. |
| `screenshotDeadlineStillStopsAfterWindowGenerationChanges` | A window-generation change cannot consume the deadline for a still-hung screenshot worker and verifies process-wide poison. |
| `fullScreenDetectorBoxOnlyRedactsSegmentedBodyPixels` | A full-screen detection rectangle produces only the synthetic body-pixel mask; the rest remains transparent. |
| `fullScreenConfirmedRegionIsRejectedByThePerRegionPixelLimit` | A full-screen EXPLICIT body mask is rejected by the 35% per-region cap. |
| `fullScreenConfirmedDetectionStopsProtectionAndClearsTheOverlay` | The service receives a full-screen EXPLICIT result and actually stops/clears rather than displaying it. |
| `confirmedRegionsCannotBypassTheCombinedPixelCoverageLimit` | Two confirmed masks covering 60% together are rejected by the 45% combined cap. |
| `overlayPreparationChangesOnlyTheDetectedRectangle` | Pixel-level output remains constrained to the detection geometry. |
| `gaussianBlurChangesOnlyPixelsInsideTheDetectedRegion` | Blur effect does not alter transparent pixels outside the selected body mask. |
| `allowOnlyResultsDoNotProduceAnOverlayBitmap` | ALLOW-only results create no overlay bitmap. |

## 58 unit tests

Unit tests cover policy limits and category exemptions (none), model profiles/catalog pins, parser bounds, body-mask class mapping, letterbox coordinate mapping, mask-buffer validation, detection-box normalization, screenshot-to-overlay geometry and redaction bitmap behavior. The source tree currently contains 58 `@Test` methods under `app/src/test/java`.

## What emulator tests do not prove

- The actual five downloaded production model artifacts initialize and infer correctly on-device.
- Segmentation masks are accurate on app screenshots, multiple distant people, occlusions, varied skin tones, clothing types, GIFs or video.
- Swimwear-specific detection; no approved model currently supplies that capability.
- Accuracy, precision/recall, missed content or false-positive rate.
- Samsung One UI screenshot behavior, overlay alignment, power management or notification behavior.
- Galaxy S24 FE latency, RAM, GPU/CPU/NPU behavior, heat, thermal throttling or battery drain.
- Recovery if Android's main process/looper itself is completely unresponsive.

The lifecycle tests inject fake detector and segmentation masks for deterministic safety scenarios. The explicit Stop lifecycle case also waits for a committed overlay frame and drains known pending work before stopping, following the deterministic gating pattern used in the earlier R5 failure correction. Separate instrumentation smoke tests load the exact pinned Google EfficientDet and Selfie Multiclass artifacts and invoke the real LiteRT Interpreter CPU (local) runtime; these prove artifact/runtime/tensor-contract plumbing, not detection or segmentation accuracy. The screenshot-plumbing test uses Android's real screenshot API. A green R7 emulator suite must not be presented as a real-model accuracy test.

**R7 status at package creation:** 28 lifecycle instrumentation test methods, 2 real-model runtime tests, and 1 deterministic EfficientDet box-order contract test (31 total), and 58 unit test methods are present in source. Gradle execution on API 34 and API 36 is still required; this document does not claim they have passed.

### V8 overlay-removal assertion synchronization

The repeated-model-failure and repeated-capture-failure lifecycle tests wait for the actual
`dumpsys window windows` snapshot to stop listing `ScreenVeil redaction` after the service
reports its safety stop. This is a bounded 8-second wait. A window that remains present still
fails the test, and the failure includes the latest status, service diagnostics, and matching
WindowManager dump line. This avoids assuming that the status update and WindowManager's
independently observed teardown are synchronous.


## R7 runtime migration gate

The former Task Vision library was removed. EfficientDet-Lite0 and Selfie Multiclass now use the LiteRT `2.3.2` standalone CPU `Interpreter` compatibility adapter. Two real-model plumbing tests now exercise the exact pinned models on API 34 and API 36; neither claims classification accuracy. API 36 emulator success is not a substitute for the Galaxy S24 FE ARM64 test. CI also checks 16 KB ELF/ZIP alignment of packaged 64-bit native libraries.


## R8 build-only dependency correction

R8 adds a strict AndroidX Fragment `1.9.1` dependency so the resolved FragmentActivity implementation meets the Activity Result API compatibility requirement detected by Android lint. This does not change the instrumentation suite, model runtime, screenshot path or protection policy. CI records `dependencyInsight` output and fails unless `1.9.1` is selected. See `CI_LINT_FIX_FRAGMENT_ACTIVITY_RESULT_R8.md`.


## R10 source inventory and stricter EfficientDet diagnostics

R10 contains 64 local unit test methods (five new EfficientDet contract tests relative to R9) and 31 instrumentation methods. Its real EfficientDet test requires at least one active detection from the same neutral-grey smoke input that produced active detections in the prior R9 emulator run. It reports non-finite and inverted raw box values separately, including the offending tuple, and tests out-of-frame clipping. The Android app compares the raw zero-based person class index `0` directly; it does not apply the `+1` COCO evaluation offset. This strengthens output interpretation tests but still does not benchmark detector accuracy. The R10 API 34/API 36 instrumented jobs must run before these tests can be considered passing.

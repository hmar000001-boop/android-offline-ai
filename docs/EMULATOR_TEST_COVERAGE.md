# Emulator test coverage — ScreenVeil 1.6.0 (R6)

The R6 instrumentation suite is configured for `connectedDebugAndroidTest` on Android API 34 and API 36. The suite downloads only the pinned segmentation model needed by its real-runtime smoke test; detector and lifecycle cases inject deterministic outputs. A debug-only test seam provides synthetic detector, screenshot and segmentation implementations for those deterministic tests. The Android AccessibilityService, watchdog scheduling, screenshot flow, inference worker, real `OverlayController`/`WindowManager` and service lifecycle remain active where each test requires them.

## 28 lifecycle instrumentation tests plus 1 real-model segmenter plumbing test (29 total)

| Test | What it exercises |
|---|---|
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

The lifecycle tests inject fake detector and segmentation masks for deterministic safety scenarios. The explicit Stop lifecycle case also waits for a committed overlay frame and drains known pending work before stopping, following the deterministic gating pattern used in the earlier R5 failure correction. A separate instrumentation test loads the exact pinned Google segmentation artifact and invokes the real TFLite Task Vision segmenter on a synthetic frame; this proves artifact/runtime/API plumbing, not segmentation accuracy. The screenshot-plumbing test uses Android's real screenshot API. A green R6 emulator suite must not be presented as a real-model accuracy test.

**R6 status at package creation:** 28 lifecycle instrumentation test methods plus 1 real-model segmenter test (29 total), and 58 unit test methods are present in source. Gradle execution on API 34 and API 36 is still required; this document does not claim they have passed.

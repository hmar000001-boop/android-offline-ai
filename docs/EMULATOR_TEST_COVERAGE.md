# Emulator test coverage — ScreenVeil 1.5.1

The instrumentation suite runs with `connectedDebugAndroidTest` on Android API 34 and API 36.
It deliberately does not download model artifacts. A debug-only test seam injects a deterministic
`ScreenContentDetector` and, for deterministic capture/error cases, a synthetic bitmap provider.
The Android `AccessibilityService`, its main-thread scheduling, the inference worker, real
`OverlayController`/`WindowManager`, and the service lifecycle remain active. One end-to-end test
uses Android's real `takeScreenshotOfWindow()` API and a separate test-APK activity as the capture
target. A fixture with `FLAG_SECURE` exercises Android's actual screenshot-denial callback.

## Instrumentation tests

| Test | What it exercises |
|---|---|
| `cleanInstallDefaultsProtectionToOff` | Fresh preferences default to OFF. |
| `previousReleaseOnFlagIsMigratedToOff` | A legacy persisted ON flag is migrated to OFF. |
| `enablingAccessibilityDoesNotStartProtectionOrCreateOverlay` | Android binds the real AccessibilityService, but binding alone does not start monitoring or add a window. |
| `startWithoutVerifiedModelsStopsSafelyAndLeavesNoOverlay` | Explicit Start with no verified model artifacts is rejected, protection is reset OFF, and no overlay remains. |
| `successfulSyntheticInferenceCreatesOnlyLocalizedAccessibilityOverlay` | Fake detector output and synthetic frame travel through the service workers and real OverlayController; a localized accessibility overlay window appears without opaque-cover state. |
| `androidWindowScreenshotApiDeliversAFrameToTheDetector` | Real Android `takeScreenshotOfWindow()` captures the fixture activity; deterministic detector output is committed through the real localized overlay. |
| `repeatedContentChangeEventsAreDebouncedToOneCapture` | Thirty synthetic `TYPE_WINDOW_CONTENT_CHANGED` events pass through the production event handler and coalesce into one capture. |
| `repeatedModelFailuresStopAndClearAnExistingOverlay` | One successful localized mask is followed by three injected inference failures; the existing window is removed and protection is reset OFF. |
| `repeatedCaptureFailuresStopAndClearAnExistingOverlay` | Five injected frame-capture failures cause a safety stop and remove the previously displayed localized mask. |
| `androidSecureWindowScreenshotDenialPausesWithoutOverlay` | A real `FLAG_SECURE` fixture causes Android's screenshot-denial callback; repeated failures stop protection without adding an overlay. |
| `overlayRenderingFailureStopsProtectionAndClearsRenderer` | An injected renderer failure returns `false`; service stops and invokes renderer cleanup. |
| `explicitStopWhileLocalizedOverlayIsVisibleRemovesItImmediately` | Explicit Stop removes a real accessibility overlay window while it is visible. |
| `accessibilityInterruptionStopsAndRemovesActiveOverlay` | Android's `onInterrupt()` path stops active protection and removes its localized overlay. |
| `disablingAccessibilityWhileOverlayIsVisibleRemovesTheOverlay` | Disabling the service while a mask is visible causes unbind/shutdown cleanup. |
| `overlayPreparationChangesOnlyTheDetectedRectangle` | Pixel-level assertion: only the specified normalized box is opaque; pixels outside remain transparent. |
| `gaussianBlurChangesOnlyPixelsInsideTheDetectedRegion` | A checkerboard fixture proves the selected pixels are blurred while pixels outside the rectangle remain transparent. |
| `allowOnlyResultsDoNotProduceAnOverlayBitmap` | ALLOW-only detector output creates no overlay bitmap. |

## Debug-only test seams

`ScreenVeilTestHooks` permits instrumentation to inject:

- a `ScreenContentDetector` implementation;
- a deterministic screenshot-frame provider or a provider that throws;
- a renderer subclass that simulates WindowManager/rendering failure.

The service consults these overrides only when `BuildConfig.DEBUG` is true. Release builds continue
to use the fixed approved model catalog, Android's screenshot callback, and the real `OverlayController`.

The injected detector is not evidence of model accuracy. It only drives deterministic service paths.
The actual screenshot API test verifies capture plumbing, not the correctness of any pretrained model.

## What remains outside emulator coverage

- Loading and inference with each real downloaded EfficientDet/OpenNSFW/ViT/Gemma artifact.
- Model accuracy, especially swimwear-specific recall and false-positive rates.
- Screenshot behavior on Samsung One UI and other manufacturer-specific accessibility implementations.
- Real Galaxy S24 FE inference latency, memory, thermal throttling and battery drain.
- Continuous video/frame-by-frame protection; ScreenVeil samples screenshots at intervals.
- Long-duration tests over many hours and real third-party application layouts.

No test result is considered passing until GitHub Actions executes this suite on both API levels and its reports are reviewed.

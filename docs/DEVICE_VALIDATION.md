# Device validation plan — Samsung Galaxy S24 FE

This plan is mandatory before treating ScreenVeil as a practical daily-use filter. The app's current APK is experimental. GitHub CI can prove compilation and unit tests; only a real device can establish accessibility screenshot behavior, overlay alignment, Gemma performance, battery use and thermal stability.

## First-run and service lifecycle

1. Install the debug APK on the Galaxy S24 FE (Android 14/API 34 or newer).
2. Open ScreenVeil. Confirm there is no MediaProjection/screen-recording permission flow and no request for Display over other apps.
3. Start the user-initiated Gemma download. Confirm progress and exact model verification before the model is reported installed.
4. Tap **Enable protection** and enable ScreenVeil manually in Android Settings → Accessibility → Installed apps.
5. Return to another application. Confirm the service initializes Gemma and starts inspecting screenshots without a screen-sharing dialog or a persistent capture foreground-service notification.
6. Open ScreenVeil itself. Confirm its own UI is not scanned and no redaction overlay is displayed over ScreenVeil.
7. Press Stop in ScreenVeil. Confirm screenshots stop and masks clear while Android Accessibility settings still show the service enabled.
8. Re-enable protection; reboot; force-stop/relaunch; turn the accessibility service off/on. Confirm saved preference and Android's service lifecycle behave as documented.

## Capture and window coordinate correctness

1. Open Samsung Gallery with a single test image that fills most of the app window. Confirm screenshot capture succeeds and the `HardwareBuffer` path closes resources.
2. Test an image in a feed, multiple visible image cards, a browser page, and a video player. Confirm the chosen active application window ID and its bounds match the screenshot buffer.
3. Compare overlay coordinates to the source pixels at portrait and landscape orientation, with status/navigation bars and edge-to-edge content.
4. Test split-screen, floating windows, picture-in-picture, popups and dialogs. Confirm ScreenVeil either aligns correctly or reports an unsupported window shape rather than placing a mask at an incorrect location.
5. Confirm `takeScreenshotOfWindow()` does not include ScreenVeil's own accessibility-overlay masks in subsequent captures.
6. Open a `FLAG_SECURE` screen and DRM-protected video. Confirm Android refuses the screenshot and ScreenVeil does not attempt a workaround.
7. Rapidly scroll and change applications. Confirm stale masks are cleared before new-window coordinates are applied.
8. Trigger screenshot rate limiting in a test build. Confirm the status reports throttling and retries do not create an unbounded queue.

## Gemma structured-output validation

1. Verify model SHA-256 before engine initialization.
2. On the first run, test GPU initialization and CPU fallback; record selected backend and load time.
3. Confirm `ExperimentalFlags.enableSpeculativeDecoding=false` is set before engine construction and `enableResponseFormat=true` / `ResponseFormat.json(schema)` is supplied for each conversation.
4. Test a single full-screen beach/swimwear photo in Samsung Gallery. Expected output: one media region classified as `swimwear`; expected behavior: its full media rectangle is redacted.
5. Repeat with ordinary clothing, non-swimwear beach scenes, sportswear, suggestive poses, explicit content, artworks and ordinary UI screenshots.
6. Test images within feeds and multiple media regions. Measure box intersection-over-union and decision accuracy against human labels.
7. Confirm incomplete JSON, malformed boxes, unknown categories, empty text and inference exceptions are shown as errors—not interpreted as safe.
8. Record invalid/timeout rate over at least 100 varied inputs. A constrained format is not a guarantee of correct visual understanding.

## Performance and thermal validation

Use lawfully obtained, properly licensed test images/videos and test sustained operation for at least 30 minutes.

Measure:

- screenshot success rate and rate-limit failures;
- median/P95 time from screenshot request to overlay update;
- Gemma model initialization time and peak RAM/PSS/native heap;
- GPU and CPU fallback latency separately;
- battery drain and device temperature;
- dropped/overlapping events and inference queue growth;
- correctness of screen-to-overlay coordinate transformation;
- behavior at 0.75, 1.5, 3 and 5 second analysis intervals.

Do not describe this as smooth frame-by-frame video protection unless sustained frame throughput and latency measurements substantiate that claim. The current design samples screenshots, not a video stream.

## Stop criteria

Do not recommend daily use if the app repeatedly crashes, produces persistent misaligned masks, destabilizes the UI, fails to release screenshot buffers/bitmaps, causes excessive battery or thermal load, cannot keep Gemma resident, or generates materially unreliable classifications/boxes.

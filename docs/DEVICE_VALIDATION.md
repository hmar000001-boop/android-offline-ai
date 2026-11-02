# Device validation plan — Samsung Galaxy S24 FE

This plan is mandatory before treating ScreenVeil as a practical daily-use filter. CI can prove source compilation and unit tests; only the physical Galaxy S24 FE can establish accessibility screenshot behavior, overlay alignment, model runtime compatibility, battery use and thermal stability.

## First run and model installation

1. Install the debug APK on the Galaxy S24 FE (Android 14/API 34 or newer).
2. Open ScreenVeil. Confirm there is no MediaProjection/screen-recording permission flow and no Display over other apps permission request.
3. Keep **Balanced multi-model · offline** selected. Download the three specialist model artifacts. Confirm that each is reported installed only after the full SHA-256 has been checked.
4. Test an interrupted download, redirect to an unapproved domain, mismatched SHA-256, oversized file and low-storage condition. Confirm incomplete files are deleted and no partial artifact is reported installed.
5. Select **Fast specialized · offline** and verify only EfficientDet-Lite0 and Yahoo OpenNSFW are required. Select Gemma separately and verify its large download is not required by either specialist profile.
6. Enable protection and manually enable ScreenVeil in Android Settings → Accessibility → Installed apps.
7. Return to another application. Confirm the selected profile initializes the expected local runtime and starts screenshot inspection.
8. Open ScreenVeil itself. Confirm its own UI is not scanned and no redaction overlay is displayed over ScreenVeil.
9. Press Stop. Confirm screenshots stop and masks clear while the Android accessibility service remains enabled.
10. Switch profiles while stopped, then start each profile. Confirm the previous runtime is closed and the selected one is initialized; a previously loaded Gemma runtime must not remain active under a specialist profile label.

## Specialist model contract tests

1. Confirm EfficientDet-Lite0 loads through standalone TensorFlow Lite Task Vision `0.4.4` and returns person boxes in screenshot pixel coordinates. Confirm no MediaPipe Tasks SDK dependency or analytics/telemetry network call is present in the resolved runtime graph.
2. Verify Yahoo OpenNSFW ONNX input `[1,224,224,3]`, output `[P(sfw), P(nsfw)]`, preprocessing, full-frame inference and person-crop inference.
3. Verify ViT-Tiny NSFW ONNX input `[1,3,384,384]`, output logits `[NSFW,safe]`, softmax order, full-frame and person-crop inference.
4. In Balanced profile with no person boxes, verify all nine overlapping ViT tiles are evaluated and each positive tile is redacted as a whole region.
5. With one or more person detections, verify the app does not silently run tile scanning; record any media outside person boxes that is missed.
6. Verify a person region that is not flagged by the general NSFW classifiers remains `UNCERTAIN`, never `ALLOW`. With the default uncertain-redaction setting on, verify it is covered. Record false positives on ordinary people.
7. Pass malformed dimensions, non-finite scores, unknown output names and ONNX errors. Confirm bounded retries, visible error status, protection OFF and overlay cleared; never a full-window fallback.
8. Confirm the Fast profile has no ViT runtime/file dependency and Balanced has all three specialist artifacts. Confirm neither specialist profile loads Gemma.

## Capture and window correctness

1. Test portrait/landscape, status/navigation bars, edge-to-edge app content, split screen, floating windows, popups and dialogs.
2. Test scroll, rapid feed replacements and window changes while inference runs. Confirm stale generations and old capture sequences cannot overwrite the latest overlay.
3. Test secure/DRM windows and screenshot rate limiting. Confirm Android restrictions are respected, localized masks are not replaced by a global cover and repeated capture errors pause protection.
4. Force inference and overlay errors in a test build. Confirm the service pauses after three model failures, clears the overlay and leaves the app usable.
5. Verify startup, service rebind and enabling Accessibility never start protection without an explicit Start tap. Confirm model loading never draws a full-window cover.
6. Confirm content changes clear only localized masks and schedule a new capture; repeated events must never create a global cover.

## Representative moderation dataset

Use lawful, consented/licensed images and videos with human labels. Include:

- bikinis, one-piece swimsuits, swim trunks, rash guards, sports swimwear, modest swimwear and cover-ups;
- lingerie, suggestive poses, nudity and explicit sexual content;
- ordinary beach, pool, fitness and skin-exposing but non-sexual images;
- people who are small, partially hidden, overlapping, distant or cropped by a feed card;
- no-person images, cartoons, drawings, memes, collages and screenshots;
- multiple media regions and video frames from varied apps and aspect ratios.

Measure per-category precision/recall, false-negative and false-positive rates, person-detection recall, region recall, bounding-box IoU and the proportion of screen area masked. Report Fast and Balanced separately. A high aggregate accuracy can hide poor recall for swim trunks, one-piece swimsuits and other swimwear. There is currently no admitted swimwear-specific classifier, so do not describe this dataset test as model validation until such a model is added.

## Performance and thermal validation

Run each profile for at least 30 minutes and record:

- model download size, hash-verification time and initialization time;
- ONNX/TFLite first-inference and steady-state time;
- P50/P95 time from screenshot request to overlay commit;
- GPU/CPU use, peak RAM/PSS/native heap and app process restarts;
- battery drain, thermal throttling and device temperature;
- screenshot success / rate-limit rate and events missed between samples;
- behavior at 0.75, 1.5, 3 and 5 second intervals.

Balanced adds a second classifier and a possible nine-tile fallback; it may be slower than Fast. Do not assume the Galaxy S24 FE can sustain either profile before measuring it.

## Stop criteria

Do not recommend daily use if the app crashes, leaves persistent misaligned overlays, materially misses target content, causes disruptive false positives, cannot retain a model in memory, destabilizes other apps, consumes excessive battery or thermally throttles. This application samples screenshots; it is not a guaranteed frame-by-frame video filter.

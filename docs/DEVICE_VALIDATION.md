# Device validation plan — Samsung Galaxy S24 FE

This plan is mandatory before treating ScreenVeil as a practical daily-use filter. CI can prove compilation and unit tests; only the physical Galaxy S24 FE can establish accessibility screenshot behavior, overlay alignment, ONNX/MediaPipe runtime compatibility, battery use and thermal stability.

## First run and model installation

1. Install the debug APK on the Galaxy S24 FE (Android 14/API 34 or newer).
2. Open ScreenVeil. Confirm there is no MediaProjection/screen-recording permission flow and no Display over other apps permission request.
3. Keep **Balanced multi-model · offline** selected. Download all four specialist model artifacts. Verify the app reports each model verified only after full SHA-256 validation.
4. Test an intentionally interrupted model download and low-storage condition. Confirm incomplete files are deleted and no partially downloaded model is reported installed.
5. Select **Fast specialized · offline** and verify only the three Fast-profile models are required. Select Gemma separately and verify its large download is not required for the specialist profiles.
6. Tap Enable protection and manually enable ScreenVeil in Android Settings → Accessibility → Installed apps.
7. Return to another application. Confirm the selected model profile initializes, displays the correct backend and starts screenshot inspection.
8. Open ScreenVeil itself. Confirm its own UI is not scanned and no redaction overlay is displayed over ScreenVeil.
9. Press Stop. Confirm screenshots stop and masks clear while the Android accessibility service remains enabled.
10. Switch profiles while stopped, then start each profile. Confirm the correct runtime is closed and reinitialized; a previously loaded Gemma runtime must not remain active under a specialist profile label.

## Specialist model contract tests

1. Confirm EfficientDet-Lite0 loads through MediaPipe Tasks Vision and returns person boxes in screenshot pixel coordinates.
2. Run a person-centred RGB test crop through Electblake EfficientNetV2-S. Confirm ONNX input `NCHW [1,3,384,256]` and five logits in the exact class order: `denim_shorts`, `lingerie`, `maid`, `nurse`, `swim_bikini`.
3. Verify `swim_bikini` above threshold produces `SWIMWEAR`; `lingerie` above threshold produces `SUGGESTIVE`; all other clothing labels, unknown labels and low-confidence outcomes produce `UNCERTAIN`, never `ALLOW`.
4. Verify Yahoo OpenNSFW's input `[1,224,224,3]`, output `[P(sfw),P(nsfw)]` and its full-frame/person-crop classification.
5. Verify ViT-Tiny NSFW input `NCHW [1,3,384,384]`, output logits `[NSFW,safe]`, softmax order and its independent score.
6. In Balanced profile, provide a screen capture with no person boxes. Confirm all nine overlapping ViT tiles are evaluated and each positive tile is redacted as a whole region.
7. Test one image containing a person plus other media outside the person box. Record whether any uncovered media is missed; the current design does not run tile scanning when person boxes exist.
8. Pass malformed dimensions, non-finite scores and ONNX errors. Confirm they are treated as failures and trigger the opaque fallback rather than `ALLOW`.

## Capture and window correctness

1. Test portrait/landscape, status/navigation bars, edge-to-edge app content, split screen, floating windows, popups and dialogs.
2. Test scroll, rapid feed replacements and window changes while inference runs. Confirm stale generations and old capture sequences cannot overwrite the latest overlay.
3. Test secure/DRM windows and screenshot rate limiting. Confirm Android restrictions are respected and the app uses the documented fallback.
4. Force inference and overlay errors in a test build. Confirm the opaque cover remains; after three failures inference retries stop until explicit Stop → Start.
5. Measure whether the cover is shown quickly enough after a notified event. The opaque cover improves failure behavior but does not make Android screenshot redaction instantaneous.

## Representative moderation dataset

Use lawful, consented/licensed images and videos with human labels. Separate training-independent validation data from threshold tuning. Include:

- one-piece swimsuits, bikinis, swim trunks, rash guards, sports swimwear, modest swimwear and cover-ups;
- lingerie categories, suggestive poses, nudity and explicit sexual content;
- ordinary beach, pool, fitness and skin-exposing but non-sexual images;
- people who are small, partially hidden, overlapping, distant or cropped by a feed card;
- no-person images, cartoons, drawings, memes, collages and screenshots;
- multiple media regions and video frames from varied apps and aspect ratios.

Measure per-category precision/recall, false-negative and false-positive rates, person-detection recall, region recall, bounding-box IoU and the proportion of screen area masked. Report Fast and Balanced separately. A high aggregate accuracy can hide poor recall for swim trunks or one-piece swimsuits.

## Performance and thermal validation

Run each profile for at least 30 minutes and record:

- model download size and initialization time;
- ONNX/MediaPipe first-inference and steady-state time;
- P50/P95 time from screenshot request to overlay commit;
- GPU/CPU use, peak RAM/PSS/native heap and app process restarts;
- battery drain, thermal throttling and device temperature;
- screenshot success / rate-limit rate and events missed between samples;
- behavior at 0.75, 1.5, 3 and 5 second intervals.

The Balanced profile adds a second NSFW model and a possible nine-tile fallback, so it may be slower than Fast. Do not assume the Galaxy S24 FE can sustain either profile before measuring it.

## Stop criteria

Do not recommend daily use if the app crashes, leaves persistent misaligned overlays, materially misses swimsuit categories, causes disruptive false positives, cannot retain a model in memory, destabilizes other apps, consumes excessive battery or thermally throttles. This application samples screenshots; it is not a guaranteed frame-by-frame video filter.

# ScreenVeil R6 V9 — Diagnostics field guide

## Purpose

V9 adds an opt-in, one-shot screenshot-cycle trace and low-frequency device-health monitoring. The diagnostics are intended to help investigate real Galaxy S24 FE behavior after a protection session. They are observational only.

No diagnostic feature changes the moderation decision, screenshot schedule, model input, model thresholds, segmentation behavior, coverage limits, stop conditions, worker deadlines, overlay flags, emergency controls, or process-poison behavior.

## How to capture one detailed detection cycle

1. Install the V9 APK produced by the green CI workflow. V9 increments `versionCode` so it can be installed as an update over V8 when signed with the same key.
2. Enable the ScreenVeil Accessibility service, grant notification permission, and make sure the ongoing Stop notification can be shown.
3. Add the ScreenVeil Stop tile to Quick Settings. Test the tile before starting a protection session.
4. Choose **Fast** for the first run. Keep Gemma disabled during the first device/performance pass.
5. Start protection. Open ScreenVeil's **Emergency stop and diagnostics** section and tap **Trace next screenshot**.
6. Leave ScreenVeil and display the content/screen you want to examine. Do not take or upload screenshots for this trace. The next actual capture request consumes the one-shot request.
7. Return to ScreenVeil after the pipeline has completed. Tap **Copy last cycle** and paste the text into the support conversation.
8. Tap **Copy session report** to copy the health snapshot, bounded event log and latest detailed cycle together.

If the next cycle is skipped, rejected, stale, or fails, that outcome is included in the trace. A trace is one-shot; arm it again for another screenshot. If capture is rate-limited or the service is busy, it records the next capture request that is actually admitted by the existing pipeline, not every accessibility event.

## What the one-shot trace records

- Cycle identifier, timestamp, active model profile and Android/device build identifiers.
- Active-window selection time, bounds, dimensions and display rotation. It does not record the foreground app/package name.
- Android screenshot callback delay and hardware-buffer dimensions.
- HardwareBuffer-to-CPU-bitmap copy duration and frame dimensions.
- The selected detector backend and the individual stages reported by that detector:
  - EfficientDet-Lite0 person detection.
  - Yahoo OpenNSFW classification per person crop.
  - Marqo-derived ViT-Tiny classification per person crop, or its bounded screen-tile scan when applicable.
  - Gemma image resize/JPEG preparation, inference backend, GPU failure/CPU fallback and fallback initialization timings.
- Detector pipeline duration, parse status and decision counts (not classifier scores or raw model responses).
- Redaction-policy timing, eligible/retained/dropped region counts.
- Google Selfie Multiclass body segmentation duration and output/frame dimensions.
- Pixel-mask composition duration, visible-pixel count, final per-region and combined coverage percentages, mapped region rectangles relative to the captured application window (not pixel-tight alpha bounds), and the unchanged hard caps.
- WindowManager add/update call duration and whether the call was accepted; end-to-end capture-to-overlay-submission duration and final outcome.

The WindowManager metric means that Android accepted the add/update call. It does **not** prove that the compositor presented the frame on the physical display. Geometry is measured from the app's window and prepared overlay. A visual alignment check on the Galaxy S24 FE is still required.

## Device and session health

While protection is active, a low-priority daemon sampler records a snapshot every 15 seconds. A main-looper heartbeat runs every 5 seconds and reports the latest and maximum scheduling delay since the previous health sample.

Metrics:

- Battery charge percentage, battery temperature sensor reading and charging state.
- Android thermal status and, when supported, the Android thermal-headroom forecast. Android defines a headroom value of 1.0 as the severe-throttling threshold; values above 1.0 can indicate heavier throttling, and the API can return unavailable/NaN on unsupported devices. <https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)>
- ScreenVeil process PSS memory, system available RAM and Android's low-memory flag.
- ScreenVeil process CPU time converted to a percentage of one fully busy logical CPU core. This value can exceed 100% on a multi-core device; it is not total-device CPU utilization.
- Main-looper heartbeat delay, a coarse responsiveness signal.

Limitations:

- Battery temperature is the battery sensor temperature, not SoC/GPU temperature.
- Battery percentage is a whole-device reading; it does not measure ScreenVeil's exact mAh consumption.
- The app does not expose per-app GPU utilization, exact CPU package temperature, or a precise frame-presentation timestamp through these diagnostics.
- Android may report thermal headroom as unavailable/unsupported on a device or build.
- Main-looper delay is sampled at heartbeat intervals and can only be recorded after the looper runs again; it is diagnostic, not an independent watchdog.

## Privacy and retention

- The diagnostic trace does not store screenshot pixels, image crops, recognized screen text, model raw responses/scores, or foreground app/package names.
- The diagnostics files are in ScreenVeil's app-private internal files directory. Nothing is uploaded or transmitted by this feature.
- The normal model pipeline still uses in-memory screenshots. Gemma's existing inference path writes a temporary JPEG in app-private cache and deletes it in its `finally` block after inference.
- The event log is bounded to approximately 96 KiB; the latest one-shot cycle report is bounded to 24 KiB in UTF-8. The copied session report includes the latest 22,000 event-log characters and is bounded to 48,000 characters.
- **Copy last cycle** and **Copy session report** are explicit clipboard actions. Review the text before sharing it.
- **Clear diagnostics** disarms a pending trace immediately and queues deletion of the event log and latest cycle report in the background.

## Safety invariants deliberately left unchanged

The following production controls are unchanged from V8:

- At most 35% coverage for one final body/clothing mask.
- At most 45% combined mask coverage.
- The independent 35% coverage watchdog threshold and 3-second dwell.
- Five-minute maximum protection session.
- Five-second screenshot hard timeout and eight-second inference hard timeout.
- The worker-poison latches, executor shutdown and process-restart requirement after a hard worker timeout.
- No rectangular fallback when a segmentation mask is absent, invalid or fails to render.
- No whole-screen NSFW scoring/redaction fallback; the overlay remains a transparent, non-touchable accessibility window.
- Notification Stop action and Quick Settings Stop tile requirements.

The V9 trace observes values already calculated by the moderation/overlay path. It does not feed diagnostic geometry or health metrics back into the coverage policy or safety decisions.

## CI and test scope

- Existing CI jobs, existing unit/instrumentation test sources, and the existing API 34/API 36 test matrix are retained.
- No new diagnostics-specific unit or instrumentation tests were added, as requested.
- No existing test was deleted or weakened as part of V9.
- The V8 CI result was reported green by the user. V9 still needs to run through the same CI workflow once to verify the new Kotlin/Compose code compiles and that the unchanged suite remains green.

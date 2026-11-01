# Galaxy S24 FE device-validation plan

This plan is required before treating ScreenVeil as more than an experimental build.

## Device and software record

Record the following before each test run:

- Exact Galaxy S24 FE model / region and Android version.
- One UI version and security patch level.
- Screen resolution, refresh rate, battery percentage and free storage.
- ScreenVeil APK version and SHA-256.
- Gemma artifact SHA-256 and LiteRT-LM runtime version (`0.18.0`).
- Selected backend (GPU or CPU fallback).
- Test start/end time and ambient temperature if available.

## Functional acceptance checks

1. Install the debug APK from the GitHub Actions artifact.
2. Verify that the model download is user-initiated and that the UI shows progress.
3. Interrupt a download and relaunch; ensure an incomplete `.part` file is not treated as installed.
4. Download the model and confirm the app reports it as installed only after SHA-256 verification.
5. Modify a test copy of the downloaded model file; start protection and confirm the app rejects it before engine initialization.
6. Deny overlay permission; confirm capture does not start.
7. Grant overlay permission and deny notification permission on Android 13+; verify the documented FGS behavior and that the user can still stop the session.
8. Start capture and select **Entire screen**. Confirm other app windows are visible to the captured stream where Android permits it.
9. Stop using the app button, notification Stop action, Android projection stop affordance and permission revocation.
10. Rotate the device and verify the capture surface resizes without leaving old-coordinate masks.
11. Open a protected `FLAG_SECURE` screen and confirm the app does not attempt to bypass capture restrictions.
12. Verify the overlay is non-touchable and does not steal taps or keyboard focus.
13. Test all three redaction effects and intensity settings.
14. Verify all preferences survive process death/relaunch.
15. Confirm no screenshot or temporary frame remains after normal inference; after force-stop, relaunch and check cache cleanup.

## Model quality benchmark

Use only lawfully obtained, properly licensed evaluation media. Separate development/tuning data from a held-out test set. Keep adult-content evaluation to adults; do not use this model as a child-safety classifier.

Suggested strata:

- ordinary safe images and ordinary clothing;
- all forms of swimwear (beach, pool, sports, varied poses and lighting);
- revealing clothing / lingerie;
- suggestive poses and sexualized presentation;
- explicit nudity;
- sexual activity;
- artwork, statues, medical material and other ambiguous contexts;
- screenshots with multiple media items, overlapping cards, small thumbnails, captions, UI controls and picture-in-picture.

Measure:

- media-region detection recall (did Gemma find each media rectangle?);
- box intersection-over-union against human annotations;
- per-category precision and recall;
- swimwear recall (the user's strict rule);
- safe-content false-positive rate;
- false-negative rate by media size and screen layout;
- malformed/invalid JSON rate;
- time from frame receipt to overlay display;
- P50/P95 inference latency;
- peak process RSS / PSS and native heap;
- battery drain and device temperature over at least 30 minutes;
- sustained behavior at 1.0, 1.5, 3.0 and 5.0 second analysis intervals.

Do not report a single aggregate accuracy number as sufficient. Report each stratum and confidence intervals. A false negative on a category the user wants redacted must be visible in the report.

## Stop criteria

Stop testing and do not recommend daily use if any of the following occurs:

- repeated projection or overlay crashes;
- system UI becomes unresponsive;
- the app repeatedly loses the capture or overlay permission;
- the model cannot stay resident without memory pressure or system process kills;
- CPU fallback latency makes redaction effectively unusable;
- thermal throttling or battery drain is excessive;
- model outputs are inconsistent enough that redactions are misleading;
- privacy checks reveal frame files or model input being sent off-device.

## Expected result

A successful GitHub build proves only that the project compiles and tests pass. It does not prove model quality, live-screen coordinate accuracy, battery suitability, thermal suitability or compatibility with every app. Those require device testing and a separately reviewed evaluation set.

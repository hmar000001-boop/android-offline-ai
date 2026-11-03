# ScreenVeil 1.6.0 (R6) — implementation and validation report

## Implemented

- Universal final-pixel safety limits: maximum 35% of the application window for one redaction mask and 45% for the union of all masks. No decision-category exemption remains. An over-limit result is rejected and protection stops/clears; masks are not cropped to arbitrary smaller rectangles.
- Independent scheduled coverage watchdog: stop/clear after coverage remains at or above 35% for 3 seconds.
- Maximum protection session: 5 minutes for this test period. Repeated Start requests are idempotent and do not extend the existing deadline.
- Screenshot callback deadline: 5 seconds. Model inference deadline: 8 seconds.
- Hard worker timeout shuts down the worker and sets a process-wide poison latch. Accessibility unbind/rebind cannot create another worker while an interrupted native/JNI call may still exist. A full app-process force-stop/restart is required before trying again.
- Google Selfie Multiclass 256x256 TFLite model added as a fifth pinned model artifact. It is used only for localization: body-skin and clothing pixel classes are redacted, while face, hair, accessories and background remain transparent. It is not a swimwear/content classifier or anatomical parser.
- The segmentation input is aspect-preserving letterboxed; mask mapping retains exact content bounds. The selected pixel mask is intersected with each detector region, dilated slightly, measured after rasterization, and checked again after composition. There is no rectangular fallback if segmentation fails.
- Model profiles now require the segmentation model. The Balanced profile uses person detector + OpenNSFW + ViT + segmentation; Fast uses person detector + OpenNSFW + segmentation; experimental Gemma uses Gemma + segmentation. The Swimwear-aware profile remains disabled.
- CI downloads and verifies the exact segmentation model for its real-runtime smoke test; the manual model-pin workflow verifies all five catalog artifacts.
- Emergency Stop remains mandatory: Start requires the notification Stop action to be available; Quick Settings Stop remains a separate stop-only tile.
- Documentation, model governance, license notices, device validation plan and CI coverage were updated.

## Added/updated regression coverage

- 58 unit-test methods.
- 28 lifecycle instrumentation-test methods.
- 1 instrumentation smoke test loads the exact pinned Google segmentation model and invokes the real TFLite Task Vision segmenter on a synthetic frame.
- Added explicit tests for a full-screen EXPLICIT region being rejected, confirmed masks exceeding aggregate coverage, a full-screen detection stopping/clearing through the service, watchdogs remaining effective after screen-generation changes, process poison after hard timeouts, and repeated Start not extending the five-minute session.

These are test methods present in source, not claims that the R6 test suite has passed.

## Static checks performed in this environment

- `bash -n` passed for the emulator test, runtime diagnostic and fixture-preparation scripts.
- `python3 -m py_compile scripts/verify-model-pins.py` passed.
- `python3 scripts/verify-model-pins.py` passed catalog metadata validation for all five artifacts (URL host, SHA-256 format, file names and size bounds). This did not download files or verify their actual bytes.
- All Android XML files parsed successfully.
- GitHub Actions YAML parsed successfully.
- Fixture source check found exactly one Java declaration for each fixture class and no legacy Kotlin fixture files.
- Source count check found 58 unit tests, 28 lifecycle instrumentation tests and 1 real segmenter smoke test.
- Kotlin parser check found no syntax diagnostics in the changed Kotlin source files; the standalone compiler could not resolve Android/Gradle dependencies and therefore this is not a compilation result.
- The ZIP integrity and SHA-256 are checked after packaging.

## Not executed / required next

This environment could not download the pinned Gradle distribution (`services.gradle.org` DNS unavailable) and has no Android SDK/emulator. Therefore this exact R6 source revision has **not** been Gradle-compiled, unit-tested, linted, packaged into an APK, or run on API 34/API 36. It has not been run on the Galaxy S24 FE.

Before installing on the phone:

1. Run the R6 GitHub Actions workflow and require `build`, `release-build`, API 34 emulator and API 36 emulator jobs to pass.
2. Run the manual `verify-model-pins` job and confirm all five downloads pass actual size and SHA-256 verification, especially the new Google segmentation model.
3. Download the debug APK artifact (not the unsigned release APK).
4. Follow `docs/DEVICE_VALIDATION.md` on the Galaxy S24 FE. Validate body-mask alignment, false positives/misses, stop controls, coverage limits, watchdogs, process restart after timeout, five-minute auto-stop, performance, heat, RAM and battery.

## 2026-11-03 CI compiler corrections

The subsequent GitHub Actions run reached `:app:compileDebugKotlin` and reported three source-level errors. These were corrected in the follow-up package: the coverage watchdog now uses an explicitly typed `Runnable` object expression; the synthetic segmentation mask builds a `ByteArray` directly; and the overlay reads `PixelBounds.width` / `height` as properties. The corrections preserve the safety thresholds, timers, segmentation policy, model catalog and dependencies. See `docs/CI_COMPILATION_FIX_2026-11-03.md` for the exact diagnosis and validation boundary. A full Android Gradle build still needs to be rerun in GitHub Actions; it has not been claimed as passing locally.

## Important functional limitations

- Segmentation localizes body-skin and clothing pixels; it does not decide whether clothing is swimwear and does not anatomically separate hands/arms/torso/legs.
- Current approved detectors are general NSFW/person detectors. There is no validated swimwear-specific classifier.
- The new segmentation model's real accuracy, latency and thermal behavior on Galaxy S24 FE are unknown until physical-device testing.
- A green emulator suite validates tested lifecycle and segmentation runtime plumbing, not moderation accuracy or complete protection of every image/video frame.

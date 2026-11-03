# R6 CI compilation fix — 2026-11-03

## Trigger

The supplied GitHub Actions log failed at `:app:compileDebugKotlin`, before the requested unit-test, lint, or APK tasks could complete. The Kotlin compiler reported three source defects:

1. Recursive type checking in the `coverageWatchdog` property initializer in `ScreenProtectionAccessibilityService.kt`.
2. `toByteArray()` was called on a `List<Int>` in the synthetic body-mask helper in `HumanBodySegmenter.kt`; the available extension applies to `Collection<Byte>`.
3. `MappedRegion.destination` is a `PixelBounds`, whose `width` and `height` are Kotlin `Int` properties. `OverlayController.kt` incorrectly invoked them as functions.

The `Unable to strip ... .so` messages in the CI log were warnings and were not the build failure.

## Source corrections

### `ScreenProtectionAccessibilityService.kt`

The watchdog is now an explicitly typed `Runnable` object expression with an explicit `override fun run()`. It reschedules itself using `this`, which refers to the `Runnable` instance. This avoids both recursive property type inference and reading the property before its initialization is complete. The dwell threshold, stop action, diagnostic message, and scheduling behaviour are unchanged.

### `HumanBodySegmenter.kt`

The synthetic mask helper now constructs the binary dilation input with `ByteArray(categories.size)`, mapping each non-zero category to `1.toByte()` and zero to `0.toByte()`. This preserves the intended 0/1 mask values and uses the type expected by `dilate`.

### `OverlayController.kt`

The two destination-size comparisons and `Bitmap.createScaledBitmap` arguments now read `target.width` and `target.height`. `PixelBounds` declares these as `Int` properties. The rasterization dimensions and coverage calculation are unchanged.

## Deliberately unchanged

This is a compiler-correction patch only. It does not change:

- the 35% per-region or 45% aggregate final-pixel coverage limits;
- the 3-second coverage watchdog, screenshot/inference deadlines, or session deadline;
- safety-stop and overlay-clear behaviour;
- segmentation classes, dilation radius, mask intersection, or no-rectangle-fallback rule;
- emergency-stop requirements;
- model catalog entries, model URLs, hashes, licenses, or profiles;
- Gradle dependencies or Android configuration;
- existing unit and instrumentation test source.

## Validation performed after the correction

- Project shell scripts pass `bash -n`.
- The model-pin verification script's Python source passes AST parsing.
- `scripts/verify-model-pins.py` passes metadata validation for all five catalog artifacts. This does not download or hash the model bytes.
- All 7 Android XML files parse.
- The GitHub Actions YAML parses.
- The watchdog object-expression pattern and the byte-array conversion were independently compiled with the locally available Kotlin compiler using minimal language-level reproductions.
- Source diff review confirms only the three Kotlin files above plus this documentation/report update changed from the packaged R6 baseline.

## Not verified here

This container does not have Gradle installed, the pinned Gradle 8.14.6 distribution cached, or an Android SDK/emulator. Therefore the Android project itself could not be Gradle-compiled here, and no claim is made that the full CI workflow now passes. The next verification is to run the same GitHub Actions workflow on this corrected ZIP/source revision. The user's supplied CI run is the baseline failure, not a post-fix result.

# ScreenVeil R6 V11 — EfficientDet private-filesystem loader fix

## Root cause

ScreenVeil downloads and verifies EfficientDet-Lite0 into the app-private `files/models` directory. The previous implementation called TensorFlow Lite Task Vision 0.4.4 `ObjectDetector.createFromFileAndOptions(Context, String, Options)` and passed `personFile.absolutePath` as the String. In this exact library version, that overload passes the String to `AssetManager.openFd`, so it treats the absolute path as an APK asset name and throws `FileNotFoundException`.

## Fix

The detector now checks that the verified `File` exists and is readable, then calls `ObjectDetector.createFromFileAndOptions(File, Options)`. In TensorFlow Lite Task Vision 0.4.4 this overload opens the provided File using `ParcelFileDescriptor.open(..., MODE_READ_ONLY)` and gives the descriptor to JNI. This is the correct API for the app-private downloaded model.

The same source audit confirmed that `TfliteHumanBodySegmenter` already uses the `File, Options` overload. ONNX Runtime model loading uses absolute filesystem paths and is unchanged.

## Unchanged safety behavior

No change was made to `CoveragePolicy.kt`, screenshot/inference deadlines, worker poisoning, session watchdog, coverage watchdog, emergency stop controls, segmentation categories, mask composition, overlay lifecycle, model hashes, download bounds, or profile policy. A model initialization failure still stops protection and leaves the screen visible.

## Version

Android `versionCode` increased from 12 to 13. `versionName` remains 1.6.0. An install-over update requires the same signing key as the installed build.

## Validation

Source-level checks verify that no production model loader passes an absolute private-filesystem model path to the `Context, String` ObjectDetector overload, and that the segmentation loader continues to use `File, Options`. ZIP integrity and SHA-256 are checked at packaging. Android Gradle compilation and device runtime initialization require the user's CI/device environment; they are not claimed as executed in this container.

# ScreenVeil 1.6.5 (R9) — EfficientDet output-contract correction

Date: 2026-11-04

## Failure addressed

The R8 API 34 emulator instrumentation test loaded the pinned `mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite` file using the LiteRT Interpreter and reported exactly two output tensors. R7/R8 code incorrectly assumed four outputs from an in-model `TFLite_Detection_PostProcess` operator. The actual two-output variant requires external SSD anchor decoding/NMS driven by custom `DETECTOR_METADATA`; the four-array adapter cannot decode it. Treating the two tensors as four would be unsafe.

## Change

R9 replaces only the person-detector model artifact with `litert-community/efficientdet` (commit `971d935f3679eabbcce7b4d3733f351d403ff2b9`) TFLite deployment of TensorFlow Hub `tensorflow/efficientdet/lite0/detection/1`.

- File: `efficientdet_lite0_detection.tflite`
- Published SHA-256: `33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704`
- Exact published file size: 4,561,184 bytes (enforced as both minimum and maximum)
- Source: `https://huggingface.co/litert-community/efficientdet/resolve/971d935f3679eabbcce7b4d3733f351d403ff2b9/efficientdet_lite0_detection.tflite`
- Verified upstream pointer size and SHA-256 are recorded at the same full repository commit.
- Repository license declaration: Apache-2.0
- The upstream repo states this is a TFLite deployment of `tensorflow/efficientdet/lite0/detection/1`; TensorFlow Hub documents RGB `uint8` input in `[0,255]` and four postprocessed outputs.

The LiteRT adapter now supports the required `UINT8` input as well as the existing `FLOAT32` segmentation input, and supports `FLOAT32`/`INT32` outputs converted to float arrays. All byte capacities are derived from tensor shape and type and checked against runtime `numBytes()`. Unknown tensor count errors include each tensor's index, type and shape.

The EfficientDet preprocessing now passes raw RGB byte values, not float-normalized values. The expected detector outputs remain `[1,25,4]`, `[1,25]`, `[1,25]`, `[1]`, with float32 postprocess results; the instrumented test executes the exact pinned artifact on CPU and validates bounds, class IDs, scores and count.

## Other changes

- Version bumped to `1.6.5` / versionCode `18`.
- Model catalog points to the new file, source, SHA-256 and size bounds.
- CI model download verification automatically downloads the new filename from the catalog; the instrumentation test reads the same filename from `ci-models`.
- Added a unit assertion for the detector's file name, hash, source and runtime.
- Previous invalid R7 detector file, partial download and old verification marker are included in the upgrade cleanup list.
- No changes to OpenNSFW or ViT ONNX models, Selfie Multiclass model, segmentation policy, masks, coverage thresholds, watchdogs, inference/screenshot deadlines, emergency Stop or privacy boundaries.

## Validation status

Static source/catalog/workflow checks and archive checks are performed during packaging. The model's published SHA-256 is sourced from the Hugging Face artifact page. This environment cannot download the model, resolve Gradle distributions, or run an Android emulator. Therefore the exact remote model bytes, R9 compile, lint, and R9 instrumentation test are **not verified here**. The next GitHub Actions run must first verify the artifact hash, then confirm that the real LiteRT test sees the expected input/output tensor types and shapes and completes inference. If it fails, the enriched contract diagnostic will print the actual output tensor types/shapes instead of only a count.

R9 is source-only until those checks pass; do not install it on the Galaxy S24 FE yet.

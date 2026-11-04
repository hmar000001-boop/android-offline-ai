# ScreenVeil 1.6.6 (R10) — EfficientDet raw output correction

Date: 2026-11-04

## Why R9 failed

R9 loaded the pinned four-output EfficientDet Lite0 TFLite artifact and executed CPU inference. Its real-model instrumentation test failed at a combined assertion requiring each box value to be finite, within `[0,1]`, and correctly ordered. The CI log did not include the actual box values, so it does not prove whether the observed value was out of range or non-finite. The assertion conflated normalized coordinate units with an in-frame clipping guarantee and did not expose which predicate failed. R10 splits these checks and prints the raw tuple on failure, so the next run will resolve that ambiguity.

The TensorFlow Lite `DetectionPostProcess` kernel decodes raw boxes as `[ymin,xmin,ymax,xmax]`. Its `ValidateBoxes` implementation checks corner ordering but does not require coordinates to be finite or clamp them to the image frame before writing the output tensor. The model's evaluation path treats output coordinates as normalized and multiplies them by image height/width. Finite, ordered detections may therefore partially extend beyond the model input frame, while non-finite values must still be rejected by ScreenVeil.

The raw TFLite class index is zero-based. In the COCO training label order, raw model class index `0` is person. Google's evaluation code adds `CLASS_OFFSET = 1` only to translate the model index into the one-based COCO category ID used by COCO evaluation. Google's TensorFlow Lite Model Maker source explicitly notes that this offset is not needed in the Android application. R9 compared the raw index directly with the one-based category ID, which could miss the person class and misidentify the next class.

## R10 changes

1. `EfficientDetBoxDecoder.validateRawBox` now rejects non-finite and inverted coordinates but does not incorrectly require every coordinate to be within `[0,1]`.
2. The production decoder clips valid boxes to the visible frame and discards boxes that do not intersect the screen or are smaller than the minimum crop size.
3. `EfficientDetClassDecoder` validates raw class indices as integral values in `0..89`; the production detector compares the raw zero-based index directly with `PERSON_CLASS_INDEX = 0`. It deliberately does not add the COCO evaluation offset in the Android application.
4. The detector validates active output boxes, class indices and scores during warm-up and inference. Malformed outputs still fail closed through the existing protection stop.
5. The emulator test now reports non-finite and inverted raw coordinates separately, includes the offending coordinate tuple, and does not impose a false `[0,1]` output clamp assumption. It also asserts at least one active detection so the tensor checks cannot pass vacuously. Unit tests cover partial out-of-frame clipping, boxes that do not intersect the frame, malformed coordinates, and raw class-index validation.
6. App version bumped to `1.6.6`, versionCode `19`.

## Unchanged

- No model files, URLs, SHA-256 pins, sizes or licenses changed.
- No TensorFlow Lite Task Vision or MediaPipe Tasks dependency was reintroduced.
- ONNX models and Google Selfie Multiclass segmentation artifact are unchanged.
- No screenshot capture, inference deadline, coverage cap, watchdog, overlay geometry policy, emergency Stop, privacy rule or fail-closed behavior was relaxed.

## Validation boundary

The source patch and static contracts can be checked in this environment. The remote EfficientDet model cannot be downloaded here, and this environment has no working Android emulator/Gradle distribution access. Therefore the R10 Android build, real-model emulator test and Samsung ARM64 inference remain unverified. The next GitHub Actions run is expected to exercise the exact pinned model and the updated output handling. Do not install R10 until the workflow is green.

## Upstream contract references

- TensorFlow Lite `DetectionPostProcess` output order, coordinate structure, box validation, and raw class index writes: https://android.googlesource.com/platform/external/tensorflow/+/fefc192ea12/tensorflow/lite/kernels/detection_postprocess.cc
- Google EfficientDet TFLite evaluation code and coordinate scaling: https://github.com/google/automl/blob/master/efficientdet/tf2/eval_tflite.py
- TensorFlow Lite Model Maker's explanation that the COCO label offset is for evaluation and is not added in Android: https://github.com/tensorflow/examples/blob/master/tensorflow_examples/lite/model_maker/core/task/model_spec/object_detector_spec.py
- COCO category 1 is `person`: https://github.com/tensorflow/models/blob/master/research/object_detection/data/mscoco_label_map.pbtxt

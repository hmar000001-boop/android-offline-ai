# ScreenVeil 1.6.6 (R10) — EfficientDet raw-output contract correction

Date: 2026-11-04

## Executive summary

R9 passed Android build/lint and the earlier connected instrumentation suite except for one real-model EfficientDet assertion. The LiteRT model loaded, its four output tensors matched the pinned shape contract, inference ran, and the test reached active detections. The failing assertion combined three predicates (finite values, values inside `[0,1]`, and correct corner order) and did not print the raw values.

R10 corrects the production decoder and the test to follow the low-level TensorFlow Lite `DetectionPostProcess` contract, not assumptions copied from higher-level Task API / evaluation representations. R10 also corrects the person class comparison: the model emits zero-based class indices; in this COCO label ordering raw class index `0` is `person`. The `+1` offset is for one-based COCO evaluation IDs and is not added by Android applications.

## Why the iterative fixes took several rounds

The failures were not all the same defect. Each round surfaced a different interface boundary that had not been verified against the exact artifact and runtime:

1. **R6 / V11 native loading:** ScreenVeil used TensorFlow Lite Task Vision `0.4.4`. The Galaxy S24 FE report failed while loading `task_vision_jni`. Correcting the private-filesystem model path was necessary, but it did not replace the incompatible/failed native Task Vision layer.
2. **R7 runtime migration:** EfficientDet and Selfie Multiclass moved to bundled LiteRT `2.3.2` CPU Interpreter to avoid the old Task Vision JNI library. This kept model execution local and avoided adding MediaPipe Tasks usage-metrics behavior. The interpreter's output buffers are sized from actual tensor metadata rather than the problematic CompiledModel output allocation path for `TFLite_Detection_PostProcess`.
3. **R8 lint:** The application compiled and the debug APK assembled, but `lintDebug` rejected Activity Result use with an old Fragment implementation in the resolved dependency graph. R8 explicitly pins AndroidX Fragment `1.9.1`; it does not suppress the lint check.
4. **R8 model output count:** The first detector model file used with the raw interpreter exposed two outputs, whereas the app expected the four postprocessed outputs from a detection model. R9 replaced that artifact with the pinned LiteRT Community deployment of TensorFlow Hub EfficientDet-Lite0 that includes the post-processing operator and four output tensors.
5. **R9 raw coordinate assertion:** The real model then loaded and executed, but the test incorrectly required every raw box coordinate to be in `[0,1]`. That is not a guarantee made by the TFLite `DetectionPostProcess` kernel. Its `ValidateBoxes` routine checks corner ordering only; the raw decoder must also check finiteness, and the application must clip valid boxes to the visible image before using them. The R9 log did not print the values, so it cannot establish whether the observed failure was an out-of-range value or a non-finite value. R10 splits the checks and prints the tuple to make the next result conclusive.
6. **Class-index semantics:** R9 compared the raw zero-based class index directly with COCO category ID `1`. These are different namespaces. The first model class index (`0`) is the person label. Google evaluation code adds `CLASS_OFFSET = 1` for COCO's one-based evaluation IDs, while TensorFlow Lite Model Maker explicitly documents that this offset is not needed in an Android application. R10 compares raw index `0` directly.

The common process failure was that earlier code relied on different layers' descriptions of the same model (Task Vision, TF Hub serving signature, raw LiteRT tensors and COCO evaluation) as though they had identical representations. They do not. R10 now pins the distinctions in code and test assertions.

## Exact raw model contract used by R10

- Model artifact: `litert-community/efficientdet/efficientdet_lite0_detection.tflite`
- Immutable repository commit: `971d935f3679eabbcce7b4d3733f351d403ff2b9`
- Exact bytes: `4,561,184`
- SHA-256: `33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704`
- Input: RGB `uint8`, NHWC `[1,320,320,3]`, pixel values `[0,255]`
- Raw output tensor order from TFLite `DetectionPostProcess`:
  1. boxes `[1,25,4]`, `[ymin,xmin,ymax,xmax]`
  2. classes `[1,25]`, zero-based class indices
  3. scores `[1,25]`
  4. detection count `[1]`
- Raw class index `0` is the first COCO label, `person`; the app does not apply COCO evaluation's `+1` offset.
- Box coordinates are normalized relative to the model input. They can be outside `[0,1]`; they must still be finite and ordered (`ymin <= ymax`, `xmin <= xmax`).

## R10 implementation

1. `EfficientDetBoxDecoder` validates bounds offset, finiteness and corner order. It does not require in-frame coordinates.
2. The decoder clips a valid box against the visible screen (`[0,1]` in normalized coordinates), maps it outward to pixel bounds, and discards boxes with no visible intersection or less than the existing minimum side.
3. The decoder now returns a pure Kotlin `EfficientDetPixelBounds` value object. Android `RectF` is created only at the Android service/detector boundary, so the local unit tests do not rely on Android framework stubs.
4. `EfficientDetClassDecoder` validates finite integral raw class indices in `0..89`; the production code compares the raw index directly to `PERSON_CLASS_INDEX = 0`. No COCO-evaluation offset is applied.
5. The detector validates all active boxes, class indices and scores both during warm-up and each inference. Malformed results still throw and flow into the existing fail-closed stop path.
6. The real-model instrumentation assertion reports non-finite boxes separately from inverted boxes and includes the raw coordinate tuple. It does not require values to be within `[0,1]`. It also requires the deterministic smoke input to produce at least one active detection, so the contract loop cannot pass vacuously.
7. Five local unit tests cover out-of-frame clipping, fully off-screen rejection, non-finite/inverted box rejection, raw person class index `0`, and invalid class IDs.
8. App version: `1.6.6`, `versionCode = 19`.

## Other approaches considered

- **Keep Task Vision `0.4.4`:** rejected because it is the JNI library named in the device failure and does not remove the original issue.
- **Migrate to MediaPipe Tasks:** technically plausible for 16 KB page-size support, but the project requires local inference without a usage-metrics path. It was not introduced.
- **LiteRT `CompiledModel`:** not selected for the postprocessed detector because the known output-buffer issue around `TFLite_Detection_PostProcess` makes it unsafe to assume caller output allocation is correct.
- **Convert the detector to ONNX or hand-write anchor/NMS post-processing:** rejected for this correction. The pinned model already has a supported postprocessing operator; a conversion or custom decoder would introduce another model/anchor/NMS contract to validate.
- **Simply relax the failing assertion:** rejected. Production decoding had the same invalid `[0,1]` assumption, and the old class comparison also needed correction. The production path and test now share the same raw-output contract.

## Preserved safety behavior

No coverage or time limit was changed. In particular, universal 35% per-mask and 45% combined-coverage limits, the 35%/3-second watchdog, five-minute maximum session, screenshot and inference deadlines, process-poison timeout latch, segmented pixel masks, transparent non-selected pixels, no rectangular fallback, Emergency Stop notification action and Quick Settings Stop tile remain unchanged.

The following important files are byte-for-byte unchanged from R9: `CoveragePolicy.kt`, `ScreenProtectionAccessibilityService.kt`, and `OverlayController.kt`. The full R9-to-R10 change set is limited to the detector, its tests, app version/User-Agent, README and model/test governance documentation.

## Validation performed for this source package

- Model catalog static pin/host/size/SHA syntax validator: passed for all five artifacts.
- Shell syntax: passed for all seven scripts.
- XML parsing: passed for all seven XML files.
- GitHub Actions workflow YAML parse: passed.
- Kotlin/JVM contract harness for the pure decoder helpers: passed (clipping, fully off-screen discard, finite/order validation, raw class index 0 person).
- R9/R10 source comparison: expected detector/test/docs/version files only; no files removed.
- Critical coverage policy, accessibility service and overlay controller files compared byte-for-byte with R9: unchanged.
- ZIP integrity and SHA-256: checked during packaging.

## Not validated here

This environment has no Android SDK/emulator configured and cannot download the Gradle distribution or the pinned remote model binary. Therefore this package has **not** been Android-built, run through `lintDebug`, tested on API 34/API 36 emulators, or run on the Samsung Galaxy S24 FE ARM64 device. Static checks are not a substitute for those runs.

## Required next CI interpretation

The next R10 Actions run should:

1. Build and lint successfully.
2. Download both exact model artifacts and verify their pinned hashes.
3. Pass the 31 connected instrumentation methods on the configured emulator APIs.
4. Specifically pass `pinnedEfficientDetLite0RunsAndMatchesFourOutputDetectionPostProcessContract`.

If that model test fails again, the assertion should now identify whether the raw box is non-finite or inverted and include its exact tuple. An out-of-frame but finite and ordered box is expected to be clipped, not rejected. Do not install R10 until the entire workflow is green; even then, a physical Samsung ARM64 test remains necessary.

## Upstream references

- TensorFlow Lite `DetectionPostProcess` kernel, tensor order, decoded coordinate structure, validation and class output: https://android.googlesource.com/platform/external/tensorflow/+/fefc192ea12/tensorflow/lite/kernels/detection_postprocess.cc
- Google EfficientDet TFLite evaluation code and normalized coordinate scaling: https://github.com/google/automl/blob/master/efficientdet/tf2/eval_tflite.py
- TensorFlow Lite Model Maker note about the COCO offset being for evaluation and not needed in Android: https://github.com/tensorflow/examples/blob/master/tensorflow_examples/lite/model_maker/core/task/model_spec/object_detector_spec.py
- COCO category ID `1` is person: https://github.com/tensorflow/models/blob/master/research/object_detection/data/mscoco_label_map.pbtxt
- TensorFlow Hub EfficientDet-Lite0 input/output contract: https://github.com/tensorflow/tfhub.dev/blob/master/assets/docs/tensorflow/models/efficientdet/lite0/detection/1.md

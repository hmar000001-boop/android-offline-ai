# ScreenVeil — visual model candidate review

**Review date:** 2026-11-03  
**Project baseline:** ScreenVeil 1.6.0 R6  
**Scope:** Fully on-device Android screenshot moderation/redaction, including explicit and suggestive material and the unresolved swimwear-specific signal.  
**Decision:** No new model from this candidate review is admitted to the production catalog.

> **Status clarification (2026-11-03):** The user confirms that every point in the R6 design/review report that preceded this document has been completed. This candidate review is retained as historical model research and provenance, not as a checklist of unfinished R6 safety or segmentation work. The separate follow-up addendum extends the candidate comparison only.

## 1. Decision in one page

The R6 Google Selfie Multiclass model solves a different problem from content understanding. It provides pixel categories for body skin and clothes, which R6 uses to shape a mask **after** another model has selected a region. It does not identify a swimsuit, lingerie, nudity, or sexual content. Google's current task description lists background, hair, body-skin, face-skin, clothes, and accessories as the six Selfie Multiclass categories. Google's published pipeline benchmark for this model is 217.76 ms CPU / 71.24 ms GPU on a Pixel 6; this is a reference number, not a Galaxy S24 FE measurement and not a promise of screenshot-to-overlay latency. [Google AI Edge — Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter)

The new search found two relevant clothing candidates from Electblake:

- **RT-DETRv2 R18vd** is an object detector with `swim` and `swim_sports` labels, plus several lingerie/shorts labels. It is the more relevant *detector* candidate. However, its released model is an 80.5 MB Safetensors checkpoint, not a ready-to-load ONNX/TFLite Android artifact. The model card documents a private data collection, YOLO-World pseudo-labels, an eight-epoch training run, and no independent external benchmark. The author explicitly says it is not designed for safety-critical decisions. It is a research candidate, not a safe production addition. [Model card](https://huggingface.co/electblake/clothing_type_rtdetr_v2_r18vd) · [repository files](https://huggingface.co/electblake/clothing_type_rtdetr_v2_r18vd/tree/main)
- **EfficientNetV2-S Clothing Type Classifier** has a ready ONNX artifact and declares MIT for the fine-tuned model (the base model is declared Apache-2.0). It predicts only `denim_shorts`, `lingerie`, `maid`, `nurse`, or `swim_bikini`. It has no `ordinary clothing`, `other`, or rejection class. It always chooses one of those five labels and its softmax scores are not calibrated. Its reported 89.73% is a model-selection validation result, not an independent benchmark. This makes it useful for an isolated research experiment, but not for deciding that a person is safe or that a candidate should be ignored. [Model card and ONNX contract](https://huggingface.co/electblake/clothing_type_classifier/blob/main/README.md) · [repository files](https://huggingface.co/electblake/clothing_type_classifier/tree/main)

Under the current source/provenance and conservative admission policy, **Google SSD MobileNet V2 trained on Open Images V4 remains the preferred production-conversion candidate** because the reviewed Google model card declares Apache-2.0 and the dataset has a documented 600-class boxable taxonomy including a `Swimwear` class. But the artifact reviewed is a SavedModel, not a pinned Android TFLite export; there is no ScreenVeil-verified TFLite file, label map, tensor contract, class-specific evaluation, or Galaxy S24 FE result. It stays disabled. [Google model card](https://www.kaggle.com/models/google/mobilenet-v2/tensorFlow1/openimages-v4-ssd-mobilenet-v2/1) · [Open Images V4 facts and licensing](https://storage.googleapis.com/openimages/web/factsfigures_v4.html)

**Therefore: R6 is not changed to claim swimsuit recognition.** Existing Balanced/Fast/Gemma behavior, coverage limits, emergency-stop requirements, and fail-stop segmentation behavior are preserved. No third-party weights were downloaded into this source ZIP.

## 2. Candidate comparison

| Candidate | What it actually predicts | Android artifact situation | Main evidence gap | Decision |
|---|---|---|---|---|
| Google SSD MobileNet V2 / Open Images V4 | General object boxes; Open Images taxonomy includes `Swimwear`, `Person`, and other garment/object classes | Reviewed model is a SavedModel (~63.36 MB); conversion to TFLite is required | No pinned TFLite bytes, class map verification, ScreenVeil class precision/recall, or S24 FE run | **Preferred conversion candidate**, but disabled |
| Electblake RT-DETRv2 R18vd | Clothing boxes labelled `swim`, `swim_sports`, `denim_shorts`, and colour-specific lingerie classes | Repository provides an 80.5 MB `model.safetensors`; no ONNX/TFLite artifact is listed | Private dataset, pseudo-labels, small training run, private validation only, export/runtime parity not done | **Best detector research candidate**, not admitted |
| Electblake EfficientNetV2-S | One whole-crop label among five: shorts, lingerie, maid, nurse, swim_bikini | Direct FP32 ONNX is listed (81.8 MB); ONNX Runtime Android is available | No `other`/reject class, uncalibrated softmax, narrow labels, reported validation accuracy only | **Possible isolated classifier experiment**, not admitted |
| NudeNet (reviewed repository/package family) | Anatomical exposed/covered body-part regions, not swimwear | ONNX 320×320 and 640×640 variants are described by the project | Exact upstream source/release/weight license needs reconciliation; model's class semantics are not clothing taxonomy | Not a swimwear solution; do not import without provenance review |
| NSFWJS | General `Drawing`, `Hentai`, `Neutral`, `Porn`, `Sexy` classes | TensorFlow.js model; project also demonstrates React Native use | Not a swimwear detector and not a native Kotlin/TFLite artifact in this project | Not selected for this Android source tree |
| Desert Ant Labs Moderator | General NSFW flag/score and regions including nipples, genitals, buttocks, nude, sexAct | Kotlin Android SDK documented; on-device inference | Source-available, not open source; visible attribution and licensing/scale conditions; no published swimsuit class in the reviewed API | Technically viable for a separate vendor-licensed evaluation; not admitted |
| Google Selfie Multiclass (already in R6) | Per-pixel background/hair/body-skin/face-skin/clothes/accessories | Versioned TFLite artifact pinned in R6 | Segmentation does not infer garment type or content sensitivity; S24 FE quality is unmeasured | Keep as **mask localization only** |

### What the metrics do and do not say

- RT-DETRv2 reports overall validation mAP 0.646 and class AP 0.5712 for `swim` and 0.8796 for `swim_sports`. These are from the model author's private validation split; the card says no independent external benchmark was used. They are not ScreenVeil accuracy estimates.
- The EfficientNet model card reports 89.73% validation accuracy for the selected checkpoint. The model card explicitly says it is a validation/model-selection result, not an independent benchmark or test-set result. It also says predictions are restricted to the five labels and the confidence scores are uncalibrated.
- Google's Open Images SSD reports overall dataset mAP, not a swimwear-specific metric. A large general-object taxonomy does not guarantee useful precision or recall for swimwear on mobile screenshots.
- Google's Selfie Multiclass model task benchmarks are not measurements on the Galaxy S24 FE or on arbitrary full-screen app screenshots.

Do not compare these metrics as though they were measured on the same dataset, label set, or evaluation protocol.

## 3. Deployment analysis

### Android ONNX path

ONNX Runtime documents Android Java/C/C++ support and CPU, NNAPI, and XNNPACK execution providers. It recommends starting with the full ONNX Runtime package and then measuring/optimizing for the target device. Actual execution-provider performance is model- and device-specific. This means an ONNX file is a plausible Android delivery format, **not** proof that the model will be fast, small, or fully supported on a Galaxy S24 FE. [ONNX Runtime — Mobile deployment](https://onnxruntime.ai/docs/tutorials/mobile/) · [Android NNAPI EP](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html) · [XNNPACK EP](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)

The EfficientNet clothing classifier is the only newly reviewed swimsuit-related candidate with a directly listed ONNX inference graph and documented input preprocessing. It still needs exact revision pinning, artifact SHA-256, Android session creation, input/output tensor checks, and CPU/XNNPACK/NNAPI benchmarking. The RT-DETRv2 detector needs a reproducible export from Safetensors/PyTorch first. Hugging Face documents ONNX export and custom architecture configurations, but a generic export guide is not evidence that this exact fine-tuned checkpoint will export correctly or preserve its detections. [Optimum ONNX export guide](https://huggingface.co/docs/optimum-onnx/onnx/usage_guides/export_a_model)

### Google TFLite path

TensorFlow's Object Detection API documents an SSD-specific TFLite export path (`export_tflite_ssd_graph.py` and the TFLite detection post-processing operation). That establishes that an SSD conversion workflow exists; it does not establish that the specific Open Images V4 SavedModel converts cleanly with the current project toolchain or preserves class scores and boxes. A source-vs-export parity test remains mandatory. [TensorFlow Object Detection — run on mobile with TFLite](https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/running_on_mobile_tensorflowlite.md)

## 4. Model-specific notes

### Google Open Images V4 SSD MobileNet V2

- The Google Kaggle card describes SSD + MobileNet V2 trained on Open Images V4, 600 boxable categories, Apache-2.0, and a SavedModel artifact around 63.36 MB.
- Open Images V4 lists 600 boxable classes. Its annotations are CC BY 4.0; the dataset page notes image licences should be checked individually. Do not conflate dataset-image licensing with the model card's stated model license.
- `Swimwear` is a class name in the reviewed OIDv4 taxonomy; this is semantic garment recognition, unlike Selfie Multiclass segmentation.
- Keep the candidate disabled until a reproducible export is produced and validated.

### Electblake RT-DETRv2 R18vd

- Hugging Face declares Apache-2.0 for this checkpoint. The listed model file is `model.safetensors` at 80.5 MB; the repository does not list an Android ONNX/TFLite artifact.
- Labels include `swim` and `swim_sports`; they are not a broad garment taxonomy and the card warns about incomplete labels and garment/color conflation.
- Training data is private and omitted from the repository. Initial boxes were generated with YOLO-World pseudo-labeling. The card reports eight training epochs and private validation results.
- The model card's intended use is experimental clothing detection/indexing/dataset organization and explicitly excludes safety-critical decisions.
- If evaluated, export without changing learned weights, pin every tool/library/source revision, compare PyTorch and ONNX outputs on a fixed corpus (labels, scores, and boxes), then test with ONNX Runtime Android. Do not silently fine-tune or quantize it as part of an export.

### Electblake EfficientNetV2-S Clothing Type Classifier

- The repository declares MIT for the fine-tuned artifact and Apache-2.0 for the base model.
- It lists an 81.8 MB FP32 ONNX graph, opset 18, 20,183,893 parameters, input `[N,3,384,256]`, and a dynamic batch dimension.
- Preprocessing is part of the model contract: RGB, aspect-preserving fit inside 256×384, centre padding with `(127,127,127)`, scale by 1/255, normalize with mean/std 0.5, then NCHW. The training pipeline first detected and cropped the largest person; the classifier itself does not include person detection.
- It always chooses one of five labels and has no generic/unknown class. A high softmax score therefore cannot be interpreted as calibrated evidence that the crop truly belongs to the class.
- The data card lists 16,496 person crops split into train/validation/test sets, but the published metric is validation accuracy for checkpoint selection. The model card says non-critical clothing tagging only.

### NudeNet and NSFWJS

NudeNet's public descriptions show a 320×320 ONNX default, a larger 640×640 option, and anatomical classes such as exposed/covered breasts, buttocks, genitalia, anus, belly and armpits. These labels can be useful for anatomical-exposure research but do not identify swimwear garments. The reviewed PyPI project page reports MIT, while the separately reviewed `amarchandan/Nudenet` GitHub repository lists AGPL-3.0. That discrepancy is a provenance warning, not proof that every NudeNet release has the same licence: the exact repository, version, model bytes and bundled notices would have to be reconciled before reuse. [NudeNet project/package page](https://pypi.org/project/nudenet/) · [reviewed GitHub fork](https://github.com/amarchandan/Nudenet)

NSFWJS documents TensorFlow.js inference and the categories Drawing, Hentai, Neutral, Porn, and Sexy. It runs client-side, but the reviewed API is a general NSFW classifier, not a garment classifier. Its TensorFlow.js runtime/model packaging would be a separate integration path rather than simply reusing the existing native Kotlin ONNX/TFLite adapters. [NSFWJS](https://nsfwjs.com/) · [source repository](https://github.com/infinitered/nsfwjs)

### Desert Ant Labs Moderator

The reviewed Android SDK exposes an `isNSFW` result, score, and region types including nipples, genitals, buttocks, nude, and sexAct. The vendor describes it as on-device moderation and provides Kotlin installation instructions. Its published license is Source-Available License v1.0, not an OSI open-source license; it requires visible attribution and provides a free tier below 100,000 monthly active devices per platform/model, with a commercial license above that threshold. The license itself describes local/on-device use, so it should not be rejected on the false premise that inference requires a remote service. It is a separate vendor-licensed option, and no swimwear-specific class was established by the reviewed API. [Moderator Android docs](https://desertant.com/docs/moderator/) · [Source-Available License v1.0](https://license.desertant.com/1.0)

## 5. Admission gates before any candidate changes runtime decisions

1. **Identity and rights:** immutable upstream revision, exact artifact URL, file SHA-256, byte bounds, model licence, base-model licence, conversion-code licence, and dataset provenance. Record any restrictions and attribution requirements.
2. **Reproducible export:** export script, environment lock, conversion command, deterministic labels/config/preprocessor files, tensor names/shapes/types, colour channel, normalization, resize/crop/pad behavior, and post-processing/NMS contract.
3. **Numerical parity:** run the same fixed corpus through the upstream model and exported artifact; compare class IDs, scores, and boxes. Define tolerances before the run.
4. **Representative evaluation:** use a documented, permission-cleared, held-out test set with ordinary clothing, lingerie, swimwear, modest swimwear, sports swimwear, varied skin tones, poses, occlusions, lighting, image/video crops, screen layouts and multi-person scenes. Report per-class precision, recall, false positives, false negatives, and confidence calibration. Split by source/person to reduce leakage.
5. **Safe decision semantics:** an absent class or low score must never imply `ALLOW`. A closed-set classifier cannot provide a safe negative class unless a reject/unknown path is added and validated. Keep model score separate from policy decision.
6. **Android feasibility:** run with network disabled after explicit installation; verify no runtime telemetry; measure cold start, P50/P95 inference, RAM peak, CPU/GPU/NPU behavior, battery and thermal throttling on Galaxy S24 FE. Test long sessions and background/foreground changes.
7. **Failure behavior:** model file mismatch, load error, malformed tensor, timeout, crash, or empty output must follow the app's explicit stop/clear policy. Do not add a fallback that silently expands to full rectangles or silently returns `ALLOW`.
8. **Release gate:** only after the above results are reviewed should a model be added to the fixed catalog with its real SHA-256 and tested size bounds, an adapter and regression tests, and a profile whose required artifact list is non-empty. Emulator tests establish plumbing/lifecycle behavior, not visual accuracy.

## 6. Final decision for this package

- Preserve R6 runtime code and active model catalog.
- Keep `SWIMWEAR_AWARE` disabled; do not populate its catalogue with guessed hashes or converted files that have not been measured.
- Keep Selfie Multiclass as pixel-mask localization only.
- Record Google Open Images V4 SSD as the preferred production-conversion candidate under the current provenance/licensing policy.
- Record RT-DETRv2 as the strongest newly found swimwear-labelled detector research candidate, and EfficientNetV2-S as the quickest direct-ONNX classifier experiment. Neither is approved for safety decisions.
- Do not market or describe ScreenVeil as recognizing all swimwear, lingerie, nudity, or video frames. It remains experimental and its false-positive/false-negative behavior is not certified.

---

## Sources reviewed

1. [Google Open Images V4 SSD MobileNet V2 model card](https://www.kaggle.com/models/google/mobilenet-v2/tensorFlow1/openimages-v4-ssd-mobilenet-v2/1)
2. [Open Images V4 facts, class taxonomy and licensing](https://storage.googleapis.com/openimages/web/factsfigures_v4.html)
3. [Open Images V4 downloads and annotation definitions](https://storage.googleapis.com/openimages/web/download_v4.html)
4. [Google AI Edge Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter)
5. [Electblake RT-DETRv2 model card](https://huggingface.co/electblake/clothing_type_rtdetr_v2_r18vd)
6. [Electblake RT-DETRv2 repository files](https://huggingface.co/electblake/clothing_type_rtdetr_v2_r18vd/tree/main)
7. [Electblake EfficientNet classifier model card and ONNX contract](https://huggingface.co/electblake/clothing_type_classifier/blob/main/README.md)
8. [Electblake EfficientNet classifier repository files](https://huggingface.co/electblake/clothing_type_classifier/tree/main)
9. [ONNX Runtime mobile deployment](https://onnxruntime.ai/docs/tutorials/mobile/)
10. [ONNX Runtime Android NNAPI execution provider](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)
11. [ONNX Runtime XNNPACK execution provider](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
12. [Hugging Face Optimum ONNX export guide](https://huggingface.co/docs/optimum-onnx/onnx/usage_guides/export_a_model)
13. [TensorFlow Object Detection API TFLite mobile export guide](https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/running_on_mobile_tensorflowlite.md)
14. [NudeNet PyPI project](https://pypi.org/project/nudenet/)
15. [Reviewed NudeNet GitHub fork](https://github.com/amarchandan/Nudenet)
16. [NSFWJS website and model labels](https://nsfwjs.com/)
17. [NSFWJS source repository](https://github.com/infinitered/nsfwjs)
18. [Desert Ant Labs Moderator Android documentation](https://desertant.com/docs/moderator/)
19. [Desert Ant Labs Source-Available License v1.0](https://license.desertant.com/1.0)

**Source caution:** model cards, repository metadata, vendor documentation, and published validation numbers are publisher statements. This review did not independently verify the weights, reproduce metrics, or benchmark any candidate on the Galaxy S24 FE.

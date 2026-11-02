# Model governance and artifact allow-list

ScreenVeil uses only existing pretrained model artifacts. The project does not train, fine-tune, quantize, modify weights or build a model from scratch. Model files are downloaded only after a user action, stored in private app storage, size-checked and hashed before any runtime loads them. The catalog uses immutable source references and pinned SHA-256 values; arbitrary external model import is not supported.

## Operational profile: Balanced multi-model (default)

| Component | Artifact / immutable source | Size floor | SHA-256 | License | Runtime | Responsibility |
|---|---|---:|---|---|---|---|
| Google EfficientDet-Lite0 float32 | [MediaPipe Object Detector model](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite) | 13,836,895 bytes | `40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58` | Apache-2.0 | MediaPipe Tasks Vision | Locate `person` regions; not a content classifier |
| Electblake Clothing Type EfficientNetV2-S ONNX | [revision `31d25e7`](https://huggingface.co/electblake/clothing_type_classifier/tree/31d25e7) | 80,000,000 bytes | `52b486a66845593d16f1e743dea9ceff2ed5427daf476cfd523fb41290265150` | MIT classifier; Apache-2.0 base | ONNX Runtime | Person-centred clothing classification |
| Yahoo OpenNSFW ResNet-50 ONNX | [release `model-v1`](https://github.com/gawryco/opennsfw-onnx/releases/tag/model-v1) | 23,590,724 bytes | `864bb37bf8863564b87eb330ab8c785a79a773f4e7c43cb96db52ed8611305fa` | Yahoo weights BSD-2-Clause; conversion package Apache-2.0; referenced preprocessing lineage MIT | ONNX Runtime | Binary pornography / NSFW score |
| Marqo-derived ViT-Tiny NSFW ONNX | [revision `ab04f01`](https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/tree/ab04f01) | 22,000,000 bytes | `e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2` | Apache-2.0 as declared by ONNX repository | ONNX Runtime | Independent binary NSFW score; tile fallback when no person box is detected |

The Balanced profile uses all four artifacts. The ONNX classifiers run on CPU through `com.microsoft.onnxruntime:onnxruntime-android:1.31.1`; EfficientDet-Lite0 runs through `com.google.mediapipe:tasks-vision:0.10.35`. The model implementations and exact input preprocessing are in `SpecializedScreenModerationDetector.kt`.

### Model contracts and interpretation

**EfficientDet-Lite0** is a general object detector. ScreenVeil filters its output to the `person` category. It locates potential people; it does not classify clothing, suggestiveness or nudity.

**Electblake Clothing Type EfficientNetV2-S** is a 5-logit classifier with labels in exact output order: `denim_shorts`, `lingerie`, `maid`, `nurse`, `swim_bikini`. The model card specifies RGB input `[1,3,384,256]`, aspect-preserving resize, centre pad with RGB `(127,127,127)`, scale to `[0,1]`, mean/std 0.5 normalization and NCHW layout. The source training pipeline person-crops before classification; EfficientDet provides that crop in ScreenVeil. It has no `normal`, `other` or generic-safe class. Therefore `denim_shorts`, `maid`, `nurse`, low-confidence results and unknown labels map to `UNCERTAIN`, never `ALLOW`. `swim_bikini` and `lingerie` map to positive decisions only over the configured confidence thresholds. This is deliberately conservative.

**Yahoo OpenNSFW** outputs `[P(sfw), P(nsfw)]`. The app's 224×224 preprocessing follows the published pipeline: resize to 256 square, centre crop 224×224, JPEG quality 75 round-trip, RGB-to-BGR channel order and mean subtraction `(104,117,123)`. It is a binary pornography classifier, not a swimsuit or nuanced suggestiveness classifier; exposed skin, stylized artwork and fitness imagery can produce false positives.

**ViT-Tiny NSFW** accepts RGB float32 NCHW `[1,3,384,384]`, direct 384-square Catmull-Rom resize and per-channel normalization `(pixel/255 - 0.5)/0.5`. Its two outputs are logits `[NSFW, safe]`; ScreenVeil applies softmax and reads index 0. In the Balanced profile the model independently checks the full screenshot and each person crop. If no person detections are returned, it scans up to nine overlapping tiles and masks a whole positive tile. This expands coverage beyond detected people but is not a general screen-layout detector.

The classifier thresholds in source (`0.52` for `swim_bikini`, `0.58` for `lingerie`, `0.82` on person crops, `0.90` on tiles and `0.94` on full frames) are engineering starting values, not calibrated performance results. A non-finite or malformed model output is an inference failure and must trigger the opaque fallback, not an implicit allow.

## Alternate profiles

- **Fast specialized · offline** uses EfficientDet-Lite0, Electblake Clothing Type and Yahoo OpenNSFW. It omits the second ViT classifier and the nine-tile fallback. It is intended to lower storage/compute use, not to increase accuracy.
- **Gemma 4 vision** is a separate optional profile using the pinned `litert-community/gemma-4-E2B-it-litert-lm` artifact, commit `6e5c4f1e395deb959c494953478fa5cec4b8008f`, size `2,588,147,712` bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, repository-listed Apache-2.0 license and LiteRT-LM 0.18.0. Gemma is not invoked by the specialist profiles.

When a user changes profiles, the accessibility service closes the previous detector and initializes the newly selected profile on the serialized inference worker. An old loaded runtime is never reused under a different profile label.

## Candidates intentionally excluded

### Desert Ant Labs Moderator

This is a relevant compact content moderation model, but the current source-available license requires periodic license validation over the network and sends an installation identifier and app/model metadata. Even though image content is not described as uploaded, the mandatory network check conflicts with ScreenVeil's strict offline inference / no tracking design. It is not included or selectable. Reconsider only if the user explicitly changes that privacy requirement and the legal terms are reviewed.

### Electblake RT-DETRv2 clothing detector

- Source: [electblake/clothing_type_rtdetr_v2_r18vd](https://huggingface.co/electblake/clothing_type_rtdetr_v2_r18vd), Apache-2.0 declared, immutable reviewed revision `f8578d3`.
- It detects six labels with bounding boxes, including `swim` and `swim_sports`, plus `denim_shorts` and three lingerie colour classes. The model card lists an 80.5 MB Safetensors checkpoint and reports validation mAP 0.646 / recall@100 0.8696 on its private split.
- The model card states its data is private, initial boxes were pseudo-labelled by YOLO-World, training ran for eight epochs and its metrics are self-reported. It warns that the label set is incomplete and may not represent other styles, lighting, poses or occlusion.
- The published artifact is Safetensors/Transformers, not a pre-exported ONNX/TFLite Android model. At reviewed revision `f8578d3`, the 80.5 MB `model.safetensors` artifact has upstream SHA-256 `b2c20807c15e30c98bfb5dd4c85549192c598f87954502e64ec8fb4caaa88af4`.
- It requires an export/compatibility exercise, exact generated-artifact hash and Android test before integration. A conversion would preserve existing pretrained weights; it must not introduce any training or fine-tuning. It is a stronger candidate for future swimwear-specific detection than the current five-class crop classifier, but it is not yet shipped in this APK/source profile.

### Blue-Eye

Its author reports a safe/suggestive/explicit image classifier and a benchmark on 3,000 real photos. However, the published weights are gated and about 1.2 GB, its model is built on DINOv3's model license rather than a simple Apache/MIT license, and the safe class explicitly includes swimwear. It is therefore not a direct replacement for a swimwear detector and is not in this mobile offline profile.

### Desert Ant's Genlook product-photo classifier

Its model card explicitly says it classifies product photos and should not be used to identify or make decisions about people. That intended-use restriction rules it out for ScreenVeil's person-image moderation flow.

### ScreenParser

ScreenParser is a screen-layout/UI-element detector with classes such as `Image` and `Video`, not an adult-content or clothing classifier. Its reviewed checkpoint is YOLO11-L/Ultralytics `.pt`; there is no verified Android runtime artifact here, and the exact Ultralytics licensing implications were not resolved. It remains excluded.

### NSFWJS and NudeNet

NSFWJS is primarily a TensorFlow.js package with no verified Kotlin/Android artifact in this app. NudeNet is a body-part detector, not a swimwear classifier; upstream license metadata and weight provenance need resolution before inclusion. Neither is selectable.

## Validation policy

The app does not claim these models have been validated on ScreenVeil screenshots. The upstream performance claims are not substitutes for a ScreenVeil benchmark. Before a production release, run a representative, lawfully obtained test corpus and record per-class precision/recall, false negatives, false positives, screen-region recall, bounding-box IoU, latency, memory and thermal effects on the Galaxy S24 FE. The dataset should include one-piece suits, bikinis, swim trunks, rash guards, sports swimwear, lingerie, ordinary beach scenes, small people, partial occlusion, multi-person grids, illustrations and video frames.

Do not silently lower thresholds or map a classifier's limited non-target labels to `ALLOW` to improve apparent false-positive rates.

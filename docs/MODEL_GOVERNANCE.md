# Model governance and artifact allow-list

## Approval policy

ScreenVeil follows the user's **balanced** model-approval policy with **download-only network access**:

1. Accept established research/open-source projects and named publishers when the upstream artifact, license declaration and intended use are documented.
2. Pin an exact artifact URL/revision and SHA-256. Enforce lower/upper artifact byte bounds.
3. Download only after an explicit user action. Use HTTPS and manually validate every redirect against an allow-list of model-hosting domains.
4. Store the artifact in private app storage and load it only after the complete file passes size and SHA-256 checks.
5. Run screenshot inference locally. Do not add telemetry, remote inference, background update checks, arbitrary URLs or user-imported models.
6. Do not train, fine-tune, quantize or modify model weights.

This is a supply-chain admission policy, not a statement that the models have been independently security-audited or that their classifications are accurate. The Kotlin type is named `ApprovedModelArtifact`, not `TrustedModel`, to avoid implying a stronger guarantee than the checks provide.

## Selectable catalog

The source catalog is implemented in `GemmaModelCatalog.kt` as `SpecializedModelCatalog` plus `GemmaModelCatalog`. The download installer uses that fixed allow-list.

| Model artifact | Pinned source | Size bounds enforced | SHA-256 | License declaration | Android runtime | Responsibility |
|---|---|---:|---|---|---|---|
| Google EfficientDet-Lite0 float32 | [Fixed Google Storage artifact](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite) | 13,836,895–20,000,000 bytes | `40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58` | Apache-2.0 | TensorFlow Lite Task Vision `0.4.4` (standalone local runtime) | Person/object localization; not a content classifier |
| Yahoo OpenNSFW ResNet-50 ONNX | [gawryco/opennsfw-onnx release `model-v1`](https://github.com/gawryco/opennsfw-onnx/releases/tag/model-v1) | 23,590,724–26,000,000 bytes | `864bb37bf8863564b87eb330ab8c785a79a773f4e7c43cb96db52ed8611305fa` | Original Yahoo weights BSD-2-Clause; ONNX conversion/preprocessing components have separate notices | ONNX Runtime Android `1.31.1` | General binary NSFW score |
| ViT-Tiny NSFW 384 ONNX | [KanariKanaru repository revision `ab04f01`](https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/tree/ab04f01) | 22,000,000–30,000,000 bytes | `e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2` | Apache-2.0 as declared by the ONNX repository | ONNX Runtime Android `1.31.1` | Second general NSFW score; tiled fallback when no person box is detected |
| Gemma 4 E2B Instruct LiteRT-LM | [litert-community revision `6e5c4f1e395deb959c494953478fa5cec4b8008f`](https://huggingface.co/litert-community/Gemma-4-E2B-it-litert-lm/tree/6e5c4f1e395deb959c494953478fa5cec4b8008f) | 2,588,147,712–2,700,000,000 bytes | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | Apache-2.0 for Gemma 4; LiteRT-LM community packaging has a separate upstream provenance | LiteRT-LM Android `0.18.0` | Optional general multimodal vision profile |

The `minimumBytes` and `maximumBytes` values are accepted artifact-size windows, not advertised exact sizes. The exact SHA-256 digest is the identity check. A `.verified` marker is a UI/cache hint only; `verifyInstalledBlocking` re-hashes the complete artifact before it is handed to the model runtime.

## Profiles

| Profile | Required model artifacts | Approximate download | Availability |
|---|---|---:|---|
| Balanced multi-model (default) | EfficientDet-Lite0 + Yahoo OpenNSFW + ViT-Tiny NSFW | about 60 MB | Selectable; three artifacts; no Gemma. ViT tile scan runs only if no person boxes are detected. |
| Fast specialized | EfficientDet-Lite0 + Yahoo OpenNSFW | about 38 MB | Selectable; two artifacts; no Gemma, no second classifier or tile fallback. |
| Swimwear-aware · Google Open Images V4 | Google SSD MobileNet V2 Open Images detector + Yahoo OpenNSFW + ViT-Tiny NSFW (planned) | Not yet pinned | Visible in the profile list but disabled. No candidate model is downloaded, admitted or run before conversion and validation. |
| Gemma 4 vision | Gemma 4 E2B only | about 2.6 GB | Selectable separate optional profile; it does not install or require specialist models. |

## Runtime contracts and interpretation

### EfficientDet-Lite0

The model is loaded using the standalone `org.tensorflow:tensorflow-lite-task-vision:0.4.4` library and `ObjectDetector`. The app allow-lists the `person` label and maps returned pixel-space boxes to the screenshot. Pixel-to-normalized conversion is performed by the pure `DetectionBoxNormalizer.fromPixelBounds` helper using floating-point division and outward rounding; regression tests verify both the normalized result and its subsequent mapping into the overlay window. The runtime dependency is TensorFlow Lite Task Vision; this app does **not** use the MediaPipe Tasks Android SDK. This is intentional: the MediaPipe Tasks usage-metrics documentation describes API usage/device/inference metrics being sent to Google, while the selected product policy permits network access only for model downloads. The standalone TFLite Task Vision runtime is an Apache-2.0 library; its app usage path is local.

EfficientDet is a general object detector. It does not identify nudity, sexual content, lingerie or swimwear.

### Yahoo OpenNSFW

The model emits `[P(sfw), P(nsfw)]`. The app's 224×224 preprocessing follows the published ONNX project pipeline: square resize to 256, centre crop 224, JPEG quality 75 round-trip, RGB-to-BGR channel order and mean subtraction `(104,117,123)`. It is a binary/general pornographic-content classifier, not a swimsuit or nuanced suggestiveness classifier. Its weights originate from Yahoo, but the app consumes a third-party ONNX conversion, so both original-weight licensing and conversion/preprocessing notices matter.

### ViT-Tiny NSFW

The app expects RGB float32 NCHW `[1,3,384,384]`, direct 384×384 resize and per-channel normalization `(pixel/255 - 0.5)/0.5`. The model repository documents two logits `[NSFW, safe]`; the app applies softmax and reads index 0. The repository's Apache-2.0 declaration and conversion information are documented, but its accuracy figures are publisher-reported, not an independent ScreenVeil evaluation.

### Conservative decision policy

Both general NSFW classifiers inspect the full screenshot; a high configured score can cause a full-frame redaction. The app then detects people and evaluates each person crop. If the general NSFW score does not cross the explicit threshold, the person crop is `UNCERTAIN`, not `ALLOW`, because no approved clothing classifier is present. The default **Redact uncertain content** setting covers these boxes. This can mask ordinary people and is a conservative trade-off, not swimwear classification.

Balanced additionally scans up to nine overlapping tiles with the ViT only when EfficientDet returns no people. If people are detected, the tile fallback is not run; unrelated media outside those boxes may be missed. Fast has no tile fallback. Thresholds are engineering starting values and are not calibrated or benchmarked for this product.

## Excluded candidates

The following candidates are not present in selectable profiles or the active model catalog:

- **Electblake Clothing Type EfficientNetV2-S:** five labels, including `swim_bikini`, but no generic `other`/ordinary-clothing class; limited publisher dataset/evaluation and no sufficiently broad swimwear coverage. Its old downloaded artifact is deleted on app startup/upgrade.
- **Electblake RT-DETRv2:** potentially interesting because it advertises `swim` and `swim_sports` boxes, but the reviewed checkpoint is Safetensors/Transformers rather than a validated Android ONNX/TFLite artifact. Its model card describes private/pseudo-labelled data and generalization limitations. It is not admitted until a no-training export is reproducible, hash-pinned, license-reviewed and Android-tested.
- **Desert Ant Labs Moderator:** recurring online license validation conflicts with the offline inference and download-only network policy.
- **NSFWJS:** no approved, pinned Android model artifact/runtime adapter in this project.
- **NudeNet:** body-part detector rather than a swimwear classifier; artifact/license provenance needs more review.
- **IBM/ETH ScreenParser:** identifies screen-layout elements such as images/video, not adult content; available detector/runtime licensing and Android execution were not admitted.
- **Ultralytics Open Images YOLO exports:** the associated Open Images label set includes `Swimwear`, but the reviewed Ultralytics model/configuration is under AGPL-3.0. It is excluded from this Apache-2.0 distribution unless a separate licensing decision is made.
- **Genlook product-photo classifier:** not intended for decisions about people.

### Preferred future swimwear detector: Google MobileNet V2 SSD · Open Images V4

This is the strongest candidate found so far under the balanced license policy:

- The Google-published Kaggle model is an SSD MobileNet V2 detector trained on Open Images V4, declares Apache-2.0 and reports 600 boxable categories. The published SavedModel artifact is about 63.36 MB; its reported overall Open Images V4 mAP is 0.34, not a swimwear-specific metric.
- The Open Images V4 class list explicitly includes `Swimwear`, `Brassiere`, `Shorts` and `Person`. The model output contract includes bounding boxes, class names/labels and scores.
- It is not yet admitted to the app: the reviewed Google artifact is distributed as a SavedModel, not as a pinned TFLite Android artifact. Its exact exported TFLite bytes, metadata/class map, input normalization, output tensor contract and device behavior are therefore not yet verified.
- TensorFlow documents conversion of eligible SSD Detection API models to TFLite for mobile use. The exact OIDv4 checkpoint still needs a reproducible conversion using a compatible TensorFlow Object Detection API version, with no training or fine-tuning. The export must then be tested against the source SavedModel on fixed images, carry the correct `Swimwear` label map/metadata (or use a custom TFLite Interpreter adapter), receive an exact SHA-256 and size bounds, and run successfully on Android.

This model meets the license/provenance screen as a *candidate*, but not the artifact/runtime approval gate. A disabled `SWIMWEAR_AWARE` profile entry is now present so the intended profile is visible without implying it can be installed. Its required-model list is intentionally empty, the profile is excluded by `ModelProfile.fromStored`, the UI disables selection, and the service refuses initialization. The candidate is not in `SpecializedModelCatalog.all`, so the downloader cannot fetch it. Do not enable it before conversion and validation. Until then, **ScreenVeil has no approved swimwear-specific model**.

## Security and approval limitations

- SHA-256 protects against unexpected/altered bytes relative to the pinned catalog; it does not prove a publisher's identity or the safety of the architecture/weights.
- Apache/MIT/BSD license declarations are not security audits and do not prove dataset quality or model accuracy.
- No independent model-binary audit, full transitive dependency SBOM, ScreenVeil validation dataset or Galaxy S24 FE benchmark has been completed.
- A model must not be described as `100% safe`, `100% trusted` or `100% accurate`.

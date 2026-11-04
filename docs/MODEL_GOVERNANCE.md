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
| LiteRT Community EfficientDet-Lite0 uint8 | [Pinned Hugging Face artifact at commit `971d935f3679eabbcce7b4d3733f351d403ff2b9`](https://huggingface.co/litert-community/efficientdet/commit/971d935f3679eabbcce7b4d3733f351d403ff2b9) | exactly 4,561,184 bytes | `33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704` | Apache-2.0 (upstream repository declaration) | Google AI Edge LiteRT `2.3.2` Interpreter CPU (local) | COCO object localization; person class is used by the app; not a content classifier |
| Yahoo OpenNSFW ResNet-50 ONNX | [gawryco/opennsfw-onnx release `model-v1`](https://github.com/gawryco/opennsfw-onnx/releases/tag/model-v1) | 23,590,724–26,000,000 bytes | `864bb37bf8863564b87eb330ab8c785a79a773f4e7c43cb96db52ed8611305fa` | Original Yahoo weights BSD-2-Clause; ONNX conversion/preprocessing components have separate notices | ONNX Runtime Android `1.31.1` | General binary NSFW score |
| ViT-Tiny NSFW 384 ONNX | [KanariKanaru repository revision `ab04f01`](https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/tree/ab04f01) | 22,000,000–30,000,000 bytes | `e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2` | Apache-2.0 as declared by the ONNX repository | ONNX Runtime Android `1.31.1` | Second general NSFW score; tiled fallback when no person box is detected |
| Google Selfie Multiclass 256×256 TFLite | [Google versioned MediaPipe model artifact](https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite) | 16,000,000–17,000,000 bytes | `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0` | Apache-2.0 as stated on the Google model card | Google AI Edge LiteRT `2.3.2` Interpreter CPU | Pixel classes for body-skin/clothes versus face, hair, accessories and background; not a content classifier |
| Gemma 4 E2B Instruct LiteRT-LM | [litert-community revision `6e5c4f1e395deb959c494953478fa5cec4b8008f`](https://huggingface.co/litert-community/Gemma-4-E2B-it-litert-lm/tree/6e5c4f1e395deb959c494953478fa5cec4b8008f) | 2,588,147,712–2,700,000,000 bytes | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | Apache-2.0 for Gemma 4; LiteRT-LM community packaging has a separate upstream provenance | LiteRT-LM Android `0.18.0` | Optional general multimodal vision profile |

The `minimumBytes` and `maximumBytes` values are accepted artifact-size windows, not advertised exact sizes. The exact SHA-256 digest is the identity check. A `.verified` marker is a UI/cache hint only; `verifyInstalledBlocking` re-hashes the complete artifact before it is handed to the model runtime.

## Profiles

| Profile | Required model artifacts | Approximate download | Availability |
|---|---|---:|---|
| Balanced multi-model (default) | EfficientDet-Lite0 + Yahoo OpenNSFW + ViT-Tiny NSFW + Google Selfie Multiclass segmentation | about 76 MB | Selectable; four artifacts; no Gemma. ViT tile scan runs only if no person boxes are detected, and all resulting regions still pass through the body/clothing mask and universal coverage gate. |
| Fast specialized | EfficientDet-Lite0 + Yahoo OpenNSFW + Google Selfie Multiclass segmentation | about 54 MB | Selectable; three artifacts; no Gemma, ViT or tile fallback. |
| Swimwear-aware · Google Open Images V4 | Google SSD MobileNet V2 Open Images detector + Yahoo OpenNSFW + ViT-Tiny NSFW (planned) | Not yet pinned | Visible in the profile list but disabled. No candidate model is downloaded, admitted or run before conversion and validation. |
| Gemma 4 vision | Gemma 4 E2B + Google Selfie Multiclass segmentation | about 2.6 GB + 16–17 MB | Selectable optional multimodal profile; requires segmentation for every redaction. |


## Runtime contracts and interpretation

### EfficientDet-Lite0

The pinned LiteRT Community deployment at commit `971d935f3679eabbcce7b4d3733f351d403ff2b9` is executed directly with Google AI Edge LiteRT `2.3.2` `Interpreter` CPU compatibility API. The app maps the verified private model file directly; it does not invoke Task Vision or MediaPipe Tasks. TensorFlow Hub documents the input as RGB `uint8` NHWC at 320×320 with values in [0,255], and outputs as detection boxes, scores, classes and count. The raw TFLite adapter reads boxes `[ymin,xmin,ymax,xmax]`, zero-based class indices, scores and count. The TFLite `DetectionPostProcess` kernel guarantees corner ordering but does not clamp decoded boxes to [0,1]; therefore the adapter requires finite, non-inverted coordinates and clips each valid box to the visible screenshot frame. Raw class index `0` is the first COCO label, `person`. The `+1` offset shown in Google evaluation code translates model indices to COCO's one-based evaluation category IDs; Google's Model Maker source explicitly says not to add that offset in an Android application. ScreenVeil therefore compares raw index `0` directly with `PERSON_CLASS_INDEX`, without offset. The existing 0.30 person threshold and 40-result cap are preserved. The real-model instrumentation test checks four output shapes, local CPU inference, finite ordered boxes, zero-based class IDs, confidence ranges and integer detection count. Malformed coordinates fail closed into the existing safety stop; boxes outside the screen are discarded and partially visible boxes are clipped before crop creation. This is a direct model-runtime integration, not a content classifier or a model-weight conversion. The resulting pixel boxes and subsequent overlay geometry remain constrained by the existing mask and coverage gates.

EfficientDet is a general object detector. It does not identify nudity, sexual content, lingerie or swimwear.

### Yahoo OpenNSFW

The model emits `[P(sfw), P(nsfw)]`. The app's 224×224 preprocessing follows the published ONNX project pipeline: square resize to 256, centre crop 224, JPEG quality 75 round-trip, RGB-to-BGR channel order and mean subtraction `(104,117,123)`. It is a binary/general pornographic-content classifier, not a swimsuit or nuanced suggestiveness classifier. Its weights originate from Yahoo, but the app consumes a third-party ONNX conversion, so both original-weight licensing and conversion/preprocessing notices matter.

### ViT-Tiny NSFW

The app expects RGB float32 NCHW `[1,3,384,384]`, direct 384×384 resize and per-channel normalization `(pixel/255 - 0.5)/0.5`. The model repository documents two logits `[NSFW, safe]`; the app applies softmax and reads index 0. The repository's Apache-2.0 declaration and conversion information are documented, but its accuracy figures are publisher-reported, not an independent ScreenVeil evaluation.

### Google Selfie Multiclass body/clothing segmentation (R6)

Each policy-approved detection region gets its own 256×256 inference from Google's Selfie Multiclass model. The model input is an expanded context crop (10% padding around the detector rectangle, clipped to the screenshot), rather than shrinking the entire tall phone screenshot into one 256×256 mask. The crop-local category mask is mapped back to its exact screenshot coordinates. The renderer still intersects that mask with the original, unexpanded detector rectangle, so context padding cannot enlarge the redaction. Category IDs are `0 background`, `1 hair`, `2 body-skin`, `3 face-skin`, `4 clothes`, and `5 others/accessories`; only IDs 2 and 4 are selected. The model is used only for localization; it does not decide whether clothing is swimwear or whether content is sensitive.

The TFLite file is verified against the versioned `float32/1` URL, catalog SHA-256 and 16–17 MB bounds before loading. LiteRT `2.3.2` `Interpreter` maps that verified private file locally and executes it on the CPU. Each region crop is resized aspect-preservingly and letterboxed into 256×256; RGB float32 NHWC values are normalized as `pixel / 127.5 - 1`. The model output is checked for the expected 256×256×6 float tensor; per-pixel argmax reproduces the model's six category IDs. The mask adapter selects only body-skin (2) and clothes (4), validates the buffer/dimensions and maps the crop-local mask into the original screenshot region. The mask is dilated by the existing bounded radius of 2; dilation is clipped to the non-padding content area. Inference is performed once per policy-approved region (bounded by the universal 24-region frame limit and the existing 8-second inference deadline). If a crop mask does not intersect its original detector rectangle, protection stops and clears the overlay; it never substitutes a rectangle. This remains a localization model, not a moderation or swimwear classifier.

If segmentation fails, returns an invalid/empty result, does not intersect a selected region, or cannot be mapped, protection stops and the overlay is cleared. There is no rectangle fallback. The model card describes a selfie/person segmentation model and its published benchmark is not a Galaxy S24 FE result. Its use on full application screenshots, small/distant people, complex collages and multiple simultaneous persons needs physical-device validation. It is not an anatomical human parser and has no swimwear class.

### R6 universal coverage policy

`CoveragePolicy` no longer exempts `EXPLICIT`, `SUGGESTIVE` or `SWIMWEAR`. The final mask for one region must be ≤35% of the active application window, and the composed union mask must be ≤45%. Both are measured using the actual visible pixel mask, not only the detector rectangle. An output over either bound is rejected and protection stops; it is not cropped to an arbitrary smaller box. A scheduled coverage watchdog stops protection if total coverage remains ≥35% for 3 seconds.

The service also arms an independent 5-minute maximum-session timer, 8-second inference deadline and 5-second screenshot callback deadline. These run from the main looper separately from the inference/screenshot executors. The main looper itself must remain responsive for the application-level stop/clear callback to execute; Android process/service teardown is the system fallback for a completely unresponsive process.

### Conservative decision policy

The app detects people and evaluates each person crop with the general NSFW classifiers. It does not use a whole-screen NSFW score to redact the entire application window. If the general NSFW score does not cross the explicit threshold, the person crop is `UNCERTAIN`, not `ALLOW`, because no approved clothing classifier is present. The default **Redact uncertain content** setting covers these boxes. This can mask ordinary people and is a conservative trade-off, not swimwear classification.

Balanced additionally scans up to nine overlapping tiles with the ViT only when EfficientDet returns no people. Those tile detections do not create rectangular overlays: they are intersected with the same full-frame body/clothing mask and must pass the universal 35% / 45% pixel-coverage limits. If people are detected, tile fallback is not run; unrelated media outside those boxes may be missed. Fast has no tile fallback. Thresholds are engineering starting values and are not calibrated or benchmarked for this product.

## Candidate review update (2026-11-03)

A wider review of swimwear-specific models is recorded in [`MODEL_CANDIDATE_REVIEW_2026-11-03.md`](MODEL_CANDIDATE_REVIEW_2026-11-03.md). The decision is unchanged: no new candidate is admitted to the downloadable catalog, and `SWIMWEAR_AWARE` remains disabled.

### Candidates not admitted

- **Electblake Clothing Type EfficientNetV2-S:** this is not merely a PyTorch checkpoint; the repository lists a direct FP32 ONNX artifact and declares MIT for the fine-tuned model (Apache-2.0 for its base). It predicts only `denim_shorts`, `lingerie`, `maid`, `nurse`, or `swim_bikini`. It has no `other`/ordinary-clothing/rejection class, always selects one of its five labels, and the publisher says its softmax scores are uncalibrated. Its 89.73% figure is a validation/model-selection result, not an independent benchmark. The 81.8 MB ONNX artifact has not been pinned, hash-verified, evaluated on ScreenVeil screenshots, or tested on Android. It is a possible isolated research experiment, not an allow/deny model.
- **Electblake RT-DETRv2 R18vd:** this is the most directly relevant newly reviewed clothing-box detector because its labels include `swim` and `swim_sports`. Hugging Face declares Apache-2.0, but the listed 80.5 MB artifact is Safetensors, not ONNX/TFLite. Its model card documents private training data, YOLO-World-generated pseudo-labels, eight epochs, private validation only, no independent benchmark, incomplete labels, and that the intended use is experimental indexing rather than safety-critical decisions. It remains a research/export candidate until no-training export, parity, licensing, Android and representative validation gates pass.
- **Desert Ant Labs Moderator:** do not describe this as requiring a remote inference service; the vendor documents on-device Android/Kotlin inference. Its Source-Available License v1.0 is not an OSI open-source licence, requires visible attribution, and requires a commercial licence above 100,000 monthly active devices per platform/model. The currently reviewed API describes general NSFW scores/regions, not a swimwear class. It is a separate vendor-licensed option, not part of the current catalog.
- **NSFWJS:** its TensorFlow.js model predicts Drawing, Hentai, Neutral, Porn and Sexy, not swimwear. It is a JavaScript integration path rather than a ready-made native Kotlin ONNX/TFLite adapter in this project.
- **NudeNet:** its reviewed 320×320/640×640 ONNX detector exposes anatomical exposed/covered body-part labels, not swimwear garment labels. The exact repository/package/model lineage and licence must be reconciled before reuse; a similarly named fork does not prove the licence of another package's weights.
- **IBM/ETH ScreenParser:** identifies screen-layout elements such as images/video, not adult content; available detector/runtime licensing and Android execution were not admitted.
- **Ultralytics Open Images YOLO exports:** the reviewed Ultralytics implementation/export is under AGPL-3.0. It is excluded from this Apache-2.0 distribution unless a separate licensing decision is made.
- **Genlook product-photo classifier:** not intended for decisions about people.

### Preferred future swimwear detector: Google MobileNet V2 SSD · Open Images V4

This remains the preferred **production-conversion candidate** under the current model source and licensing policy, not an approved runtime model:

- Google's model card describes an SSD + MobileNet V2 detector trained on Open Images V4, 600 boxable categories, Apache-2.0, and a SavedModel artifact of approximately 63.36 MB. The reported overall dataset mAP is not a swimwear-specific metric.
- The Open Images V4 taxonomy includes `Swimwear` alongside `Person` and other object classes. Dataset annotation licensing and image licensing are separate from the model-card licence; the Open Images page says annotations are CC BY 4.0 and asks users to verify individual image licences.
- The model reviewed is a SavedModel, not a pinned Android TFLite artifact. Its exact exported bytes, label map, input normalization, output tensor contract and device behavior are unverified.
- TensorFlow documents an SSD TFLite export path. The exact OIDv4 checkpoint still needs a reproducible conversion with pinned tooling, source-vs-export parity, verified `Swimwear` class mapping, actual TFLite metadata/adapter contract, exact SHA-256 and size bounds, and Galaxy S24 FE testing.

A separate Electblake RT-DETRv2 candidate has more explicit swimwear-oriented labels but weaker provenance of training data and no directly listed mobile artifact. The EfficientNet classifier has direct ONNX, but its closed five-class label set means its score cannot be treated as a safe negative. Neither displaces the Google candidate for the production conversion track under the current policy.

The disabled profile's required-model list remains empty, it is excluded by `ModelProfile.fromStored`, the UI disables selection, and the service refuses initialization. The candidate is not in `SpecializedModelCatalog.all`, so the downloader cannot fetch it. Until an admitted detector passes the documented gates, **ScreenVeil has no approved swimwear-specific model**.

## Security and approval limitations

- SHA-256 protects against unexpected/altered bytes relative to the pinned catalog; it does not prove a publisher's identity or the safety of the architecture/weights.
- Apache/MIT/BSD license declarations are not security audits and do not prove dataset quality or model accuracy.
- No independent model-binary audit, full transitive dependency SBOM, ScreenVeil validation dataset or Galaxy S24 FE benchmark has been completed.
- A model must not be described as `100% safe`, `100% trusted` or `100% accurate`.

## R7 runtime migration (2026-11-04)

The previous Task Vision `0.4.4` native loader was removed after a Galaxy S24 FE API 36 session failed while obtaining `task_vision_jni`. Both EfficientDet and Selfie Multiclass now use the bundled LiteRT `2.3.2` CPU `Interpreter` compatibility API. This keeps inference on-device and avoids adding the MediaPipe Tasks Android SDK and its usage-metrics path. Model URLs, hashes, size windows, licenses, and learned weights are unchanged.

The new code performs one warm-up inference on synthetic data at model initialization, allocates input/output buffers once, and reuses them on the existing single-thread inference executor. A CI gate inspects the built APK's 64-bit native libraries for 16 KB ELF LOAD alignment and verifies shared-library ZIP alignment. That gate is necessary because a runtime dependency version alone is not proof that every prebuilt native library is aligned. The source archive itself has not been compiled or exercised on the physical Galaxy S24 FE in this environment.

# Model governance

## Current approved model

- Display name: Gemma 4 E2B Instruct (LiteRT-LM)
- Publisher repository: `litert-community/gemma-4-E2B-it-litert-lm`
- Immutable commit: `6e5c4f1e395deb959c494953478fa5cec4b8008f`
- File: `gemma-4-E2B-it.litertlm`
- Size: `2,588,147,712` bytes
- SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
- Repository-listed license: Apache-2.0
- Runtime: `com.google.ai.edge.litertlm:litertlm-android:0.18.0`

The upstream file page marks the upload commit as verified and displays the SHA-256 above. The application pins both the immutable commit URL and the SHA-256. TLS transport alone is not considered sufficient; the app independently hashes the entire downloaded file before it can be loaded.

## Why arbitrary model import is not enabled

A SHA-256 supplied by the same package that contains a model proves only that the package is internally consistent. It does not prove who published the model, what data it was trained on, whether its license is accurate, or whether it contains a compatible/hostile artifact.

Because this app's model can inspect sensitive screen content, a model importer must not silently trust arbitrary `.litertlm` files. New models must enter the code-reviewed trusted catalog only after checking:

1. publisher and repository identity;
2. immutable commit/release reference;
3. cryptographic provenance/signature where available;
4. exact artifact SHA-256 and byte size;
5. license for both model weights and associated metadata;
6. supported LiteRT-LM version and Android API;
7. input modality, supported resolution and output contract;
8. measured memory/latency on the target phone;
9. per-category performance on a held-out, lawfully sourced dataset.

The `ScreenContentDetector` interface is the extension point for another runtime adapter. The catalog is the trust allow-list. In this build, Gemma 4 is the only approved entry. A future build may show multiple audited entries in Settings. Arbitrary external model import is intentionally unavailable until an authentic publisher/signature scheme can be established.

## Model behavior is not a security guarantee

Gemma 4 is a general-purpose multimodal model. The prompt requests media-region boxes and moderation labels, but prompt instructions do not constitute model fine-tuning or measured classification guarantees. The app validates JSON syntax and coordinate ranges only; it cannot prove the semantic correctness of the model output.


## Structured response contract

The detector uses LiteRT-LM 0.18.0 constrained JSON generation (`ResponseFormat.json`) and disables speculative decoding for Gemma 4 E2B. See [`GEMMA_STRUCTURED_OUTPUT.md`](GEMMA_STRUCTURED_OUTPUT.md) for the upstream issue and implementation rationale. A valid JSON document only establishes syntactic conformance; it does not establish that an image region was found or that its moderation label is correct.

## Candidate review for the Android build (2026-11-01)

The following candidates are **not installed, not selectable and not approved** by this build. They are reviewed here so that their advertised capabilities are not confused with a verified Android pipeline.

### ScreenParser — media-region locator candidate only

- Source: [docling-project/ScreenParser](https://huggingface.co/docling-project/ScreenParser)
- Model card declares IBM Research / ETH Zurich, YOLO11-L, Apache-2.0, and 55 UI-element classes including `Image` and `Video`.
- Current checkpoint is a 153 MB `best.pt` file. The model card's integration example loads it through the Python Ultralytics API; it does not publish a tested Android/ONNX adapter in the model card.
- This detector labels UI elements. It does **not** classify swimwear, nudity or suggestive content.
- The card's Apache-2.0 declaration and Ultralytics' own licensing page need to be reconciled for the exact checkpoint/runtime combination before redistribution. Ultralytics states that YOLO code and trained models are AGPL-3.0 by default unless the Enterprise license is used for deployments that do not meet the AGPL requirements. This build therefore does not distribute the checkpoint or Ultralytics runtime.

### Electblake Clothing Type EfficientNetV2-S — experimental classifier candidate

- Source: [electblake/clothing_type_classifier](https://huggingface.co/electblake/clothing_type_classifier)
- The model card declares MIT for the classifier and Apache-2.0 for its base model. It publishes an ONNX opset-18 artifact and specifies preprocessing and output order.
- It has five labels: `denim_shorts`, `lingerie`, `maid`, `nurse`, `swim_bikini`. It is a 20,183,893-parameter FP32 classifier with 384×256 image input.
- The card says the training pipeline first detects the largest person and crops to that person's box; person detection is **not** included in the published classifier. A raw media rectangle from a screen is not guaranteed to be a person-centred input.
- It has no labels for swim trunks, one-piece categories as a separate class, rash guards, sports swimwear variants, or swimwear scenes where the person is small in the frame. It cannot replace a media/person detector and cannot certify all-swimwear coverage.
- Its repository head and ONNX artifact need to be pinned to an immutable revision with a separately verified exact byte count and SHA-256 before download is enabled. That audit is not complete in this build.

### NSFWJS MobileNetV2 — adult-content candidate, not swimwear detection

- Source: [infinitered/nsfwjs](https://github.com/infinitered/nsfwjs)
- Its five outputs are `Drawing`, `Hentai`, `Neutral`, `Porn` and `Sexy`. It has no dedicated swimwear output.
- The project is primarily a TensorFlow.js library with browser/Node usage. A React Native example or browser inference does not by itself establish a supported Kotlin/Android native model artifact and runtime configuration for this application.
- No pinned Android-native artifact, preprocessing adapter and reproducible benchmark are included here. It is not selectable.

### NudeNet 320n — explicit body-part detector candidate

- Upstream project: [notAI-tech/NudeNet](https://github.com/notAI-tech/NudeNet)
- It can localize trained body-part classes in an image; such detections may help with explicit-content localization but do not constitute a swimwear classifier or media-region detector.
- The reviewed fork's repository metadata shows AGPL-3.0 while its README text states MIT. This license inconsistency, plus the need to verify the exact official weight publisher/release and its licensing separately, is an exclusion until resolved.
- It is not included in the app and no weights are downloaded.

### Resulting profile policy

The app exposes one operational profile today: **Default — Gemma 4 E2B**. A specialized profile will only be added after every constituent model has a compatible Android inference adapter, exact immutable artifact and checksum, documented preprocessing/output semantics, license/provenance review and Galaxy S24 FE measurements. Until then, displaying those candidates as selectable or trusted would be misleading.

No model training, fine-tuning, quantization, weight editing or custom checkpoint creation is performed by ScreenVeil. All model weights used by this build are existing publisher-provided pretrained artifacts.

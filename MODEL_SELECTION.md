# Model selection and integration status — 0.3.1

## Integrated experimental model

### Viddexa NSFW Detection 2 Nano

- Source: `viddexa/nsfw-detection-2-nano`
- Pinned revision: `12e57200346246b37382f746e4d94d10b014f6a1`
- Model-card metadata: Apache-2.0
- Architecture: fine-tuned EfficientNet-B0 image classifier
- Input: 224×224 RGB, direct resize, NCHW tensor, `image_mean=[0.485,0.456,0.406]`, `image_std=[0.47853944,0.4732864,0.47434163]`, rescale 1/255
- Labels: `safe`, `hentai`, `porn`, `sexy`, `drawing`
- Runtime: ONNX Runtime Android 1.31.1, CPU provider
- Publisher-reported metrics: macro F1 93.00%; per-class F1 values appear in the upstream model card. These values have not been independently reproduced by FilterBridge and must not be treated as target-device accuracy.

#### Model-card limitation

The class descriptions define `sexy` as images without explicit nudity that may show suggestive nudity or risqué clothing. However, the intended-use section states that the model is designed to detect explicit nudity and that suggestive nudity or risqué clothing without explicit nudity may be labelled safe. This contradiction prevents us from approving the `sexy` output as a reliable suggestive detector.

The adapter exposes `sexy` as an experimental signal. It marks NUDITY, SUGGESTIVE, and SEXUALIZED_ILLUSTRATION as partially covered, not fully covered. Other categories are not covered. A model score is not a calibrated probability until we calibrate it on our own representative dataset.

The classifier is global to the whole image; it returns no bounding boxes. BLUR therefore pixelates the entire image preview, not a specific body region. HIDE removes the preview. The user can explicitly reveal the image once. No video frames are classified in this release.

## Evaluated but not integrated

### Viddexa NSFW Detection 2 Mini

EfficientNet-B4 with 17M parameters and a larger model file. Its publisher reports higher F1 than Nano, including on the `sexy` class, but the same intended-use contradiction applies. It remains a device-performance and quality comparison candidate, not the default runtime.

### Freepik NSFW Image Detector

EVA-based classifier with `neutral`, `low`, `medium`, `high` outputs, MIT model-card metadata, and a 173 MB published weight file. It is heavier than Nano and uses synthetic labels according to its model card. Keep as a high-resource comparison candidate; it is not packaged in this release.

### NudeNet

Not integrated. The upstream project and third-party mirrors have conflicting licence/provenance signals, including AGPL-labelled upstream materials and mirrors whose weight provenance is not independently demonstrated. Do not bundle any NudeNet weights until exact model-weight origin, licence and distribution rights are cleared.

### Blue-Eye

Not integrated. Its DINOv3 ViT-L/16 weight is roughly 1.21 GB and carries model-specific licence conditions; it is not appropriate for the current 4–6 GB phone target.

## Other categories — future candidate directions

- Violence: dedicated image/action classifiers, not just object labels.
- Alcohol/drugs/gambling: category-specific object detection plus contextual classification.
- Music: YAMNet as a baseline audio-event classifier, followed by domain-specific validation/fine-tuning.
- Profanity: offline ASR (sherpa-onnx candidate), language identification, OCR, transcript normalization and multilingual text classification.
- Flirting/sexualized interactions: contextual classifier over legitimately available text/transcripts; not keyword matching alone.
- Sexualized illustrations: separate illustration-focused evaluation, not assumed solved by `hentai` alone.
- Custom categories: simple rules, semantic embeddings, or user-labelled classifier adaptation depending on the category.

All candidate models and weights require individual licence/provenance review and target-device benchmarking. `filter-core` remains model-independent; each detector will be a replaceable specialist module.

## Android preprocessing validation

Android Bitmap resize/colour-conversion parity against the upstream PIL image processor has not yet been measured on a physical device; this must be checked before production use.

# ScreenVeil R6 — model candidate research addendum

**Research date:** 2026-11-03  
**Baseline:** ScreenVeil 1.6.0 R6, including the safety and segmentation work described in the R6 implementation report and the user's completed R6 design report.  
**Purpose:** Continue the model research beyond the first clothing-detector comparison. This is not a proposal to redo R6.

## 1. Scope and non-regression boundary

The R6 safety design and implementation are treated as completed baseline work: the universal coverage policy, stop-and-clear recovery, independent deadlines/watchdogs, pixel-level human segmentation, masked rendering, fail-stop behavior when segmentation fails, emergency stop controls, model pinning/governance, and their regression coverage are not reopened by this addendum.

This addendum evaluates additional *content-classification* and *garment-classification* candidates. Those are different tasks:

- **Content classification:** estimate whether an image is neutral, suggestive, or explicit.
- **Garment/object detection:** locate and label an object such as swimwear in an image.
- **Human parsing/segmentation:** identify pixels belonging to body or clothing classes for redaction geometry.

A model that solves one task must not be described as solving the others. In particular, R6's Selfie Multiclass model remains a localization model; an NSFW score does not identify the garment type; and a product-image swimwear classifier is not automatically suitable for screenshots containing people.

No new weights or runtime code are added in this addendum. The existing R6 model catalog, runtime profiles, safety gates and redaction behavior remain unchanged.

## 2. New candidates evaluated

### 2.1 Marqo NSFW Image Detection 384 — already integrated in the R6 catalog

**Sources:** [Marqo model card](https://huggingface.co/Marqo/nsfw-image-detection-384), [ONNX conversion repository](https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx), [conversion notices and validation](https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/blob/main/MODEL_NOTICES.md).

- Task: whole-image classification, not object detection.
- Labels: `NSFW` and `SFW` only. There is no separate suggestive class, no swimwear class, and no output box or pixel mask.
- Architecture/input: `vit_tiny_patch16_384`, 384 × 384 RGB, ONNX opset 17, float32, fixed batch size 1; NCHW input and two logits.
- License: the source model and the ONNX conversion declare Apache-2.0. Preserve source attribution/notices. The R6 catalog already pins the ONNX artifact SHA-256 as `e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2` and the ONNX repository revision as `ab04f01`.
- Publisher evaluation: Marqo reports 98.56% accuracy on its own proprietary 20,000-image test set. The model card says the training set contains 200,000 images across the two classes. This is publisher-reported performance, not an independent ScreenVeil result.
- Conversion evidence: the ONNX repository reports very small source-vs-ONNX numerical differences, but says its check used one synthetic random input. It explicitly does not establish real-image classification accuracy.
- Important preprocessing discrepancy: the R6 implementation currently uses direct 384 × 384 Catmull–Rom resizing, as recorded in its inference adapter. The ONNX manifest/example also directly resizes to 384 × 384, which changes aspect ratio; the original timm configuration specifies a center crop with `crop_pct=1.0`. The ONNX card warns these paths can produce different predictions on non-square images. R6 therefore needs a parity evaluation against the source pipeline, not an assumption that the conversion check establishes identical real-image semantics.

**R6 status and assessment:** This model is **already present in the approved R6 catalog** as `marqo-vit-tiny-nsfw-onnx`, pinned to repository revision `ab04f01` with the SHA-256 above. R6 already uses it as a second general NSFW score and for its Balanced tile fallback. It is not a proposed addition. The immediate task is to validate the model that is already shipped: especially the actual app preprocessing versus the original timm center-crop configuration, representative-screen error rates, and S24 FE runtime cost. Its output remains binary; it cannot separately classify suggestive versus explicit, and it does not identify swimwear. Do not change its profile or threshold based on the publisher's headline accuracy.

### 2.2 Freepik EVA-based NSFW Image Classifier — strongest multi-level experiment

**Sources:** [Freepik model card](https://huggingface.co/Freepik/nsfw_image_detector), [NSFW Sherlock model manifest containing an ONNX artifact record and SHA-256](https://github.com/M1chlCZ/nsfw_sherlock/blob/main/models/manifest.json), [NSFW Sherlock project and model/code license separation](https://github.com/M1chlCZ/nsfw_sherlock).

- Task: whole-image classification.
- Labels: `neutral`, `low`, `medium`, `high`. These are the publisher's severity levels, not a validated mapping to ScreenVeil's exact policy terms. The model card also supports cumulative thresholds such as medium-or-higher.
- Architecture/input: EVA02 base, 448 × 448 image input. The published Hugging Face checkpoint is 86.4M parameters and BF16. The model card reports server-side BF16 timings on an RTX 3090; those timings and VRAM figures are not Android or S24 FE measurements.
- Training/evaluation: the card reports 100,000 synthetically labelled training images and three training epochs on three RTX 3090 GPUs. Its global category-comparison figures are publisher-reported. The card separately says its manually labelled cross-content sample is relatively small.
- License: the model card declares MIT. Do not confuse this with the AGPL-3.0 license of the separate NSFW Sherlock Go service. The model and any copied wrapper/service code have separate licence obligations.
- Android export: the NSFW Sherlock manifest records a `freepik-eva02-448.onnx` artifact, its input/output names, 448 × 448 preprocessing, four labels and a SHA-256. This establishes that a third party has packaged an ONNX graph; it does not establish parity with Freepik's official Transformers pipeline, target-device speed, or suitability of that third-party wrapper for inclusion.

**Assessment:** This is the more relevant *research comparator* when ScreenVeil wants multiple severity levels. It is materially larger than Marqo, and the 448-pixel input plus 86.4M parameters make memory, latency, heat and scan cadence important unknowns on the S24 FE. The existing ONNX Runtime dependency makes a controlled load/inference experiment feasible, but no phone benchmark is available. First verify the exact graph digest, operator compatibility, preprocessing parity and logits against the reference implementation. Do not treat `low/medium/high` as `suggestive/explicit` until a policy mapping is explicitly defined and validated.

### 2.3 Falconsai NSFW Image Detection — binary baseline, not a new capability

**Sources:** [Falconsai model card](https://huggingface.co/Falconsai/nsfw_image_detection), [labels file](https://huggingface.co/Falconsai/nsfw_image_detection/blob/main/labels.json).

- Task/labels: ViT image classification with only `normal` and `nsfw`.
- Training statement: the model card describes an approximately 80,000-image proprietary dataset for those two classes.
- Publisher-reported metric: the card reports evaluation accuracy of 98.0375%. Dataset provenance and the publisher's own evaluation should be treated as such, not as independently reproduced performance.
- License: Apache-2.0 is declared on the model repository.
- Deployment: the repository presents a PyTorch/Transformers path. A separately listed YOLOv9 quantized artifact in the repository should not be confused with this ViT classifier without verifying which weights and architecture are being used.

**Assessment:** Useful as a comparison point in a controlled test, but it adds no class-level distinction over a binary NSFW classifier and does not detect swimwear. The already-integrated Marqo model is the current compact binary ONNX baseline. There is no reason to add Falconsai as a second binary classifier before measuring whether it improves the existing ensemble.

### 2.4 TostAI NSFW Image Detection Large — multi-class, licence blocker

**Sources:** [TostAI model repository](https://huggingface.co/TostAI/nsfw-image-detection-large), [Blue-Eye comparison table](https://huggingface.co/pp1618/Blue-Eye).

- Task/labels: image classifier with `SAFE`, `QUESTIONABLE`, `UNSAFE` classes.
- Architecture: FocalNet-family model; published model repository uses Safetensors, not a ready verified Android ONNX artifact.
- License: the repository declares CC-BY-NC-SA-4.0. The non-commercial and share-alike conditions make it unsuitable as a default distributable model for ScreenVeil unless a separate rights review establishes an acceptable use case and distribution path.
- Benchmark evidence: one published comparison includes TostAI, but it is the Blue-Eye author's own benchmark, not an independent replication.

**Assessment:** Semantically interesting because it has three classes, but not selected. Licensing is the immediate blocker; deployment conversion, parity and device validation are also open.

### 2.5 Blue-Eye — semantic fit, explicit out-of-scope restriction

**Sources:** [Blue-Eye model card and gated repository](https://huggingface.co/pp1618/Blue-Eye), [DINOv3-derived licence terms](https://ai.meta.com/resources/models-and-libraries/dinov3-license/).

- Task/labels: image-level `safe`, `suggestive`, `explicit` classification at 512 × 512.
- Architecture: DINOv3 ViT-L/16, 303M parameters; the repository lists a 1.21 GB Safetensors checkpoint and requires acceptance of access conditions.
- Licence/use restriction: the model card explicitly says the model must not be used to monitor or make decisions about individual people. That makes it unsuitable for ScreenVeil's intended on-device monitoring of the current screen, regardless of its benchmark headline.
- Evaluation: the model card reports 88.9% accuracy on a 3,000-real-photo benchmark that includes skin-heavy non-sexual examples such as swimwear, fitness and medical images. The same card later presents a separate 6,000-image overall table (4,000 photos + 2,000 illustrations), so those evaluation populations must not be conflated. Per-class recall in the 3,000-photo benchmark is reported as 89.5% safe, 75.4% suggestive and 94.8% explicit. Demographic-group performance is stated not to have been evaluated.

**Assessment:** Do not use or integrate it. The explicit prohibition on monitoring/decisions about individual people is dispositive for this application. The model is also too large to be the sensible first mobile experiment. Its author-run benchmark is useful for understanding the challenge of skin-heavy non-sexual images, not proof of suitability for ScreenVeil.

### 2.6 Genlook Try-On Safety Classifier — swimwear labels, wrong task and explicit restriction

**Sources:** [Genlook model card](https://huggingface.co/Genlook/tryon-safety-classifier), [published labelling policy](https://huggingface.co/Genlook/tryon-safety-classifier/blob/main/LABELLING_POLICY.md), [release explanation](https://genlook.app/blog/open-source-tryon-safety-classifier).

- Task: classify *product photos* to predict what a virtual try-on result would show. It does not classify the content of a photograph of a person on a screen.
- Outputs: ordered `ok`, `revealing`, `lingerie`, `adult` levels; subject type; and swimwear tags `not_swimwear`, `regular`, `near_micro`, `micro`.
- Dataset/evaluation: approximately 2,600 garment/accessory products from online shop catalogues; the model card says the photos are product images, not shoppers. It reports five-fold cross-validation over those 2,596 products, 94.6% four-level accuracy, 90.0% swimwear-tag accuracy, and 91/95 true micro-swimwear examples caught. These are results for its product-photo task and cannot be carried over to screenshot photos of people.
- Model artefact: the Hugging Face repository lists an 857 MB Safetensors file and 0.4B parameters. It is not a lightweight ready-to-run Android model.
- Licence/use restriction: Apache-2.0 is declared, but the model card explicitly says not to use it to identify or make decisions about people. This is separate from its source licence and must be respected.

**Assessment:** It is one of the few reviewed models with explicit swimwear granularity, but its task and use restriction exclude it from ScreenVeil. Do not repurpose product-photo labels as person-screen moderation labels.

### 2.7 Google ShieldGemma 2 — image safety model, not a mobile candidate

**Sources:** [Google ShieldGemma documentation](https://ai.google.dev/responsible/docs/safeguards/shieldgemma), [ShieldGemma 2 model card](https://ai.google.dev/gemma/docs/shieldgemma/model_card_2).

Google describes ShieldGemma 2 as a 4-billion-parameter open-weight image safety classifier, based on Gemma 3, which evaluates images against a supplied policy. This is more policy-flexible than a fixed two-class NSFW model, but it is a 4B model rather than a small per-frame detector. There is no verified S24 FE Android runtime, latency, memory or thermal result in the reviewed sources. Gemma terms also need to be reviewed separately; do not infer Apache licensing from Google code examples.

**Assessment:** Keep as a research/reference option for offline policy experiments on workstation-class hardware, not as a candidate for the current ScreenVeil real-time phone pipeline.

## 3. What the broader comparison tells us

| Candidate | Primary job | Labels relevant here | Main blocker | Decision for R6 |
|---|---|---|---|---|
| Marqo 384 ONNX | Image-level NSFW classifier | NSFW / SFW | Binary only; publisher test only; preprocessing parity and device evaluation remain important | **Already in R6; validate current use** |
| Freepik EVA02 448 ONNX | Image-level severity classifier | Neutral / low / medium / high | 86.4M parameters; third-party ONNX parity and S24 FE costs unknown | **Second comparator** |
| Falconsai ViT | Image-level NSFW classifier | Normal / NSFW | Binary; larger PyTorch checkpoint; no garment class | Benchmark baseline only |
| TostAI FocalNet | Image-level severity classifier | Safe / questionable / unsafe | CC-BY-NC-SA-4.0; no verified Android graph | Exclude pending licence resolution |
| Blue-Eye | Image-level sexual-content classifier | Safe / suggestive / explicit | Explicit no-individual-monitoring restriction; 303M parameters | **Do not use** |
| Genlook Try-On Safety | Product-photo / virtual try-on safety | Swimwear regular / near-micro / micro plus severity | Product-photo task; explicit no-decisions-about-people restriction; 857 MB weights | **Do not use for ScreenVeil** |
| ShieldGemma 2 | Policy-conditioned image safety | Prompt/policy-dependent | 4B parameters; no S24 FE mobile proof | Workstation research only |
| Google Open Images SSD | Object detection | Includes Swimwear | TFLite conversion and class-specific app validation still required | Retain as preferred swimwear detector conversion path from prior review |
| Electblake RT-DETRv2 | Clothing object detection | `swim`, `swim_sports`, lingerie labels | Private/pseudo-labelled training evidence; no pinned Android export | Research-only garment detector |
| Electblake EfficientNetV2-S | Whole-crop clothing classification | Five labels including `swim_bikini` | No neutral/other/reject label; forced choice | Research-only comparator |
| R6 Selfie Multiclass | Pixel segmentation/localization | Body skin / clothing etc. | Not a content or garment classifier | Keep for its existing R6 localization role |

## 4. Cross-benchmark caution

The published metrics above are not interchangeable:

1. Marqo's 98.56% is its publisher's proprietary binary test set. The Blue-Eye author's 73.8% is a separate three-class evaluation on that author's 3,000-image benchmark, with different label semantics and thresholding.
2. Freepik's own model card evaluates four severity labels on its own labelled data and reports a relatively small manually-labelled cross-content sample. It also explains that binary competitors were considered correct if they returned NSFW for any of low/medium/high; that cannot establish that those binary models can separate severity levels.
3. Blue-Eye's benchmark is useful because it says it includes 525 skin-heavy but non-sexual images. On that same author-reported 3,000-photo benchmark, the card reports three-class accuracy of 79.2% for TostAI, 73.8% for Marqo, 70.9% for Falconsai and 67.6% for Freepik. These figures are a single model-author evaluation, not an independent replication; demographic-group performance was not evaluated, and the card also shows a separate 6,000-image table. These details need clarification before treating it as a reproducible external leaderboard.
4. None of these model cards establishes performance on ScreenVeil screenshots, screenshot crops, scrolling feeds, video frames, notification panels, diverse Android rendering, or the Galaxy S24 FE.

Use the models' own claims as candidate metadata, not as ScreenVeil acceptance evidence.

## 5. Recommended next evaluation — no runtime change yet

### First evaluation: the Marqo 384 ONNX model already used by R6

Do not add a duplicate artifact. Evaluate the exact R6-pinned model in its existing implementation/profile. The source/export declares Apache-2.0, the graph is ONNX opset 17, and the application already declares ONNX Runtime Android. The evaluation must:

1. Use the exact artifact already pinned by R6 (`ab04f01`, `vit-tiny-nsfw-384.onnx`, SHA-256 `e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2`). Do not replace or duplicate the catalog entry during evaluation.
2. Compare reference and ONNX logits on a fixed set of real, representative images, not only one synthetic tensor.
3. Resolve the non-square preprocessing difference (direct warp vs original center-crop configuration). Record the exact path used and test both before choosing.
4. Evaluate at least these separately labelled groups: ordinary clothing; ordinary swimwear; modest swimwear; fitness/sportswear; medical/breastfeeding contexts; lingerie/underwear; suggestive but clothed images; explicit imagery; drawings/anime; memes/text-heavy images; low-resolution and partial/occluded subjects.
5. Report confusion matrix, per-group false-positive/false-negative rates, precision/recall, PR curve and calibration. Do not choose a threshold from the model card's generic example.
6. Benchmark cold model load, warm inference p50/p95, peak native/RSS memory, CPU utilisation, and sustained thermal/battery cost on the actual Galaxy S24 FE. Start CPU/XNNPACK for the float graph; test NNAPI only as a separate device-specific experiment.
7. Use the result only as another signal inside the existing R6 safety policy. It must not bypass the universal coverage gate, watchdogs, segmentation requirement or stop-and-clear behavior.

### Next distinct comparator: Freepik EVA02 ONNX

After the existing R6 Marqo model has a representative baseline, Freepik is the next distinct severity-aware model to compare in an isolated experiment. Its four outputs may be more useful for separate policy thresholds, but first verify the third-party ONNX artifact and preprocessing against Freepik's model implementation. Do not use the RTX 3090 timing as a mobile estimate. Keep the multi-level classes as source labels until a separate ScreenVeil policy mapping is justified by the evaluation set.

### Swimwear-specific requirement

No reviewed candidate simultaneously satisfies all of these conditions: (a) actual swimwear recognition on photographs/screenshots of people, (b) permissive and appropriate use rights for this application, (c) a reproducible pinned Android model artifact, (d) independent class-specific performance evidence, and (e) demonstrated S24 FE performance.

The current best path for a *garment box* remains the previously reviewed Open Images SSD conversion experiment, with RT-DETRv2 as a research comparator. Neither should be described as validated. Genlook has swimwear-specific product taxonomy but is not an appropriate substitute for person-screen classification and its model card explicitly restricts decisions about people.

## 6. Decision

- Keep the completed R6 safety/segmentation implementation intact.
- Do not add Blue-Eye, Genlook or TostAI to ScreenVeil.
- Do not add ShieldGemma 2 to the phone pipeline.
- First evaluate the exact Marqo ONNX artifact already used by R6; do not add a duplicate copy or call it a new model. If a new model experiment is approved after that baseline, use Freepik as the severity-aware comparator in isolation.
- Do not enable a new model for redaction until its data, preprocessing, licence, artifact hash, on-device resource cost and ScreenVeil-specific error profile are all documented and reviewed.

## 7. Source register

1. [Marqo NSFW Image Detection 384 model card](https://huggingface.co/Marqo/nsfw-image-detection-384)
2. [Marqo-derived ONNX model card, tensor contract and preprocessing note](https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx)
3. [Marqo-derived ONNX model notices, SHA-256 and Apache-2.0 attribution](https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/blob/main/MODEL_NOTICES.md)
4. [Freepik EVA-based NSFW classifier model card](https://huggingface.co/Freepik/nsfw_image_detector)
5. [NSFW Sherlock pinned model manifest: Freepik ONNX file and digest](https://github.com/M1chlCZ/nsfw_sherlock/blob/main/models/manifest.json)
6. [NSFW Sherlock source and separate AGPL-3.0 code / model licences](https://github.com/M1chlCZ/nsfw_sherlock)
7. [Falconsai NSFW image detector model card](https://huggingface.co/Falconsai/nsfw_image_detection)
8. [Falconsai binary labels](https://huggingface.co/Falconsai/nsfw_image_detection/blob/main/labels.json)
9. [TostAI NSFW Image Detection Large](https://huggingface.co/TostAI/nsfw-image-detection-large)
10. [Blue-Eye model card, use restriction, model parameters and author benchmark](https://huggingface.co/pp1618/Blue-Eye)
11. [Meta DINOv3 licence](https://ai.meta.com/resources/models-and-libraries/dinov3-license/)
12. [Genlook Try-On Safety Classifier](https://huggingface.co/Genlook/tryon-safety-classifier)
13. [Genlook published product labelling policy](https://huggingface.co/Genlook/tryon-safety-classifier/blob/main/LABELLING_POLICY.md)
14. [Genlook release explanation](https://genlook.app/blog/open-source-tryon-safety-classifier)
15. [Google ShieldGemma documentation](https://ai.google.dev/responsible/docs/safeguards/shieldgemma)
16. [ShieldGemma 2 model card](https://ai.google.dev/gemma/docs/shieldgemma/model_card_2)
17. [ONNX Runtime mobile deployment guide](https://onnxruntime.ai/docs/tutorials/mobile/)
18. [ONNX Runtime NNAPI execution provider](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)

---

**Evidence status:** This addendum records public model cards, repositories, model manifests and deployment documentation reviewed on 2026-11-03. It did not download or execute candidate weights, reproduce any accuracy claim, or benchmark on Android. All reported metrics remain attributed to the publishing model/research author unless separately validated by ScreenVeil.

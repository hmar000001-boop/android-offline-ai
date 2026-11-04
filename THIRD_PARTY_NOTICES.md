# Third-party notices

ScreenVeil application source is licensed under Apache License 2.0; see `LICENSE`.

This file records direct runtime dependencies and downloaded model artifacts. It is not a complete Software Bill of Materials; transitive dependencies must be resolved and audited from the Gradle dependency graph before redistribution. License identifiers below reflect upstream declarations and do not constitute legal advice.

## Downloaded pretrained model artifacts

| Artifact | Pinned source in catalog | Upstream license declaration | Use / caveat |
|---|---|---|---|
| EfficientDet-Lite0 uint8 TFLite deployment | `litert-community/efficientdet`, commit `971d935f3679eabbcce7b4d3733f351d403ff2b9`; exact SHA-256 in `SpecializedModelCatalog.personDetector` | Apache-2.0 as declared by the upstream model repository | General COCO object detection; TFLite graph includes DetectionPostProcess; not a clothing or NSFW classifier |
| Yahoo OpenNSFW ResNet-50 ONNX | `gawryco/opennsfw-onnx` release `model-v1`; exact SHA-256 in catalog | Original Yahoo weights: BSD-2-Clause. ONNX conversion package and preprocessing lineage have separate notices; see the pinned project source. | General binary NSFW score; not swimsuit-specific |
| ViT-Tiny NSFW 384 ONNX | `KanariKanaru/nsfw-image-detection-384-onnx`, revision `ab04f01`; exact SHA-256 in catalog | Apache-2.0 as declared by the ONNX repository | General binary NSFW score; publisher-reported evaluation is not an independent benchmark |
| Google Selfie Multiclass 256×256 TFLite | Google MediaPipe image segmenter, versioned `float32/1` artifact; exact SHA-256 in catalog | Apache-2.0 as stated on the [Google MediaPipe Multiclass Segmentation model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Multiclass%20Segmentation.pdf) | Pixel labels for background, hair, body skin, face skin, clothes and accessories; used for body/clothing localization only, not moderation or swimwear classification |
| Gemma 4 E2B Instruct LiteRT-LM | `litert-community/Gemma-4-E2B-it-litert-lm`, commit `6e5c4f1e395deb959c494953478fa5cec4b8008f`; exact SHA-256 in catalog | Apache-2.0 for Gemma 4; community packaging has separate provenance | Optional standalone multimodal profile; not needed for specialist profiles |

All URLs, revisions, hashes and size bounds are defined in `app/src/main/java/dev/screenveil/app/model/GemmaModelCatalog.kt`. The app verifies each complete artifact before loading it. Exact hashes establish byte identity, not safety or accuracy.

## Not yet selectable: preferred swimwear candidate

- **Google MobileNet V2 SSD · Open Images V4:** Apache-2.0 is declared for the published model and the 600-class Open Images V4 detection label list includes `Swimwear`. The reviewed checkpoint is a SavedModel, not a validated TFLite Android artifact. It is tracked for conversion/equivalence testing in `docs/MODEL_GOVERNANCE.md`, but is not downloaded or selectable in this release.

## Explicitly excluded model candidates

These candidates are not in the selectable model catalog:

- **Electblake Clothing Type EfficientNetV2-S:** five classes and no generic `other` class; the evidence and coverage are too limited for the product's swimwear requirement. Removed from selectable profiles and old app copies are deleted on upgrade.
- **Electblake RT-DETRv2:** Safetensors/Transformers checkpoint rather than a validated Android ONNX/TFLite artifact; publisher describes private/pseudo-labelled data and generalization limitations. No exported artifact is admitted.
- **Desert Ant Labs Moderator:** source-available terms require recurring online license validation, incompatible with the selected offline inference/privacy policy.
- **NSFWJS:** no approved, pinned Android model artifact and adapter in this app.
- **NudeNet:** body-part detection is not clothing classification; model provenance/license and Android artifact chain are not sufficiently resolved.
- **IBM/ETH ScreenParser:** detects screen-layout elements rather than adult content; its model/runtime licensing combination is not approved here.
- **Ultralytics Open Images YOLO exports:** include a `Swimwear` class in the associated Open Images label set, but the available Ultralytics implementation/configuration is AGPL-3.0. It is excluded from this Apache-2.0 app distribution pending a separate licensing decision.
- **Genlook product-photo classifier:** not intended for moderation decisions about people.

A future Google/TensorFlow Open Images detector may be evaluated as a swimwear candidate, but its exact class map, artifact export, license, runtime compatibility, hash and device performance must be verified before it enters the catalog. No current model is approved as a validated swimwear classifier.

## Runtime and build components

| Component | Version | License / upstream |
|---|---:|---|
| LiteRT-LM Android runtime | `0.18.0` | Apache-2.0; [Google AI Edge LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) |
| ONNX Runtime Android | `1.31.1` | MIT; [ONNX Runtime](https://github.com/microsoft/onnxruntime) |
| Google AI Edge LiteRT Android | `2.3.2` | Apache-2.0; [LiteRT](https://github.com/google-ai-edge/LiteRT) |
| AndroidX Core, Activity, Lifecycle and Compose | Versions pinned in `app/build.gradle.kts` / Compose BOM | Apache-2.0; [AndroidX](https://github.com/androidx/androidx) |
| Kotlin / Kotlin Compose compiler | `2.4.21` | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin) |
| Kotlin Coroutines Android | `1.10.2` | Apache-2.0; [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| JUnit | `4.13.2` (test only) | Eclipse Public License 1.0; [JUnit 4](https://github.com/junit-team/junit4) |
| JSON-java | `20250517` (test only) | JSON License; [JSON-java](https://github.com/stleary/JSON-java) |

Before redistribution, generate and review a complete SBOM from the resolved Gradle dependency graph and include required license texts for all transitive runtime dependencies.

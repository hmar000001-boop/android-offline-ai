# Third-party notices

ScreenVeil application source is licensed under Apache License 2.0; see `LICENSE`.

The following artifacts and libraries are included as dependencies or downloaded by the app. Model license declarations must be rechecked before redistribution. This is an attribution index, not a complete Software Bill of Materials; the resolved Gradle graph may include additional transitive components.

## Downloaded pretrained model artifacts

| Component | Pinned source | License declared by upstream | Use |
|---|---|---|---|
| Google EfficientDet-Lite0 float32 | MediaPipe model URL in `SpecializedModelCatalog.personDetector` | Apache-2.0 | Person detection |
| Electblake Clothing Type EfficientNetV2-S ONNX | HF revision `31d25e7` | MIT classifier; Apache-2.0 base model | Five-class clothing classification (`swim_bikini`, `lingerie`, etc.) |
| Yahoo OpenNSFW ResNet-50 ONNX | GitHub release `model-v1` from `gawryco/opennsfw-onnx` | Yahoo weights BSD-2-Clause; ONNX packaging/conversion project Apache-2.0; preprocessing lineage includes MIT code | Pornographic-content score |
| Marqo-derived ViT-Tiny NSFW ONNX | HF revision `ab04f01` from `KanariKanaru/nsfw-image-detection-384-onnx` | Apache-2.0 as declared by repository | Independent NSFW score |
| Gemma 4 E2B Instruct LiteRT-LM | HF commit `6e5c4f1e395deb959c494953478fa5cec4b8008f` | Apache-2.0 as listed by model repository | Optional separate multimodal profile; not used by specialist profiles |

Every model URL, byte-size floor and SHA-256 digest is pinned in `app/src/main/java/dev/screenveil/app/model/GemmaModelCatalog.kt`. The application verifies the complete downloaded artifact before loading it.

## Runtime and build components

| Component | Version | License / upstream |
|---|---:|---|
| LiteRT-LM Android runtime | `0.18.0` | Apache-2.0; [Google AI Edge LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) |
| ONNX Runtime Android | `1.31.1` | MIT; [ONNX Runtime](https://github.com/microsoft/onnxruntime) |
| MediaPipe Tasks Vision | `0.10.35` | Apache-2.0; [MediaPipe](https://github.com/google-ai-edge/mediapipe) |
| AndroidX Core, Activity, Lifecycle and Compose | versions pinned in `app/build.gradle.kts` / Compose BOM | Apache-2.0; [AndroidX](https://github.com/androidx/androidx) |
| Kotlin / Kotlin Compose compiler | `2.4.21` | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin) |
| Kotlin Coroutines Android | `1.10.2` | Apache-2.0; [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| JUnit | `4.13.2` (test only) | Eclipse Public License 1.0; [JUnit 4](https://github.com/junit-team/junit4) |
| JSON-java | `20250517` (test only) | JSON License; [JSON-java](https://github.com/stleary/JSON-java) |

Before redistribution, generate and review a complete Software Bill of Materials from the resolved Gradle dependency graph and include required license texts for all transitive runtime dependencies.

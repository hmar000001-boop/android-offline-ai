# Third-party notices

ScreenVeil application source is licensed under Apache License 2.0; see `LICENSE`.

The following upstream components are used by the build. Their transitive dependencies may add further notices; consult the resolved dependency metadata when producing a release SBOM.

| Component | Version | License / upstream |
|---|---:|---|
| Gemma 4 E2B Instruct LiteRT-LM model artifact | pinned commit `6e5c4f1e395deb959c494953478fa5cec4b8008f` | Apache-2.0 as listed by the model repository; [model repository](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| LiteRT-LM Android runtime | `0.18.0` | Apache-2.0; [Google AI Edge LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) |
| AndroidX Core, Activity, Lifecycle and Compose | versions pinned in `app/build.gradle.kts` / Compose BOM | Apache-2.0; [AndroidX](https://github.com/androidx/androidx) |
| Kotlin / Kotlin Compose compiler | `2.4.21` | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin) |
| Kotlin Coroutines Android | `1.10.2` | Apache-2.0; [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| JUnit | `4.13.2` (test only) | Eclipse Public License 1.0; [JUnit 4](https://github.com/junit-team/junit4) |
| JSON-java | `20250517` (test only) | JSON License; [JSON-java](https://github.com/stleary/JSON-java) |

Before redistribution, generate and review a complete Software Bill of Materials from the resolved Gradle dependency graph and include the required license texts for all transitive runtime dependencies. The table above is an attribution index, not a substitute for a release-time SBOM or legal review.

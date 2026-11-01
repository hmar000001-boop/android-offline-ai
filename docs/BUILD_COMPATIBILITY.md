# Android build compatibility baseline

ScreenVeil deliberately targets Android API 36 and uses AGP 8.13.2. Keep the AndroidX dependency graph within that toolchain until the project is intentionally migrated to an AGP 9.x / API 37 toolchain.

Pinned versions:

| Component | Version | Reason |
|---|---:|---|
| Android Gradle Plugin | 8.13.2 | API 36 toolchain; avoids the AGP 9 built-in Kotlin DSL migration |
| Kotlin Gradle Plugin | 2.4.21 | Compatible with AGP 8.13.2 |
| Kotlin Compose compiler plugin | 2.4.21 | Must match Kotlin plugin |
| Gradle | 8.14.6 | Compatible with KGP 2.4.21; avoids the older-Gradle warning |
| Compile / target SDK | 36 / 36 | The chosen AGP 8.13 line recommends compile SDK 36 |
| Compose BOM | 2025.12.00 | Compose 1.10 generation; does not require API 37 / AGP 9 |
| AndroidX Core | 1.17.0 | Core 1.18 raised its compile SDK requirement to API 36.1; Core 1.19 requires newer tooling |
| Activity Compose | 1.12.4 | Stable Activity release before the later API/tooling line |
| Lifecycle | 2.9.4 | Stable Lifecycle release on the API-36-compatible line |

The Compose BOM is imported with `enforcedPlatform`, and Core/Core-KTX have strict dependency constraints. This makes dependency resolution fail rather than silently selecting a newer Compose or Core artifact that requires API 37.

Do not update Compose BOM to 2026.09.00 while this toolchain remains unchanged: it resolves Compose 1.12.1, whose AAR metadata requires compile SDK 37 and AGP 9.1+. Do not update AndroidX Core to 1.19.1: its AAR metadata also requires the newer Android toolchain.

When intentionally moving to API 37, first upgrade AGP and migrate Kotlin Android plugin integration according to the official AGP built-in Kotlin migration. Then update Compose BOM and Core together, update SDK installation in CI, and validate the LiteRT-LM integration. Do not solve the AAR metadata failure by suppressing the metadata check.

## Kotlin source/API compatibility fixes (2026-11-01)

The CI run against the API-36 dependency set reached `:app:compileDebugKotlin` and exposed three source errors. They are fixed in the current source:

- `MainActivity.kt`: `RowScope.weight` is a scope member extension and is available inside `Row` content; it must not be imported as `androidx.compose.foundation.layout.weight`.
- `MainActivity.kt`: the final spacer uses `Modifier.height`, so the file imports `androidx.compose.foundation.layout.height`.
- `ScreenModerationDetector.kt`: LiteRT-LM 0.18.0's `com.google.ai.edge.litertlm.Message` has no `text` property. Its `toString()` delegates to `contents.toString()`, and `Content.Text.toString()` returns the contained text. The detector therefore passes `response.toString()` to the JSON parser.

The LiteRT-LM API behavior is checked against the upstream `v0.18.0` source, not inferred from a later release: <https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Message.kt>.

The corresponding Compose `weight` API is scoped to `RowScope`/`ColumnScope`; the calls in this UI are inside the appropriate `Row` content lambdas.

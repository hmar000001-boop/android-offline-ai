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

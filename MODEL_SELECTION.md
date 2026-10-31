# Model integration decision — FilterBridge Lab 0.2.2

## Current state

No image, video, audio, speech, OCR, or text-classification weights are bundled. The application deliberately reports `NOT_ANALYZED` / `UNVERIFIED` until a real local model adapter is installed. This is safer than presenting an untested generic classifier as a complete safety system.

## Candidate: Desert Ant Labs Moderator (nudity only)

The vendor currently documents an Android SDK (`ai.desertant:moderator:4.0.0` in its SDK guide; its model repository page has since been updated to 4.2.1), an approximately 9.2 MB int8 TFLite model, and five visual outputs related to exposed nipples, genitals, buttocks, nudity, and sexual activity. It supports image and video-frame scoring, not the rest of FilterBridge's category list. The vendor describes its reported recall/specificity as an internal evaluation on a proprietary held-out set, not a published independent benchmark.

**Privacy/licensing issue:** the vendor's Source-Available License v1.1 (effective 2026-10-30) requires a background license activation. It sends a random install ID, app identifier, model/version, SDK/platform details, and possibly an API key; the license check has no opt-out. It says inputs, outputs, and user content are not sent. Below 100,000 monthly licenses per platform/model the stated tier is free; above that a commercial license is required. Air-gapped use requires contacting the vendor for a signed offline license. It also requires discoverable attribution.

This may be technically suitable as an optional nudity adapter, but it is **not included** because its mandatory licensing metadata check conflicts with a strict no-metadata/offline-only deployment unless the vendor provides an acceptable offline license. It must not become a hidden default. Re-evaluate the exact pinned SDK version and license before adopting it.

## Candidate: GantMan nsfw_model / OpenNSFW2

GantMan's model page describes five image labels (`drawings`, `hentai`, `neutral`, `porn`, `sexy`) and publishes the inference implementation and model artifacts. A current repository issue asks whether the released weights themselves are covered by the repository's MIT license. The implementation license therefore must not be assumed to settle the model-weight license. OpenNSFW2 is documented as a Keras/Python implementation focused on pornographic imagery; it is not a ready-to-use Android Java runtime package. These are not bundled.

## Recommended runtime architecture

1. Keep `ContentAnalyzer` and the normalized `Detection` contract in `filter-core`.
2. Add a separate Android runtime adapter for each specialist model. Keep all vendor classes out of `filter-core`.
3. Record the model ID, version, license, taxonomy, calibration dataset, and preprocessing contract in a model manifest.
4. A detector must return category, calibrated score or explicitly uncalibrated rank, evidence type, optional normalized region, and optional video time range. Do not treat raw model scores as probabilities without calibration evidence.
5. A failed/unavailable model must return `FAILED` or `NOT_ANALYZED`; the policy layer maps incomplete analysis to `UNVERIFIED`.
6. Before enabling a detector for users, run a representative evaluation set and publish per-category precision/recall, confusion matrix, latency, memory, and thermal measurements.

## Why the first runtime is not wired in this release

Google ML Kit supports custom image-labeling models bundled inside the APK, allowing inference without downloading the model at runtime. However, the developer must supply a compatible trained model. ML Kit's generic labeler is not itself a model for nudity, suggestive content, alcohol, violence, flirting, or music. The project therefore keeps a model-neutral boundary until a legally distributable model with suitable labels and evaluation data is selected.

## Scope that still requires specialist models

| Category | Required perception capability |
|---|---|
| Nudity / exposed body regions | Image/frame classifier or detector with spatial boxes |
| Suggestive content / poses | Context-aware image classifier; requires explicit label policy and evaluation |
| Sexualized illustrations | Dedicated image/illustration classifier |
| Violence / disturbing imagery | Image/frame classifier and potentially temporal video model |
| Alcohol / drugs / gambling | Object/logo/context detection; separate category heads may be needed |
| Music | Audio event / music classifier; not merely speech recognition |
| Profanity | Speech recognition or text extraction plus multilingual language classifier |
| Flirting / sexualized interactions | Text + dialogue/context classifier; high ambiguity and language dependence |
| User-defined categories | Requires a compatible classifier, user-authored rule, or a future model-training path; adding an ID alone does not create detection capability |

The perception system must make observations only. User-configured deterministic policy decides whether to warn, blur, or hide.


## Release gate — why the model integration remains intentionally disabled

The current application build has a real media picker, local preview/decode path, filtering preferences, and a fail-closed policy engine. It does **not** yet have a content-classification engine. We are not bundling an arbitrary NSFW or object-detection model merely to make the Analyze button appear functional.

A single nudity model would not cover music, profanity, violence, substances, flirting, sexualized illustrations, or custom user categories. It also would not automatically provide spatial regions or calibrated scores. The policy engine now requires explicit category coverage, so a single-category result cannot be presented as a fully verified result.

Before integration, each candidate must pass all of these gates:

- **License:** explicit permission for commercial distribution of both runtime and model weights; no assumptions that the source-code license also covers weights or training data.
- **Offline operation:** no required license activation, telemetry, remote inference, or unannounced model download.
- **Taxonomy fit:** documented outputs for the intended category, with a clear distinction between whole-image classification and spatial object detection.
- **Evaluation:** an independently reviewable test set relevant to our intended content, with per-category precision/recall and false-negative analysis.
- **Mobile engineering:** an Android-supported runtime, quantized model, memory and latency benchmarks, and thermal/battery measurements on the target phone range.
- **Calibration:** scores must not be treated as probabilities unless calibration has been measured.

Current leads such as Desert Ant Labs Moderator and public NSFW image classifiers are limited to particular visual categories and have license, offline-use, evaluation, runtime, or region-localization questions. They are candidates for controlled evaluation, not approved production dependencies. A model for one category can be integrated as one specialist adapter, but the user interface must continue to show incomplete coverage for every other enabled category.

## Implementation sequence

1. Select and validate one offline visual specialist model for explicit nudity.
2. Add a dedicated `android-runtime-*` module; keep runtime-specific code out of `filter-core`.
3. Return observations with model/version metadata, category coverage, calibrated score, evidence type, and an optional normalized bounding box.
4. Add deterministic image-region blur and whole-item hide rendering, driven only by `PolicyDecision`.
5. Build an evaluation harness and report model quality separately from policy-engine correctness.
6. Add separate detectors for suggestive content, sexualized illustrations, violence, substances/gambling, speech/profanity, and music.
7. Only then begin timeline-aware, sampled video inference and real-time performance work.

Until those gates are met, `NOT_ANALYZED` / `UNVERIFIED` is the correct result.

# ScreenVeil 1.6.0 R6 — research follow-up package report

**Date:** 2026-11-03  
**Baseline:** `ScreenVeil-1.6.0-R6-safety-body-segmentation.zip`  
**Package:** `ScreenVeil-1.6.0-R6-research-follow-up.zip`

## What changed

This is a continuation package based directly on the previous R6 source ZIP. It does not restart the app or alter runtime behavior. The follow-up adds `docs/MODEL_CANDIDATE_REVIEW_2026-11-03.md`, expands `docs/MODEL_GOVERNANCE.md`, updates `docs/OPEN_IMAGES_SWIMWEAR_PROFILE.md`, and adds a short model-research decision note to `README.md`.

The wider review compared the existing Google Open Images V4 SSD route with Electblake RT-DETRv2 and EfficientNetV2-S, and also recorded NudeNet, NSFWJS, Desert Ant Labs Moderator, Google Selfie Multiclass, and Android ONNX Runtime deployment considerations. The package corrects the old statement that Desert Ant's model requires recurring online licence validation: the published vendor documents on-device inference, but its source-available license includes attribution and scale-dependent commercial terms. It also records the licence discrepancy between the reviewed NudeNet PyPI page and a separately reviewed GitHub fork instead of treating them as interchangeable.

## Decision

No additional model is approved for runtime safety decisions. The active R6 model catalog remains unchanged. Google Open Images V4 SSD stays the preferred production-conversion candidate under the current source/licensing policy, with its `SWIMWEAR_AWARE` profile disabled until a reproducible TFLite conversion, parity checks, class-specific evaluation, artifact hash, and Galaxy S24 FE validation pass. RT-DETRv2 and EfficientNetV2-S are documented as research-only candidates.

No new model weights or proprietary binaries were added to the ZIP. Existing R6 model downloads remain explicit user-initiated downloads from the fixed catalog.

## Validation performed for this follow-up

This is a documentation-only change to the available R6 source snapshot. The following checks were executed in the packaging environment and passed:

- `python3 scripts/verify-model-pins.py`: all five catalog entries passed static metadata checks.
- `python3 -m py_compile scripts/verify-model-pins.py`.
- `bash -n` for all four shell scripts in `scripts/`.
- XML parsing: all seven Android XML files.
- GitHub Actions YAML parsing: the workflow file parsed successfully.
- Runtime-source comparison: `app/src`, app/root Gradle files, wrapper files and Gradle configuration match the source baseline byte-for-byte; no runtime or dependency code changed.
- ZIP integrity and SHA-256 verification after packaging.

These static checks do **not** establish Gradle compilation, unit-test success, lint success, model-download integrity, APK packaging, emulator success, model accuracy, or Galaxy S24 FE behavior. Do not infer those results from the static checks. Follow the existing CI and device-validation procedure for those separate gates.

## Important scope boundary

The package is a source ZIP, not an APK. It does not certify adult-content detection, swimwear recognition, suggestive-content accuracy, or continuous video-frame coverage. R6 remains experimental.


## Addendum 2 — broader visual-model research (2026-11-03)

The previous follow-up's work is preserved. A second research addendum was added at `docs/MODEL_CANDIDATE_RESEARCH_ADDENDUM_2026-11-03.md`. It evaluates the Marqo 384 ONNX classifier already pinned in the R6 catalog, plus additional whole-image NSFW classifiers (Freepik EVA02, Falconsai, TostAI), the Blue-Eye three-class model, Genlook's product-photo swimwear classifier, and Google's ShieldGemma 2. It separates image-level content classification from garment detection and pixel segmentation, documents licence/use restrictions and ONNX deployment evidence, and proposes evaluation of the existing Marqo implementation first, followed by an isolated Freepik comparison.

This addendum treats the user's ten-part R6 safety and segmentation report as completed baseline work. It changes documentation only: no runtime code, model catalog, runtime profile, safety rule, dependency or model weight was changed. The active R6 catalog and existing research decisions are preserved. Candidate weights were not downloaded or executed, and no S24 FE performance or accuracy claim is made.


## Source snapshot and status clarification

The user confirms that every point in the supplied R6 safety-hardening/body-segmentation report is completed. None of those points is treated as an outstanding implementation task by this addendum. The original candidate review is retained as historical model research, not a backlog.

The source tree packaged here is based on the exact R6 source ZIP available in this conversation (`ScreenVeil-1.6.0-R6-safety-body-segmentation.zip`). No newer source archive or local working-tree changes were provided in this turn. Therefore, this ZIP faithfully preserves that available source snapshot and adds the research documentation; it cannot include any subsequent source changes that were not present in the supplied archive. Runtime source, model catalog, dependencies and profiles were not modified.


## Build correction included in the follow-up source package

The CI Kotlin compilation errors received after this research pass have also been corrected in the source tree. This correction is separate from the model research and does not alter any model or inference configuration. See `docs/CI_COMPILATION_FIX_2026-11-03.md`. The Android Gradle build must still be rerun in GitHub Actions.

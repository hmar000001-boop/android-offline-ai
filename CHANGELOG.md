# Changelog

## 0.3.1-local-image-classifier

- Pinned the Viddexa model to the immutable revision containing its current model card and matching `safe` label mapping.
- Corrected ONNX tensor input to use a direct native-order NIO buffer.
- Added ONNX input/output tensor type and shape checks to the CI export validation.
- Made incomplete image analysis pixelate the preview by default, with explicit one-time reveal.
- Kept unanalysed video playback hidden until explicit one-time reveal; the user's filtering-Off setting is honoured.
- Ensured unexpected UI/orchestration errors also apply the UNVERIFIED fail-closed rendering.

## 0.3.0-local-image-classifier

- Added a dedicated `ai-runtime-onnx` Android library module using ONNX Runtime Android 1.31.1.
- Added CI-only model export for a pinned Viddexa NSFW Detection 2 Nano revision.
- CI downloads the model at an immutable revision, exports a static 224x224 NCHW ONNX graph, validates the graph, and checks PyTorch/ONNX Runtime output parity on deterministic sample inputs before Android packaging.
- Android loads the packaged model from assets, installs it into app-private storage, verifies its SHA-256 checksum, and runs local CPU inference.
- Added source-model and runtime-model provenance to model detections.
- Added partial category coverage to the analysis contract. A positive detection no longer implies that a category was exhaustively analysed.
- Added experimental `porn`, `hentai`, and `sexy` signal mapping. The `sexy` output is not treated as validated suggestive-content coverage due to model-card limitations.
- Added whole-image pixelation / hide preview actions and one-time explicit reveal. Incomplete category coverage is pixelated under the balanced default. This model does not provide region localization.
- Video classification remains explicitly not implemented and returns unverified; playback is hidden until an explicit one-time reveal (unless the user has disabled filtering).
- Added mapper tests and Android runtime lint to CI.

## 0.2.2-direct-media-picker

- Direct in-app Android Photo Picker flow for images and videos.
- Local preview and media metadata, bounded decoding, settings and coverage-aware core.
- No AI model was connected in that version.

- Protect newly selected images with a pixelated preview until analysis or an explicit one-time reveal when filtering is enabled.

## 0.3.2 — dual local image classifiers
- Added OwenElliott Image Safety Classifier XS as a second, independently pinned ONNX image model.
- Added broad `adult_visual_content` category. Owen's NSFW output is not misrepresented as separate nudity/suggestive classes.
- Added experimental NSFL visual-distress signal, explicitly marked as underrepresented by the publisher.
- Added a composite analyzer: one model's SFW/normal class can never cancel another model's positive detection.
- Changed Viddexa `safe` UI wording to `normal (upstream label safe)` and added explicit "not a safety verdict" language.
- GitHub CI now downloads, checksum-verifies, ONNX-validates and smoke-runs both model assets before packaging them in the APK.
- Other visual, audio, speech and video categories remain unimplemented or experimental; no full-coverage claim is made.

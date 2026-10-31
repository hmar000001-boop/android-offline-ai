# FilterBridge architecture — 0.3.1

## Boundaries

- `filter-core` contains platform-neutral contracts and deterministic policy only.
- `android-ingest` adapts Android Photo Picker/content URIs and local media preview.
- `ai-runtime-onnx` owns ONNX Runtime, image decoding/preprocessing, model asset verification and the Viddexa Nano adapter.
- `demo-app` composes the selected runtime with the policy engine and renders actions.
- `tools/model-export` is build-time tooling; it is not shipped as app code.

## Model input contract

- One image per request.
- Decode from the selected `MediaSource` stream; no network access.
- Apply EXIF orientation where available.
- Reject invalid or more-than-100-megapixel images; decode downsampled to a maximum edge of 2048 before final model resize.
- Resize to 224×224 using nearest-neighbour to match the pinned `resample=0` processor setting.
- Convert RGB, rescale by 1/255, normalize with the mean/std values in the generated manifest.
- ONNX input: `pixel_values`, float32 `[1,3,224,224]`; output: `logits`, float32 `[1,5]`.

## Evidence and coverage

- `porn` -> NUDITY signal.
- `hentai` -> SEXUALIZED_ILLUSTRATION signal.
- `sexy` -> experimental SUGGESTIVE signal.
- Each Detection records model ID, immutable revision, and raw model label.
- Analysis marks those categories as partially covered, not fully covered. No score alone certifies full category coverage.
- A detection may trigger user policy, but the detection does not cause a category to be counted as completely analysed.
- Other enabled categories remain unverified.

## Rendering

Viddexa Nano is a whole-image classifier. It produces no boxes, masks or temporal segments. The demo therefore pixelates the whole image when policy says BLUR or when the balanced default receives an incomplete/UNVERIFIED result, hides it when policy says HIDE, and permits an explicit one-time reveal. Video playback stays hidden until an explicit one-time reveal because video inference is not implemented. A future region detector/segmenter can add spatial evidence without changing model-independent policy contracts.

## Runtime lifecycle

- CI-generated ONNX and `.properties` manifest are packed into app assets.
- On first launch/session, the runtime copies the model to app-private storage through a temporary file, verifies SHA-256, then atomically renames it.
- Runtime creates one ONNX session per analyzer instance, serializes access to the session and closes it when the activity's service is destroyed.
- `OrtEnvironment` is a process singleton and is not closed by the analyzer.
- Missing assets, checksum mismatch, invalid tensor shapes, non-finite logits and runtime errors are FAILED/UNVERIFIED.

## Offline assurance

The demo app manifest has no `INTERNET` permission. The GitHub build machine fetches the pinned model; the installed phone uses the packaged ONNX file only. The export step validates the source model and ONNX graph before APK assembly.

# FilterBridge Lab 0.3.1 — real local image-classifier integration

FilterBridge Lab is an Android engineering prototype for a privacy-first, on-device media-filtering SDK. This release connects a real still-image classifier through ONNX Runtime Android. It remains an experimental prototype, not a production content-safety guarantee.

## What works in this release

- Direct in-app Android Photo Picker selection for one photo or video.
- Image preview and local metadata. No broad media-library permissions.
- A CI-exported Viddexa NSFW Detection 2 Nano ONNX model, pinned to an immutable Hugging Face revision.
- Offline ONNX Runtime Android inference for still images.
- Five raw model scores: `safe`, `hentai`, `porn`, `sexy`, `drawing`.
- `porn` is exposed as a NUDITY signal, `hentai` as a SEXUALIZED_ILLUSTRATION signal, and `sexy` as an experimental SUGGESTIVE signal.
- SHA-256 verification before copying the ONNX asset to app-private internal storage.
- Explicit model/revision provenance in the detection records.
- Partial category coverage: no category is declared fully covered by this experimental adapter. The model card says its intended use is explicit nudity and simultaneously describes the `sexy` category as suggestive imagery; that conflict means the suggestive score must not be treated as a validated suggestive detector.
- Deterministic user policy actions. BLUR pixelates the entire image preview because this classifier does not localize body parts; HIDE replaces the preview with a filtered-content message; an explicit one-time Reveal action is available.
- Under the balanced default, an image whose enabled categories are not fully covered is marked UNVERIFIED and pixelated until the user explicitly reveals it. This is uncertainty handling, not a claim that the image contains unwanted content.
- Video remains NOT_ANALYZED / UNVERIFIED. Unfiltered playback is hidden by default and requires an explicit one-time reveal; the global Off setting is honoured. The previous video frame preflight is not used as a substitute for model inference.
- No Internet permission, no media upload, and no model download on the device. The model is downloaded only by GitHub Actions during APK build.

## AI integration status

This is the first actual AI inference in the app: one whole-image five-class classifier. It is not a complete nudity detector, does not localize body regions, and its `sexy` label is experimental. The model's publisher-reported F1 scores are not FilterBridge benchmark results. We still need our own representative test set, score calibration, false-positive/false-negative analysis, and measurements on target phones.

No real video classification, audio classification, OCR, ASR, violence, alcohol/drug/gambling detectors or contextual interaction models are connected yet. Those are not inferred from this image model. See `MODEL_SELECTION.md` and `MODEL_NOTICE.md`.

## Project modules

| Module | Responsibility |
|---|---|
| `filter-core` | Pure Java content contracts, detections with model provenance, full/partial category coverage, deterministic policy engine and unit tests |
| `android-ingest` | Android Photo Picker, content URI adapters, safe bounded image preview decoding and video preview support |
| `ai-runtime-onnx` | ONNX Runtime Android adapter, immutable model asset installation/checksum verification, image preprocessing and inference |
| `demo-app` | Local media picker, preview, analysis reporting, filtering settings, whole-image pixelation/hide and explicit reveal |
| `tools/model-export` | CI-only pinned Hugging Face model download, PyTorch-to-ONNX export and numerical parity validation |

## Model and runtime

- Model repository: `viddexa/nsfw-detection-2-nano`
- Revision: `12e57200346246b37382f746e4d94d10b014f6a1`
- Model-card licence metadata: Apache-2.0 (review all upstream provenance/terms before commercial release)
- Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.31.1`
- Input: 224×224 RGB, model-card image preprocessing, static batch-one NCHW graph
- CI export dependencies are pinned in `tools/model-export/requirements.txt`.

The source model weights are not committed into this ZIP. GitHub Actions downloads the immutable model revision, exports ONNX, validates graph structure and compares PyTorch vs ONNX Runtime output on two deterministic inputs. It embeds the ONNX file and model manifest into the APK. If model download/export/parity validation fails, CI stops and does not produce an APK without a model.

## Build versions

- Android Gradle Plugin: `9.5.0`
- Gradle: `9.6.0`
- Android compile/target SDK: `36`
- Android min SDK: `26`
- Java: `17`
- AndroidX Activity: `1.13.0`
- AndroidX ExifInterface: `1.4.2`
- ONNX Runtime Android: `1.31.1`
- Python export: `3.11`, CPU PyTorch `2.8.0`, Transformers `4.56.1`, ONNX `1.19.0`, ONNX Runtime `1.31.1`

The project includes Gradle wrapper distribution properties but not wrapper scripts/JAR. CI installs Gradle `9.6.0` directly.

## GitHub Actions: obtain the APK

1. Replace the repository contents with the contents of this project folder, including `.github/` and `tools/`.
2. Push to GitHub.
3. Open **Actions** and select the newest `Android CI` run.
4. Confirm the **Download pinned model, export ONNX and verify parity** step succeeds.
5. Confirm the Android lint, core tests and APK assembly steps succeed.
6. Download `filterbridge-lab-debug-apk-*` under **Artifacts**. It includes the debug APK, build metadata, model manifest and model export summary.

The workflow has been authored but has not been executed against your GitHub repository. The first successful run is the authoritative end-to-end export and Android build validation.

## Quick device test

1. Install the newest APK produced by a successful workflow. Uninstall the older app first if Android reports a signing conflict.
2. Enable Airplane mode.
3. Open FilterBridge Lab, tap **Choose photo**, and select an image.
4. Tap **Analyze on device**. The model should run locally and report all five score outputs, plus `PARTIALLY_ANALYZED` and `UNVERIFIED` overall coverage.
5. Use photos from varied benign and explicit/suggestive contexts that you are comfortable testing. Do not interpret individual predictions as reliable until we have a benchmark.
6. If a configured threshold triggers BLUR, the entire preview is pixelated; incomplete category coverage is also pixelated under the balanced default; HIDE hides the preview. Reveal restores it once for this viewing session.
7. Tap **Choose video**. Playback is hidden by default because this version does not analyse video. Tap **Analyze on device** to see the explicit `NOT_ANALYZED` / `UNVERIFIED` status, or tap **Reveal unfiltered video once** to deliberately preview it without filtering.

## Important limitations

1. Still-image classification only; no video-frame inference.
2. No object localization or segmentation; blur applies to the whole image.
3. Suggestive score is experimental and not validated as a complete suggestive-content detector.
4. Other enabled categories remain unverified.
5. Publisher metrics are not independent validation and must not be treated as guarantees. Android Bitmap resize/colour-conversion parity against the upstream PIL processor has not yet been measured on a physical device.
6. No model quantization, GPU/NPU acceleration or adaptive runtime selection has yet been benchmarked. This first release uses ONNX Runtime CPU.
7. No robust custom-category model training, OCR, speech, audio or contextual model is connected.
8. This is an engineering test build, not a Play Store release or a production security guarantee.

## Privacy

- App manifest declares no Internet permission.
- No broad photo/video library permission is requested.
- The phone uses the bundled model; it does not fetch weights or send media to a server.
- The selected media is read from its content URI and decoded in process memory.
- ONNX weights are copied once to app-private internal storage and integrity-checked; user media is not copied into that directory.
- Filtering preferences are local. No media history or inference telemetry is stored.

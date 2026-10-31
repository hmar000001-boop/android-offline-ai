# FilterBridge Lab 0.3.1 test guide

## Build/CI gate

- CI downloads the pinned Hugging Face revision and exports ONNX.
- Export script rejects changed labels, wrong dimensions, unexpected image preprocessing, invalid ONNX graphs and PyTorch/ONNX parity error greater than 1e-3.
- Core tests validate stable softmax, output-to-category mapping, non-finite output rejection and partial coverage behaviour.
- Android lint runs for `android-ingest`, `ai-runtime-onnx`, and `demo-app`.

## Quick device test (2–5 minutes)

1. Install the APK from the latest successful `Android CI` workflow run.
2. Enable Airplane mode before starting the test.
3. Open the app and select **Choose photo**.
4. Select a photo and wait for its preview.
5. Press **Analyze on device**.
6. Expected: the five class scores appear; analysis status is `PARTIALLY_ANALYZED`; category coverage is `INCOMPLETE`; the model/revision is identified.
7. A detection above the current policy's activation confidence can trigger a whole-image pixelation or hide action. An incomplete analysis is also pixelated under the balanced default. This is not region-level blur.
8. Tap **Reveal once** to restore the original image for this viewing session.
9. Select a video. Playback is initially hidden. Tap Analyze to see `NOT_ANALYZED` / `UNVERIFIED`, or explicitly tap **Reveal unfiltered video once** to play it without filtering.

If the app says the model asset is missing, the installed APK did not come from a successful CI run that completed the model-export step. A local Gradle build without running the model exporter will deliberately fail closed.

## Model quality evaluation

Do not validate a content classifier using one or two images. Use a curated, lawful, representative evaluation set with labels separated by:

- Explicit nudity / explicit sexual content
- Suggestive clothing / poses / framing
- Ordinary skin exposure (sports, beach, medical, non-sexual contexts)
- Sexualized illustration / non-sexual drawing
- Safe ordinary images

Record precision, recall, F1, confusion matrix, false-positive and false-negative examples, per-class thresholds, and performance by image source/quality. Keep a separate hold-out test set. Obtain consent and rights for any media used in model evaluation; do not put sensitive test images in a public repository or CI artifact.

The upstream reported evaluation metrics belong to the model publisher and must not be described as FilterBridge accuracy. `sexy` is experimental because the model card contains conflicting descriptions and an explicit-nudity-only intended-use statement.

## Device performance evaluation

Record device model, Android version, RAM class, image dimensions, model initialization time, first-inference latency, warmed latency, peak app memory, battery/thermal behaviour and app package size. Repeat with multiple image sizes and image formats. These measurements have not yet been produced by CI.

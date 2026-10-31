# FilterBridge Lab 0.3.2

Android proof-of-concept for private, on-device image filtering with two independent local ONNX classifiers.

## What's new
- Viddexa Nano plus OwenElliott Image Safety Classifier XS.
- Separate model score reports; no score averaging.
- A broad `adult_visual_content` category for Owen's undifferentiated NSFW output.
- `safe`/`SFW` are explicitly labelled as model predictions, never as safety verdicts.
- One model's normal result cannot cancel another model's positive detection.
- Both model files are fetched at immutable Hugging Face revisions and SHA-256 verified in GitHub Actions, then packaged in the APK.

## Build
Push the entire extracted project contents to GitHub. The workflow installs Python dependencies, downloads/verifies/validates both models, runs pure Java core tests, Android lint, and assembles the debug APK. Download the `filterbridge-lab-debug-apk-*` artifact.

## Test
See `QUICK_START.md` and `TESTING.md`. Test in Airplane mode. Select a photo and tap Analyze on device. The report must show two separate classifier outputs. A normal/SFW result is not a safety guarantee; the overall result remains UNVERIFIED because several enabled categories have no detector yet.

See `BUG_ANALYSIS.md` for the investigation into the previous 100% normal/safe result and `BUILD_STATUS.txt` for exact verification status.

## Known limitations
- Both models classify the whole image and do not return blur boxes.
- Suggestive-content detection remains experimental; Viddexa's own documentation warns that some suggestive clothing/poses may be classified as safe; Owen's NSFW output is broad and cannot distinguish nudity from suggestive content.
- Video inference, music, speech/profanity, alcohol, drugs, gambling and robust violence recognition are not production-ready in this release.
- The app is not a complete or certified content-safety product.

# Quick start — FilterBridge Lab 0.3.1

1. Replace your GitHub repository contents with this project's contents, including hidden `.github/` and `tools/` folders.
2. Push changes and open **Actions**.
3. The workflow downloads the pinned Viddexa Nano weights, exports ONNX, and validates PyTorch/ONNX Runtime numerical parity.
4. Download the debug APK artifact from the newest successful run.
5. Install the APK. If Android reports a signing conflict, uninstall the previous debug build first.
6. Enable Airplane mode, open FilterBridge Lab and choose a photo.
7. Tap **Analyze on device**. Review the raw class scores and incomplete category coverage.
8. Choose a video separately; this release will explicitly report video as not analysed.

Do not use this as a production safety guarantee. The classifier is an experimental whole-image signal, does not localize regions, and its suggestive-content output is not validated.

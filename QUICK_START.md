# Quick start — test a photo or video directly in the app

This demo no longer requires Android's Share menu.

## Get the APK

1. Push the complete project to your GitHub repository (put the contents of this folder at repository root).
2. Open **Actions** and wait for **Android CI** to finish successfully.
3. Open that run and download the `filterbridge-lab-debug-apk-*` artifact.
4. Extract the artifact ZIP and install the APK on your Android phone.
5. Confirm the app shows `Build 0.2.2-direct-media-picker`.

## Test a photo

1. Open FilterBridge Lab.
2. Tap **Choose photo**.
3. Select one image in Android's system Photo Picker.
4. Confirm the preview and file details appear.
5. Tap **Analyze on device**.
6. Expected for this release: `STATUS: NOT_ANALYZED` and `POLICY: UNVERIFIED`. This proves the import/preflight path ran; it does not classify the photo.

## Test a video

1. Tap **Choose video**.
2. Select a short local video.
3. Press play in the preview.
4. Tap **Analyze on device**.
5. Expected: metadata and 0–6 preflight frame samples, followed by `NOT_ANALYZED` / `UNVERIFIED`. API 26 and files without usable dimensions can safely report zero samples.

## Test settings and offline operation

1. Tap **Filtering settings**.
2. Change the global level and thresholds; add a custom category; save.
3. Reopen settings and verify they persisted.
4. Enable Airplane mode and repeat photo selection and preview.
5. Tap **Clear** to release the selection and return to the empty state.

## Important

This release does not include an AI classifier, actual blur/hide rendering, audio analysis, OCR, or real-time video protection. `UNVERIFIED` is expected and must not be interpreted as safe.

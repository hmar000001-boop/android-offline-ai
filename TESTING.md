# FilterBridge Lab 0.2.2 — device test plan

## A. Install

1. Push the full project to GitHub.
2. Open **Actions** and wait for the newest **Android CI** run to succeed.
3. Download `filterbridge-lab-debug-apk-*` from the run's Artifacts section.
4. Extract the artifact ZIP and install the APK.
5. Confirm the app subtitle shows `Build 0.2.2-direct-media-picker` (or the exact Gradle version name).

## B. Photo import

1. Open FilterBridge Lab.
2. Tap **Choose photo**.
3. Select a normal JPEG or PNG in Android's system picker.
4. Confirm the preview appears and the details show `IMAGE`, MIME type, file name, approximate size, and preview dimensions.
5. Tap **Analyze on device**.
6. Expected: `STATUS: NOT_ANALYZED`, `POLICY: UNVERIFIED`, and a message that no local classifier is installed.

Try a large image and a rotated phone photo to exercise downsampling and EXIF orientation. No broad storage permission should be requested.

## C. Video import

1. Tap **Choose video**.
2. Select a short MP4 or another supported video.
3. Confirm video playback controls appear.
4. Tap play to check that the selected URI is readable.
5. Tap **Analyze on device**.
6. Expected: video duration, dimensions when available, and 0–6 extracted frame samples. Samples are discarded after the preflight. The result remains `NOT_ANALYZED` / `UNVERIFIED` because no detector is present.

## D. Settings

1. Tap **Filtering settings**.
2. Change the global level (Off, Warning, Blur, Strict).
3. Change activation and hide confidence thresholds.
4. Choose a category and set a category-specific override.
5. Add a custom category ID, e.g. `smoking`.
6. Save, close and reopen settings. Confirm values persist.

## E. Clear and offline

1. Tap **Clear** and confirm the selected preview disappears.
2. Enable Airplane mode.
3. Choose and preview a photo again.
4. Confirm no network connection is required.

## Expected scope

These tests prove direct local file selection, URI access, preview decoding, bounded video preflight (when supported), local settings persistence, and the deterministic policy engine's fail-closed and coverage-aware behaviour. They do **not** prove AI detection or content safety. Do not treat `UNVERIFIED` as safe.

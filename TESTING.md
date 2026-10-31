# FilterBridge Lab 0.1.1 — Quick Device Test

This guide tests the current proof of concept. It does **not** test AI classification because no on-device model is connected yet.

## A. Build a fresh APK on GitHub

1. Extract the complete project ZIP.
2. Copy the **contents** of `filterbridge-poc-launcher-fix/` into the root of your GitHub repository. The `.github` directory must be included.
3. Commit and push the changes (or upload the files using GitHub's web interface).
4. Open the repository's **Actions** tab and select the newest `Android CI` run.
5. Wait for the job `Test and build debug APK` to finish successfully.
6. Open that run and download the artifact named `filterbridge-debug-apk-...`.
7. Extract the artifact ZIP. It contains `demo-app-debug.apk`.

The workflow runs the `filter-core` unit tests and builds the debug APK. It installs JDK 17, Android SDK Platform 36, Build Tools 36.0.0, and Gradle 9.6.0. The APK is for testing, not Play Store release.

## B. Install it

1. Transfer `demo-app-debug.apk` to the Android phone or download it directly there.
2. Install it. If Android reports a signature conflict with an older debug build, uninstall the old FilterBridge Lab and install this APK again.
3. Open FilterBridge Lab from the launcher.

Expected first screen:

- Build label: `Build 0.1.1 · launcher-intent-fix`
- Main message: `Ready to receive content...`
- Result: `Waiting for shared content.`
- No `Unsupported share action: android.intent.action.MAIN` error.

## C. Quick test (about 2–3 minutes)

### Test 1 — text/link share

1. Open Chrome and visit `https://example.com`.
2. Open Chrome's menu and choose **Share**.
3. Choose **FilterBridge Lab** from Android's Sharesheet. It may be under **More** or require scrolling.

Expected in FilterBridge Lab:

- `Import source: SHARE_SHEET`
- `Platform hint: UNKNOWN`
- `Attachments: 0`
- The shared `https://example.com` link appears under `Shared text / URL`.
- Result says the item was imported but no model has run yet.

### Test 2 — image share

1. Open Gallery or Google Photos.
2. Select one ordinary photo.
3. Tap **Share** and select **FilterBridge Lab**.

Expected:

- `Import source: SHARE_SHEET`
- `Attachments: 1`
- One `IMAGE` entry with a display name, MIME type (for example `image/jpeg`) and size (if exposed by the provider).

The exact filename, MIME type and size depend on the device and image provider.

### Test 3 — run and clear

1. After importing either item, tap **Run filtering pipeline**.
2. Expected: `Analysis status: NOT_ANALYZED` and `Policy action: UNVERIFIED`.
3. Tap **Clear imported content**.
4. Expected: the app returns to `Waiting for shared content.`

`UNVERIFIED` is intentional: the analyzer is a placeholder. It does not mean the content is safe or that an AI model inspected it.

## D. Further share tests

Try one item at a time, then multiple items:

- YouTube: share a video link. A URL is imported; the underlying YouTube video is **not** downloaded or extracted.
- Instagram and Facebook: share a post/link when the source app offers sharing.
- Gallery: share multiple images, if the Gallery app provides a multi-select share action.
- My Files: share a local video and an audio file.

For media, the expected result is a matching attachment kind (`IMAGE`, `VIDEO`, or `AUDIO`) and metadata. The demo does not yet decode or analyse media bytes.

## E. What this milestone does and does not prove

It does prove, when the corresponding test succeeds:

- The Android launcher action is not mistaken for a share.
- Android can route supported `ACTION_SEND` / `ACTION_SEND_MULTIPLE` content to this app.
- The share adapter can create the platform-neutral content representation and report available metadata.
- The demo can call the deterministic filtering pipeline with its placeholder analyzer.
- The core policy unit tests exercise decisions such as blur, hide, allow, and unverified for synthetic analysis results.

It does **not** prove:

- Real AI image, video, audio, OCR, speech, profanity, music, or contextual detection.
- Automatic blur, muting, or removal in the UI.
- Full Facebook, Instagram, or YouTube feed access.
- That a shared social-media URL exposes the media behind it.
- Production-grade persistence of URI permissions or long-running media processing.

## F. If FilterBridge Lab does not appear in the Sharesheet

1. Confirm that the newly built APK is installed and the launcher shows `0.1.1`.
2. Use a standard file or photo and share it from Gallery, Photos, or My Files.
3. Check the MIME type supported by the sender (text, image, video, or audio).
4. Expand the Sharesheet's full app list or tap **More**.
5. If it still does not appear, send the Android version, the source app, the type of item, and a screenshot of the expanded Sharesheet.

## Current confirmed device result

The user-provided screenshot of build `0.1.1 · launcher-intent-fix` shows the normal launcher opening the waiting screen without the old `ACTION_MAIN` import error. Share-intent handling still needs the tests above.

# FilterBridge Lab — Android Share/Import Proof of Concept

This is the first vertical slice for the offline social-content filtering product. It proves the boundary between Android-specific content intake and a reusable, platform-neutral filtering core.

## What is included

- `:filter-core`: plain Java library. It contains normalized content contracts, detector contracts, category identifiers (including user-defined categories), confidence thresholds, and deterministic policy evaluation.
- `:android-ingest`: Android-only adapter. It converts `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents into the core's `InboundContent` model. Shared media remains a lazy stream; the adapter does not copy it to disk.
- `:demo-app`: minimal Java Android app that appears in Android's share sheet for text, image, video, and audio shares. It reports metadata and runs the pipeline with an explicit `NotConfiguredAnalyzer` placeholder.

## Important: AI is not connected yet

The demo intentionally reports `UNVERIFIED`. It does not download a URL, scrape a social network, extract a YouTube stream, classify media, or claim that content is safe. The `ContentAnalyzer` interface is the point where separately benchmarked, fully local models will be connected later.

The policy engine is deliberately independent from perception. It consumes observations and applies user preferences; it does not issue religious rulings.

## Build environment

The project is pinned to:

- Android Gradle Plugin `9.5.0`
- Gradle Wrapper distribution `9.6.0`
- Android compile SDK `36`
- Android target SDK `36`
- Minimum Android API `26`
- Java source/target `17`

JDK 17 or newer is required by the Android Gradle Plugin. The Gradle distribution is configured in `gradle/wrapper/gradle-wrapper.properties`.

This source bundle does not include the Gradle Wrapper JAR/scripts. The GitHub Actions workflow therefore installs the exact Gradle `9.6.0` distribution directly; it does not depend on wrapper files being present.

For a local command-line build, install Gradle 9.6.0 and run:

```bash
gradle --no-daemon clean :filter-core:test :demo-app:assembleDebug
```

To generate and commit a Gradle Wrapper for local development, run once from this directory:

```bash
gradle wrapper --gradle-version 9.6.0 --distribution-type bin
```

Then local builds can use `./gradlew` (Linux/macOS) or `gradlew.bat` (Windows):

```bash
./gradlew clean :filter-core:test :demo-app:assembleDebug
```

Alternatively, open the root folder in a compatible Android Studio installation and use the configured Gradle 9.6.0 distribution.


## GitHub Actions CI

The workflow is included at `.github/workflows/android-ci.yml`. It runs on every push, pull request, or manual workflow dispatch and:

1. Installs Temurin JDK 17.
2. Installs Android SDK Platform 36 and Build Tools 36.0.0.
3. Installs Gradle 9.6.0 (the version required by Android Gradle Plugin 9.5.0).
4. Runs `:filter-core:test` and builds `:demo-app:assembleDebug`.
5. Uploads the debug APK and unit-test reports as workflow artifacts (14-day retention).

To use it, create a GitHub repository, upload/push the contents of this project to the repository root, and open the **Actions** tab. When the workflow completes successfully, open the run and download `filterbridge-debug-apk-*` from **Artifacts**. The APK is a debug build signed with the automatically generated Android debug key; it is for testing, not Play Store release.

## Try the app

Opening the app from the launcher sends `ACTION_MAIN`; this is a normal launch and displays the waiting screen. Only `ACTION_SEND` and `ACTION_SEND_MULTIPLE` are passed to the share importer.

1. Install the debug APK on an Android device.
2. Open Facebook, Instagram, YouTube, or another app.
3. Use its Share action and select **FilterBridge Lab**.
4. Confirm that shared text/links and available media attachments appear in the app.
5. Tap **Run filtering pipeline**. It should report `NOT_ANALYZED` / `UNVERIFIED`, because the model adapter is not yet installed.
6. Share multiple images/videos/audio files to exercise `ACTION_SEND_MULTIPLE`.

A shared URL is only a URL. The app does not assume it can retrieve the underlying video or post.

## Core policy defaults

The balanced default currently uses strict mode with:

- activation confidence: `0.45` (start blurring)
- hide confidence: `0.90` (hide only at high confidence)

Per-category overrides can independently set `NONE`, `WARNING`, `BLUR`, or `STRICT`. These are starter defaults for testing, not validated model thresholds; they must be calibrated against real test data and user feedback before release.

## Privacy properties of this proof of concept

- No `INTERNET` permission is declared.
- No account credentials, cookies, or private platform APIs are used.
- Media bytes are not opened by the demo or persisted by the adapter.
- `MediaSource.openStream()` is lazy and must be consumed while the sender's URI permission remains valid. For future long-running work, the import coordinator must either persist an eligible URI grant or copy to a temporary app-private file with explicit lifecycle cleanup.
- The clear button drops the in-memory content reference. Android's process and cache lifecycle still need production-grade cleanup before release.

## Architecture rule

Platform adapters may depend on `filter-core`; `filter-core` must never depend on Android APIs or a social platform. Future Facebook, Instagram, YouTube, Share, file-picker, and MediaProjection adapters must all produce the same core content model.


## Confirm that the launcher-fix build is installed

The corrected build displays `Build 0.1.1 · launcher-intent-fix` directly under the subtitle. Its Android package version is `0.1.1-launcher-fix` (versionCode 2). If the screen still displays `Unsupported share action: android.intent.action.MAIN`, the installed APK is not this build.

For a clean test, download the artifact from the newest successful Actions run after pushing this project. If Android reports an APK signature conflict, uninstall the prior FilterBridge Lab first, then install this APK.

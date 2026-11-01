# Replacing an existing ScreenVeil checkout

This archive contains the complete ScreenVeil project tree. Its entries are rooted at the repository root (`app/`, `gradle/`, `.github/`, `build.gradle.kts`, and so on).

## Important: do not extract over an old checkout

Some archive extractors overwrite matching files but leave obsolete files untouched. That can leave the old `ScreenProtectionService.kt` (MediaProjection implementation) beside the new `ScreenProtectionAccessibilityService.kt` and cause Gradle to compile stale code.

Use a clean checkout or clean replacement:

1. Back up any local changes you need.
2. Extract this ZIP into a new, empty directory.
3. Verify that `app/src/main/java/dev/screenveil/app/capture/` contains `ProtectionStatus.kt` and `ScreenProtectionAccessibilityService.kt`, and does not contain `ScreenProtectionService.kt`.
4. Replace the tracked project files in your repository with the extracted project contents. Preserve the repository's `.git/` directory only; do not preserve old source/build files.
5. Run the GitHub Actions workflow.

## PowerShell verification

Run from the repository root after copying the extracted files:

```powershell
$capture = "app/src/main/java/dev/screenveil/app/capture"
Get-ChildItem $capture
if (Test-Path "$capture/ScreenProtectionService.kt") {
    throw "Obsolete MediaProjection service remains in the checkout. Remove it before building."
}
if (-not (Test-Path "$capture/ScreenProtectionAccessibilityService.kt")) {
    throw "AccessibilityService implementation is missing. Check the extraction destination."
}
```

## Build

GitHub Actions installs the pinned Gradle 8.14.6 and Android SDK 36, runs unit tests and lint, then builds `app/build/outputs/apk/debug/app-debug.apk`.

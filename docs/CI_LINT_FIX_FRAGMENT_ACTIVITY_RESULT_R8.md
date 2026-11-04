# CI lint correction — Activity Result / Fragment compatibility (R8)

Date: 2026-11-04

## CI failure

The R7 GitHub Actions run compiled Kotlin, ran `testDebugUnitTest`, and assembled the debug APK. It failed only at `lintDebug` with:

`InvalidFragmentVersionForActivityResult`: upgrade Fragment to at least 1.3.0 at the `registerForActivityResult()` call in `MainActivity`.

`MainActivity` extends `androidx.activity.ComponentActivity` and uses the Activity Result API for the mandatory notification permission request. The app did not declare an explicit Fragment dependency. A transitive AndroidX dependency can therefore bring an older Fragment implementation into the resolved application graph. Android's lint check documents this as a real compatibility issue, not a warning to suppress: older `FragmentActivity` versions mishandled `onRequestPermissionsResult()` and request codes.

## Fix

1. Added `androidx.fragment:fragment:1.9.1` as an explicit application dependency.
2. Strictly pinned it to `1.9.1` to prevent a transitive version constraint or future graph change from selecting an older Fragment release.
3. Added a source-level verification script.
4. Added a CI `dependencyInsight` step which prints the resolved Fragment dependency path and asserts that `1.9.1` is selected. Its output is uploaded with the build reports.
5. Increased app version from `1.6.2` / versionCode 15 to `1.6.3` / versionCode 16.

The version is the stable release listed in the official AndroidX Fragment release notes on September 23, 2026.

## Scope and safety

No `registerForActivityResult()` behavior was changed. Notification permission remains required before protection starts, and the Emergency Stop notification requirement remains intact. No model, inference, segmentation, coverage, overlay, watchdog, timeout, screenshot-capture or privacy behavior was changed.

No lint rule was disabled and no lint baseline was added.

## Validation boundary

The R7 CI log proves `compileDebugKotlin`, `testDebugUnitTest`, and `assembleDebug` completed; `lintDebug` was the failing task. This patch is statically checked in the source archive, but a new GitHub Actions run is required to prove that lint now passes and that the selected Fragment version is 1.9.1. No new Android build or device test is claimed by this report.

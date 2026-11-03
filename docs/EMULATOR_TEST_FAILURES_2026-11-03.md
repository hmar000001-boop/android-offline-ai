# Emulator instrumentation follow-up — 2026-11-03

## r4 regression: fixture Activity launch command

The API 34 run reported 11 failures with the same message: `ActivityManager did not report a successful fixture launch`, with empty command output. Inspection of `launchFixtureWindow()` found that r4 passed `--activity-new-task` to `am start`. That is not a supported `ActivityManagerShellCommand` option. `am start` already adds `Intent.FLAG_ACTIVITY_NEW_TASK`; the extra argument was both invalid and unnecessary.

The launch command now retains the supported `--activity-clear-task` option and removes `--activity-new-task`. It merges standard error into standard output with `2>&1`, because `UiAutomation.executeShellCommand()` exposes standard output only.

The helper no longer treats the exact text `Status: ok` as the sole success condition. It waits for the expected fixture component to appear as the resumed Activity, then independently waits for the fixture ContentProvider report to confirm Activity creation, window focus, and the requested `FLAG_SECURE` state. If either check times out, the assertion includes the full command, merged command output, ActivityTaskManager resumed-Activity diagnostics, and fixture state.

## Earlier test corrections retained

### `repeatedModelFailuresStopAndClearAnExistingOverlay`

The fake detector returns valid detections until the test observes an active status with one redacted region and a visible overlay. Only then does the test enable injected failures and request another capture. This removes the race in which a scheduled capture could fail before the test observed its initial overlay.

### `androidSecureWindowScreenshotDenialPausesWithoutOverlay`

The Java fixture handles `onNewIntent()` and reapplies or clears `FLAG_SECURE` based on the incoming Intent. The launch helper resets fixture IPC state before each launch and now validates that the published secure-flag state matches the requested `secure` argument.

## Verification status

Static source inspection and shell checks can validate the command shape and diagnostics, but they do not establish Android Activity Manager behavior. Gradle compilation, API 34/API 36 instrumentation runs, and real Galaxy S24 FE validation must still be confirmed by CI and device testing. No emulator test pass is claimed by this source revision.


## 2026-11-03 permission-preflight history and V5 correction

V2 failed before instrumentation because it invoked the nonexistent `pm check-permission` command. V3 corrected the command but its exact source provenance was not established in the user's subsequent run. V4 moved the grant to ADB installation (`adb install -r -g`) and configured AGP `installation.installOptions` so `connectedDebugAndroidTest` receives `-g` during its own install.

The user's API 34 V4 log then showed `adb install -r -g` returning `Success` and the detailed `User 0` -> `runtime permissions` block showing `android.permission.POST_NOTIFICATIONS: granted=true`. However, the separate `dumpsys package check-permission ... 0` query returned `-1`, causing V4's script to abort before AndroidJUnitRunner. This run did **not** execute instrumentation and does not diagnose the original lifecycle crash.

V5 no longer treats the contradictory one-line query as authoritative. The host preflight records the detailed package dump and the one-line query as diagnostics, then proceeds when installation and package paths are valid. The decisive permission assertion remains `targetContext.checkSelfPermission(POST_NOTIFICATIONS)` in the instrumentation `@Before`, which runs after AGP's own connected-test APK installation. If AGP's install loses the grant, the instrumentation test reports the actual target-context permission value rather than aborting in a host-side parser.

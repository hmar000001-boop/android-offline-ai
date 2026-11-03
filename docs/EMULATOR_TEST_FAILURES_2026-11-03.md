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


## 2026-11-03 permission-preflight correction

A later CI run failed before instrumentation with exit code 255 immediately after `Pre-granting POST_NOTIFICATIONS outside instrumentation...`. The cause was the invalid command `pm check-permission`. The verification subcommand belongs to `dumpsys package`, and its output is numeric (`0` granted, `-1` denied). Use `scripts/pregrant-notification-permission.sh`, which grants with `pm grant --user 0` and verifies with `dumpsys package check-permission`. A run failing at this preflight has not executed instrumentation and therefore provides no new evidence about the lifecycle test crash.

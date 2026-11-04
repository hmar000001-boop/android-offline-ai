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

## 2026-11-03 API 36 SDK archive failure and V6 retry

A subsequent API 36 job failed during `sdkmanager --install`, while unpacking `system-images;android-36;google_apis;x86_64` (`x86_64-36_r07.zip`), with `Error on ZipFile unknown archive`. The step exited before the emulator action, Gradle connected-test task, or AndroidJUnitRunner. This run therefore supplies no new evidence about instrumentation behavior or the V5 permission correction.

V6 adds `scripts/install-android-sdk-with-retries.sh` and routes the debug, release, and emulator SDK setup steps through it. It clears only SDK Manager temporary directories between up to three attempts, logs every attempt, and fails normally if all attempts fail. The emulator job now preinstalls the full package set required by the pinned `reactivecircus/android-emulator-runner@v2.38.0`, including the action's Build Tools 37.0.0 pin, so that the action should find those components already installed rather than needing its own un-retried downloads. This is based on inspection of the action's `v2.38.0/lib/sdk-installer.js`; no app runtime code changed.

V6 has only static and mocked-script validation in this environment. It has not yet passed a real GitHub Actions run, a live SDK download, API 34/API 36 emulator startup, or instrumentation tests.


## 2026-11-04 API 36 timeout-test synchronization correction (V7)

The API 36 run reached AndroidJUnitRunner and completed all 29 instrumentation methods; 27 passed. Two timeout tests failed at the process-poison assertion. Inspection found a race in both tests: `requestStart()` only posts `startProtection()` to the service main Handler, while each test's first `await` condition was `!ProtectionStatus.state.value.active && !hasRedactionWindow()`. That condition is already true before the queued Start command runs, so the tests could assert the poison latch before either worker had begun. The short 350ms test deadline also left too little time for target-window selection / emulator scheduling before a screenshot provider was entered.

The two tests now use a 900ms debug deadline and an `AtomicBoolean` set only from inside the blocked worker operation. They first await that signal, then await all three watchdog outcomes: protection inactive, no redaction window, and the exact `Screenshot watchdog expired` / `Inference watchdog expired` diagnostic in `ProtectionStatus.error`. Only after that do they assert the process-wide poison latch. The inference test name no longer claims to validate a late result that its interruptible fake detector never actually returned; the screenshot test name now identifies the injected frame provider rather than implying this case uses the real Android screenshot callback.

No production Kotlin, runtime watchdog implementation, policy, model, manifest, or CI workflow was changed for this correction. This is a test synchronization fix, not a relaxation of the production timeout / process-poison requirement. The two test changes still require a real Gradle instrumentation run on API 34 and API 36.

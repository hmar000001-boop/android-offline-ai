# API 34 instrumentation follow-up — debounce isolation and secure-window fixture

## Log diagnosis

The 2026-11-02 API 34 run compiled both APKs and executed all 18 instrumentation tests. Two assertions failed:

1. The content-change storm observed three more synthetic-frame-provider calls than the expected single capture (`expected 4, actual 7`). The test's prior `isCapturePipelineIdleForTest()` checked only screenshot, inference and redaction worker flags. It did not inspect queued `Handler` callbacks (`periodicCapture`, `debouncedCapture`, `retryCapture`), pending capture coalescing flags, or the content-change burst flag. In addition, real framework accessibility events could arrive while the test injected its own 30-event burst. Therefore the old test did not establish an isolated baseline and could not attribute every additional capture to the injected burst.

2. The secure-window precondition searched `UiAutomation.rootInActiveWindow` for a text node. That is not a direct assertion about the Activity's actual `Window` flags and can fail independently of whether `FLAG_SECURE` was applied.

## Source changes

- Split the service event path into `onAccessibilityEvent()` and `handleAccessibilityEvent()`. Production events and injected events both use `handleAccessibilityEvent()`.
- Added a debug-only isolation switch used solely by the deterministic debounce instrumentation. While enabled, ambient framework events are ignored; injected test events still run through the same production event handler.
- The isolation entry point runs on the main thread, disables periodic work, removes pre-existing debounced/retry callbacks and resets the burst latch before the test takes its counter baseline.
- `isCapturePipelineIdleForTest()` now checks screenshot/inference/redaction work, both coalescing flags, the burst latch and queued periodic/debounce/retry callbacks. The test waits for this complete quiescent state after the new capture has been analyzed and its mask restored before asserting exactly one capture.
- Teardown always exits the test-only event-isolation mode.
- The fixture Activity now reports its runtime `Window` flags and window-focus state through test-APK-private SharedPreferences from `onCreate()` and `onWindowFocusChanged()`. The test clears that report before launch and waits until the fixture reports both focus and the actual `FLAG_SECURE` bit. It no longer uses an accessibility-tree text search as a proxy for window flags.

No production capture, screenshot error handling, detector, policy or overlay behavior was changed in this follow-up.

## Validation boundary

Static XML parsing, workflow YAML parsing, Bash syntax checking, Kotlin parser diagnostics, and ZIP integrity checks are performed for the archive. This environment does not have an Android SDK or configured Gradle distribution, so Android Gradle compilation and emulator execution are not claimed. API 34 and API 36 GitHub Actions runs remain mandatory.

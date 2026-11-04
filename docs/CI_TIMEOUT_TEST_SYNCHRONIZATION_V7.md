# ScreenVeil R6 — timeout instrumentation synchronization correction (V7)

## Input evidence

The supplied API 36 workflow log shows `connectedDebugAndroidTest` starting successfully, executing all 29 tests, and finishing with two JUnit failures. The uploaded report records 27 passed, 2 failed, 0 errors, and 0 skipped. The failures occurred at the process-poison assertions in the screenshot and inference deadline cases; the instrumentation process itself completed normally.

## Root cause in the test sequence

`ScreenProtectionAccessibilityService.requestStart()` returns after posting `startProtection()` to the service main Handler. It does not synchronously establish that protection has started or that a worker has entered its operation. Both failing tests then waited for `!ProtectionStatus.state.value.active && !hasRedactionWindow()`. That condition is true in the initial OFF state, before the queued Start command is processed. The tests could therefore check `isWorkerPoisonedForTest()` too early.

The failing tests also used a 350ms debug timeout. The screenshot watchdog is armed before window selection and frame-provider execution, so such a short deadline can expire during emulator scheduling/window enumeration instead of testing a worker that is already blocked.

## Changes

Only `app/src/androidTest/java/dev/screenveil/app/capture/ProtectionLifecycleInstrumentedTest.kt` changes runtime test behavior:

- Inference case: set an `AtomicBoolean` inside the fake detector, wait until it is true, then wait for the exact inference-watchdog diagnostic plus inactive protection and no overlay before asserting process poison.
- Screenshot case: set an `AtomicBoolean` inside the injected frame provider, wait until it is true, then wait for the exact screenshot-watchdog diagnostic plus inactive protection and no overlay before asserting process poison.
- Both debug watchdog overrides are increased from 350ms to 900ms, matching the already-existing generation-change watchdog tests that successfully wait for worker entry. Production deadlines remain unchanged (5 seconds screenshot, 8 seconds inference).
- Test names and coverage descriptions are corrected so they do not claim these interruptible fake-worker cases prove a real native/JNI uninterruptible call or a late inference result.

No production Kotlin, Gradle configuration, CI workflow, dependencies, manifest, permissions, model assets or safety thresholds were changed.

## Validation and limitations

This package was prepared from V6 and statically inspected. The two test bodies now contain explicit worker-entry gates and watchdog-reason gates; references to their renamed test methods were checked in the repository documentation. ZIP integrity and SHA-256 are verified at packaging time.

No Android SDK / `adb` emulator is installed in this authoring environment, so Gradle compilation and live instrumentation execution were not performed here. The API 36 rerun must confirm these two tests pass; API 34 must also run. A green test would validate the fake-worker timeout path and process-poison assertion, not production native inference behavior on a Galaxy S24 FE.

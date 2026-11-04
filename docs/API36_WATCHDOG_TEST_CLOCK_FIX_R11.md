# ScreenVeil 1.6.7 (R11) — API 36 watchdog test clock correction

## Evidence and diagnosis

The supplied CI result has API 34 green and API 36 with 30/31 instrumentation tests passing. The only failure is `fiveMinuteSessionWatchdogStopsAndClearsTheOverlayWithoutBeingExtendedByStart`, with `elapsed=5622ms`. The R10 real EfficientDet contract smoke test therefore passed in that API 36 run; the failure is isolated to the shortened session-timeout lifecycle test.

Production `startProtection()` is idempotent: it returns immediately when `monitoringRequested` is already true, before `armProtectionSessionTimeout()` is called. A duplicate Start cannot re-arm the production watchdog through this path.

The test previously recorded `System.currentTimeMillis()` immediately before calling `requestStart()`. `requestStart()` posts the actual operation to the AccessibilityService main Handler, and the service records its session start later using `SystemClock.elapsedRealtime()`. The assertion compared the stop time to the pre-dispatch caller timestamp and imposed a fixed 5,500ms upper bound on a 5,000ms test timeout. The observed 5,622ms is consistent with Start queue/dispatch delay plus the intended five-second timer and Handler scheduling. Since the test's wait for stop completed at roughly 5.6s, this is not evidence that a second 5s timer was armed after the repeated Start (which would instead stop around 7.2s from the original caller timestamp).

## Changes

- Added a read-only debug-only accessor for the service-owned `protectionSessionStartedAtMs`.
- Reworked the lifecycle test to drain the main Handler after the first Start and capture the actual monotonic timestamp at which the service arms its watchdog.
- After the duplicate Start, drained the Handler again and asserted that the watchdog start timestamp is exactly unchanged. This directly tests idempotence instead of inferring it from a fragile wall-clock upper bound.
- The test now waits for the safety stop with a bounded 8-second allowance and verifies that it did not occur before the configured 5-second test deadline.
- Production timeout remains `CoveragePolicy.MAX_PROTECTION_SESSION_MS` (300,000ms); no watchdog scheduling, safety limit, overlay behavior or Start logic is changed.
- Bumped the app version to 1.6.7 / versionCode 20 for the next installable CI build.

## Validation boundary

Static source and packaging checks are performed for this archive. This environment does not provide Android Gradle/emulator execution; the R11 instrumentation test must be run in GitHub Actions on both API 34 and API 36. A green lifecycle test validates the fake-runtime service watchdog path, not Samsung device scheduling or real five-minute physical-device behavior.

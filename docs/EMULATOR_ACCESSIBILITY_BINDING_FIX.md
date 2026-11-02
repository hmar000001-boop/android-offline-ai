# Emulator Accessibility binding test fix

The first API 34 and API 36 emulator runs successfully compiled and packaged both APKs,
but three instrumentation tests timed out while waiting for
`ScreenProtectionAccessibilityService` to bind.

Root cause in test setup: Android `UiAutomation` suppresses other Accessibility
services by default. The tests were issuing shell commands through the default
`Instrumentation.uiAutomation`, so the harness could suppress the service that
the tests were trying to exercise.

The instrumentation test now obtains automation with
`Instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)`.
This flag explicitly permits existing Accessibility services to continue and new
services to bind. The test setup also asserts that the ScreenVeil component and
global Accessibility setting were actually written before waiting for connection.

If service binding still times out, the failure includes the enabled-service setting,
global Accessibility setting, and the tail of `dumpsys accessibility` to distinguish
setup, manifest, and Android binding failures. The wait is 20 seconds.

This correction changes the instrumentation harness only; it does not change
production protection behavior. Android build and emulator tests must be rerun to
verify the fix.

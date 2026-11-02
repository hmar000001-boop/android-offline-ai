# Accessibility settings write fix — 2026-11-02

## Observed failure

The API 36 instrumentation run started 17 tests. Twelve tests failed with the same assertion from
`enableScreenVeilAccessibility()`. The diagnostic printed the configured component with literal quote
characters, for example:

```text
Configured services: ['dev.screenveil.app/dev.screenveil.app.capture.ScreenProtectionAccessibilityService']
```

The service-dependent test bodies did not run. This run therefore did not test actual service binding,
screenshot capture, detector callbacks or overlay lifecycle.

## Root cause

The test used `UiAutomation.executeShellCommand()` with a command containing shell-style single quotes.
AOSP's `UiAutomationConnection` implementation invokes `Runtime.getRuntime().exec(command)`, not
`sh -c`. The quotes are therefore not interpreted by a shell and can be passed through as literal
characters in the command arguments. This is incompatible with values being written to
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. Equivalent quoted writes also existed in teardown
and in the test which disables the service.

References:

- [AOSP UiAutomationConnection](https://android.googlesource.com/platform/frameworks/base/+/fc2be9c0d2beddfbff420d64c0af3d287327a2ee/core/java/android/app/UiAutomationConnection.java)
- [Android UiAutomation API](https://developer.android.com/reference/android/app/UiAutomation)
- [Android Settings.Secure API](https://developer.android.com/reference/android/provider/Settings.Secure)

## Correction

- Non-null secure-setting values are written with `Settings.Secure.putString()` from instrumentation.
- The test temporarily adopts only `android.permission.WRITE_SECURE_SETTINGS` through
  `UiAutomation.adoptShellPermissionIdentity(...)`, verifies the exact value by reading it back, and
  always drops the shell permission identity in `finally`.
- Null settings are deleted using `settings delete secure <key>` without quoting.
- The same safe write routine is used when enabling the service, disabling the service and restoring
  both original settings after every test.
- Test and app component checks parse both short and fully-qualified Android flattened component names
  with `ComponentName.unflattenFromString()`.
- A regression test verifies both accepted component forms and rejection of literal quoted values.

## Validation status

- Static source inspection: completed.
- Shell quoting search: no remaining quoted `settings put secure` invocations in instrumentation code.
- Android build and emulator execution of this corrected revision: **not run in this environment**.
- API 34 and API 36 GitHub Actions runs remain required.

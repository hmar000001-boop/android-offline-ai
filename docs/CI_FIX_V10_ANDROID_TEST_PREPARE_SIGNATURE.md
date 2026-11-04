# ScreenVeil R6 V10 — Android instrumentation compile fix

## Failure addressed

V9 successfully compiled the production app (`:app:compileDebugKotlin`) but failed while compiling instrumentation sources (`:app:compileDebugAndroidTestKotlin`). The only reported Kotlin error was:

```text
ProtectionLifecycleInstrumentedTest.kt:1233:9
'prepare' overrides nothing
Potential signature:
prepare(frame, regions, settings, screenBounds, collectDiagnosticGeometry)
```

## Root cause

V9 added the optional `collectDiagnosticGeometry: Boolean = false` parameter to `OverlayController.prepare(...)`. The `FailingOverlay` test double still overrode the previous four-parameter signature. Kotlin therefore did not consider the method an override.

## Change

Updated the test double override to accept:

```kotlin
collectDiagnosticGeometry: Boolean
```

The override intentionally does not use this argument because this fake exists to simulate `showPrepared()` failure and its existing behavior remains unchanged. The parameter has no default value in the override; Kotlin inherits the default argument from the base declaration at call sites.

The app `versionCode` is incremented from 11 to 12 so a V10 debug APK can update V9 when both are signed with the same key. `versionName` remains `1.6.0`.

## Scope and safety

No production Kotlin implementation, model, dependency, permission, manifest, accessibility-service configuration, CI workflow, coverage rule, timeout, watchdog or emergency-stop behavior was changed. `CoveragePolicy.kt` is byte-for-byte identical to V9. No tests were added, removed or weakened; only the stale test-double method signature was corrected.

## Validation and remaining work

Static source/signature checks, XML/YAML parsing, shell syntax, Python syntax, model-catalog metadata validation, ZIP integrity and SHA-256 verification were run for V10. The validation environment does not have an Android SDK or `adb`, so a Gradle Android build and emulator run were not executed here. Run the existing CI workflow again; the next expected step is successful `compileDebugAndroidTestKotlin`, followed by the existing API 34/API 36 instrumentation suites.

# Fixture components rewritten in Java (2026-11-02)

## Evidence (API 34 and API 36 diagnostic runs, identical)
- Kotlin stdlib 2.4.21 IS in `debugAndroidTestRuntimeClasspath`.
- The instrumentation APK's DEX files define no `kotlin.*` classes; `kotlin.jvm.internal.Intrinsics` lookup fails.
- A standalone `adb shell content call` against the fixture provider crashes with
  `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`, with no test runner involved.
- The class path in the crash is only the test package's `base.apk`.

## Cause
`ScreenVeilFixtureActivity` and `ScreenVeilFixtureStateProvider` are declared by the instrumentation APK, so
Android starts them in the `dev.screenveil.app.test` process, which loads only that APK. Android Gradle Plugin
leaves libraries already packaged in the target app (including the Kotlin stdlib) out of the androidTest APK, so
the explicit `androidTestImplementation(kotlin-stdlib)` declaration never changed the APK contents.

## Fix
Both fixture classes are now Java with unchanged public names, constants and behavior. The test class is
unchanged (it runs inside the target app process, where the Kotlin runtime exists). The fixture must stay in the
test package because it represents a window from a different package than ScreenVeil.

## CI gate
`scripts/diagnose-instrumentation-runtime.sh` now exits non-zero if the standalone provider probe crashes with a
missing Kotlin runtime. After the fix the expected summary line is
`PROVIDER_PROBE_RESULT=CONTENT_PROVIDER_CALL_RETURNED_BUNDLE`.

Not validated locally: no Gradle build or emulator was available when this change was prepared.

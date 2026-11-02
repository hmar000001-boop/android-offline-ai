# Instrumentation fixture state IPC correction — 2026-11-02

## Evidence from the 19:10 UTC API 34 run

The ActivityTaskManager diagnostics show:

`topResumedActivity=ActivityRecord{... dev.screenveil.app.test/dev.screenveil.app.fixture.ScreenVeilFixtureActivity ...}`

This establishes that ActivityManager launched and resumed the fixture Activity. At the same time, the test read `created=false`, `focused=false`, and `flags=0` from the fixture's runtime report. These two observations conflict if the report read is current.

The previous implementation exchanged this report through `SharedPreferences`: the fixture Activity wrote the test APK's preferences, while instrumentation read them through a separate context/process. Android's SharedPreferences API explicitly does not support access across processes; the deprecated `MODE_MULTI_PROCESS` is not a reliable workaround. The resulting stale cached state explains why the fixture was resumed but the instrumentation repeatedly observed the all-default report. See Android's API references:

- https://developer.android.com/reference/android/content/SharedPreferences
- https://developer.android.com/reference/android/content/Context#MODE_MULTI_PROCESS

## Correction

The test-only fixture now publishes its runtime window report through `ScreenVeilFixtureStateProvider`, a `ContentProvider` declared in the instrumentation APK manifest. Activity publication and test retrieval both use `ContentResolver.call()`, which is an explicit Binder IPC boundary. The provider stores one immutable-by-copy Bundle snapshot and returns a copy to callers. The test resets the provider state before each Activity launch and checks that the provider authority matches the installed instrumentation package. The target APK also declares the exact provider authority in `<queries>` so Android's package-visibility filtering cannot hide the instrumentation-only provider from the app-under-test process. The query has no production behavior because the test provider is not part of the production APK.

The fixture Activity continues to report the actual `Window.attributes.flags`, the `FLAG_SECURE` bit, and its focus callback. No production AccessibilityService, capture, detection, redaction, or overlay code was changed for this correction.

## What this does and does not establish

Static source validation can confirm that the unsupported SharedPreferences exchange has been removed and the provider is declared consistently. It cannot establish that the test APK provider is correctly merged, installed, or invoked on Android. The next API 34 run must prove that it can read `created=true`, `focused=true`, and the expected flag bit before any capture test proceeds. API 36 still needs to be run after API 34.

The 19:10 run did not reach the debounce or secure-screenshot behavior tests because fixture setup failed first. Therefore that run provides no new result about whether the debounce test's exact-one-capture assertion passes, or whether Android returns `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` in the secure fixture test.

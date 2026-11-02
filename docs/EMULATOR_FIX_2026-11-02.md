# Emulator instrumentation compilation fix — 2026-11-02

The latest API 34 and API 36 jobs successfully compiled the production app module (`compileDebugKotlin`,
`compileDebugJavaWithJavac`) and reached `compileDebugAndroidTestKotlin`. Both jobs stopped on one
test-fixture source error:

```text
ScreenVeilFixtureActivity.kt:38:49 Unresolved reference 'ACCESSIBILITY_EVENT_TYPE_WINDOW_CONTENT_CHANGED'
```

The fixture used `View.ACCESSIBILITY_EVENT_TYPE_WINDOW_CONTENT_CHANGED`, which is not an Android API
constant. The window-content-change event type is `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED`.
The fixture now imports `android.view.accessibility.AccessibilityEvent` and passes that constant to
`View.sendAccessibilityEvent(...)`.

This correction is limited to the instrumentation fixture. The previous CI logs confirm that production
Kotlin compilation and debug APK packaging were reached successfully; they do not prove that the
instrumentation source compiles or that any of the 17 tests pass. Rerun API 34 and API 36 to continue.

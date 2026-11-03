package dev.screenveil.app.capture

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.screenveil.app.AppPreferences
import dev.screenveil.app.fixture.ScreenVeilFixtureActivity
import dev.screenveil.app.fixture.ScreenVeilFixtureStateProvider
import dev.screenveil.app.model.DetectionResult
import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.ModelFiles
import dev.screenveil.app.model.NormalizedBox
import dev.screenveil.app.model.ScreenContentDetector
import dev.screenveil.app.overlay.OverlayController
import dev.screenveil.app.policy.ContentDecision
import dev.screenveil.app.policy.ModerationSettings
import dev.screenveil.app.policy.RedactionEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs on a real Android emulator. The debug-only seams substitute model outputs and, where
 * appropriate, screenshot frames; Android's AccessibilityService, Handler, WindowManager and
 * overlay window lifecycle remain real. One test intentionally uses Android's screenshot API.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // UiAutomation suppresses non-test Accessibility services by default. ScreenVeil's
    // lifecycle tests must explicitly allow the app's own service to bind.
    private val uiAutomation by lazy(LazyThreadSafetyMode.NONE) {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    private val context: Context get() = instrumentation.targetContext
    private val fixturePackage: String get() = instrumentation.context.packageName

    private var originalEnabledServices: String? = null
    private var originalAccessibilityEnabled: String? = null
    private var fakeCaptureCount = AtomicInteger(0)

    @Before
    fun setUp() {
        ScreenVeilTestHooks.reset()
        fakeCaptureCount = AtomicInteger(0)
        originalEnabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        originalAccessibilityEnabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED
        )
        context.getSharedPreferences("screenveil_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AppPreferences(context).setProtectionEnabled(false)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            ScreenProtectionAccessibilityService.endIsolatedAccessibilityEventTest()
        }
        ScreenProtectionAccessibilityService.requestStop()
        await("protection stopped", timeoutMs = 5_000L) { !ScreenProtectionAccessibilityService.isMonitoringForTest() }
        shell("input keyevent KEYCODE_HOME")
        restoreSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalEnabledServices)
        restoreSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, originalAccessibilityEnabled)
        val component = ComponentName(context, ScreenProtectionAccessibilityService::class.java)
        val wasPreviouslyEnabled = originalEnabledServices.orEmpty()
            .split(':')
            .any { isSameComponent(it, component) }
        if (!wasPreviouslyEnabled) {
            await("Accessibility service unbind", timeoutMs = 8_000L) {
                !ScreenProtectionAccessibilityService.isServiceConnected()
            }
        }
        ScreenVeilTestHooks.reset()
    }

    @Test
    fun accessibilityComponentMatchingAcceptsAndroidFlattenedFormsOnly() {
        val expected = ComponentName(context, ScreenProtectionAccessibilityService::class.java)

        assertTrue(isSameComponent(expected.flattenToString(), expected))
        assertTrue(isSameComponent(expected.flattenToShortString(), expected))
        assertFalse(isSameComponent("'${expected.flattenToString()}'", expected))
    }

    @Test
    fun cleanInstallDefaultsProtectionToOff() {
        assertFalse("Protection must default to OFF", AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun previousReleaseOnFlagIsMigratedToOff() {
        context.getSharedPreferences("screenveil_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("protection_enabled", true)
            .putInt("safety_migration_version", 0)
            .commit()

        assertFalse("Upgrade migration must turn the old automatic-start flag OFF", AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun enablingAccessibilityDoesNotStartProtectionOrCreateOverlay() {
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()

        val state = ProtectionStatus.state.value
        assertFalse("Accessibility binding must not start monitoring", state.active)
        assertFalse("Accessibility binding must not show an opaque cover", state.opaqueCoverActive)
        assertFalse("No ScreenVeil overlay window may exist after binding", hasRedactionWindow())
        assertFalse("Binding must leave the persisted protection switch OFF", AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun startWithoutVerifiedModelsStopsSafelyAndLeavesNoOverlay() {
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()

        assertTrue("Service should accept the explicit start request", ScreenProtectionAccessibilityService.requestStart())
        await("missing-model safe-stop state") {
            !ProtectionStatus.state.value.active &&
                ProtectionStatus.state.value.message.contains("model files are missing", ignoreCase = true)
        }

        val state = ProtectionStatus.state.value
        assertFalse("Missing models must not leave protection active", state.active)
        assertFalse("Missing models must not trigger a full-window cover", state.opaqueCoverActive)
        assertFalse("Missing models must not leave a redaction overlay", hasRedactionWindow())
        assertFalse("Failed start must reset the persisted protection switch", AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun successfulSyntheticInferenceCreatesOnlyLocalizedAccessibilityOverlay() {
        installFakeRuntime(resultDetector())
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()

        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        await("successful fake inference and localized overlay", timeoutMs = 20_000L) {
            ProtectionStatus.state.value.lastEndToEndMs != null &&
                ProtectionStatus.state.value.regionsRedacted == 1 && hasRedactionWindow()
        }
        assertTrue("Protection should remain active after valid inference", ProtectionStatus.state.value.active)
        assertEquals(1, ProtectionStatus.state.value.regionsRedacted)
        assertTrue("Synthetic capture should have been requested", fakeCaptureCount.get() >= 1)
        assertFalse("A localized mask must not use the opaque whole-window state", ProtectionStatus.state.value.opaqueCoverActive)
    }

    @Test
    fun androidWindowScreenshotApiDeliversAFrameToTheDetector() {
        // No frameProvider is installed in this test: requestScreenshot must call the actual
        // AccessibilityService.takeScreenshotOfWindow API and pass the returned bitmap to inspect().
        installFakeRuntime(resultDetector(), useSyntheticFrames = false)
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()

        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        await("real Android screenshot reaching detector and localized overlay", timeoutMs = 25_000L) {
            ProtectionStatus.state.value.lastEndToEndMs != null &&
                ProtectionStatus.state.value.regionsRedacted == 1 && hasRedactionWindow()
        }
        assertTrue("Service should remain active after a successful screenshot and redaction", ProtectionStatus.state.value.active)
        assertFalse("A localized screenshot result must not activate opaque full-window fallback", ProtectionStatus.state.value.opaqueCoverActive)
    }

    @Test
    fun repeatedContentChangeEventsAreDebouncedToOneCapture() {
        configureLongCaptureInterval()
        val inspections = AtomicInteger(0)
        val validResult = DetectionResult(
            regions = listOf(
                DetectedMediaRegion(
                    box = NormalizedBox(250, 250, 750, 750),
                    decision = ContentDecision.EXPLICIT,
                    mediaType = "image"
                )
            ),
            parseSucceeded = true
        )
        installFakeRuntime(FakeDetector {
            inspections.incrementAndGet()
            validResult
        })
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        await("initial localized inference", timeoutMs = 20_000L) {
            ProtectionStatus.state.value.lastEndToEndMs != null &&
                fakeCaptureCount.get() >= 1 && hasRedactionWindow()
        }
        instrumentation.runOnMainSync {
            assertTrue(
                "Could not enter isolated accessibility-event test mode",
                ScreenProtectionAccessibilityService.beginIsolatedAccessibilityEventTest()
            )
        }
        await("capture workers and all delayed Handler callbacks to become idle", timeoutMs = 5_000L) {
            ScreenProtectionAccessibilityService.isCapturePipelineIdleForTest()
        }
        val beforeCaptures = fakeCaptureCount.get()
        val beforeInspections = inspections.get()
        instrumentation.runOnMainSync {
            repeat(30) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                event.packageName = fixturePackage
                ScreenProtectionAccessibilityService.dispatchAccessibilityEventForTest(event)
                event.recycle()
            }
        }
        await("one debounced screenshot after content-change storm", timeoutMs = 8_000L) {
            fakeCaptureCount.get() >= beforeCaptures + 1
        }
        // The frame-provider counter advances before inference and overlay preparation. Wait for
        // the complete pipeline rather than sleeping an arbitrary 350 ms after capture starts.
        try {
            await("localized mask restored after debounced inference", timeoutMs = 12_000L) {
                inspections.get() >= beforeInspections + 1 &&
                    ProtectionStatus.state.value.active &&
                    ProtectionStatus.state.value.regionsRedacted == 1 &&
                    hasRedactionWindow()
            }
            await("debounced capture pipeline and Handler queue to become idle", timeoutMs = 8_000L) {
                ScreenProtectionAccessibilityService.isCapturePipelineIdleForTest()
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Debounced capture did not restore a localized mask. " +
                    "status=${ProtectionStatus.state.value}; captures=${fakeCaptureCount.get()}; " +
                    "inspections=${inspections.get()}; service=${ScreenProtectionAccessibilityService.diagnosticsForTest()}",
                failure
            )
        }
        val captureDelta = fakeCaptureCount.get() - beforeCaptures
        assertTrue(
            "A burst of 30 content events should coalesce into exactly one screenshot. " +
                "captureDelta=$captureDelta; inspectionsDelta=${inspections.get() - beforeInspections}; " +
                "service=${ScreenProtectionAccessibilityService.diagnosticsForTest()}",
            captureDelta == 1
        )
    }

    @Test
    fun repeatedModelFailuresStopAndClearAnExistingOverlay() {
        configureLongCaptureInterval()
        val inspections = AtomicInteger(0)
        // Keep normal inference successful until the test has observed the localized overlay.
        // The previous test failed after the first inspect() call, allowing a second framework-
        // initiated capture to trip the error before the test thread could observe the overlay.
        val injectFailures = AtomicBoolean(false)
        val detector = FakeDetector {
            inspections.incrementAndGet()
            if (injectFailures.get()) {
                error("injected model failure")
            }
            DetectionResult(
                listOf(DetectedMediaRegion(NormalizedBox(250, 250, 750, 750), ContentDecision.EXPLICIT, "image")),
                parseSucceeded = true
            )
        }
        installFakeRuntime(detector)
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        try {
            await("initial overlay before model failure injection", timeoutMs = 20_000L) {
                ProtectionStatus.state.value.active &&
                    ProtectionStatus.state.value.regionsRedacted == 1 &&
                    hasRedactionWindow()
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "A valid first inference did not create an overlay. inspections=${inspections.get()}; " +
                    "captures=${fakeCaptureCount.get()}; status=${ProtectionStatus.state.value}; " +
                    "service=${ScreenProtectionAccessibilityService.diagnosticsForTest()}",
                failure
            )
        }
        injectFailures.set(true)
        ScreenProtectionAccessibilityService.requestScreenshotForTest()
        await("model failure circuit breaker", timeoutMs = 12_000L) {
            !ProtectionStatus.state.value.active &&
                ProtectionStatus.state.value.message.contains("repeated model errors", ignoreCase = true)
        }
        assertTrue("The detector should have been called for initial success plus three failures", inspections.get() >= 4)
        assertFalse("Model failure must reset persisted protection", AppPreferences(context).isProtectionEnabled())
        assertFalse("Model failure must remove the existing localized overlay", hasRedactionWindow())
        assertFalse("Model failure must never leave an opaque cover", ProtectionStatus.state.value.opaqueCoverActive)
    }

    @Test
    fun repeatedCaptureFailuresStopAndClearAnExistingOverlay() {
        configureLongCaptureInterval()
        installFakeRuntime(resultDetector())
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        await("initial overlay before capture failure injection", timeoutMs = 20_000L) { hasRedactionWindow() }

        val beforeFailures = fakeCaptureCount.get()
        ScreenVeilTestHooks.frameProvider = {
            fakeCaptureCount.incrementAndGet()
            error("injected screenshot capture failure")
        }
        ScreenProtectionAccessibilityService.requestScreenshotForTest()
        await("capture failure circuit breaker", timeoutMs = 15_000L) {
            !ProtectionStatus.state.value.active &&
                ProtectionStatus.state.value.message.contains("repeated screen-capture errors", ignoreCase = true)
        }
        assertTrue("The capture provider should be exercised through the five-failure stop threshold", fakeCaptureCount.get() - beforeFailures >= 5)
        assertFalse(AppPreferences(context).isProtectionEnabled())
        assertFalse("Capture failures must remove the existing localized overlay", hasRedactionWindow())
        assertFalse(ProtectionStatus.state.value.opaqueCoverActive)
    }

    @Test
    fun androidSecureWindowScreenshotDenialPausesWithoutOverlay() {
        installFakeRuntime(resultDetector(), useSyntheticFrames = false)
        launchFixtureWindow(secure = true)
        try {
            await("fixture Activity to report focus and its actual FLAG_SECURE window attribute") {
                fixtureWindowReport().let { report ->
                    report.focused && report.secureFlagApplied
                }
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Secure fixture runtime report was not confirmed: ${fixtureWindowReport()}; " +
                    "resumedActivity=${resumedActivityDiagnostics()}",
                failure
            )
        }
        val secureReport = fixtureWindowReport()
        assertTrue(
            "The fixture Activity did not report FLAG_SECURE from its runtime Window attributes: $secureReport",
            secureReport.secureFlagApplied
        )
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        signalFixtureWindowAsForeground()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())

        try {
            await("Android FLAG_SECURE screenshot refusal pauses protection", timeoutMs = 20_000L) {
                !ProtectionStatus.state.value.active &&
                    ProtectionStatus.state.value.message.contains("FLAG_SECURE", ignoreCase = true)
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Secure-window test did not reach Android's secure-window refusal. " +
                    "status=${ProtectionStatus.state.value}; resumedActivity=${resumedActivityDiagnostics()}; " +
                    "service=${ScreenProtectionAccessibilityService.diagnosticsForTest()}",
                failure
            )
        }
        assertTrue(
            "Android should identify FLAG_SECURE as the screenshot refusal reason",
            ProtectionStatus.state.value.error.orEmpty().contains("FLAG_SECURE", ignoreCase = true)
        )
        assertFalse("A secure window must never cause a ScreenVeil cover", hasRedactionWindow())
        assertFalse(ProtectionStatus.state.value.opaqueCoverActive)
        assertFalse(AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun overlayRenderingFailureStopsProtectionAndClearsRenderer() {
        val failingOverlay = FailingOverlay(context)
        installFakeRuntime(resultDetector())
        ScreenVeilTestHooks.overlayFactory = { failingOverlay }
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())

        await("overlay failure safe stop", timeoutMs = 20_000L) {
            !ProtectionStatus.state.value.active &&
                ProtectionStatus.state.value.message.contains("overlay could not be updated", ignoreCase = true)
        }
        assertTrue("Safety stop must call overlay.clear()", failingOverlay.clearCalled)
        assertFalse(AppPreferences(context).isProtectionEnabled())
        assertFalse(ProtectionStatus.state.value.opaqueCoverActive)
    }

    @Test
    fun explicitStopWhileLocalizedOverlayIsVisibleRemovesItImmediately() {
        installFakeRuntime(resultDetector())
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        await("active localized overlay", timeoutMs = 20_000L) { hasRedactionWindow() }

        assertTrue(ScreenProtectionAccessibilityService.requestStop())
        await("active overlay removal", timeoutMs = 5_000L) { !hasRedactionWindow() && !ProtectionStatus.state.value.active }
        assertFalse(AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun accessibilityInterruptionStopsAndRemovesActiveOverlay() {
        installFakeRuntime(resultDetector())
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        await("active localized overlay", timeoutMs = 20_000L) { hasRedactionWindow() }

        ScreenProtectionAccessibilityService.interruptForTest()
        await("interruption safety stop", timeoutMs = 5_000L) {
            !ProtectionStatus.state.value.active && !hasRedactionWindow()
        }
        assertFalse(AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun disablingAccessibilityWhileOverlayIsVisibleRemovesTheOverlay() {
        installFakeRuntime(resultDetector())
        launchFixtureWindow()
        enableScreenVeilAccessibility()
        awaitAccessibilityServiceConnection()
        assertTrue(ScreenProtectionAccessibilityService.requestStart())
        await("active overlay before service disable", timeoutMs = 20_000L) { hasRedactionWindow() }

        val component = ComponentName(context, ScreenProtectionAccessibilityService::class.java)
        val configuredServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val remaining = configuredServices.split(':')
            .filter { it.isNotBlank() && !isSameComponent(it, component) }
        if (remaining.isEmpty()) {
            putSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null)
            putSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, "0")
        } else {
            putSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, remaining.joinToString(":"))
        }

        await("service unbind and overlay removal", timeoutMs = 10_000L) {
            !ScreenProtectionAccessibilityService.isServiceConnected() && !hasRedactionWindow()
        }
        assertFalse("Service unbind must leave persisted protection OFF", AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun overlayPreparationChangesOnlyTheDetectedRectangle() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val controller = OverlayController(context)
        val prepared = controller.prepare(
            frame = source,
            regions = listOf(
                DetectedMediaRegion(NormalizedBox(250, 250, 750, 750), ContentDecision.EXPLICIT, "image")
            ),
            settings = ModerationSettings(effect = RedactionEffect.OPAQUE_COVER, intensity = 18),
            screenBounds = Rect(0, 0, 100, 100)
        )
        assertNotNull("A valid local region should produce an overlay bitmap", prepared)
        val overlay = checkNotNull(prepared)
        val output = overlay.bitmap
        assertEquals("Outside the box must remain transparent", 0, Color.alpha(output.getPixel(10, 10)))
        assertEquals("Inside the box must be opaque redaction", Color.rgb(18, 22, 30), output.getPixel(50, 50))
        assertEquals("Outside the box must remain transparent on the opposite corner", 0, Color.alpha(output.getPixel(90, 90)))
        assertEquals(1, overlay.redactedRegions)
        output.recycle()
        source.recycle()
        controller.close()
    }

    @Test
    fun gaussianBlurChangesOnlyPixelsInsideTheDetectedRegion() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val checkerboard = IntArray(100 * 100) { index ->
            val x = index % 100
            val y = index / 100
            if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE
        }
        source.setPixels(checkerboard, 0, 100, 0, 0, 100, 100)
        val prepared = checkNotNull(
            OverlayController(context).prepare(
                frame = source,
                regions = listOf(
                    DetectedMediaRegion(NormalizedBox(250, 250, 750, 750), ContentDecision.EXPLICIT, "image")
                ),
                settings = ModerationSettings(effect = RedactionEffect.GAUSSIAN_BLUR, intensity = 18),
                screenBounds = Rect(0, 0, 100, 100)
            )
        )
        val blurred = prepared.bitmap
        val originalInside = source.getPixel(50, 50)
        val blurredInside = blurred.getPixel(50, 50)
        assertTrue("The selected high-frequency area should be blurred", originalInside != blurredInside)
        assertTrue("Blurred content should remain opaque inside the selected region", Color.alpha(blurredInside) > 240)
        assertEquals("Pixels outside the selected region must remain transparent", 0, Color.alpha(blurred.getPixel(10, 10)))
        assertEquals("The opposite outside corner must also remain transparent", 0, Color.alpha(blurred.getPixel(90, 90)))
        blurred.recycle()
        source.recycle()
    }

    @Test
    fun allowOnlyResultsDoNotProduceAnOverlayBitmap() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val prepared = OverlayController(context).prepare(
            frame = source,
            regions = listOf(
                DetectedMediaRegion(NormalizedBox(100, 100, 900, 900), ContentDecision.ALLOW, "image")
            ),
            settings = ModerationSettings(),
            screenBounds = Rect(0, 0, 64, 64)
        )
        assertEquals(null, prepared)
        source.recycle()
    }

    private fun installFakeRuntime(
        detector: ScreenContentDetector,
        useSyntheticFrames: Boolean = true
    ) {
        ScreenVeilTestHooks.detectorFactory = { _, _ -> detector }
        if (useSyntheticFrames) {
            ScreenVeilTestHooks.frameProvider = { bounds ->
                fakeCaptureCount.incrementAndGet()
                Bitmap.createBitmap(bounds.width().coerceAtLeast(2), bounds.height().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.rgb(48, 80, 112)) }
            }
        }
    }

    private fun resultDetector() = FakeDetector {
        DetectionResult(
            regions = listOf(
                DetectedMediaRegion(
                    box = NormalizedBox(250, 250, 750, 750),
                    decision = ContentDecision.EXPLICIT,
                    mediaType = "image"
                )
            ),
            parseSucceeded = true
        )
    }

    private fun configureLongCaptureInterval() {
        val prefs = AppPreferences(context)
        prefs.saveSettings(prefs.getSettings().copy(frameIntervalMs = 5_000L))
    }

    private fun launchFixtureWindow(secure: Boolean = false) {
        assertEquals(
            "Fixture IPC authority must match the installed instrumentation APK package",
            fixturePackage,
            ScreenVeilFixtureStateProvider.AUTHORITY.removeSuffix(".screenveil-fixture-state")
        )
        // The fixture is packaged in the instrumentation APK, separate from ScreenVeil's target
        // package. Use ActivityManager's wait mode and inspect ActivityTaskManager's resumed
        // Activity instead of parsing WindowManager's private dump format. On Android 13/14,
        // `dumpsys window windows` may list `Window #...` entries without an mCurrentFocus line.
        val resetResponse = instrumentation.context.contentResolver.call(
            ScreenVeilFixtureStateProvider.CONTENT_URI,
            ScreenVeilFixtureStateProvider.METHOD_RESET,
            null,
            null
        )
        assertTrue(
            "Could not reset the fixture state through its IPC provider",
            resetResponse?.getBoolean(ScreenVeilFixtureStateProvider.KEY_ACCEPTED, false) == true
        )
        val component = ComponentName(fixturePackage, ScreenVeilFixtureActivity::class.java.name)
        val secureExtra = if (secure) " --ez secure true" else " --ez secure false"
        // The fixture APK is reused across instrumentation tests. Clear its previous task so
        // every launch starts with the requested Intent extras rather than inheriting an older
        // Activity instance and its previous Window flags.
        val launchOutput = shell(
            "am start -W -n ${component.flattenToString()} --activity-clear-task --activity-new-task$secureExtra"
        )
        assertTrue(
            "ActivityManager did not report a successful fixture launch. " +
                "component=${component.flattenToString()}; output=$launchOutput",
            launchOutput.contains("Status: ok", ignoreCase = true)
        )
        try {
            await("test fixture activity to become the resumed foreground activity", timeoutMs = 10_000L) {
                isFixtureActivityResumed(component)
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Fixture launch returned successfully but it did not become the resumed activity. " +
                    "component=${component.flattenToString()}; launchOutput=$launchOutput; " +
                    "activityState=${resumedActivityDiagnostics()}",
                failure
            )
        }
        try {
            await("fixture Activity creation and window focus callback", timeoutMs = 10_000L) {
                fixtureWindowReport().let { it.created && it.focused }
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Fixture Activity launch succeeded but its runtime window report is incomplete: " +
                    "${fixtureWindowReport()}; resumedActivity=${resumedActivityDiagnostics()}",
                failure
            )
        }
        instrumentation.waitForIdleSync()
    }

    private data class FixtureWindowReport(
        val focused: Boolean,
        val secureFlagApplied: Boolean,
        val flags: Int,
        val created: Boolean
    )

    private fun fixtureWindowReport(): FixtureWindowReport {
        val state = instrumentation.context.contentResolver.call(
            ScreenVeilFixtureStateProvider.CONTENT_URI,
            ScreenVeilFixtureStateProvider.METHOD_READ,
            null,
            null
        ) ?: throw AssertionError("Fixture state ContentProvider returned no state bundle")
        return FixtureWindowReport(
            focused = state.getBoolean(ScreenVeilFixtureActivity.KEY_WINDOW_FOCUSED, false),
            secureFlagApplied = state.getBoolean(ScreenVeilFixtureActivity.KEY_SECURE_FLAG_APPLIED, false),
            flags = state.getInt(ScreenVeilFixtureActivity.KEY_WINDOW_FLAGS, 0),
            created = state.getBoolean(ScreenVeilFixtureActivity.KEY_ACTIVITY_CREATED, false)
        )
    }

    private fun isFixtureActivityResumed(component: ComponentName): Boolean {
        val dump = shell("dumpsys activity activities")
        return dump.lineSequence().any { line ->
            val isResumedRecord = line.contains("topResumedActivity", ignoreCase = true) ||
                line.contains("mResumedActivity", ignoreCase = true) ||
                line.contains("mFocusedActivity", ignoreCase = true)
            isResumedRecord &&
                line.contains(component.packageName) &&
                line.contains(component.className)
        }
    }

    private fun resumedActivityDiagnostics(): String =
        shell("dumpsys activity activities").lineSequence()
            .filter { line ->
                line.contains("topResumedActivity", ignoreCase = true) ||
                    line.contains("mResumedActivity", ignoreCase = true) ||
                    line.contains("mFocusedActivity", ignoreCase = true)
            }
            .take(20)
            .joinToString("\n")
            .ifBlank { "No top/resumed/focused activity marker in dumpsys activity activities" }

    /**
     * The fixture is launched before Accessibility is enabled. Deliver the equivalent
     * window-state notification after binding so the service's foreground-package guard is
     * unambiguously pointed at the external fixture rather than the app that launched tests.
     */
    private fun signalFixtureWindowAsForeground() {
        instrumentation.runOnMainSync {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.packageName = fixturePackage
            try {
                ScreenProtectionAccessibilityService.dispatchAccessibilityEventForTest(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun enableScreenVeilAccessibility() {
        val component = ComponentName(
            context,
            ScreenProtectionAccessibilityService::class.java
        )
        val existing = originalEnabledServices.orEmpty()
            .split(':')
            .filter { it.isNotBlank() && !isSameComponent(it, component) }
        val services = (existing + component.flattenToString()).joinToString(":")

        // Write the secure settings through the framework API while adopting only the
        // shell permission required by this instrumentation test. Do not shell-quote
        // values: UiAutomation command execution does not interpret shell quoting.
        putSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
        putSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")

        val rawConfiguredServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val configuredServices = rawConfiguredServices.split(':').filter { it.isNotBlank() }
        assertTrue(
            "Test setup did not enable the ScreenVeil service component. " +
                "Expected=${component.flattenToString()}; rawSetting=$rawConfiguredServices; " +
                "parsedServices=$configuredServices",
            configuredServices.any { isSameComponent(it, component) }
        )
        assertEquals(
            "Global Accessibility setting did not become enabled",
            "1",
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        )
    }

    private fun hasRedactionWindow(): Boolean =
        shell("dumpsys window windows").contains("ScreenVeil redaction", ignoreCase = true)

    private fun awaitAccessibilityServiceConnection() {
        try {
            await("Accessibility service connection", timeoutMs = 20_000L) {
                ScreenProtectionAccessibilityService.isServiceConnected()
            }
        } catch (failure: AssertionError) {
            val enabledServices = shell("settings get secure enabled_accessibility_services").trim()
            val accessibilityEnabled = shell("settings get secure accessibility_enabled").trim()
            val accessibilityDump = shell("dumpsys accessibility").takeLast(12_000)
            throw AssertionError(
                "Timed out waiting for ScreenVeil AccessibilityService. enabled_accessibility_services=$enabledServices; " +
                    "accessibility_enabled=$accessibilityEnabled; dumpsys accessibility (tail):\n$accessibilityDump",
                failure
            )
        }
    }

    private fun await(description: String, timeoutMs: Long = 12_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun restoreSecureSetting(key: String, value: String?) {
        putSecureSetting(key, value?.takeUnless { it.isBlank() })
    }

    /**
     * Writes one Secure setting using only the shell permission needed by the test.
     * Null removes the setting through the `settings delete` command; non-null values
     * use Settings.Secure directly so punctuation is preserved byte-for-byte.
     */
    private fun putSecureSetting(key: String, value: String?) {
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            if (value == null) {
                shell("settings delete secure $key")
                assertEquals("Secure setting $key should be absent after deletion", null,
                    Settings.Secure.getString(context.contentResolver, key))
            } else {
                assertTrue(
                    "Unable to write secure setting $key",
                    Settings.Secure.putString(context.contentResolver, key, value)
                )
                assertEquals(
                    "Secure setting $key did not round-trip accurately",
                    value,
                    Settings.Secure.getString(context.contentResolver, key)
                )
            }
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun isSameComponent(value: String, expected: ComponentName): Boolean {
        val parsed = ComponentName.unflattenFromString(value.trim()) ?: return false
        return parsed == expected
    }

    private fun shell(command: String): String {
        val descriptor = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private class FakeDetector(
        private val inspectBlock: (Bitmap) -> DetectionResult
    ) : ScreenContentDetector {
        override var activeBackend: String = "instrumentation-fake"
            private set

        override fun initialize(modelFiles: ModelFiles) = Unit
        override fun inspect(bitmap: Bitmap): DetectionResult = inspectBlock(bitmap)
        override fun close() = Unit
    }

    private class FailingOverlay(context: Context) : OverlayController(context) {
        @Volatile var clearCalled = false
            private set

        override fun prepare(
            frame: Bitmap,
            regions: List<DetectedMediaRegion>,
            settings: ModerationSettings,
            screenBounds: Rect
        ): OverlayController.PreparedOverlay = OverlayController.PreparedOverlay(
            bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
            screenBounds = Rect(screenBounds),
            redactedRegions = regions.size
        )

        override fun showPrepared(prepared: OverlayController.PreparedOverlay?): Boolean = false
        override fun clear() { clearCalled = true }
        override fun close() { clear() }
    }
}

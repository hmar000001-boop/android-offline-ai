package dev.screenveil.app.capture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.screenveil.app.AppPreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android emulator. These tests deliberately do not download models.
 * They verify the high-risk service-binding/start/failure/stop lifecycle with a clean install.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private var originalEnabledServices: String? = null
    private var originalAccessibilityEnabled: String? = null

    @Before
    fun setUp() {
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
        ScreenProtectionAccessibilityService.requestStop()
        restoreSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalEnabledServices)
        restoreSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, originalAccessibilityEnabled)
        val component = ComponentName(context, ScreenProtectionAccessibilityService::class.java).flattenToString()
        val wasPreviouslyEnabled = originalEnabledServices.orEmpty().split(':').any { it.equals(component, true) }
        if (!wasPreviouslyEnabled) {
            await("Accessibility service unbind", timeoutMs = 5_000L) {
                !ScreenProtectionAccessibilityService.isServiceConnected()
            }
        }
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
        await("Accessibility service connection") {
            ScreenProtectionAccessibilityService.isServiceConnected()
        }

        val state = ProtectionStatus.state.value
        assertFalse("Accessibility binding must not start monitoring", state.active)
        assertFalse("Accessibility binding must not show an opaque cover", state.opaqueCoverActive)
        assertFalse("No ScreenVeil overlay window may exist after binding", hasRedactionWindow())
        assertFalse("Binding must leave the persisted protection switch OFF", AppPreferences(context).isProtectionEnabled())
    }

    @Test
    fun startWithoutVerifiedModelsStopsSafelyAndLeavesNoOverlay() {
        enableScreenVeilAccessibility()
        await("Accessibility service connection") {
            ScreenProtectionAccessibilityService.isServiceConnected()
        }

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
    fun explicitStopIsIdempotentAndRemovesAnyRedactionWindow() {
        enableScreenVeilAccessibility()
        await("Accessibility service connection") {
            ScreenProtectionAccessibilityService.isServiceConnected()
        }

        assertTrue(ScreenProtectionAccessibilityService.requestStop())
        await("stopped status") { !ProtectionStatus.state.value.active }
        assertFalse(ProtectionStatus.state.value.opaqueCoverActive)
        assertFalse(hasRedactionWindow())
        assertFalse(AppPreferences(context).isProtectionEnabled())
    }

    private fun enableScreenVeilAccessibility() {
        val component = ComponentName(
            context,
            ScreenProtectionAccessibilityService::class.java
        ).flattenToString()
        val existing = originalEnabledServices.orEmpty()
            .split(':')
            .filter { it.isNotBlank() && !it.equals(component, ignoreCase = true) }
        val services = (existing + component).joinToString(":")
        shell("settings put secure enabled_accessibility_services '${shellQuote(services)}'")
        shell("settings put secure accessibility_enabled 1")
    }

    private fun hasRedactionWindow(): Boolean =
        shell("dumpsys window windows").contains("ScreenVeil redaction", ignoreCase = true)

    private fun await(description: String, timeoutMs: Long = 12_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun restoreSecureSetting(key: String, value: String?) {
        if (value.isNullOrBlank()) shell("settings delete secure $key")
        else shell("settings put secure $key '${shellQuote(value)}'")
    }

    private fun shellQuote(value: String): String = value.replace("'", "'\\''")

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}

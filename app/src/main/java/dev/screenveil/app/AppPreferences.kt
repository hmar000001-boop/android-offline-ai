package dev.screenveil.app

import android.content.Context
import dev.screenveil.app.policy.ModerationSettings
import dev.screenveil.app.policy.RedactionEffect
import dev.screenveil.app.model.ModelProfile

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("screenveil_preferences", Context.MODE_PRIVATE)

    init {
        migrateProtectionStartupDefault()
    }

    /** A previous release could leave protection enabled across Accessibility activation/rebind.
     * Force a one-time opt-out when upgrading so an app update can never re-enable the overlay
     * without a fresh, explicit Start action from the user.
     */
    private fun migrateProtectionStartupDefault() {
        val storedVersion = prefs.getInt(KEY_SAFETY_MIGRATION_VERSION, 0)
        if (storedVersion < SAFETY_MIGRATION_VERSION) {
            prefs.edit()
                .putBoolean(KEY_PROTECTION_ENABLED, false)
                .putInt(KEY_SAFETY_MIGRATION_VERSION, SAFETY_MIGRATION_VERSION)
                .apply()
        }
    }

    fun getSettings(): ModerationSettings = ModerationSettings(
        effect = runCatching {
            RedactionEffect.valueOf(prefs.getString(KEY_EFFECT, RedactionEffect.GAUSSIAN_BLUR.name)!!)
        }.getOrDefault(RedactionEffect.GAUSSIAN_BLUR),
        intensity = prefs.getInt(KEY_INTENSITY, 18).coerceIn(4, 32),
        frameIntervalMs = prefs.getLong(KEY_INTERVAL, 1_500L).coerceIn(MIN_CAPTURE_INTERVAL_MS, 5_000L),
        redactUncertain = prefs.getBoolean(KEY_UNCERTAIN, true)
    )

    fun isProtectionEnabled(): Boolean = prefs.getBoolean(KEY_PROTECTION_ENABLED, false)

    fun getModelProfile(): ModelProfile = ModelProfile.fromStored(prefs.getString(KEY_MODEL_PROFILE, ModelProfile.BALANCED_SPECIALIZED.name))

    fun setModelProfile(profile: ModelProfile) {
        prefs.edit().putString(KEY_MODEL_PROFILE, profile.name).apply()
    }

    fun setProtectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()
    }

    fun saveSettings(settings: ModerationSettings) {
        prefs.edit()
            .putString(KEY_EFFECT, settings.effect.name)
            .putInt(KEY_INTENSITY, settings.intensity.coerceIn(4, 32))
            .putLong(KEY_INTERVAL, settings.frameIntervalMs.coerceIn(MIN_CAPTURE_INTERVAL_MS, 5_000L))
            .putBoolean(KEY_UNCERTAIN, settings.redactUncertain)
            .apply()
    }

    companion object {
        private const val KEY_EFFECT = "redaction_effect"
        private const val KEY_INTENSITY = "redaction_intensity"
        private const val KEY_INTERVAL = "frame_interval_ms"
        private const val KEY_UNCERTAIN = "redact_uncertain"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"
        private const val KEY_MODEL_PROFILE = "model_profile"
        private const val KEY_SAFETY_MIGRATION_VERSION = "safety_migration_version"
        private const val SAFETY_MIGRATION_VERSION = 1
        private const val MIN_CAPTURE_INTERVAL_MS = 750L
    }
}

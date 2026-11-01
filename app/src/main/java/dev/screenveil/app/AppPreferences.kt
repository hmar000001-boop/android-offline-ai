package dev.screenveil.app

import android.content.Context
import dev.screenveil.app.policy.ModerationSettings
import dev.screenveil.app.policy.RedactionEffect

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("screenveil_preferences", Context.MODE_PRIVATE)

    fun getSettings(): ModerationSettings = ModerationSettings(
        effect = runCatching {
            RedactionEffect.valueOf(prefs.getString(KEY_EFFECT, RedactionEffect.GAUSSIAN_BLUR.name)!!)
        }.getOrDefault(RedactionEffect.GAUSSIAN_BLUR),
        intensity = prefs.getInt(KEY_INTENSITY, 18).coerceIn(4, 32),
        frameIntervalMs = prefs.getLong(KEY_INTERVAL, 1_500L).coerceIn(1_000L, 5_000L),
        redactUncertain = prefs.getBoolean(KEY_UNCERTAIN, true)
    )

    fun saveSettings(settings: ModerationSettings) {
        prefs.edit()
            .putString(KEY_EFFECT, settings.effect.name)
            .putInt(KEY_INTENSITY, settings.intensity.coerceIn(4, 32))
            .putLong(KEY_INTERVAL, settings.frameIntervalMs.coerceIn(1_000L, 5_000L))
            .putBoolean(KEY_UNCERTAIN, settings.redactUncertain)
            .apply()
    }

    companion object {
        private const val KEY_EFFECT = "redaction_effect"
        private const val KEY_INTENSITY = "redaction_intensity"
        private const val KEY_INTERVAL = "frame_interval_ms"
        private const val KEY_UNCERTAIN = "redact_uncertain"
    }
}

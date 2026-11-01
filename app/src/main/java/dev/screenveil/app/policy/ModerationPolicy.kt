package dev.screenveil.app.policy

/** Categories are produced by the visual model; policy remains deterministic and user-owned. */
enum class ContentDecision {
    ALLOW,
    SWIMWEAR,
    SUGGESTIVE,
    EXPLICIT,
    UNCERTAIN
}

enum class RedactionEffect(val label: String) {
    GAUSSIAN_BLUR("Gaussian blur"),
    PIXELATION("Pixelation"),
    OPAQUE_COVER("Opaque cover")
}

data class ModerationSettings(
    val effect: RedactionEffect = RedactionEffect.GAUSSIAN_BLUR,
    val intensity: Int = 18,
    val frameIntervalMs: Long = 1_500L,
    val redactUncertain: Boolean = true
)

object ModerationPolicy {
    /** Swimwear is always redacted, regardless of scene or context, per the configured policy. */
    fun shouldRedact(decision: ContentDecision, redactUncertain: Boolean): Boolean = when (decision) {
        ContentDecision.ALLOW -> false
        ContentDecision.SWIMWEAR,
        ContentDecision.SUGGESTIVE,
        ContentDecision.EXPLICIT -> true
        ContentDecision.UNCERTAIN -> redactUncertain
    }
}

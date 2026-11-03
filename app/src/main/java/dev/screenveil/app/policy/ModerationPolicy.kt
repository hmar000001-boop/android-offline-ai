package dev.screenveil.app.policy

/** Categories are produced by the visual model; policy remains deterministic and user-owned. */
enum class ContentDecision {
    ALLOW,
    SWIMWEAR,
    SUGGESTIVE,
    EXPLICIT,
    UNCERTAIN,

    /**
     * A person was detected but both general NSFW classifiers scored it low. This is NOT a clothing
     * or swimwear verdict. It is covered only when the user enables "Cover every detected person".
     */
    PERSON_LOW_RISK
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
    val redactUncertain: Boolean = true,
    val redactAllPeople: Boolean = false
)

object ModerationPolicy {
    /** A SWIMWEAR decision is redacted if a future validated detector supplies that decision. */
    fun shouldRedact(
        decision: ContentDecision,
        redactUncertain: Boolean,
        redactAllPeople: Boolean = false
    ): Boolean = when (decision) {
        ContentDecision.ALLOW -> false
        ContentDecision.SWIMWEAR,
        ContentDecision.SUGGESTIVE,
        ContentDecision.EXPLICIT -> true
        ContentDecision.UNCERTAIN -> redactUncertain
        ContentDecision.PERSON_LOW_RISK -> redactAllPeople
    }
}

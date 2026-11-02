package dev.screenveil.app.policy

/** Shared score-threshold semantics for the general NSFW classifiers. */
object SpecialistDecisionPolicy {
    fun isNsfwFlagged(score: Float, threshold: Float): Boolean =
        score.isFinite() && threshold.isFinite() && score >= threshold
}

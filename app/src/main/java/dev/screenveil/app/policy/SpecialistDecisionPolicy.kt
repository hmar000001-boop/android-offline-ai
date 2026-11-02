package dev.screenveil.app.policy

/** Conservative interpretation of the limited five-class clothing model. */
object SpecialistDecisionPolicy {
    fun clothingDecision(label: String, confidence: Float): ContentDecision {
        if (!confidence.isFinite() || confidence !in 0f..1f) return ContentDecision.UNCERTAIN
        return when (label) {
            "swim_bikini" -> if (confidence >= 0.52f) ContentDecision.SWIMWEAR else ContentDecision.UNCERTAIN
            "lingerie" -> if (confidence >= 0.58f) ContentDecision.SUGGESTIVE else ContentDecision.UNCERTAIN
            // The model has no normal/other class. These labels cannot prove that content is safe.
            "denim_shorts", "maid", "nurse" -> ContentDecision.UNCERTAIN
            else -> ContentDecision.UNCERTAIN
        }
    }

    fun isNsfwFlagged(score: Float, threshold: Float): Boolean =
        score.isFinite() && threshold.isFinite() && score >= threshold
}

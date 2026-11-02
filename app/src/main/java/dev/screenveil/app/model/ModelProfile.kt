package dev.screenveil.app.model

enum class ModelProfile(
    val title: String,
    val description: String,
    val requiredModels: List<ApprovedModelArtifact>
) {
    BALANCED_SPECIALIZED(
        title = "Balanced multi-model · offline",
        description = "One person detector + two independent general NSFW classifiers, including a tiled fallback. No Gemma. This catalog has no validated swimwear-specific classifier; uncertain person regions are redacted when the conservative setting is enabled.",
        requiredModels = SpecializedModelCatalog.ensembleProfileModels
    ),
    FAST_SPECIALIZED(
        title = "Fast specialized · offline",
        description = "One person detector + one general NSFW classifier. No Gemma and no tile fallback. It does not classify clothing or guarantee swimwear detection.",
        requiredModels = SpecializedModelCatalog.fastProfileModels
    ),
    GEMMA_VISION(
        title = "Gemma 4 vision · optional",
        description = "General-purpose multimodal screenshot analysis. Large download, higher memory use and slower startup; separate from both specialist profiles.",
        requiredModels = listOf(GemmaModelCatalog.selected)
    );

    companion object {
        fun fromStored(value: String?): ModelProfile =
            entries.firstOrNull { it.name == value } ?: BALANCED_SPECIALIZED
    }
}

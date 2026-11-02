package dev.screenveil.app.model

enum class ModelProfile(
    val title: String,
    val description: String,
    val requiredModels: List<ApprovedModelArtifact>,
    val selectable: Boolean = true,
    val availabilityNotice: String? = null
) {
    BALANCED_SPECIALIZED(
        title = "Balanced multi-model · offline",
        description = "One EfficientDet person detector + two independent general NSFW classifiers, including a tiled fallback. No Gemma. This profile has no validated swimwear-specific detector; uncertain person regions are redacted when the conservative setting is enabled.",
        requiredModels = SpecializedModelCatalog.ensembleProfileModels
    ),
    FAST_SPECIALIZED(
        title = "Fast specialized · offline",
        description = "One EfficientDet person detector + one general NSFW classifier. No Gemma and no tile fallback. It does not classify clothing or guarantee swimwear detection.",
        requiredModels = SpecializedModelCatalog.fastProfileModels
    ),
    SWIMWEAR_AWARE(
        title = "Swimwear-aware · Google Open Images (validation pending)",
        description = "Planned separate detector profile using Google's SSD MobileNet V2 trained on Open Images V4, whose label set includes Swimwear and Person.",
        // Deliberately empty: the Google checkpoint has not yet been converted into a verified,
        // hash-pinned Android TFLite artifact. This profile must not reach download or inference.
        requiredModels = emptyList(),
        selectable = false,
        availabilityNotice = "Not installable yet. The Google-published checkpoint is a SavedModel, not a verified Android TFLite artifact. Conversion, output/label validation, exact SHA-256 pinning and Galaxy S24 FE testing are still required. Existing EfficientDet profiles remain available."
    ),
    GEMMA_VISION(
        title = "Gemma 4 vision · optional",
        description = "General-purpose multimodal screenshot analysis. Large download, higher memory use and slower startup; separate from both specialist profiles.",
        requiredModels = listOf(GemmaModelCatalog.selected)
    );

    companion object {
        fun fromStored(value: String?): ModelProfile =
            entries.firstOrNull { it.name == value && it.selectable } ?: BALANCED_SPECIALIZED
    }
}

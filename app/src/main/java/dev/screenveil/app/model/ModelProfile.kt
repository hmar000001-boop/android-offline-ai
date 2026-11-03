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
        description = "EfficientDet + two general NSFW classifiers + Google six-class human segmentation. Detection decides whether to redact; the pixel mask blurs body-skin and clothing while leaving background, hair and face visible. No Gemma. Swimwear-specific recognition is not yet validated.",
        requiredModels = SpecializedModelCatalog.ensembleProfileModels
    ),
    FAST_SPECIALIZED(
        title = "Fast specialized · offline",
        description = "EfficientDet + one general NSFW classifier + Google six-class human segmentation. Redaction is restricted to body-skin and clothing pixels, not the person's rectangle. No Gemma or tile fallback. Swimwear-specific recognition is not validated.",
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
        description = "Experimental multimodal screenshot analysis plus the same body/clothing pixel segmentation and hard overlay safety limits. Large download, higher memory use and slower startup. Visible text can steer a language model; prefer Balanced.",
        requiredModels = listOf(GemmaModelCatalog.selected, SpecializedModelCatalog.bodySegmenter)
    );

    companion object {
        fun fromStored(value: String?): ModelProfile =
            entries.firstOrNull { it.name == value && it.selectable } ?: BALANCED_SPECIALIZED
    }
}

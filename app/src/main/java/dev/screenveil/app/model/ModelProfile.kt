package dev.screenveil.app.model

enum class ModelProfile(
    val title: String,
    val description: String,
    val requiredModels: List<TrustedModel>
) {
    BALANCED_SPECIALIZED(
        title = "Balanced multi-model · offline",
        description = "Person detection + swimwear/lingerie classifier + two independent NSFW classifiers. No Gemma; higher CPU and battery use than Fast.",
        requiredModels = SpecializedModelCatalog.ensembleProfileModels
    ),
    FAST_SPECIALIZED(
        title = "Fast specialized · offline",
        description = "Person detection + swimwear/lingerie classifier + one NSFW classifier. No Gemma; smaller download and lower compute cost.",
        requiredModels = SpecializedModelCatalog.fastProfileModels
    ),
    GEMMA_VISION(
        title = "Gemma 4 vision",
        description = "General-purpose multimodal screenshot analysis. Large download, high memory use and slower startup.",
        requiredModels = listOf(GemmaModelCatalog.selected)
    );

    companion object {
        fun fromStored(value: String?): ModelProfile =
            entries.firstOrNull { it.name == value } ?: BALANCED_SPECIALIZED
    }
}

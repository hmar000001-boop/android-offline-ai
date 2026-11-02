package dev.screenveil.app.model

/** Immutable allow-list of pretrained model artifacts. Every downloaded file is SHA-256 checked. */
data class TrustedModel(
    val id: String,
    val displayName: String,
    val repository: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val minimumBytes: Long,
    val license: String,
    val sourceUrl: String,
    val runtime: String
)

object GemmaModelCatalog {
    val selected: TrustedModel = TrustedModel(
        id = ModelIds.GEMMA,
        displayName = "Gemma 4 E2B Instruct (LiteRT-LM)",
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minimumBytes = 2_588_147_712L,
        license = "Apache-2.0",
        sourceUrl = "https://huggingface.co/litert-community/Gemma-4-E2B-it-litert-lm",
        runtime = "LiteRT-LM"
    )
}

object ModelIds {
    const val GEMMA = "gemma-4-e2b-it-litert-lm"
    const val PERSON_DETECTOR = "mediapipe-efficientdet-lite0-f32"
    const val CLOTHING_CLASSIFIER = "electblake-clothing-efficientnetv2s"
    const val OPEN_NSFW = "yahoo-open-nsfw-onnx"
    const val VIT_NSFW = "marqo-vit-tiny-nsfw-onnx"
}

object SpecializedModelCatalog {
    val personDetector = TrustedModel(
        id = ModelIds.PERSON_DETECTOR,
        displayName = "EfficientDet-Lite0 · Person detector",
        repository = "Google AI Edge / MediaPipe",
        fileName = "efficientdet_lite0.tflite",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite",
        sha256 = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58",
        minimumBytes = 13_836_895L,
        license = "Apache-2.0",
        sourceUrl = "https://developers.google.com/edge/mediapipe/solutions/vision/object_detector",
        runtime = "MediaPipe Tasks Vision"
    )

    val clothingClassifier = TrustedModel(
        id = ModelIds.CLOTHING_CLASSIFIER,
        displayName = "Clothing Type EfficientNetV2-S",
        repository = "electblake/clothing_type_classifier",
        fileName = "clothing_type-tf_efficientnetv2_s.in21k_ft_in1k.onnx",
        downloadUrl = "https://huggingface.co/electblake/clothing_type_classifier/resolve/31d25e7/clothing_type-tf_efficientnetv2_s.in21k_ft_in1k.onnx",
        sha256 = "52b486a66845593d16f1e743dea9ceff2ed5427daf476cfd523fb41290265150",
        minimumBytes = 80_000_000L,
        license = "MIT (classifier); Apache-2.0 (base model)",
        sourceUrl = "https://huggingface.co/electblake/clothing_type_classifier/tree/31d25e7",
        runtime = "ONNX Runtime"
    )

    val openNsfw = TrustedModel(
        id = ModelIds.OPEN_NSFW,
        displayName = "Yahoo OpenNSFW · ResNet-50",
        repository = "gawryco/opennsfw-onnx (Yahoo weights)",
        fileName = "open-nsfw.onnx",
        downloadUrl = "https://github.com/gawryco/opennsfw-onnx/releases/download/model-v1/open-nsfw.onnx",
        sha256 = "864bb37bf8863564b87eb330ab8c785a79a773f4e7c43cb96db52ed8611305fa",
        minimumBytes = 23_590_724L,
        license = "BSD-2-Clause (Yahoo weights)",
        sourceUrl = "https://github.com/gawryco/opennsfw-onnx/releases/tag/model-v1",
        runtime = "ONNX Runtime"
    )

    val vitNsfwClassifier = TrustedModel(
        id = ModelIds.VIT_NSFW,
        displayName = "ViT-Tiny NSFW · Marqo (ONNX)",
        repository = "KanariKanaru/nsfw-image-detection-384-onnx",
        fileName = "vit-tiny-nsfw-384.onnx",
        downloadUrl = "https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/resolve/ab04f01/model.onnx",
        sha256 = "e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2",
        minimumBytes = 22_000_000L,
        license = "Apache-2.0",
        sourceUrl = "https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/tree/ab04f01",
        runtime = "ONNX Runtime"
    )

    // Three-model profile for lower latency/storage. The balanced profile adds an independent ViT.
    val fastProfileModels = listOf(personDetector, clothingClassifier, openNsfw)
    val ensembleProfileModels = fastProfileModels + vitNsfwClassifier
    val all = listOf(GemmaModelCatalog.selected) + ensembleProfileModels
}

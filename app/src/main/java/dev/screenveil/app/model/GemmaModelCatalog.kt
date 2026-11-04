package dev.screenveil.app.model

/**
 * Explicit allow-list of pretrained model artifacts.
 *
 * Download URLs point at immutable revisions/releases where possible. Both lower and upper
 * byte bounds are enforced before a SHA-256 match is accepted. A matching hash establishes file
 * integrity against this catalog; it is not a security audit or an accuracy certificate.
 */
data class ApprovedModelArtifact(
    val id: String,
    val displayName: String,
    val repository: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val minimumBytes: Long,
    val maximumBytes: Long,
    val license: String,
    val sourceUrl: String,
    val runtime: String
)

object GemmaModelCatalog {
    val selected: ApprovedModelArtifact = ApprovedModelArtifact(
        id = ModelIds.GEMMA,
        displayName = "Gemma 4 E2B Instruct (LiteRT-LM)",
        repository = "litert-community/Gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minimumBytes = 2_588_147_712L,
        maximumBytes = 2_700_000_000L,
        license = "Apache-2.0 (Gemma 4)",
        sourceUrl = "https://ai.google.dev/gemma/docs/core",
        runtime = "LiteRT-LM"
    )
}

object ModelIds {
    const val GEMMA = "gemma-4-e2b-it-litert-lm"
    const val PERSON_DETECTOR = "google-efficientdet-lite0-f32"
    const val OPEN_NSFW = "yahoo-open-nsfw-onnx"
    const val VIT_NSFW = "marqo-vit-tiny-nsfw-onnx"
    const val BODY_SEGMENTER = "google-selfie-multiclass-256-tflite"
}

object SpecializedModelCatalog {
    val personDetector = ApprovedModelArtifact(
        id = ModelIds.PERSON_DETECTOR,
        displayName = "Google EfficientDet-Lite0 · Person detector",
        repository = "Google AI Edge model storage",
        fileName = "efficientdet_lite0.tflite",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite",
        sha256 = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58",
        minimumBytes = 13_836_895L,
        maximumBytes = 20_000_000L,
        license = "Apache-2.0",
        sourceUrl = "https://developers.google.com/edge/mediapipe/solutions/vision/object_detector",
        runtime = "LiteRT Interpreter CPU (local)"
    )

    val openNsfw = ApprovedModelArtifact(
        id = ModelIds.OPEN_NSFW,
        displayName = "Yahoo OpenNSFW · ResNet-50",
        repository = "gawryco/opennsfw-onnx (Yahoo weights)",
        fileName = "open-nsfw.onnx",
        downloadUrl = "https://github.com/gawryco/opennsfw-onnx/releases/download/model-v1/open-nsfw.onnx",
        sha256 = "864bb37bf8863564b87eb330ab8c785a79a773f4e7c43cb96db52ed8611305fa",
        minimumBytes = 23_590_724L,
        maximumBytes = 26_000_000L,
        license = "BSD-2-Clause (Yahoo weights); conversion notices in THIRD_PARTY_NOTICES.md",
        sourceUrl = "https://github.com/gawryco/opennsfw-onnx/releases/tag/model-v1",
        runtime = "ONNX Runtime"
    )

    val vitNsfwClassifier = ApprovedModelArtifact(
        id = ModelIds.VIT_NSFW,
        displayName = "ViT-Tiny NSFW · Marqo-derived (ONNX)",
        repository = "KanariKanaru/nsfw-image-detection-384-onnx",
        fileName = "vit-tiny-nsfw-384.onnx",
        downloadUrl = "https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/resolve/ab04f01/model.onnx",
        sha256 = "e9350e576608afe4b57a089ffeb0ebafa1389cdcea4882dd61df28c45f1c24d2",
        minimumBytes = 22_000_000L,
        maximumBytes = 30_000_000L,
        license = "Apache-2.0 (as declared by the ONNX repository)",
        sourceUrl = "https://huggingface.co/KanariKanaru/nsfw-image-detection-384-onnx/tree/ab04f01",
        runtime = "ONNX Runtime"
    )

    val bodySegmenter = ApprovedModelArtifact(
        id = ModelIds.BODY_SEGMENTER,
        displayName = "Google Selfie Multiclass · body/clothing segmentation",
        repository = "Google MediaPipe SelfieMulticlass 256x256",
        fileName = "selfie_multiclass_256x256.tflite",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite",
        // SHA-256 independently recorded for the same named upstream model artifact in multiple
        // verified mirrors. CI downloads the official Google URL and must match this exact digest.
        sha256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0",
        minimumBytes = 16_000_000L,
        maximumBytes = 17_000_000L,
        license = "Apache-2.0 (Google MediaPipe model card)",
        sourceUrl = "https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter",
        runtime = "LiteRT Interpreter CPU (local) · local category-mask segmentation"
    )

    // Every profile requires the same local human segmentation stage. Detectors decide whether
    // content is sensitive; segmentation only limits the overlay to body-skin and clothing pixels.
    // Fast uses one NSFW classifier; Balanced adds ViT and its tile fallback. Neither needs Gemma.
    val fastProfileModels = listOf(personDetector, openNsfw, bodySegmenter)
    val ensembleProfileModels = listOf(personDetector, openNsfw, vitNsfwClassifier, bodySegmenter)
    val all = listOf(GemmaModelCatalog.selected) + ensembleProfileModels
}

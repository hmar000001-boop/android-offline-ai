package dev.screenveil.app.model

/** Immutable allow-list entry. New models must be reviewed and added with a pinned hash. */
data class TrustedModel(
    val id: String,
    val displayName: String,
    val repository: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val minimumBytes: Long,
    val license: String,
    val sourceUrl: String
)

object GemmaModelCatalog {
    val selected: TrustedModel = TrustedModel(
        id = "gemma-4-e2b-it-litert-lm",
        displayName = "Gemma 4 E2B Instruct (LiteRT-LM)",
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
        // SHA-256 published by the Hugging Face file page for this exact artifact.
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        // The upstream file listing displays 2.59 GB. SHA-256 is the authoritative integrity check.
        minimumBytes = 2_588_147_712L,
        license = "Apache-2.0",
        sourceUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
    )
}

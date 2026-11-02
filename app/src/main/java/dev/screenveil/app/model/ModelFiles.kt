package dev.screenveil.app.model

import java.io.File

/** Resolved artifact set. Required entries fail loudly; optional entries are used only when present. */
data class ModelFiles(private val filesById: Map<String, File>) {
    fun require(modelId: String): File =
        find(modelId) ?: error("Required model artifact is unavailable: $modelId")

    fun find(modelId: String): File? = filesById[modelId]?.takeIf(File::isFile)
}

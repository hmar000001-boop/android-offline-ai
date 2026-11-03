package dev.screenveil.app.model

import android.content.Context

/** Removes model-input screenshots that a crash or process kill left in the private cache. */
object TemporaryFrameFiles {
    fun removeAbandoned(context: Context) {
        context.cacheDir.listFiles()
            ?.filter { it.isFile && (it.name.startsWith("screen-frame-") || it.name.startsWith("screenveil-frame-")) }
            ?.forEach { runCatching { it.delete() } }
    }
}

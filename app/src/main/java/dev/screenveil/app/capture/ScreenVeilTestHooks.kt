package dev.screenveil.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import dev.screenveil.app.model.ModelProfile
import dev.screenveil.app.model.ScreenContentDetector
import dev.screenveil.app.overlay.OverlayController

/**
 * Debug-build-only dependency seams used by Android instrumentation tests.
 * Production/release builds never consult these overrides.
 */
internal object ScreenVeilTestHooks {
    @Volatile var detectorFactory: ((Context, ModelProfile) -> ScreenContentDetector)? = null
    @Volatile var frameProvider: ((Rect) -> Bitmap)? = null
    @Volatile var overlayFactory: ((Context) -> OverlayController)? = null

    fun reset() {
        detectorFactory = null
        frameProvider = null
        overlayFactory = null
    }
}

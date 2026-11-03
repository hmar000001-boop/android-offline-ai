package dev.screenveil.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import dev.screenveil.app.model.ModelProfile
import dev.screenveil.app.model.HumanBodySegmenter
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
    @Volatile var bodySegmenterFactory: ((Context) -> HumanBodySegmenter)? = null
    @Volatile var sessionTimeoutMsOverride: Long? = null
    @Volatile var coverageWatchdogDwellMsOverride: Long? = null
    @Volatile var coverageWatchdogThresholdOverride: Double? = null
    @Volatile var inferenceTimeoutMsOverride: Long? = null
    @Volatile var screenshotTimeoutMsOverride: Long? = null

    fun reset() {
        detectorFactory = null
        frameProvider = null
        overlayFactory = null
        bodySegmenterFactory = null
        sessionTimeoutMsOverride = null
        coverageWatchdogDwellMsOverride = null
        coverageWatchdogThresholdOverride = null
        inferenceTimeoutMsOverride = null
        screenshotTimeoutMsOverride = null
    }
}

package dev.screenveil.app.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.screenveil.app.policy.RedactionEffect
import kotlin.math.max
import kotlin.math.min

object BitmapRedactor {
    fun redact(source: Bitmap, effect: RedactionEffect, intensity: Int): Bitmap {
        val target = downscaleForProcessing(source, 1200)
        return when (effect) {
            RedactionEffect.GAUSSIAN_BLUR -> boxGaussianApproximation(target, intensity.coerceIn(4, 32))
            RedactionEffect.PIXELATION -> pixelate(target, intensity.coerceIn(4, 32)).also {
                if (it !== target) target.recycle()
            }
            RedactionEffect.OPAQUE_COVER -> opaqueCover(target).also { target.recycle() }
        }
    }

    private fun downscaleForProcessing(source: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = max(source.width, source.height)
        if (maxSide <= maxDimension) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = maxDimension.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * scale).toInt()),
            max(1, (source.height * scale).toInt()),
            true
        )
    }

    private fun opaqueCover(source: Bitmap): Bitmap = Bitmap.createBitmap(
        source.width, source.height, Bitmap.Config.ARGB_8888
    ).also { bitmap ->
        Canvas(bitmap).drawColor(Color.rgb(18, 22, 30))
    }

    private fun pixelate(source: Bitmap, intensity: Int): Bitmap {
        val block = (intensity / 2).coerceIn(3, 16)
        val smallWidth = max(1, source.width / block)
        val smallHeight = max(1, source.height / block)
        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, false)
        return Bitmap.createScaledBitmap(small, source.width, source.height, false).also {
            if (small !== source && small !== it) small.recycle()
        }
    }

    /** Three horizontal/vertical box passes approximate a Gaussian blur without RenderScript. */
    private fun boxGaussianApproximation(source: Bitmap, intensity: Int): Bitmap {
        var working = source
        val radius = max(2, intensity / 3)
        repeat(3) {
            val next = boxBlurPass(working, radius)
            if (working !== source) working.recycle()
            working = next
        }
        if (working !== source) source.recycle()
        return working
    }

    private fun boxBlurPass(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val horizontal = IntArray(pixels.size)
        val output = IntArray(pixels.size)
        val window = radius * 2 + 1

        for (y in 0 until height) {
            var red = 0L; var green = 0L; var blue = 0L; var alpha = 0L
            for (offset in -radius..radius) {
                val color = pixels[y * width + offset.coerceIn(0, width - 1)]
                red += Color.red(color); green += Color.green(color)
                blue += Color.blue(color); alpha += Color.alpha(color)
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = Color.argb(
                    (alpha / window).toInt(), (red / window).toInt(),
                    (green / window).toInt(), (blue / window).toInt()
                )
                val remove = pixels[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = pixels[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
                alpha += Color.alpha(add) - Color.alpha(remove)
            }
        }

        for (x in 0 until width) {
            var red = 0L; var green = 0L; var blue = 0L; var alpha = 0L
            for (offset in -radius..radius) {
                val color = horizontal[offset.coerceIn(0, height - 1) * width + x]
                red += Color.red(color); green += Color.green(color)
                blue += Color.blue(color); alpha += Color.alpha(color)
            }
            for (y in 0 until height) {
                output[y * width + x] = Color.argb(
                    (alpha / window).toInt(), (red / window).toInt(),
                    (green / window).toInt(), (blue / window).toInt()
                )
                val remove = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
                alpha += Color.alpha(add) - Color.alpha(remove)
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(output, 0, width, 0, 0, width, height)
        }
    }
}

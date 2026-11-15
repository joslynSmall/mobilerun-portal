package com.mobilerun.portal.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Optional re-encoding of a screenshot before it leaves the device.
 *
 * The capture paths always produce a full-resolution lossless PNG (~2.7–5 MB as
 * base64 on image-heavy screens), which is slow for constrained clients such as
 * a microcontroller bridge. Callers can ask for a smaller JPEG/WebP instead.
 * With default options the PNG is passed through untouched, so the existing
 * response contract is unchanged.
 */
data class ScreenshotOptions(
    val format: String = FORMAT_PNG,
    val quality: Int = DEFAULT_QUALITY,
    val scale: Double = 1.0,
    val maxSide: Int = 0,
) {
    val isPassthrough: Boolean
        get() = format == FORMAT_PNG && scale >= 1.0 && maxSide <= 0

    companion object {
        const val FORMAT_PNG = "png"
        const val FORMAT_JPEG = "jpeg"
        const val FORMAT_WEBP = "webp"
        const val DEFAULT_QUALITY = 80

        /** Parse request params; unknown or out-of-range values fall back to safe defaults. */
        fun from(params: org.json.JSONObject): ScreenshotOptions {
            val format = when (params.optString("format", FORMAT_PNG).lowercase()) {
                "jpg", FORMAT_JPEG -> FORMAT_JPEG
                FORMAT_WEBP -> FORMAT_WEBP
                else -> FORMAT_PNG
            }
            val quality = params.optInt("quality", DEFAULT_QUALITY).coerceIn(1, 100)
            val scale = params.optDouble("scale", 1.0).let { if (it.isNaN() || it <= 0.0) 1.0 else it.coerceAtMost(1.0) }
            val maxSide = params.optInt("maxSide", 0).coerceAtLeast(0)
            return ScreenshotOptions(format, quality, scale, maxSide)
        }
    }
}

object ScreenshotTranscoder {
    /**
     * Re-encode a base64 PNG according to [options]; returns base64 (NO_WRAP).
     * `maxSide` caps the longer edge and wins over `scale` when it is smaller.
     */
    fun transcode(pngBase64: String, options: ScreenshotOptions): String {
        if (options.isPassthrough) return pngBase64
        val png = Base64.decode(pngBase64, Base64.DEFAULT)
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalStateException("cannot decode screenshot PNG")
        var scaled: Bitmap? = null
        try {
            val factor = scaleFactor(source.width, source.height, options)
            scaled = if (factor < 1.0) {
                val w = (source.width * factor).roundToInt().coerceAtLeast(1)
                val h = (source.height * factor).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, w, h, true)
            } else {
                source
            }
            val format = when (options.format) {
                ScreenshotOptions.FORMAT_JPEG -> Bitmap.CompressFormat.JPEG
                ScreenshotOptions.FORMAT_WEBP -> webpLossy()
                else -> Bitmap.CompressFormat.PNG
            }
            val out = ByteArrayOutputStream()
            if (!scaled.compress(format, options.quality, out)) {
                throw IllegalStateException("bitmap compress failed")
            }
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled != null && scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    internal fun scaleFactor(width: Int, height: Int, options: ScreenshotOptions): Double {
        var factor = options.scale.coerceIn(0.01, 1.0)
        if (options.maxSide > 0) {
            val longer = maxOf(width, height)
            if (longer * factor > options.maxSide) factor = options.maxSide.toDouble() / longer
        }
        return factor
    }

    @Suppress("DEPRECATION")
    private fun webpLossy(): Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
}

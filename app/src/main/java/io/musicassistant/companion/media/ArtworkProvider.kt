package io.musicassistant.companion.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Utility for converting artwork images to a format suitable for Bluetooth AVRCP.
 *
 * AVRCP works best with reasonably sized JPEG images. This utility decodes any
 * image format (PNG, WEBP, JPEG, etc.), scales it to a maximum of 300x300,
 * and re-encodes as JPEG.
 */
object ArtworkConverter {

    private const val TAG = "ArtworkConverter"
    private const val MAX_SIZE = 300
    private const val JPEG_QUALITY = 90

    /**
     * Convert raw image bytes to a 300x300 max JPEG suitable for Bluetooth AVRCP.
     * Returns the JPEG bytes, or null if the input cannot be decoded.
     */
    fun toJpeg(bytes: ByteArray): ByteArray? {
        return try {
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = scaleDown(original)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            val result = out.toByteArray()
            Log.d(TAG, "Converted: ${bytes.size} bytes → ${result.size} bytes JPEG (${scaled.width}x${scaled.height})")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert artwork: ${e.message}")
            null
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_SIZE && bitmap.height <= MAX_SIZE) return bitmap
        val scale = MAX_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }
}

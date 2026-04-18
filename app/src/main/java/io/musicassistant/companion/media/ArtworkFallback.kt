package io.musicassistant.companion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import io.musicassistant.companion.R
import java.io.ByteArrayOutputStream

/**
 * Builds JPEG bytes from the app launcher icon, used as the final fallback when no
 * album artwork is available. Bluetooth AVRCP requires embedded bytes (it cannot
 * resolve HTTP URIs), so we always supply real bytes.
 */
class ArtworkFallback(context: Context) {

    val bytes: ByteArray = buildBytes(context)

    private fun buildBytes(context: Context): ByteArray {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            ?: error("ArtworkFallback: R.mipmap.ic_launcher not found")
        val size = SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        val jpeg = out.toByteArray()
        Log.d(TAG, "built app-icon fallback: ${jpeg.size} bytes")
        return jpeg
    }

    companion object {
        private const val TAG = "ArtworkFallback"
        private const val SIZE_PX = 300
        private const val JPEG_QUALITY = 90
    }
}

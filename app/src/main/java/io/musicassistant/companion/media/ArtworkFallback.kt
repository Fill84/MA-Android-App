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
 *
 * Construction runs on the main thread during service onCreate. Keep this cheap
 * (<20 ms on low-end devices). JPEG is opaque — transparency in the source is lost.
 */
class ArtworkFallback(context: Context) {

    val bytes: ByteArray = buildBytes(context)

    private fun buildBytes(context: Context): ByteArray {
        val size = SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        } else {
            Log.w(TAG, "R.mipmap.ic_launcher missing — using solid-color fallback")
            canvas.drawColor(SOLID_FALLBACK_COLOR)
        }
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
        private const val SOLID_FALLBACK_COLOR: Int = 0xFF202020.toInt()
    }
}

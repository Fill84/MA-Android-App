package io.musicassistant.companion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.BitmapLoader
import androidx.media3.session.MediaLibraryService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Owns the [MediaLibraryService.MediaLibrarySession], binding the [MaPlayer] and the browse
 * callback. Registers a [BitmapLoader] that only decodes bytes → Bitmap for the legacy
 * METADATA_KEY_ALBUM_ART path that Bluetooth AVRCP reads. URI loading is refused on purpose —
 * all artwork must arrive as bytes from the Coordinator, because AVRCP cannot resolve HTTP URIs.
 */
class MediaSessionHost(
    context: Context,
    player: MaPlayer,
    browseCallback: MediaLibraryService.MediaLibrarySession.Callback
) {

    companion object {
        private const val TAG = "MediaSessionHost"
        private const val SESSION_ID = "MusicAssistant"
    }

    val session: MediaLibraryService.MediaLibrarySession =
        MediaLibraryService.MediaLibrarySession.Builder(context, player, browseCallback)
            .setId(SESSION_ID)
            .setBitmapLoader(BytesOnlyBitmapLoader())
            .build()

    fun release() {
        session.release()
    }

    private class BytesOnlyBitmapLoader : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            return if (bitmap != null) {
                Futures.immediateFuture(bitmap)
            } else {
                Log.w(TAG, "decodeBitmap failed for ${data.size} bytes")
                Futures.immediateFailedFuture(IllegalArgumentException("Invalid artwork bytes"))
            }
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            Log.w(TAG, "loadBitmap called with uri=$uri — refused (bytes only)")
            return Futures.immediateFailedFuture(UnsupportedOperationException("URI loading not supported"))
        }
    }
}

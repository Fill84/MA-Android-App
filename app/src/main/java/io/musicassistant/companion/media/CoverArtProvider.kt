package io.musicassistant.companion.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream

/**
 * In-memory holder of the current track(s) cover bytes, keyed by a stable per-track key. Filled by
 * [MaPlayer] when it builds the now-playing media items; read by [CoverArtProvider].
 */
object CoverArtStore {
    private const val MAX = 8
    private val map = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX
    }

    @Synchronized fun put(key: String, bytes: ByteArray) { map[key] = bytes }
    @Synchronized fun get(key: String): ByteArray? = map[key]
}

/**
 * Serves album/cover art over a content:// URI so Android's Bluetooth AVRCP cover-art service can
 * fetch it. On Android 13+ that service obtains the image from a resolvable URI/descriptor in the
 * media metadata — NOT from the in-metadata bitmap bytes — so providing only [MediaMetadata]
 * artworkData was not enough for cars to show cover art (the service stored nothing → no handle →
 * no BIP request). Exposing the same bytes via this provider and setting it as the metadata
 * artworkUri is what working apps (YouTube Music, Spotify) do. Read-only, cover images only.
 */
class CoverArtProvider : ContentProvider() {

    companion object {
        private const val TAG = "CoverArtProvider"
        const val AUTHORITY = "io.musicassistant.companion.coverart"

        /** content:// URI for the cover bytes stored under [key]. */
        fun uriFor(key: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(key).build()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val key = uri.lastPathSegment ?: return null
        val bytes = CoverArtStore.get(key) ?: run {
            Log.w(TAG, "No cover bytes for key=$key")
            return null
        }
        // Stream the in-memory bytes through a pipe so we never touch disk.
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(bytes) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing cover bytes: ${e.message}")
            } finally {
                runCatching { pipe[1].close() }
            }
        }.start()
        return pipe[0]
    }

    // Unused — this provider only serves images via openFile.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
}

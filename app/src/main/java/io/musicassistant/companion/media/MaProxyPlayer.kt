package io.musicassistant.companion.media

import android.graphics.Bitmap
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A proxy player that mirrors the Sendspin web player's state via the JS bridge.
 *
 * This player does NOT play audio itself. It exposes playback state and metadata
 * from the WebView's web player to the Android MediaSession system, enabling
 * media controls in notifications, lock screen, Bluetooth, etc.
 *
 * Commands (play, pause, next, previous) are routed back to the web player
 * via the onCommandReceived callback.
 */
class MaProxyPlayer(looper: Looper) : SimpleBasePlayer(looper) {

    private var _isPlaying: Boolean = false
    private var _title: String = ""
    private var _artist: String = ""
    private var _album: String = ""
    private var _artwork: Bitmap? = null
    private var _hasMedia: Boolean = false

    /** Callback to route commands back to the WebView's Sendspin player */
    var onCommandReceived: ((String) -> Unit)? = null

    override fun getState(): State {
        val stateBuilder = State.Builder()
            .setAvailableCommands(buildAvailableCommands())
            .setPlayWhenReady(
                _isPlaying,
                PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(
                if (_hasMedia) STATE_READY else STATE_IDLE
            )
            .setContentPositionMs(0L)

        if (_hasMedia) {
            val metadata = MediaMetadata.Builder()
                .setTitle(_title)
                .setArtist(_artist)
                .setAlbumTitle(_album)

            _artwork?.let { bmp ->
                metadata.setArtworkData(
                    bitmapToByteArray(bmp),
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER
                )
            }

            val mediaItem = MediaItem.Builder()
                .setMediaId("current")
                .setMediaMetadata(metadata.build())
                .build()

            stateBuilder
                .setPlaylist(listOf(MediaItemData.Builder("current")
                    .setMediaItem(mediaItem)
                    .setMediaMetadata(metadata.build())
                    .build()))
                .setCurrentMediaItemIndex(0)
        }

        return stateBuilder.build()
    }

    private fun buildAvailableCommands(): Player.Commands {
        return Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        onCommandReceived?.invoke(if (playWhenReady) "play" else "pause")
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                onCommandReceived?.invoke("nexttrack")
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                onCommandReceived?.invoke("previoustrack")
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        onCommandReceived?.invoke("stop")
        return Futures.immediateVoidFuture()
    }

    // --- Public methods called from JS bridge (via MediaSessionManager) ---

    fun updatePlaybackState(playing: Boolean) {
        _isPlaying = playing
        if (playing && !_hasMedia) _hasMedia = true
        invalidateState()
    }

    fun updateMetadata(title: String, artist: String, album: String, artwork: Bitmap?) {
        _title = title
        _artist = artist
        _album = album
        _artwork = artwork
        _hasMedia = title.isNotEmpty() || artist.isNotEmpty()
        invalidateState()
    }

    fun clearState() {
        _isPlaying = false
        _title = ""
        _artist = ""
        _album = ""
        _artwork = null
        _hasMedia = false
        invalidateState()
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}

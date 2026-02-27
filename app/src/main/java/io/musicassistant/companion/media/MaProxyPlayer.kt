package io.musicassistant.companion.media

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
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
    private var _durationMs: Long = 0L
    private var _positionMs: Long = 0L
    private var _positionTimestamp: Long = SystemClock.elapsedRealtime()
    private var _playbackSpeed: Float = 1.0f

    /** Callback to route commands back to the WebView's Sendspin player */
    var onCommandReceived: ((String) -> Unit)? = null

    /** Callback to route seek-to-position commands back to the WebView */
    var onSeekTo: ((Double) -> Unit)? = null

    override fun getState(): State {
        // Calculate current position based on elapsed time since last JS update
        val elapsedSinceUpdate = if (_isPlaying)
            SystemClock.elapsedRealtime() - _positionTimestamp else 0L
        val calculatedPosition = _positionMs + (elapsedSinceUpdate * _playbackSpeed).toLong()
        val position = if (_durationMs > 0)
            calculatedPosition.coerceIn(0, _durationMs) else calculatedPosition.coerceAtLeast(0)

        val stateBuilder = State.Builder()
            .setAvailableCommands(buildAvailableCommands())
            .setPlayWhenReady(
                _isPlaying,
                PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(
                if (_hasMedia) STATE_READY else STATE_IDLE
            )
            .setContentPositionMs(position)
            .setPlaybackParameters(PlaybackParameters(_playbackSpeed))

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
                    .setDurationUs(_durationMs * 1000)
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
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
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
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                val positionSec = positionMs / 1000.0
                onSeekTo?.invoke(positionSec)
                _positionMs = positionMs
                _positionTimestamp = SystemClock.elapsedRealtime()
                invalidateState()
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

    fun updatePositionState(durationMs: Long, positionMs: Long, playbackSpeed: Float) {
        _durationMs = durationMs
        _positionMs = positionMs
        _positionTimestamp = SystemClock.elapsedRealtime()
        _playbackSpeed = if (playbackSpeed > 0f) playbackSpeed else 1.0f
        invalidateState()
    }

    fun clearState() {
        _isPlaying = false
        _title = ""
        _artist = ""
        _album = ""
        _artwork = null
        _hasMedia = false
        _durationMs = 0L
        _positionMs = 0L
        _positionTimestamp = SystemClock.elapsedRealtime()
        _playbackSpeed = 1.0f
        invalidateState()
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}

package io.musicassistant.companion.media

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.BitmapLoader
import androidx.media3.session.MediaLibraryService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.musicassistant.companion.data.model.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages MediaSession and playback state for notification and lock screen controls.
 *
 * Uses [SimpleBasePlayer] as the MediaSession player — no ExoPlayer at all.
 * SimpleBasePlayer's [getState] returns our current state, and Media3 handles all event
 * dispatching to controllers (notification, lock screen, Android Auto).
 *
 * Sendspin AudioStreamManager writes PCM to AudioTrack directly (separate audio path).
 * This class only manages the MediaSession state — it does not play audio.
 *
 * Transport commands (play/pause/next/prev/seek) from the notification and lock screen
 * are intercepted by [MaSessionPlayer] and routed via callbacks to MusicService → MA server API.
 */
class NativeMediaManager(private val context: android.content.Context) {

    companion object {
        private const val TAG = "NativeMediaManager"
    }

    // ── Public state ────────────────────────────────────────

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrackTitle = MutableStateFlow<String?>(null)
    val currentTrackTitle: StateFlow<String?> = _currentTrackTitle.asStateFlow()

    private val _currentTrackArtist = MutableStateFlow<String?>(null)
    val currentTrackArtist: StateFlow<String?> = _currentTrackArtist.asStateFlow()

    private val _currentArtworkUri = MutableStateFlow<String?>(null)
    val currentArtworkUri: StateFlow<String?> = _currentArtworkUri.asStateFlow()

    // ── Callbacks (wired by MusicService to route commands through MA API) ──

    var onPlayRequested: (() -> Unit)? = null
    var onPauseRequested: (() -> Unit)? = null
    var onNextRequested: (() -> Unit)? = null
    var onPreviousRequested: (() -> Unit)? = null
    var onSeekRequested: ((positionMs: Long) -> Unit)? = null

    // ── MediaSession ────────────────────────────────────────

    private var sessionPlayer: MaSessionPlayer? = null
    var mediaSession: MediaLibraryService.MediaLibrarySession? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Playback state ──────────────────────────────────────

    /** Whether the Sendspin audio pipeline is actively streaming. */
    @Volatile private var streamActive = false

    /** Whether streaming is logically "playing" (not paused). */
    @Volatile private var streamPlaying = false

    // ── Server-known position/duration ──────────────────────

    @Volatile private var knownDurationMs: Long = C.TIME_UNSET

    private data class ElapsedSnapshot(val elapsedMs: Long, val atMs: Long, val playing: Boolean)
    @Volatile private var elapsedSnapshot = ElapsedSnapshot(0L, 0L, false)

    // ── Metadata ────────────────────────────────────────────

    private var currentMetadata: MediaMetadata = MediaMetadata.EMPTY
    private var prevMetadata: MediaMetadata = MediaMetadata.EMPTY
    private var nextMetadata: MediaMetadata = MediaMetadata.EMPTY

    /**
     * Fallback artwork bytes (app icon) used when metadata has no embedded artwork.
     * Bluetooth AVRCP can't resolve HTTP URIs, so we must always provide actual bytes.
     * Set by MusicService at startup.
     */
    @Volatile var fallbackArtwork: ByteArray? = null

    // ── Initialization ──────────────────────────────────────

    fun initialize(browseCallback: MediaLibraryService.MediaLibrarySession.Callback? = null) {
        if (sessionPlayer != null) return

        val player = MaSessionPlayer(Looper.getMainLooper())
        sessionPlayer = player

        val callback = browseCallback ?: object : MediaLibraryService.MediaLibrarySession.Callback {}

        // Explicit BitmapLoader that decodes artworkData bytes to Bitmap.
        // Media3 uses this to bridge artwork to the legacy MediaSession's
        // METADATA_KEY_ALBUM_ART bitmap, which Bluetooth AVRCP reads.
        val bitmapLoader = object : BitmapLoader {
            override fun supportsMimeType(mimeType: String): Boolean = true

            override fun decodeBitmap(data: ByteArray): ListenableFuture<android.graphics.Bitmap> {
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                Log.d(TAG, "BitmapLoader.decodeBitmap: ${data.size} bytes → ${bmp?.width}x${bmp?.height}")
                return if (bmp != null) {
                    Futures.immediateFuture(bmp)
                } else {
                    Futures.immediateFailedFuture(IllegalArgumentException("Failed to decode artwork"))
                }
            }

            override fun loadBitmap(uri: Uri): ListenableFuture<android.graphics.Bitmap> {
                Log.d(TAG, "BitmapLoader.loadBitmap: uri=$uri (not supported)")
                return Futures.immediateFailedFuture(UnsupportedOperationException("URI loading not supported"))
            }
        }

        mediaSession = MediaLibraryService.MediaLibrarySession.Builder(context, player, callback)
            .setId("MusicAssistant")
            .setBitmapLoader(bitmapLoader)
            .build()

        Log.d(TAG, "Initialized SimpleBasePlayer and MediaLibrarySession with BitmapLoader")
    }

    // ── Streaming state control ─────────────────────────────

    /**
     * Mark the Sendspin audio pipeline as active or inactive.
     * Called by MusicService on SendspinState transitions.
     */
    fun setStreamActive(active: Boolean) {
        streamActive = active
        if (active) {
            streamPlaying = true
            _isPlaying.value = true
        } else {
            streamPlaying = false
            _isPlaying.value = false
        }
        postInvalidate()
    }

    // ── Playback controls ───────────────────────────────────

    fun play() {
        if (streamActive) {
            streamPlaying = true
            _isPlaying.value = true
            postInvalidate()
        }
    }

    fun pause() {
        if (streamActive) {
            streamPlaying = false
            _isPlaying.value = false
            postInvalidate()
        }
    }

    fun stop() {
        streamActive = false
        streamPlaying = false
        _isPlaying.value = false
        postInvalidate()
    }

    // ── Metadata ────────────────────────────────────────────

    fun updateMetadata(
        title: String?,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        artworkData: ByteArray? = null
    ) {
        // Skip if metadata is unchanged — avoids redundant MediaSession invalidations
        // that cause Bluetooth AVRCP to continuously refresh its display.
        val metadataChanged = title != _currentTrackTitle.value ||
                artist != _currentTrackArtist.value ||
                artworkUrl != _currentArtworkUri.value ||
                artworkData != null

        if (!metadataChanged) return

        // Preserve existing artwork bytes when URL is unchanged and no new bytes provided.
        // Without this, Sendspin metadata updates (which never include artworkData) would
        // discard previously downloaded artwork and cause fallback icon to appear.
        val effectiveArtworkData = artworkData
            ?: if (artworkUrl == _currentArtworkUri.value) currentMetadata.artworkData else null

        _currentTrackTitle.value = title
        _currentTrackArtist.value = artist
        _currentArtworkUri.value = artworkUrl

        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)

        // For Bluetooth AVRCP: only set artworkData bytes, NOT artworkUri.
        // If both are set, the platform MediaSession may prefer the URI (which BT can't resolve)
        // over the embedded bitmap. By only setting artworkData, Media3 converts it to a Bitmap
        // in the legacy MediaSession metadata (METADATA_KEY_ALBUM_ART) which AVRCP can read.
        if (effectiveArtworkData != null) {
            builder.setArtworkData(effectiveArtworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else if (artworkUrl != null) {
            // Only set URI as fallback when we have no bytes at all
            builder.setArtworkUri(Uri.parse(artworkUrl))
        }

        currentMetadata = builder.build()
        Log.d(TAG, "updateMetadata: title=$title hasArtwork=${effectiveArtworkData != null} size=${effectiveArtworkData?.size}")
        postInvalidate()
    }

    /**
     * Set metadata for the previous and next queue items so Bluetooth AVRCP
     * displays distinct track info for each slot in the 3-item playlist.
     * Called from MusicService alongside [updateMetadata].
     */
    fun updateQueueNeighborMetadata(
        prevTitle: String?, prevArtist: String?,
        nextTitle: String?, nextArtist: String?
    ) {
        prevMetadata = if (prevTitle != null || prevArtist != null) {
            MediaMetadata.Builder()
                .setTitle(prevTitle)
                .setArtist(prevArtist)
                .build()
        } else {
            MediaMetadata.EMPTY
        }
        nextMetadata = if (nextTitle != null || nextArtist != null) {
            MediaMetadata.Builder()
                .setTitle(nextTitle)
                .setArtist(nextArtist)
                .build()
        } else {
            MediaMetadata.EMPTY
        }
        // No postInvalidate() — called alongside updateMetadata which already invalidates.
    }

    fun updateArtworkData(artworkData: ByteArray) {
        currentMetadata = currentMetadata.buildUpon()
            .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        postInvalidate()
    }

    // ── Position / Duration ─────────────────────────────────

    fun setKnownDuration(durationMs: Long) {
        knownDurationMs = durationMs
    }

    fun setKnownElapsedTime(elapsedMs: Long, playing: Boolean) {
        elapsedSnapshot = ElapsedSnapshot(
            elapsedMs,
            android.os.SystemClock.elapsedRealtime(),
            playing
        )
    }

    /**
     * Force MediaSession to re-read player state (duration, position, metadata).
     * Call sparingly — each invalidation triggers AVRCP updates to Bluetooth devices.
     */
    fun invalidateSessionState() {
        postInvalidate()
    }

    /** Calculate current position from the last server-reported elapsed time. */
    private fun serverPositionMs(): Long {
        val snap = elapsedSnapshot
        if (snap.elapsedMs <= 0 && !snap.playing) return 0L
        return if (snap.playing) {
            val delta = android.os.SystemClock.elapsedRealtime() - snap.atMs
            (snap.elapsedMs + delta).coerceAtLeast(0L)
        } else {
            snap.elapsedMs.coerceAtLeast(0L)
        }
    }

    val currentPositionMs: Long
        get() = if (knownDurationMs > 0) {
            serverPositionMs().coerceAtMost(knownDurationMs)
        } else {
            0L
        }

    val durationMs: Long
        get() = if (knownDurationMs > 0) knownDurationMs else 0L

    val playbackState: PlayerState
        get() {
            if (streamPlaying) return PlayerState.PLAYING
            if (streamActive) return PlayerState.PAUSED
            return PlayerState.IDLE
        }

    // ── Cleanup ─────────────────────────────────────────────

    fun release() {
        mediaSession?.release()
        mediaSession = null
        sessionPlayer = null
        Log.d(TAG, "Released MediaSession")
    }

    // ── Helpers ──────────────────────────────────────────────

    /** Post invalidateState() to the main thread (SimpleBasePlayer requires it). */
    private fun postInvalidate() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sessionPlayer?.notifyStateChanged()
        } else {
            mainHandler.post { sessionPlayer?.notifyStateChanged() }
        }
    }

    // ── SimpleBasePlayer ────────────────────────────────────

    /**
     * Custom player that drives MediaSession without ExoPlayer.
     *
     * [getState] returns the current playback state — Media3 calls it whenever
     * [invalidateState] is invoked, and pushes the result to all MediaSession controllers.
     *
     * Transport commands (play/pause/seek/next/prev) arrive through the handle* methods
     * and are routed to the MA server via the NativeMediaManager callbacks.
     *
     * Uses a 3-item playlist (prev, current, next) so that next/previous commands
     * always trigger [handleSeek] regardless of playlist position.
     */
    private inner class MaSessionPlayer(looper: Looper) : SimpleBasePlayer(looper) {

        /** Public wrapper for the protected [invalidateState]. */
        fun notifyStateChanged() {
            invalidateState()
        }

        override fun getState(): State {
            val commands = Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,  // Required for AVRCP playlist item selection
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_TIMELINE
                )
                .build()

            val builder = State.Builder()
                .setAvailableCommands(commands)
                .setPlayWhenReady(streamPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(if (streamActive) STATE_READY else STATE_IDLE)

            if (streamActive) {
                val durationUs = if (knownDurationMs > 0) knownDurationMs * 1000L else C.TIME_UNSET
                val isSeekable = knownDurationMs > 0

                // 3-item playlist: [prev] [current] [next]
                // This ensures next/previous always call handleSeek.
                val prevItem = MediaItemData.Builder("prev")
                    .setMediaItem(buildMediaItem("prev"))
                    .setDurationUs(C.TIME_UNSET)
                    .build()

                val currentItem = MediaItemData.Builder("current")
                    .setMediaItem(buildMediaItem("current"))
                    .setDurationUs(durationUs)
                    .setIsSeekable(isSeekable)
                    .setIsDynamic(false)
                    .setIsPlaceholder(false)
                    .build()

                val nextItem = MediaItemData.Builder("next")
                    .setMediaItem(buildMediaItem("next"))
                    .setDurationUs(C.TIME_UNSET)
                    .build()

                builder.setPlaylist(listOf(prevItem, currentItem, nextItem))
                builder.setCurrentMediaItemIndex(1)

                val posMs = if (knownDurationMs > 0) {
                    serverPositionMs().coerceAtMost(knownDurationMs)
                } else {
                    0L
                }
                builder.setContentPositionMs(posMs)
            }

            return builder.build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            Log.d(TAG, "handleSetPlayWhenReady: $playWhenReady (streamActive=$streamActive)")
            // Optimistically update state so getState() returns the right value
            // when SimpleBasePlayer calls it after the future resolves.
            if (streamActive) {
                streamPlaying = playWhenReady
                _isPlaying.value = playWhenReady
            }

            // Route to MusicService → MA server API
            if (playWhenReady) {
                onPlayRequested?.invoke()
            } else {
                onPauseRequested?.invoke()
            }

            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            @Player.Command seekCommand: Int
        ): ListenableFuture<*> {
            Log.d(TAG, "handleSeek: index=$mediaItemIndex pos=$positionMs cmd=$seekCommand")
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    Log.d(TAG, "handleSeek → NEXT")
                    onNextRequested?.invoke()
                }
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    Log.d(TAG, "handleSeek → PREVIOUS")
                    onPreviousRequested?.invoke()
                }
                else -> {
                    // COMMAND_SEEK_TO_MEDIA_ITEM or COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM:
                    // Determine next/prev/seek based on the target media item index.
                    // Index 0 = prev, 1 = current, 2 = next in our 3-item playlist.
                    if (mediaItemIndex > 1) {
                        Log.d(TAG, "handleSeek → NEXT (index=$mediaItemIndex)")
                        onNextRequested?.invoke()
                    } else if (mediaItemIndex < 1) {
                        Log.d(TAG, "handleSeek → PREVIOUS (index=$mediaItemIndex)")
                        onPreviousRequested?.invoke()
                    } else {
                        Log.d(TAG, "handleSeek → SEEK to $positionMs ms")
                        onSeekRequested?.invoke(positionMs)
                    }
                }
            }
            return Futures.immediateVoidFuture()
        }

        private fun buildMediaItem(id: String): MediaItem {
            val metadata = when (id) {
                "prev" -> prevMetadata
                "next" -> nextMetadata
                else -> currentMetadata
            }
            // Bluetooth AVRCP can't resolve HTTP artworkUri — always provide bytes.
            // Inject fallback (app icon) when metadata has no embedded artwork.
            val effective = if (metadata.artworkData == null && fallbackArtwork != null) {
                metadata.buildUpon()
                    .setArtworkData(fallbackArtwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            } else {
                metadata
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(effective)
                .build()
        }
    }
}

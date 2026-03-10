package io.musicassistant.companion.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import io.musicassistant.companion.data.model.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages audio playback and MediaSession.
 *
 * Two playback modes:
 * 1. URL playback: ExoPlayer plays a URL directly (play_url command)
 * 2. Stream playback: AudioTrack plays PCM directly from WebSocket binary frames
 *    (like v1's NativeAudioPlayer — no pipe, no ExoPlayer, no buffering)
 *
 * MediaSession is always attached to ForwardingPlayer for notification/lock screen controls.
 * During streaming, ForwardingPlayer overrides report the correct state even though
 * ExoPlayer itself is idle.
 */
class NativeMediaManager(private val context: Context) {

    companion object {
        private const val TAG = "NativeMediaManager"
    }

    var exoPlayer: ExoPlayer? = null
        private set
    var mediaSession: MediaSession? = null
        private set

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrackTitle = MutableStateFlow<String?>(null)
    val currentTrackTitle: StateFlow<String?> = _currentTrackTitle.asStateFlow()

    private val _currentTrackArtist = MutableStateFlow<String?>(null)
    val currentTrackArtist: StateFlow<String?> = _currentTrackArtist.asStateFlow()

    private val _currentArtworkUri = MutableStateFlow<String?>(null)
    val currentArtworkUri: StateFlow<String?> = _currentArtworkUri.asStateFlow()

    /** Callbacks for media control actions (wired by MusicService to route through MA API). */
    var onPlayRequested: (() -> Unit)? = null
    var onPauseRequested: (() -> Unit)? = null
    var onNextRequested: (() -> Unit)? = null
    var onPreviousRequested: (() -> Unit)? = null
    var onSeekRequested: ((positionMs: Long) -> Unit)? = null

    /** Direct AudioTrack player for Sendspin PCM streaming. */
    private val streamPlayer = StreamAudioPlayer()

    /** Whether streaming playback is logically "playing" (respects play/pause). */
    @Volatile private var streamingPlaying = false

    /** Server-known duration for the current track (for seekbar in notification). */
    @Volatile private var knownDurationMs: Long = C.TIME_UNSET

    /**
     * Server-known elapsed time and the system clock at which it was reported. Used by the
     * ForwardingPlayer to report a position that matches the MA server.
     */
    @Volatile private var knownElapsedMs: Long = 0L
    @Volatile private var knownElapsedAtMs: Long = 0L
    @Volatile private var isServerPlaying: Boolean = false

    /** ForwardingPlayer that exposes next/prev/seek commands to the MediaSession. */
    private var forwardingPlayer: ForwardingPlayer? = null

    fun initialize() {
        if (exoPlayer != null) return

        val player =
                ExoPlayer.Builder(context)
                        .setHandleAudioBecomingNoisy(true)
                        .build().also {
                            it.addListener(PlayerListener())
                        }
        exoPlayer = player

        forwardingPlayer = MaForwardingPlayer(player)

        mediaSession =
                MediaSession.Builder(context, forwardingPlayer!!).setId("MusicAssistant").build()

        Log.d(TAG, "Initialized ExoPlayer and MediaSession with ForwardingPlayer")
    }

    /** Set the known duration from server queue metadata. */
    fun setKnownDuration(durationMs: Long) {
        this.knownDurationMs = durationMs
    }

    /** Set the server-known elapsed time so the notification seekbar tracks correctly. */
    fun setKnownElapsedTime(elapsedMs: Long, playing: Boolean) {
        this.knownElapsedMs = elapsedMs
        this.knownElapsedAtMs = android.os.SystemClock.elapsedRealtime()
        this.isServerPlaying = playing
    }

    /**
     * Force the MediaSession to re-read player state (duration, position). Call this after updating
     * knownDuration or knownElapsedTime.
     */
    fun invalidateSessionState() {
        val fp = forwardingPlayer ?: return
        val session = mediaSession ?: return
        val temp = object : ForwardingPlayer(fp) {}
        session.setPlayer(temp)
        session.setPlayer(fp)
        Log.d(
                TAG,
                "invalidateSessionState: duration=${knownDurationMs}ms, " +
                        "elapsed=${knownElapsedMs}ms, serverPos=${serverPositionMs()}ms, " +
                        "streaming=${streamPlayer.isActive}"
        )
    }

    /** Calculate the current server-derived position. */
    private fun serverPositionMs(): Long {
        if (knownElapsedMs <= 0 && !isServerPlaying) return 0L
        val base = knownElapsedMs
        return if (isServerPlaying) {
            val delta = android.os.SystemClock.elapsedRealtime() - knownElapsedAtMs
            (base + delta).coerceAtLeast(0L)
        } else {
            base.coerceAtLeast(0L)
        }
    }

    // ── URL Playback (ExoPlayer) ─────────────────────────────

    /** Play a stream URL received from the Sendspin server or MA API. */
    fun playUrl(url: String) {
        stopStream()
        val player = exoPlayer ?: return
        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        Log.d(TAG, "Playing URL: $url")
    }

    // ── Direct PCM Streaming (AudioTrack) ────────────────────

    /**
     * Start direct PCM streaming via AudioTrack.
     * Called when Sendspin sends stream/start. Audio data is then written
     * directly via [writeStreamData] from the WebSocket thread.
     */
    fun startStreamDirect(sampleRate: Int, channels: Int, bitDepth: Int) {
        stopStream()
        exoPlayer?.stop()

        // Set a placeholder media item so MediaSession has something to attach metadata to
        val placeholderItem = MediaItem.Builder().setUri(Uri.EMPTY).build()
        exoPlayer?.setMediaItem(placeholderItem)

        streamPlayer.configure(sampleRate, channels, bitDepth)
        streamingPlaying = true
        _isPlaying.value = true
        invalidateSessionState()
        Log.d(TAG, "Started direct stream: rate=$sampleRate ch=$channels bits=$bitDepth")
    }

    /** Write audio data directly to AudioTrack. Called from WebSocket thread — zero copy. */
    fun writeStreamData(data: ByteArray, offset: Int, length: Int) {
        streamPlayer.write(data, offset, length)
    }

    /** Flush AudioTrack buffer (used on stream/clear for track transitions). */
    fun flushStream() {
        streamPlayer.flush()
    }

    /** Stop the current stream if active. */
    fun stopStream() {
        if (streamPlayer.isActive) {
            streamPlayer.stop()
            streamingPlaying = false
            _isPlaying.value = false
        }
    }

    // ── Common Controls ──────────────────────────────────────

    /** Update the current media metadata (for notification/lock screen display). */
    fun updateMetadata(title: String?, artist: String?, album: String?, artworkUrl: String?) {
        _currentTrackTitle.value = title
        _currentTrackArtist.value = artist
        _currentArtworkUri.value = artworkUrl

        val player = exoPlayer ?: return
        val metadataBuilder =
                MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)

        if (artworkUrl != null) {
            metadataBuilder.setArtworkUri(Uri.parse(artworkUrl))
        }

        // Replace current item with updated metadata
        val currentItem = player.currentMediaItem
        if (currentItem != null) {
            val updated = currentItem.buildUpon().setMediaMetadata(metadataBuilder.build()).build()
            player.replaceMediaItem(player.currentMediaItemIndex, updated)
        }
    }

    fun play() {
        if (streamPlayer.isActive) {
            streamingPlaying = true
            _isPlaying.value = true
            streamPlayer.resume() // resume AudioTrack immediately
        }
        exoPlayer?.play()
    }

    fun pause() {
        if (streamPlayer.isActive) {
            streamingPlaying = false
            _isPlaying.value = false
            streamPlayer.pause() // instant silence — pause + flush
        }
        exoPlayer?.pause()
    }

    fun stop() {
        stopStream()
        exoPlayer?.stop()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        exoPlayer?.volume = v
        streamPlayer.setVolume(v)
    }

    val currentPositionMs: Long
        get() =
                if (knownDurationMs > 0) serverPositionMs().coerceAtMost(knownDurationMs)
                else exoPlayer?.currentPosition ?: 0L
    val durationMs: Long
        get() = if (knownDurationMs > 0) knownDurationMs else exoPlayer?.duration ?: 0L
    val playbackState: PlayerState
        get() {
            if (streamingPlaying) return PlayerState.PLAYING
            if (streamPlayer.isActive) return PlayerState.PAUSED
            val player = exoPlayer ?: return PlayerState.IDLE
            return when {
                player.isPlaying -> PlayerState.PLAYING
                player.playbackState == Player.STATE_BUFFERING -> PlayerState.BUFFERING
                player.playWhenReady && player.playbackState == Player.STATE_READY ->
                        PlayerState.PLAYING
                player.playbackState == Player.STATE_READY -> PlayerState.PAUSED
                else -> PlayerState.IDLE
            }
        }

    fun release() {
        stopStream()
        streamPlayer.release()
        mediaSession?.release()
        mediaSession = null
        forwardingPlayer = null
        exoPlayer?.release()
        exoPlayer = null
        Log.d(TAG, "Released ExoPlayer, StreamPlayer and MediaSession")
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // During streaming, don't let ExoPlayer state override our streaming state
            _isPlaying.value = isPlaying || streamingPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "ExoPlayer state: $playbackState")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}")
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            // No-op: metadata StateFlows are updated authoritatively by updateMetadata()
        }
    }

    /**
     * ForwardingPlayer that exposes next/prev/seek commands to the MediaSession, even when
     * ExoPlayer only has a single streaming media item or is idle during AudioTrack streaming.
     * Also overrides duration/seekable so the notification seekbar works with server-known duration.
     */
    private inner class MaForwardingPlayer(player: ExoPlayer) : ForwardingPlayer(player) {

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands()
                    .buildUpon()
                    .addAll(
                            Player.COMMAND_SEEK_TO_NEXT,
                            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                            Player.COMMAND_SEEK_TO_PREVIOUS,
                            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                    )
                    .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }
        }

        // ── Streaming state overrides ──
        // During AudioTrack streaming, ExoPlayer is idle but MediaSession needs correct state.

        override fun isPlaying(): Boolean {
            return streamingPlaying || super.isPlaying()
        }

        override fun getPlaybackState(): Int {
            return if (streamPlayer.isActive) Player.STATE_READY else super.getPlaybackState()
        }

        override fun getPlayWhenReady(): Boolean {
            return if (streamPlayer.isActive) streamingPlaying else super.getPlayWhenReady()
        }

        // ── Duration / Position overrides for notification seekbar ──

        override fun getDuration(): Long {
            return if (knownDurationMs > 0) knownDurationMs else super.getDuration()
        }

        override fun getContentDuration(): Long {
            return if (knownDurationMs > 0) knownDurationMs else super.getContentDuration()
        }

        override fun getCurrentPosition(): Long {
            return if (knownDurationMs > 0) serverPositionMs().coerceAtMost(knownDurationMs)
            else super.getCurrentPosition()
        }

        override fun getContentPosition(): Long {
            return if (knownDurationMs > 0) serverPositionMs().coerceAtMost(knownDurationMs)
            else super.getContentPosition()
        }

        override fun getBufferedPosition(): Long {
            return if (knownDurationMs > 0) knownDurationMs else super.getBufferedPosition()
        }

        override fun getContentBufferedPosition(): Long {
            return if (knownDurationMs > 0) knownDurationMs else super.getContentBufferedPosition()
        }

        override fun getBufferedPercentage(): Int {
            return if (knownDurationMs > 0) 100 else super.getBufferedPercentage()
        }

        override fun getTotalBufferedDuration(): Long {
            return if (knownDurationMs > 0) {
                (knownDurationMs - serverPositionMs()).coerceAtLeast(0L)
            } else super.getTotalBufferedDuration()
        }

        override fun isCurrentMediaItemSeekable(): Boolean {
            return knownDurationMs > 0 || super.isCurrentMediaItemSeekable()
        }

        override fun isCurrentMediaItemLive(): Boolean {
            return if (knownDurationMs > 0) false else super.isCurrentMediaItemLive()
        }

        override fun isCurrentMediaItemDynamic(): Boolean {
            return if (knownDurationMs > 0) false else super.isCurrentMediaItemDynamic()
        }

        override fun getCurrentTimeline(): Timeline {
            val parentTimeline = super.getCurrentTimeline()
            return if (knownDurationMs > 0 && parentTimeline.windowCount > 0) {
                OverrideDurationTimeline(parentTimeline, knownDurationMs)
            } else {
                parentTimeline
            }
        }

        override fun play() {
            super.play()
            if (streamPlayer.isActive) {
                streamingPlaying = true
                _isPlaying.value = true
                streamPlayer.resume()
            }
            onPlayRequested?.invoke()
        }

        override fun pause() {
            super.pause()
            if (streamPlayer.isActive) {
                streamingPlaying = false
                _isPlaying.value = false
                streamPlayer.pause() // instant silence
            }
            onPauseRequested?.invoke()
        }

        override fun seekToNext() {
            onNextRequested?.invoke() ?: super.seekToNext()
        }

        override fun seekToNextMediaItem() {
            onNextRequested?.invoke() ?: super.seekToNextMediaItem()
        }

        override fun seekToPrevious() {
            onPreviousRequested?.invoke() ?: super.seekToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            onPreviousRequested?.invoke() ?: super.seekToPreviousMediaItem()
        }

        override fun seekTo(positionMs: Long) {
            if (onSeekRequested != null) {
                onSeekRequested?.invoke(positionMs)
            } else {
                super.seekTo(positionMs)
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (onSeekRequested != null && mediaItemIndex == currentMediaItemIndex) {
                onSeekRequested?.invoke(positionMs)
            } else {
                super.seekTo(mediaItemIndex, positionMs)
            }
        }
    }

    /**
     * Timeline wrapper that overrides the window duration so the MediaSession (and therefore the
     * system notification) sees the server-known track duration.
     */
    private class OverrideDurationTimeline(
            private val wrapped: Timeline,
            private val durationMs: Long
    ) : Timeline() {
        override fun getWindowCount(): Int = wrapped.windowCount

        override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long
        ): Window {
            wrapped.getWindow(windowIndex, window, defaultPositionProjectionUs)
            window.durationUs = durationMs * 1000L
            window.isSeekable = true
            window.isDynamic = false
            window.liveConfiguration = null
            return window
        }

        override fun getPeriodCount(): Int = wrapped.periodCount

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            wrapped.getPeriod(periodIndex, period, setIds)
            period.durationUs = durationMs * 1000L
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int = wrapped.getIndexOfPeriod(uid)

        override fun getUidOfPeriod(periodIndex: Int): Any = wrapped.getUidOfPeriod(periodIndex)
    }
}

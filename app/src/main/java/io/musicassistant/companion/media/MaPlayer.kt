package io.musicassistant.companion.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * [SimpleBasePlayer] that exposes the Coordinator's [QueueSnapshot] to Media3 (notification,
 * lock screen, Bluetooth AVRCP, Android Auto). It plays no audio itself — the Sendspin pipeline
 * writes PCM to its own AudioTrack. This class only reflects state.
 *
 * Timeline:
 *  - Normal tracks: a 3-item playlist [prev, current, next] with currentMediaItemIndex = 1, so
 *    next/previous always reach [handleSeek] (the single routing point) and AVRCP shows the right
 *    neighbor metadata. `maxSeekToPreviousPositionMs` is maxed so "previous" always moves to the
 *    previous item instead of restarting the current track.
 *  - Radio/live ([QueueSnapshot.isLive]): a single-item timeline with navigation commands removed,
 *    so the car shows no phantom prev/next (issue 4).
 */
class MaPlayer(
    looper: Looper,
    private val snapshotFlow: StateFlow<QueueSnapshot>
) : SimpleBasePlayer(looper) {

    companion object {
        private const val TAG = "MaPlayer"
    }

    // ── Callbacks (wired by MusicService) ──────────────────────
    var onPlayRequested: (() -> Unit)? = null
    var onPauseRequested: (() -> Unit)? = null
    var onNextRequested: (() -> Unit)? = null
    var onPreviousRequested: (() -> Unit)? = null
    var onSeekRequested: ((positionMs: Long) -> Unit)? = null

    // ── State set by service ───────────────────────────────────
    @Volatile private var streamActive = false
    @Volatile private var streamPlaying = false
    @Volatile private var knownDurationMs: Long = C.TIME_UNSET

    private data class ElapsedSnapshot(val elapsedMs: Long, val atMs: Long, val playing: Boolean)
    @Volatile private var elapsedSnapshot = ElapsedSnapshot(0L, 0L, false)

    private val mainHandler = Handler(looper)
    private var collectorJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────

    fun startObservingSnapshot(scope: CoroutineScope) {
        collectorJob?.cancel()
        collectorJob = scope.launch(Dispatchers.Main) {
            snapshotFlow.collect { invalidate() }
        }
    }

    fun stopObservingSnapshot() {
        collectorJob?.cancel()
        collectorJob = null
    }

    // ── Service-facing setters ─────────────────────────────────

    fun setStreamActive(active: Boolean) {
        streamActive = active
        streamPlaying = active
        invalidate()
    }

    fun setStreamPlaying(playing: Boolean) {
        if (streamActive) {
            streamPlaying = playing
            invalidate()
        }
    }

    fun setKnownDuration(durationMs: Long) {
        knownDurationMs = durationMs
    }

    fun setKnownElapsed(elapsedMs: Long, playing: Boolean) {
        elapsedSnapshot = ElapsedSnapshot(elapsedMs, SystemClock.elapsedRealtime(), playing)
    }

    fun invalidate() {
        if (Looper.myLooper() == Looper.getMainLooper()) invalidateState()
        else mainHandler.post { invalidateState() }
    }

    // ── SimpleBasePlayer ───────────────────────────────────────

    override fun getState(): State {
        val snap = snapshotFlow.value
        val isLive = snap.isLive

        val commandsBuilder = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE
        )
        if (!isLive) {
            commandsBuilder.addAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )
        }

        val builder = State.Builder()
            .setAvailableCommands(commandsBuilder.build())
            .setPlayWhenReady(streamPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (streamActive) STATE_READY else STATE_IDLE)

        if (!streamActive) return builder.build()

        if (isLive) {
            val item = MediaItemData.Builder("current")
                .setMediaItem(buildMediaItem("current", snap.current))
                .setDurationUs(C.TIME_UNSET)
                .setIsSeekable(false)
                .setIsDynamic(true)
                .build()
            builder.setPlaylist(listOf(item))
            builder.setCurrentMediaItemIndex(0)
            builder.setContentPositionMs(0L)
            return builder.build()
        }

        val durationUs = if (knownDurationMs > 0) knownDurationMs * 1_000L else C.TIME_UNSET
        val isSeekable = knownDurationMs > 0

        val prevItem = MediaItemData.Builder("prev")
            .setMediaItem(buildMediaItem("prev", snap.prev))
            .setDurationUs(C.TIME_UNSET)
            .build()
        val currentItem = MediaItemData.Builder("current")
            .setMediaItem(buildMediaItem("current", snap.current))
            .setDurationUs(durationUs)
            .setIsSeekable(isSeekable)
            .setIsDynamic(false)
            .setIsPlaceholder(false)
            .build()
        val nextItem = MediaItemData.Builder("next")
            .setMediaItem(buildMediaItem("next", snap.next))
            .setDurationUs(C.TIME_UNSET)
            .build()

        builder.setPlaylist(listOf(prevItem, currentItem, nextItem))
        builder.setCurrentMediaItemIndex(1)
        // Always treat "previous" as "go to previous item", never "restart current track".
        builder.setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)

        val pos = if (knownDurationMs > 0) serverPositionMs().coerceAtMost(knownDurationMs) else 0L
        builder.setContentPositionMs(pos)

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Log.d(TAG, "handleSetPlayWhenReady: $playWhenReady")
        if (streamActive) streamPlaying = playWhenReady
        if (playWhenReady) onPlayRequested?.invoke() else onPauseRequested?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int
    ): ListenableFuture<*> {
        Log.d(TAG, "handleSeek: index=$mediaItemIndex pos=$positionMs cmd=$seekCommand")
        // Radio/live: there is no track navigation. Ignore everything but in-current seeks.
        if (snapshotFlow.value.isLive) {
            if (seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) onSeekRequested?.invoke(positionMs)
            return Futures.immediateVoidFuture()
        }
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onNextRequested?.invoke()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onPreviousRequested?.invoke()
            else -> when {
                mediaItemIndex > 1 -> onNextRequested?.invoke()
                mediaItemIndex < 1 -> onPreviousRequested?.invoke()
                else -> onSeekRequested?.invoke(positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    private fun buildMediaItem(id: String, meta: TrackMetadata?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(meta?.title?.takeIf { it.isNotBlank() })
            .setArtist(meta?.artist?.takeIf { it.isNotBlank() })
            .setAlbumTitle(meta?.album)
            .apply {
                val bytes = meta?.artworkBytes
                if (bytes != null && bytes.isNotEmpty()) {
                    setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun serverPositionMs(): Long {
        val snap = elapsedSnapshot
        if (snap.elapsedMs <= 0 && !snap.playing) return 0L
        return if (snap.playing) {
            val delta = SystemClock.elapsedRealtime() - snap.atMs
            (snap.elapsedMs + delta).coerceAtLeast(0L)
        } else {
            snap.elapsedMs.coerceAtLeast(0L)
        }
    }
}

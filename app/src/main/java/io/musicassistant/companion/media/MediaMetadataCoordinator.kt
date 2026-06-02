package io.musicassistant.companion.media

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Single, authoritative source of [QueueSnapshot] for the MediaSession and notification.
 *
 * Inputs:
 *   - [pushQueueUpdate]       — from MA queue/player events (title/artist/URL; may need fetch).
 *   - [pushSendspinMetadata]  — from the Sendspin stream (title/artist only; never has URL/bytes).
 *
 * For every update it guarantees the emitted snapshot already carries *some* artwork bytes
 * (fresh cache hit → last-known bytes for the same trackId → app-icon fallback), so the session
 * is never blank. When the current track has an un-cached URL, an async fetch runs and re-emits
 * with the real cover once it arrives — guarded by a monotonically increasing [sequence] token so
 * a slow fetch for an old track can never overwrite a newer one.
 *
 * Radio/live streams ([QueueSnapshot.isLive]) force prev/next to null so Bluetooth AVRCP doesn't
 * show phantom neighbors (issue 4).
 */
class MediaMetadataCoordinator(
    private val pipeline: ArtworkPipeline,
    private val fallbackBytes: ByteArray,
    scopeDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    companion object {
        private const val TAG = "MetadataCoordinator"
    }

    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(parentJob + scopeDispatcher)

    private val _snapshot = MutableStateFlow(QueueSnapshot.EMPTY)
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    /** Bumped on every push; async fetches compare against it to drop stale results. */
    private val sequence = AtomicLong(0)

    /** Last known bytes keyed by trackId — preserves artwork across Sendspin-only updates. */
    private val lastBytesByTrackId = mutableMapOf<String, ByteArray>()

    /**
     * Push a full queue/player update. `current` is required; `prev`/`next` may be null.
     * Set [isLive] for radio/live streams (forces null neighbors).
     */
    fun pushQueueUpdate(
        current: TrackMetadata,
        prev: TrackMetadata?,
        next: TrackMetadata?,
        isLive: Boolean = false
    ) {
        val myToken = sequence.incrementAndGet()

        // Resolve the current track's bytes from the cache exactly once, and reuse the
        // result for both the immediate emit and the "do we need to fetch?" decision.
        val cached = current.artworkUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { pipeline.cachedOrNull(it) }
        val currentBytes = cached
            ?: lastBytesByTrackId[current.trackId]
            ?: fallbackBytes
        val enrichedCurrent = current.copy(artworkBytes = currentBytes)

        val enrichedPrev = if (isLive) null else prev?.copy(artworkBytes = resolveBytesSync(prev) ?: fallbackBytes)
        val enrichedNext = if (isLive) null else next?.copy(artworkBytes = resolveBytesSync(next) ?: fallbackBytes)

        emit(QueueSnapshot(prev = enrichedPrev, current = enrichedCurrent, next = enrichedNext, isLive = isLive))

        // Cache miss for a real URL → fetch asynchronously, then re-emit if still current.
        if (cached == null && !current.artworkUrl.isNullOrBlank()) {
            scope.launch {
                val fetched = pipeline.fetch(current.artworkUrl)
                if (fetched != null && myToken == sequence.get()) {
                    lastBytesByTrackId[current.trackId] = fetched
                    val existing = _snapshot.value
                    emit(existing.copy(current = existing.current.copy(artworkBytes = fetched)))
                }
            }
        }
    }

    /**
     * Sendspin metadata events carry no artwork URL/bytes. If the trackId matches the current
     * snapshot's track, keep its bytes; otherwise reuse last-known bytes for that trackId, else
     * fall back. Preserves the snapshot's neighbors and [QueueSnapshot.isLive] flag.
     */
    fun pushSendspinMetadata(title: String, artist: String, album: String?) {
        sequence.incrementAndGet()
        val candidate = TrackMetadata(title = title, artist = artist, album = album, artworkUrl = null, artworkBytes = null)
        val currentSnap = _snapshot.value
        val current = currentSnap.current

        val bytes = when {
            current.trackId == candidate.trackId && current.hasArtwork -> current.artworkBytes
            lastBytesByTrackId[candidate.trackId] != null -> lastBytesByTrackId[candidate.trackId]
            else -> fallbackBytes
        }
        val resolved = candidate.copy(artworkBytes = bytes ?: fallbackBytes)
        emit(currentSnap.copy(current = resolved))
    }

    /** Release the coroutine scope. Call from MusicService.onDestroy. */
    fun release() {
        parentJob.cancel()
    }

    // ── Internal ───────────────────────────────────────────────

    private fun resolveBytesSync(meta: TrackMetadata): ByteArray? {
        meta.artworkUrl?.takeIf { it.isNotBlank() }?.let { url ->
            pipeline.cachedOrNull(url)?.let { return it }
        }
        lastBytesByTrackId[meta.trackId]?.let { return it }
        return null
    }

    private fun emit(snap: QueueSnapshot) {
        _snapshot.value = snap
        Log.d(
            TAG,
            "emit: current=${snap.current.title}/${snap.current.artist} " +
                "hasArt=${snap.current.hasArtwork} live=${snap.isLive} " +
                "prev=${snap.prev?.title} next=${snap.next?.title}"
        )
    }
}

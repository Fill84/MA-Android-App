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
        private const val MAX_CACHED_TRACKS = 64
    }

    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(parentJob + scopeDispatcher)

    private val _snapshot = MutableStateFlow(QueueSnapshot.EMPTY)
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    /** Bumped on every push; async fetches compare against it to drop stale results. */
    private val sequence = AtomicLong(0)

    /**
     * Last known bytes keyed by trackId — preserves artwork across Sendspin-only updates. Bounded
     * LRU so a long session (e.g. hours of radio, hundreds of songs) can't grow it without limit.
     * Accessed from both push callers and async fetch coroutines, so guard every access.
     */
    private val lastBytesByTrackId = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_CACHED_TRACKS
    }

    @Synchronized private fun cachedBytes(trackId: String): ByteArray? = lastBytesByTrackId[trackId]
    @Synchronized private fun cacheBytes(trackId: String, bytes: ByteArray) {
        lastBytesByTrackId[trackId] = bytes
    }

    /**
     * Source context, set by the service after probing the player: whether the current source is
     * live (radio) and the station's logo bytes. Drives the single-item timeline and the radio
     * fallback chain: per-track cover → station logo → app-icon fallback.
     */
    private data class LiveContext(val isLive: Boolean, val stationBytes: ByteArray?)
    @Volatile private var liveContext = LiveContext(false, null)

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
        liveContext = liveContext.copy(isLive = isLive)

        // Resolve the current track's bytes from the cache exactly once, and reuse the
        // result for both the immediate emit and the "do we need to fetch?" decision.
        val cached = current.artworkUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { pipeline.cachedOrNull(it) }
        val currentBytes = cached
            ?: cachedBytes(current.trackId)
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
                    cacheBytes(current.trackId, fetched)
                    val existing = _snapshot.value
                    emit(existing.copy(current = existing.current.copy(artworkBytes = fetched)))
                }
            }
        }
    }

    /**
     * Sendspin stream metadata (the primary source of track changes for radio/live). It may carry
     * an [artworkUrl] — when present we fetch the real cover through the pipeline, which is the only
     * artwork path that reliably fires for radio. Resolution order for the immediate emit:
     * pipeline cache(url) → same-track existing bytes → last-known for trackId → fallback. Preserves
     * the snapshot's neighbors and [QueueSnapshot.isLive] flag.
     */
    fun pushSendspinMetadata(title: String, artist: String, album: String?, artworkUrl: String? = null) {
        val myToken = sequence.incrementAndGet()
        val candidate = TrackMetadata(title = title, artist = artist, album = album, artworkUrl = artworkUrl, artworkBytes = null)
        val currentSnap = _snapshot.value
        val current = currentSnap.current
        val ctx = liveContext

        val cached = artworkUrl?.takeIf { it.isNotBlank() }?.let { pipeline.cachedOrNull(it) }
        val bytes = cached
            ?: (if (current.trackId == candidate.trackId && current.hasArtwork) current.artworkBytes else null)
            ?: cachedBytes(candidate.trackId)
            ?: ctx.stationBytes      // radio station logo (track has no per-track cover)
            ?: fallbackBytes         // app-icon (no station logo either)
        emit(
            currentSnap.copy(
                current = candidate.copy(artworkBytes = bytes),
                isLive = ctx.isLive,
                prev = if (ctx.isLive) null else currentSnap.prev,
                next = if (ctx.isLive) null else currentSnap.next
            )
        )

        if (cached == null && !artworkUrl.isNullOrBlank()) {
            scope.launch {
                val fetched = pipeline.fetch(artworkUrl)
                if (fetched != null && myToken == sequence.get()) {
                    cacheBytes(candidate.trackId, fetched)
                    val existing = _snapshot.value
                    emit(existing.copy(current = existing.current.copy(artworkBytes = fetched)))
                }
            }
        }
    }

    /**
     * Update the live/radio context (called by the service after probing the player + queue).
     * [isLive] toggles the single-item timeline (no phantom prev/next). [stationBytes] is the radio
     * station logo, used as the fallback for tracks with no per-track cover. If the current track is
     * still showing the app-icon fallback and a station logo is now available, it's upgraded in place.
     */
    fun setLiveContext(isLive: Boolean, stationBytes: ByteArray?) {
        liveContext = LiveContext(isLive, stationBytes)
        val snap = _snapshot.value
        val cur = snap.current
        val curBytes = cur.artworkBytes
        val upgraded = if (stationBytes != null && curBytes != null && curBytes.contentEquals(fallbackBytes)) {
            cur.copy(artworkBytes = stationBytes)
        } else {
            cur
        }
        emit(
            snap.copy(
                current = upgraded,
                isLive = isLive,
                prev = if (isLive) null else snap.prev,
                next = if (isLive) null else snap.next
            )
        )
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
        cachedBytes(meta.trackId)?.let { return it }
        return null
    }

    private fun emit(snap: QueueSnapshot) {
        _snapshot.value = snap
        Log.d(
            TAG,
            "emit: current=${snap.current.title}/${snap.current.artist} " +
                "hasArt=${snap.current.hasArtwork} bytes=${snap.current.artworkBytes?.size} live=${snap.isLive} " +
                "prev=${snap.prev?.title} next=${snap.next?.title}"
        )
    }
}

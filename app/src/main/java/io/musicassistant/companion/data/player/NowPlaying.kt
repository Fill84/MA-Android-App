package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState

/**
 * Derived now-playing for one player/queue. Pure data — no Android, no network. Artwork is kept as
 * RAW references ([artworkImage] = the server image ref, [currentMediaImageUrl] = the radio song's
 * url). Turning those into a device-reachable URL and downloading bytes is the consumer's job
 * (connection identity, not server state) — see MusicService.resolveArtworkUrl / PlayerViewModel.getImageUrl.
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkImage: MediaItemImage?,
    val currentMediaImageUrl: String?,
    val isLive: Boolean,
    val durationMs: Long,
    val elapsedMs: Long,
    val currentIndex: Int?,
    val currentQueueItemId: String?,
)

/** Pure derivations that replace the duplicated logic in PlayerViewModel + MusicService. */
object NowPlayingDerivation {

    /** Radio-vs-track logic, mirroring both existing consumers exactly. */
    fun deriveNowPlaying(player: Player?, queue: PlayerQueue?): NowPlaying? {
        val currentItem = queue?.currentItem ?: return null
        val media = currentItem.mediaItem ?: return null
        val cm = player?.currentMedia
        val isRadio = media.mediaType == MediaType.RADIO
        val isLive = isRadio || currentItem.duration <= 0

        val title: String
        val artist: String
        val album: String?
        if (isRadio) {
            title = cm?.title?.takeIf { it.isNotBlank() } ?: currentItem.name.ifBlank { media.name }
            artist = cm?.artist?.takeIf { it.isNotBlank() } ?: media.name
            album = cm?.album ?: media.name
        } else {
            title = media.name
            artist = media.artists.joinToString(", ") { it.name }
            album = media.album?.name
        }

        return NowPlaying(
            title = title,
            artist = artist,
            album = album,
            artworkImage = media.image ?: currentItem.image,
            currentMediaImageUrl = cm?.imageUrl?.takeIf { it.isNotBlank() },
            isLive = isLive,
            durationMs = if (currentItem.duration > 0 && !isLive) currentItem.duration * 1000L else 0L,
            elapsedMs = (queue.elapsedTime * 1000).toLong(),
            currentIndex = queue.currentIndex,
            currentQueueItemId = currentItem.queueItemId,
        )
    }

    /** Logical play-state. queue.state is authoritative; player.state is the fallback. */
    fun deriveIsPlaying(queue: PlayerQueue?, player: Player?): Boolean = when {
        queue != null -> queue.state == PlayerState.PLAYING
        else -> player?.state == PlayerState.PLAYING
    }
}

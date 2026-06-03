package io.musicassistant.companion.media

/**
 * Immutable snapshot of what the MediaSession should show: the current track plus
 * its queue neighbors.
 *
 * [isLive] marks radio/live streams. For those there is no real previous/next track,
 * so [prev] and [next] are forced to null and [MaPlayer] renders a single-item timeline.
 * This prevents the "prev/next show the same song" artifact on Bluetooth AVRCP.
 */
data class QueueSnapshot(
    val prev: TrackMetadata?,
    val current: TrackMetadata,
    val next: TrackMetadata?,
    val isLive: Boolean = false
) {
    companion object {
        val EMPTY = QueueSnapshot(prev = null, current = TrackMetadata.EMPTY, next = null, isLive = false)
    }
}

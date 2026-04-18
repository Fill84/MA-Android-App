package io.musicassistant.companion.media

data class QueueSnapshot(
    val prev: TrackMetadata?,
    val current: TrackMetadata,
    val next: TrackMetadata?
) {
    companion object {
        val EMPTY = QueueSnapshot(prev = null, current = TrackMetadata.EMPTY, next = null)
    }
}

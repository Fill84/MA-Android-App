package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.QueueItem

/** Unified, derived state for ONE player — the consumable projection of the server mirror. */
data class PlayerSession(
    val playerId: String,
    val effectiveQueueId: String,
    val player: Player?,
    val queue: PlayerQueue?,
    val queueItems: List<QueueItem>,
    val nowPlaying: NowPlaying?,
    val isPlaying: Boolean,
) {
    companion object {
        fun empty(playerId: String) = PlayerSession(
            playerId = playerId,
            effectiveQueueId = playerId,
            player = null,
            queue = null,
            queueItems = emptyList(),
            nowPlaying = null,
            isPlaying = false,
        )
    }
}

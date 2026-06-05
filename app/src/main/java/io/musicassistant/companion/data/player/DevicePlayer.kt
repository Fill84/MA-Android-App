package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.Player

/**
 * Resolves "this device" to the MA player that actually represents it.
 *
 * Music Assistant exposes our locally-registered Sendspin player (`settings.playerId`, e.g.
 * `ma_<suffix>`) in the player list ONLY as its universal-player wrapper `upma_<suffix>` — the raw
 * `ma_<suffix>` is just the audio sink and never appears in `getPlayers()`. The universal player is
 * the one that holds the queue, current media and play-state, so it is the single correct target for
 * commands AND the single correct key for the now-playing session. Matching is by the id suffix, so
 * it is independent of the exact prefix MA uses.
 */
object DevicePlayer {

    /** The bare id without the known `ma_`/`upma_` prefixes (the stable per-device suffix). */
    fun suffixOf(playerId: String): String =
        playerId.removePrefix("upma_").removePrefix("ma_")

    /**
     * The id of the player in [players] that represents the device whose raw Sendspin id is
     * [rawPlayerId]. Prefers an exact `upma_<suffix>` match, then any player sharing the suffix.
     * Returns null when the device's player is not (yet) in the list.
     */
    fun resolveId(rawPlayerId: String, players: List<Player>): String? {
        if (rawPlayerId.isBlank()) return null
        val suffix = suffixOf(rawPlayerId)
        if (suffix.isBlank()) return null
        return players.firstOrNull { it.playerId == "upma_$suffix" }?.playerId
            ?: players.firstOrNull { suffixOf(it.playerId) == suffix }?.playerId
    }
}

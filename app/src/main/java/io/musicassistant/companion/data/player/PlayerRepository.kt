package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.QueueItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Single server mirror. The ONLY place that reacts to [MaApiClient.events]/[connectionState] and
 * fetches player/queue state. Exposes lazy, ref-counted per-id [session] flows (Approach C): each
 * lives only while observed (plus a 5s grace), so the device session stays warm as long as
 * MusicService collects it while roamed-to UI players are torn down shortly after.
 */
class PlayerRepository(
    private val api: MaApi,
    private val apiClient: MaApiClient,
    private val scope: CoroutineScope,
) {
    companion object { private const val TAG = "PlayerRepository" }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val sessions = ConcurrentHashMap<String, SharedFlow<PlayerSession>>()

    /** Shared, ref-counted derived state for [playerId]. */
    fun session(playerId: String): SharedFlow<PlayerSession> =
        sessions.getOrPut(playerId) {
            buildSessionFlow(playerId)
                .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
        }

    /**
     * Session for "this device", resolving the raw Sendspin id ([rawPlayerId], `ma_<suffix>`) to the
     * universal player MA actually exposes (`upma_<suffix>`) — the one that holds the queue, now-playing
     * and play-state. The raw `ma_` is only the audio sink and is never a valid session key. The id is
     * resolved once it appears in the player list (and re-resolved after re-authentication); from then
     * on the underlying [session] drives all live updates. See [DevicePlayer].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun deviceSession(rawPlayerId: String): Flow<PlayerSession> =
        deviceIdFlow(rawPlayerId).flatMapLatest { id -> session(id) }

    private fun deviceIdFlow(rawPlayerId: String): Flow<String> = channelFlow {
        var resolved: String? = null
        suspend fun tryResolve() {
            if (resolved != null) return
            val players = runCatching { api.getPlayers() }.getOrElse { emptyList() }
            val id = DevicePlayer.resolveId(rawPlayerId, players)
            if (id != null) { resolved = id; send(id) }
        }
        if (apiClient.connectionState.value == ConnectionState.AUTHENTICATED) tryResolve()
        launch {
            apiClient.connectionState.drop(1).collect { state ->
                if (state == ConnectionState.AUTHENTICATED) { resolved = null; tryResolve() }
            }
        }
        apiClient.events.collect { event ->
            if (resolved == null && (event.event == "player_added" || event.event == "player_updated")) {
                tryResolve()
            }
        }
    }.distinctUntilChanged()

    private fun effectiveQueueId(player: Player): String =
        player.activeSource?.takeIf { it.isNotBlank() } ?: player.playerId

    private fun tryParsePlayer(data: JsonElement?): Player? = data?.let {
        runCatching { json.decodeFromJsonElement(Player.serializer(), it) }.getOrNull()
    }

    private fun tryParseQueue(data: JsonElement?): PlayerQueue? = data?.let {
        runCatching { json.decodeFromJsonElement(PlayerQueue.serializer(), it) }.getOrNull()
    }

    private fun buildSessionFlow(playerId: String): Flow<PlayerSession> = channelFlow {
        var player: Player? = null
        var queueId: String = playerId
        var queue: PlayerQueue? = null
        var items: List<QueueItem> = emptyList()

        suspend fun emitState() {
            send(
                PlayerSession(
                    playerId = playerId,
                    effectiveQueueId = queueId,
                    player = player,
                    queue = queue,
                    queueItems = items,
                    nowPlaying = NowPlayingDerivation.deriveNowPlaying(player, queue),
                    isPlaying = NowPlayingDerivation.deriveIsPlaying(queue, player),
                )
            )
        }

        suspend fun loadQueue() {
            queue = runCatching { api.getPlayerQueue(queueId) }.getOrNull()
            items = runCatching { api.getPlayerQueueItems(queueId) }.getOrElse { emptyList() }
        }

        suspend fun reload() {
            player = runCatching { api.getPlayer(playerId) }.getOrNull()
            queueId = player?.let { effectiveQueueId(it) } ?: playerId
            loadQueue()
            emitState()
        }

        if (apiClient.connectionState.value == ConnectionState.AUTHENTICATED) reload()

        // Reload on each (re)authentication. drop(1) skips the current value which is
        // already handled by the explicit check above; this reacts only to future transitions.
        launch {
            apiClient.connectionState.drop(1).collect { state ->
                if (state == ConnectionState.AUTHENTICATED) reload()
            }
        }

        // Merge server events for this player/queue (inline event data; fallback fetch).
        apiClient.events.collect { event ->
            when (event.event) {
                "player_updated", "player_added" -> {
                    if (event.objectId != null && event.objectId != playerId) return@collect
                    val updated = tryParsePlayer(event.data)
                        ?: runCatching { api.getPlayer(playerId) }.getOrNull() ?: return@collect
                    player = updated
                    val newQid = effectiveQueueId(updated)
                    if (newQid != queueId) { queueId = newQid; loadQueue() }
                    emitState()
                }
                "queue_updated" -> {
                    if (event.objectId != null && event.objectId != queueId) return@collect
                    val q = tryParseQueue(event.data)
                        ?: runCatching { api.getPlayerQueue(queueId) }.getOrNull() ?: return@collect
                    queue = q
                    emitState()
                }
                "queue_items_updated" -> {
                    if (event.objectId != null && event.objectId != queueId) return@collect
                    items = runCatching { api.getPlayerQueueItems(queueId) }.getOrElse { emptyList() }
                    emitState()
                }
            }
        }
    }
}

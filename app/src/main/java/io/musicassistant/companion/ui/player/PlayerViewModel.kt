package io.musicassistant.companion.ui.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.model.QueueItem
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.media.NativeMediaManager
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel shared across all screens that need player state. Observes the MA API events to keep
 * player + queue state up to date.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val api: MaApi = ServiceLocator.api
    private val apiClient: MaApiClient = ServiceLocator.apiClient
    private val mediaManager: NativeMediaManager = ServiceLocator.getMediaManager(application)
    private val settingsRepo = SettingsModule.getRepository(application)

    // Current active player
    private val _activePlayer = MutableStateFlow<Player?>(null)
    val activePlayer: StateFlow<Player?> = _activePlayer.asStateFlow()

    // Queue state
    private val _queue = MutableStateFlow<PlayerQueue?>(null)
    val queue: StateFlow<PlayerQueue?> = _queue.asStateFlow()

    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueItems: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()

    // All players
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    // Derived state — isPlaying tracks the active remote player, not local ExoPlayer
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    val currentTrackTitle: StateFlow<String?> = mediaManager.currentTrackTitle
    val currentTrackArtist: StateFlow<String?> = mediaManager.currentTrackArtist
    val currentArtworkUri: StateFlow<String?> = mediaManager.currentArtworkUri

    val connectionState: StateFlow<ConnectionState> = apiClient.connectionState

    val currentPositionMs: Long
        get() = mediaManager.currentPositionMs
    val durationMs: Long
        get() = mediaManager.durationMs

    init {
        observeConnection()
        observeEvents()
    }

    private fun observeConnection() {
        apiClient
                .connectionState
                .onEach { state ->
                    if (state == ConnectionState.AUTHENTICATED) {
                        loadInitialData()
                    }
                }
                .launchIn(viewModelScope)
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                val allPlayers = api.getPlayers()
                _players.value = allPlayers

                // Always prefer this device's own Sendspin player
                val settings = settingsRepo.settingsFlow.first()
                val ourPlayer = allPlayers.find { it.playerId == settings.playerId }
                if (ourPlayer != null) {
                    _activePlayer.value = ourPlayer
                    _isPlaying.value = ourPlayer.state == PlayerState.PLAYING
                    loadQueue(ourPlayer.playerId)
                } else if (_activePlayer.value == null) {
                    // Our player isn't registered yet — don't pick a random one.
                    // It will be auto-selected when player_added event arrives.
                    Log.d(
                            TAG,
                            "Own player ${settings.playerId} not in list yet, waiting for registration"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load initial data: ${e.message}")
            }
        }
    }

    private fun loadQueue(queueId: String) {
        viewModelScope.launch {
            try {
                _queue.value = api.getPlayerQueue(queueId)
                _queueItems.value = api.getPlayerQueueItems(queueId)
                updateMetadataFromQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load queue: ${e.message}")
            }
        }
    }

    private fun observeEvents() {
        apiClient
                .events
                .onEach { event ->
                    when (event.event) {
                        "player_added", "player_updated" -> handlePlayerUpdated(event)
                        "queue_updated" -> handleQueueUpdated(event)
                        "queue_items_updated" -> handleQueueItemsUpdated(event)
                    }
                }
                .launchIn(viewModelScope)
    }

    private fun handlePlayerUpdated(event: MaApiClient.MaEvent) {
        viewModelScope.launch {
            try {
                val allPlayers = api.getPlayers()
                _players.value = allPlayers

                val settings = settingsRepo.settingsFlow.first()
                val currentId = _activePlayer.value?.playerId

                if (currentId != null) {
                    // Update the already-selected player
                    val updated = allPlayers.find { it.playerId == currentId }
                    _activePlayer.value = updated
                    _isPlaying.value = updated?.state == PlayerState.PLAYING
                    // Re-evaluate metadata — current_media may have changed (e.g. radio track)
                    updateMetadataFromQueue()
                } else {
                    // No player selected yet — auto-select our own device if it just appeared
                    val ourPlayer = allPlayers.find { it.playerId == settings.playerId }
                    if (ourPlayer != null) {
                        Log.d(TAG, "Auto-selecting own player: ${ourPlayer.playerId}")
                        _activePlayer.value = ourPlayer
                        _isPlaying.value = ourPlayer.state == PlayerState.PLAYING
                        loadQueue(ourPlayer.playerId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh players: ${e.message}")
            }
        }
    }

    private fun handleQueueUpdated(event: MaApiClient.MaEvent) {
        val queueId = _activePlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                val q = api.getPlayerQueue(queueId)
                _queue.value = q
                _isPlaying.value = q.state == PlayerState.PLAYING
                updateMetadataFromQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh queue: ${e.message}")
            }
        }
    }

    private fun handleQueueItemsUpdated(event: MaApiClient.MaEvent) {
        val queueId = _activePlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                _queueItems.value = api.getPlayerQueueItems(queueId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh queue items: ${e.message}")
            }
        }
    }

    private fun updateMetadataFromQueue() {
        val queue = _queue.value ?: return
        val currentItem = queue.currentItem ?: return
        val media = currentItem.mediaItem ?: return
        val playerMedia = _activePlayer.value?.currentMedia

        val isRadio = media.mediaType == MediaType.RADIO

        // For radio: prefer the player's current_media which has the actual track title/artist
        val title: String
        val artist: String
        val album: String?
        if (isRadio) {
            val pmTitle = playerMedia?.title?.takeIf { it.isNotBlank() }
            val pmArtist = playerMedia?.artist?.takeIf { it.isNotBlank() }
            // player.current_media.title = track title, .artist = artist, .album = station
            title = pmTitle ?: currentItem.name.ifBlank { media.name }
            artist = pmArtist ?: media.name
            album = playerMedia?.album ?: media.name
        } else {
            title = media.name
            artist = media.artists.joinToString(", ") { it.name }
            album = media.album?.name
        }

        Log.d(TAG, "updateMetadata: isRadio=$isRadio title='$title' artist='$artist'")

        val artworkImage = media.image ?: currentItem.image
        val artworkUrl =
                if (artworkImage != null) {
                    getImageUrl(artworkImage)
                } else null

        mediaManager.updateMetadata(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl
        )

        // Provide the server-known duration so the notification seekbar works
        if (currentItem.duration > 0) {
            mediaManager.setKnownDuration(currentItem.duration * 1000L)
        }

        // Update elapsed time for the notification seekbar position
        val elapsedMs = (queue.elapsedTime * 1000).toLong()
        val playing = queue.state == PlayerState.PLAYING
        mediaManager.setKnownElapsedTime(elapsedMs, playing)

        // Force the MediaSession to re-read duration/position for the notification
        mediaManager.invalidateSessionState()
    }

    // ── Player commands ─────────────────────────────────────

    fun play() {
        val playerId = _activePlayer.value?.playerId ?: return
        _isPlaying.value = true
        mediaManager.play()
        viewModelScope.launch {
            try {
                api.playerPlay(playerId)
            } catch (e: Exception) {
                Log.e(TAG, "Play failed: ${e.message}")
            }
        }
    }

    fun pause() {
        val playerId = _activePlayer.value?.playerId ?: return
        _isPlaying.value = false
        mediaManager.pause()
        viewModelScope.launch {
            try {
                api.playerPause(playerId)
            } catch (e: Exception) {
                Log.e(TAG, "Pause failed: ${e.message}")
            }
        }
    }

    fun next() {
        val playerId = _activePlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                api.playerNext(playerId)
            } catch (e: Exception) {
                Log.e(TAG, "Next failed: ${e.message}")
            }
        }
    }

    fun previous() {
        val playerId = _activePlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                api.playerPrevious(playerId)
            } catch (e: Exception) {
                Log.e(TAG, "Previous failed: ${e.message}")
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val playerId = _activePlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                api.playerSeek(playerId, positionMs / 1000.0)
            } catch (e: Exception) {
                Log.e(TAG, "Seek failed: ${e.message}")
            }
        }
    }

    fun setVolume(level: Int) {
        val playerId = _activePlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                api.playerVolumeSet(playerId, level)
            } catch (e: Exception) {
                Log.e(TAG, "Volume set failed: ${e.message}")
            }
        }
    }

    fun toggleShuffle() {
        val q = _queue.value ?: return
        viewModelScope.launch {
            try {
                api.queueCommandShuffle(q.queueId, !q.shuffleEnabled)
            } catch (e: Exception) {
                Log.e(TAG, "Shuffle toggle failed: ${e.message}")
            }
        }
    }

    fun toggleRepeat() {
        val q = _queue.value ?: return
        val nextMode =
                when (q.repeatMode) {
                    io.musicassistant.companion.data.model.RepeatMode.OFF -> "one"
                    io.musicassistant.companion.data.model.RepeatMode.ONE -> "all"
                    io.musicassistant.companion.data.model.RepeatMode.ALL -> "off"
                }
        viewModelScope.launch {
            try {
                api.queueCommandRepeat(q.queueId, nextMode)
            } catch (e: Exception) {
                Log.e(TAG, "Repeat toggle failed: ${e.message}")
            }
        }
    }

    fun playQueueIndex(index: Int) {
        val q = _queue.value ?: return
        viewModelScope.launch {
            try {
                api.queuePlayIndex(q.queueId, index)
            } catch (e: Exception) {
                Log.e(TAG, "Play index failed: ${e.message}")
            }
        }
    }

    fun deleteQueueItem(queueItemId: String) {
        val q = _queue.value ?: return
        viewModelScope.launch {
            try {
                api.queueDeleteItem(q.queueId, queueItemId)
            } catch (e: Exception) {
                Log.e(TAG, "Delete queue item failed: ${e.message}")
            }
        }
    }

    fun playMedia(
            mediaUri: String,
            mediaType: io.musicassistant.companion.data.model.MediaType? = null,
            option: String = "play"
    ) {
        viewModelScope.launch {
            try {
                // Prefer active player; fall back to our own device's player ID
                val queueId =
                        _activePlayer.value?.playerId
                                ?: settingsRepo.settingsFlow.first().playerId.ifEmpty { null }
                                        ?: return@launch
                api.playMedia(queueId, mediaUri, mediaType, option)
            } catch (e: Exception) {
                Log.e(TAG, "Play media failed: ${e.message}")
            }
        }
    }

    fun selectPlayer(playerId: String) {
        val selected = _players.value.find { it.playerId == playerId }
        _activePlayer.value = selected
        _isPlaying.value = selected?.state == PlayerState.PLAYING
        loadQueue(playerId)
    }

    fun getImageUrl(imagePath: String): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return api.getImageUrl(imagePath, baseUrl)
    }

    fun getImageUrl(image: MediaItemImage): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return api.getImageUrl(image, baseUrl)
    }
}

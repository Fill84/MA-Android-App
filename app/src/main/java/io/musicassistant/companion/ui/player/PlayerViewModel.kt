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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun tryParsePlayer(data: JsonElement?): Player? {
        data ?: return null
        return try { json.decodeFromJsonElement(Player.serializer(), data) }
        catch (e: Exception) { Log.w(TAG, "Parse Player from event failed: ${e.message}"); null }
    }

    private fun tryParsePlayerQueue(data: JsonElement?): PlayerQueue? {
        data ?: return null
        return try { json.decodeFromJsonElement(PlayerQueue.serializer(), data) }
        catch (e: Exception) { Log.w(TAG, "Parse PlayerQueue from event failed: ${e.message}"); null }
    }

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
    // Guard against event-driven flicker after user action (play/pause)
    private var lastUserActionMs: Long = 0L
    private val USER_ACTION_DEBOUNCE_MS = 1500L

    /** Update isPlaying from server state, respecting debounce after user actions. */
    private fun setIsPlayingFromServer(serverPlaying: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        val inDebounce = now - lastUserActionMs < USER_ACTION_DEBOUNCE_MS
        // During debounce: only accept events that confirm the user action, not contradict it
        if (!inDebounce || serverPlaying == _isPlaying.value) {
            _isPlaying.value = serverPlaying
        }
    }

    // UI metadata — tracks whichever player is selected (not necessarily this device)
    private val _currentTrackTitle = MutableStateFlow<String?>(null)
    val currentTrackTitle: StateFlow<String?> = _currentTrackTitle.asStateFlow()
    private val _currentTrackArtist = MutableStateFlow<String?>(null)
    val currentTrackArtist: StateFlow<String?> = _currentTrackArtist.asStateFlow()
    private val _currentArtworkUri = MutableStateFlow<String?>(null)
    val currentArtworkUri: StateFlow<String?> = _currentArtworkUri.asStateFlow()

    // Duration/position — for selected player
    private var _uiDurationMs: Long = 0L
    private var _uiElapsedMs: Long = 0L
    private var _uiElapsedAtMs: Long = 0L
    private var _uiIsPlaying: Boolean = false

    val connectionState: StateFlow<ConnectionState> = apiClient.connectionState

    val currentPositionMs: Long
        get() {
            if (_uiDurationMs <= 0) return mediaManager.currentPositionMs
            val base = _uiElapsedMs
            return if (_uiIsPlaying) {
                val delta = android.os.SystemClock.elapsedRealtime() - _uiElapsedAtMs
                (base + delta).coerceIn(0L, _uiDurationMs)
            } else {
                base.coerceAtLeast(0L)
            }
        }
    val durationMs: Long
        get() = if (_uiDurationMs > 0) _uiDurationMs else mediaManager.durationMs

    // Whether the current media item is a live stream (radio, etc.)
    private val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

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
                // Queue doesn't exist for this player — clear metadata
                _queue.value = null
                _queueItems.value = emptyList()
                updateMetadataFromQueue()
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
        val changedId = event.objectId
        val isAdded = event.event == "player_added"
        viewModelScope.launch {
            try {
                if (changedId != null && !isAdded) {
                    // Use event data directly (avoids extra round-trip); fallback to fetch
                    val updated = tryParsePlayer(event.data) ?: api.getPlayer(changedId)
                    _players.value =
                            _players.value.map { if (it.playerId == changedId) updated else it }
                    if (_activePlayer.value?.playerId == changedId) {
                        _activePlayer.value = updated
                        // Don't update isPlaying here — queue_updated is authoritative for play state.
                        // player_updated can have transitional states that cause flicker.
                        updateMetadataFromQueue()
                    }
                } else {
                    // player_added or no objectId — full refresh to pick up new players
                    val allPlayers = api.getPlayers()
                    _players.value = allPlayers

                    val currentId = _activePlayer.value?.playerId
                    if (currentId != null) {
                        val updated = allPlayers.find { it.playerId == currentId }
                        _activePlayer.value = updated
                        // isPlaying driven by queue_updated, not player_updated
                    } else {
                        // Auto-select our own device if it just appeared
                        val settings = settingsRepo.settingsFlow.first()
                        val ourPlayer = allPlayers.find { it.playerId == settings.playerId }
                        if (ourPlayer != null) {
                            Log.d(TAG, "Auto-selecting own player: ${ourPlayer.playerId}")
                            _activePlayer.value = ourPlayer
                            _isPlaying.value = ourPlayer.state == PlayerState.PLAYING
                            loadQueue(ourPlayer.playerId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh players: ${e.message}")
            }
        }
    }

    private fun handleQueueUpdated(event: MaApiClient.MaEvent) {
        val queueId = _activePlayer.value?.playerId ?: return
        // Only process events for the active queue
        if (event.objectId != null && event.objectId != queueId) return
        viewModelScope.launch {
            try {
                val prevItemId = _queue.value?.currentItem?.queueItemId
                // Use event data directly (avoids extra round-trip); fallback to fetch
                val q = tryParsePlayerQueue(event.data) ?: api.getPlayerQueue(queueId)
                _queue.value = q
                setIsPlayingFromServer(q.state == PlayerState.PLAYING)

                if (q.currentItem?.queueItemId != prevItemId) {
                    // Track changed — full UI metadata update
                    updateMetadataFromQueue()
                } else {
                    // Same track — update elapsed time for UI seekbar
                    updateElapsedTimeFromQueue()
                }
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

    /**
     * Update UI metadata from queue for the selected player.
     * Notification metadata is managed by MusicService (single source of truth for this device).
     * This method handles UI display for ANY selected player.
     */
    private fun updateMetadataFromQueue() {
        val queue = _queue.value
        val currentItem = queue?.currentItem
        val media = currentItem?.mediaItem
        if (queue == null || currentItem == null || media == null) {
            // No active content — clear UI metadata
            _currentTrackTitle.value = null
            _currentTrackArtist.value = null
            _currentArtworkUri.value = null
            _uiDurationMs = 0L
            _uiElapsedMs = 0L
            _isLive.value = false
            return
        }
        val playerMedia = _activePlayer.value?.currentMedia

        val isRadio = media.mediaType == MediaType.RADIO
        _isLive.value = isRadio || currentItem.duration <= 0

        // Set UI metadata
        if (isRadio) {
            val pmTitle = playerMedia?.title?.takeIf { it.isNotBlank() }
            val pmArtist = playerMedia?.artist?.takeIf { it.isNotBlank() }
            _currentTrackTitle.value = pmTitle ?: currentItem.name.ifBlank { media.name }
            _currentTrackArtist.value = pmArtist ?: media.name
        } else {
            _currentTrackTitle.value = media.name
            _currentTrackArtist.value = media.artists.joinToString(", ") { it.name }
        }

        val artworkImage = media.image ?: currentItem.image
        _currentArtworkUri.value = if (artworkImage != null) getImageUrl(artworkImage) else null

        // Duration/position for UI
        _uiDurationMs = if (currentItem.duration > 0 && !_isLive.value) currentItem.duration * 1000L else 0L
        updateElapsedTimeFromQueue()
    }

    /** Update elapsed time for UI seekbar. */
    private fun updateElapsedTimeFromQueue() {
        val queue = _queue.value ?: return
        _uiElapsedMs = (queue.elapsedTime * 1000).toLong()
        _uiElapsedAtMs = android.os.SystemClock.elapsedRealtime()
        _uiIsPlaying = queue.state == PlayerState.PLAYING
    }

    // ── Player commands ─────────────────────────────────────

    fun play() {
        val playerId = _activePlayer.value?.playerId ?: return
        lastUserActionMs = android.os.SystemClock.elapsedRealtime()
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
        lastUserActionMs = android.os.SystemClock.elapsedRealtime()
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
        val newValue = !q.shuffleEnabled
        _queue.value = q.copy(shuffleEnabled = newValue)
        viewModelScope.launch {
            try {
                api.queueCommandShuffle(q.queueId, newValue)
            } catch (e: Exception) {
                Log.e(TAG, "Shuffle toggle failed: ${e.message}")
                _queue.value = q // revert on failure
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
        val nextRepeatMode =
                when (nextMode) {
                    "one" -> io.musicassistant.companion.data.model.RepeatMode.ONE
                    "all" -> io.musicassistant.companion.data.model.RepeatMode.ALL
                    else -> io.musicassistant.companion.data.model.RepeatMode.OFF
                }
        _queue.value = q.copy(repeatMode = nextRepeatMode)
        viewModelScope.launch {
            try {
                api.queueCommandRepeat(q.queueId, nextMode)
            } catch (e: Exception) {
                Log.e(TAG, "Repeat toggle failed: ${e.message}")
                _queue.value = q // revert on failure
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

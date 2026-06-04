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
import io.musicassistant.companion.data.player.PlayerSession
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val repo = ServiceLocator.playerRepository
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

    // Current active player (the command target — usually this device's own player).
    private val _activePlayer = MutableStateFlow<Player?>(null)
    val activePlayer: StateFlow<Player?> = _activePlayer.asStateFlow()

    /** The player id whose session feeds the UI; null until selected/auto-selected. */
    private val selectedId = MutableStateFlow<String?>(null)

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
            val base = _uiElapsedMs
            return if (_uiIsPlaying) {
                val delta = android.os.SystemClock.elapsedRealtime() - _uiElapsedAtMs
                val pos = base + delta
                if (_uiDurationMs > 0) pos.coerceIn(0L, _uiDurationMs) else pos.coerceAtLeast(0L)
            } else {
                base.coerceAtLeast(0L)
            }
        }
    val durationMs: Long
        get() = if (_uiDurationMs > 0) _uiDurationMs else 0L

    // Whether the current media item is a live stream (radio, etc.)
    private val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

    /** This device's own player id (e.g. the Sendspin "Pixel 5" player), loaded once. */
    private var ownPlayerId: String? = null

    init {
        observeConnection()
        observeEvents()
        observeSelectedSession()
        autoSelectPlayingPlayer()
        viewModelScope.launch {
            runCatching { ownPlayerId = settingsRepo.settingsFlow.first().playerId }
        }
    }

    /**
     * Auto-select a player for the now-playing UI (mini-player) when the user hasn't picked one.
     * Reacts to the player list so it works regardless of HOW/WHEN our Sendspin player (e.g.
     * "Pixel 5") registers after an app restart — the earlier approach waited for a specific event
     * that didn't reliably fire, leaving the bar empty while music played. Prefers our own playing
     * player, then any playing player, then our own (any state). Stops once anything is selected, so
     * a manual selection always wins.
     */
    private fun autoSelectPlayingPlayer() {
        _players
                .onEach { list ->
                    if (_activePlayer.value != null) return@onEach
                    val pick = list.firstOrNull { it.playerId == ownPlayerId && it.state == PlayerState.PLAYING }
                            ?: list.firstOrNull { it.state == PlayerState.PLAYING }
                            ?: list.firstOrNull { it.playerId == ownPlayerId }
                    if (pick != null) {
                        Log.d(TAG, "auto-select now-playing: ${pick.playerId} (${pick.name}) state=${pick.state}")
                        applyActivePlayer(pick)
                    }
                }
                .launchIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSelectedSession() {
        selectedId
            .flatMapLatest { id -> if (id == null) flowOf(null) else repo.session(id) }
            .onEach { session: PlayerSession? ->
                if (session != null) {
                    session.player?.let { _activePlayer.value = it }
                    setIsPlayingFromServer(session.isPlaying)
                    _queue.value = session.queue
                    _queueItems.value = session.queueItems
                    updateMetadataFromQueue()
                }
            }
            .launchIn(viewModelScope)
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
                // Selection is handled reactively by [autoSelectPlayingPlayer]; just publish the list.
                _players.value = api.getPlayers()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load initial data: ${e.message}")
            }
        }
    }

    /** Make [player] the active (command-target) player. Queue/metadata come from the session. */
    private fun applyActivePlayer(player: Player) {
        _activePlayer.value = player
        _isPlaying.value = player.state == PlayerState.PLAYING
        selectedId.value = player.playerId
    }

    private fun observeEvents() {
        apiClient
                .events
                .onEach { event ->
                    when (event.event) {
                        "player_added", "player_updated" -> handlePlayerUpdated(event)
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
                    val updated = tryParsePlayer(event.data) ?: api.getPlayer(changedId) ?: return@launch
                    _players.value = if (_players.value.any { it.playerId == changedId }) {
                        _players.value.map { if (it.playerId == changedId) updated else it }
                    } else {
                        _players.value + updated
                    }
                } else {
                    // player_added or no objectId — full refresh to pick up new players.
                    _players.value = api.getPlayers()
                }
                // Active player / queue / metadata are driven reactively by [observeSelectedSession];
                // [autoSelectPlayingPlayer] reacts to the list update when nothing is selected yet.
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh players: ${e.message}")
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

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun deleteQueueItem(queueItemId: String) {
        val q = _queue.value ?: return
        viewModelScope.launch {
            try {
                api.queueDeleteItem(q.queueId, queueItemId)
            } catch (e: Exception) {
                Log.e(TAG, "Delete queue item failed: ${e.message}")
                _userMessage.tryEmit("Could not remove track from queue")
            }
        }
    }

    fun clearQueue() {
        val q = _queue.value ?: return
        viewModelScope.launch {
            try {
                api.queueClear(q.queueId)
            } catch (e: Exception) {
                Log.e(TAG, "Clear queue failed: ${e.message}")
                _userMessage.tryEmit("Could not clear queue")
            }
        }
    }

    fun moveQueueItem(queueItemId: String, positionShift: Int) {
        val q = _queue.value ?: return
        viewModelScope.launch {
            try {
                api.queueMoveItem(q.queueId, queueItemId, positionShift)
            } catch (e: Exception) {
                Log.e(TAG, "Move queue item failed: ${e.message}")
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
        if (selected != null) {
            applyActivePlayer(selected)
        } else {
            // Player not in the loaded list — subscribe to session; queue arrives reactively.
            _activePlayer.value = null
            selectedId.value = playerId
        }
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

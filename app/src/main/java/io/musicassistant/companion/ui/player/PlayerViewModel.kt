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
import io.musicassistant.companion.data.player.DevicePlayer
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val repo = ServiceLocator.playerRepository
    private val settingsRepo = SettingsModule.getRepository(application)

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

    // All players — single source of truth is PlayerRepository (no duplicate event handling here).
    val players: StateFlow<List<Player>> = repo.players

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

    /** This device's raw Sendspin player id (`ma_<suffix>`), loaded once. */
    private val ownPlayerId = MutableStateFlow<String?>(null)

    /** True once the user explicitly picks a player; their choice then overrides the default. */
    private var userSelected = false

    /**
     * The MA player id that represents THIS device — the universal `upma_<suffix>` wrapper, resolved
     * from the live player list. This is the single correct command target and session key for the
     * device; the raw `ma_` is only the audio sink and must never be targeted.
     */
    private fun thisDeviceId(): String? =
        ownPlayerId.value?.let { DevicePlayer.resolveId(it, players.value) }

    init {
        observeSelectedSession()
        autoSelectPlayingPlayer()
        viewModelScope.launch {
            runCatching { ownPlayerId.value = settingsRepo.settingsFlow.first().playerId }
        }
    }

    /**
     * Keep "this device" selected as the active player by default. SSOT rule: an active player is
     * always THIS device (the `upma_<suffix>` universal player) — even when idle — unless the user
     * has explicitly picked another player to control. Reacting to the player list (and our own id)
     * means it converges as soon as our player registers after an app restart, and re-targets this
     * device if it appeared only after a transient fallback. A manual [selectPlayer] sets
     * [userSelected] and wins from then on. Falls back to any playing player only while this device
     * is not yet in the list, so the mini-player is never needlessly empty.
     */
    private fun autoSelectPlayingPlayer() {
        combine(players, ownPlayerId) { list, own -> list to own }
                .onEach { (list, own) ->
                    if (userSelected) return@onEach
                    val deviceId = thisDeviceId()
                    // Until our own id is known, don't fall back to another playing player — that
                    // would briefly select the wrong player and then flip to this device (flicker).
                    val pick = list.firstOrNull { it.playerId == deviceId }
                            ?: if (own != null) list.firstOrNull { it.state == PlayerState.PLAYING } else null
                    if (pick != null && pick.playerId != _activePlayer.value?.playerId) {
                        Log.d(TAG, "auto-select this-device/now-playing: ${pick.playerId} (${pick.name}) state=${pick.state}")
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

    /** Make [player] the active (command-target) player. Queue/metadata come from the session. */
    private fun applyActivePlayer(player: Player) {
        _activePlayer.value = player
        _isPlaying.value = player.state == PlayerState.PLAYING
        selectedId.value = player.playerId
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
                _isPlaying.value = false // revert optimistic state
                _userMessage.tryEmit("Couldn't start playback")
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
                _isPlaying.value = true // revert optimistic state
                _userMessage.tryEmit("Couldn't pause")
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
                _userMessage.tryEmit("Couldn't skip track")
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
                _userMessage.tryEmit("Couldn't skip track")
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
                _userMessage.tryEmit("Could not move track")
            }
        }
    }

    fun playMedia(
            mediaUri: String,
            mediaType: io.musicassistant.companion.data.model.MediaType? = null,
            option: String = "play"
    ) {
        viewModelScope.launch {
            // Target the active player; fall back to THIS device's universal player (never the
            // raw ma_ sink, which ignores playback commands).
            val queueId = _activePlayer.value?.playerId ?: thisDeviceId()
            if (queueId == null) {
                _userMessage.tryEmit("No player available to play on")
                return@launch
            }
            try {
                api.playMedia(queueId, mediaUri, mediaType, option)
            } catch (e: Exception) {
                Log.e(TAG, "Play media failed: ${e.message}")
                _userMessage.tryEmit("Couldn't start playback")
            }
        }
    }

    fun selectPlayer(playerId: String) {
        userSelected = true
        val selected = players.value.find { it.playerId == playerId }
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

package io.musicassistant.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.session.MediaStyleNotificationHelper
import io.musicassistant.companion.MainActivity
import io.musicassistant.companion.R
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.sendspin.SendspinClient
import io.musicassistant.companion.data.sendspin.SendspinState
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.media.MaPlayer
import io.musicassistant.companion.media.MediaMetadataCoordinator
import io.musicassistant.companion.media.MediaSessionHost
import io.musicassistant.companion.media.TrackMetadata
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Foreground service that manages the connection to the MA server and audio playback.
 *
 * The media surface (notification, lock screen, Bluetooth AVRCP, Android Auto) is driven by the
 * [MediaMetadataCoordinator] → [MaPlayer] → [MediaSessionHost] stack (all shared singletons in
 * [ServiceLocator]). This service only feeds MA/Sendspin events into the Coordinator and routes
 * transport commands back out to the MA API. It owns no artwork cache and decodes no bitmaps for
 * the session — the Coordinator/ArtworkPipeline are the single source of truth for cover bytes.
 */
class MusicService : LifecycleService() {

    companion object {
        private const val TAG = "MusicService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ma_playback"

        const val ACTION_START = "io.musicassistant.companion.START"
        const val ACTION_STOP = "io.musicassistant.companion.STOP"
        const val ACTION_PLAY = "io.musicassistant.companion.PLAY"
        const val ACTION_PAUSE = "io.musicassistant.companion.PAUSE"
        const val ACTION_NEXT = "io.musicassistant.companion.NEXT"
        const val ACTION_PREVIOUS = "io.musicassistant.companion.PREVIOUS"
    }

    val apiClient: MaApiClient by lazy { ServiceLocator.apiClient }

    // New media stack (shared singletons)
    private lateinit var coordinator: MediaMetadataCoordinator
    private lateinit var mediaPlayer: MaPlayer
    private lateinit var mediaSessionHost: MediaSessionHost

    /** Logical play/pause state for the notification. */
    private val playingState = MutableStateFlow(false)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var isForeground = false
    private var connectionObserverStarted = false
    private var sendspinObserverStarted = false
    private var eventObserverStarted = false
    private var activePlayerId: String? = null
    /** Queue ID — usually equals playerId, but may differ in sync groups. */
    private var activeQueueId: String? = null

    // Current queue item ID — used to detect track changes
    private var currentQueueItemId: String? = null

    // Cache previous track info for MediaSession prev metadata (PlayerQueue has no previousItem).
    private var previousTrackTitle: String? = null
    private var previousTrackArtist: String? = null

    // WakeLock to prevent CPU throttling during background playback
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Command-Echo Guard ──────────────────────────────────
    private data class PendingCommand(val action: String, val timestamp: Long)
    private val pendingCommands = ConcurrentHashMap<String, PendingCommand>()
    private val COMMAND_ECHO_WINDOW_MS = 3000L

    private fun markCommandPending(action: String) {
        pendingCommands[action] = PendingCommand(action, SystemClock.elapsedRealtime())
    }

    private fun isCommandEcho(action: String): Boolean {
        val pending = pendingCommands.remove(action) ?: return false
        val isEcho = (SystemClock.elapsedRealtime() - pending.timestamp) < COMMAND_ECHO_WINDOW_MS
        if (isEcho) Log.d(TAG, "Suppressed command echo: $action")
        return isEcho
    }

    // ── Sendspin Reconnect Grace Period ────────────────────
    @Volatile private var sendspinDisconnectedAt: Long = 0L
    private val SENDSPIN_RECONNECT_GRACE_MS = 5000L

    private fun isSendspinReconnecting(): Boolean {
        val client = ServiceLocator.sendspinClient ?: return false
        val state = client.state.value
        if (state is SendspinState.Ready || state is SendspinState.Synchronized) {
            val elapsed = SystemClock.elapsedRealtime() - sendspinDisconnectedAt
            return elapsed < 2000L
        }
        return state is SendspinState.Reconnecting && sendspinDisconnectedAt > 0L
    }

    // Notification dedup state
    private var lastNotifTitle: String? = null
    private var lastNotifArtist: String? = null
    private var lastNotifIsPlaying: Boolean = false
    private var lastArtBytes: ByteArray? = null
    private var lastArtBitmap: android.graphics.Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val browseCallback = ServiceLocator.getOrCreateAutoBrowseCallback()
        browseCallback.appContext = applicationContext
        // Hardware media keys (AVRCP passthrough) still route through the browse callback.
        browseCallback.onNextRequested = { handleNext() }
        browseCallback.onPreviousRequested = { handlePrevious() }

        coordinator = ServiceLocator.getCoordinator(this)
        mediaPlayer = ServiceLocator.getMediaPlayer(this)
        mediaSessionHost = ServiceLocator.getMediaSessionHost(this)
        mediaPlayer.startObservingSnapshot(lifecycleScope)

        // Route transport commands from the session out to the MA API.
        mediaPlayer.onPlayRequested = { handlePlay() }
        mediaPlayer.onPauseRequested = { handlePause() }
        mediaPlayer.onNextRequested = { handleNext() }
        mediaPlayer.onPreviousRequested = { handlePrevious() }
        mediaPlayer.onSeekRequested = { positionMs -> handleSeek(positionMs) }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicAssistant::Playback")
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4-hour timeout safety net

        startForeground()
        observePlaybackState()
        Log.d(TAG, "MusicService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startConnection()
            ACTION_STOP -> stopSelf()
            ACTION_PLAY -> handlePlay()
            ACTION_PAUSE -> handlePause()
            ACTION_NEXT -> handleNext()
            ACTION_PREVIOUS -> handlePrevious()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed, service continues running")
    }

    override fun onDestroy() {
        mediaPlayer.onPlayRequested = null
        mediaPlayer.onPauseRequested = null
        mediaPlayer.onNextRequested = null
        mediaPlayer.onPreviousRequested = null
        mediaPlayer.onSeekRequested = null
        ServiceLocator.autoBrowseCallback?.onNextRequested = null
        ServiceLocator.autoBrowseCallback?.onPreviousRequested = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        apiClient.disconnect()
        lifecycleScope.launch { ServiceLocator.sendspinClient?.stop() }
        mediaPlayer.stopObservingSnapshot()
        ServiceLocator.releaseMediaStack()
        Log.d(TAG, "MusicService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startConnection() {
        lifecycleScope.launch {
            val settings = SettingsModule.getRepository(this@MusicService).settingsFlow.first()
            if (!settings.isConfigured || settings.serverUrl.isEmpty()) return@launch

            val token = settings.authToken.ifEmpty { null }

            val playerId =
                settings.playerId.ifEmpty {
                    val newId = SendspinClient.generatePlayerId()
                    SettingsModule.getRepository(this@MusicService).setPlayerId(newId)
                    newId
                }
            activePlayerId = playerId
            activeQueueId = playerId

            val existingClient = ServiceLocator.sendspinClient
            if (apiClient.connectionState.value == ConnectionState.AUTHENTICATED &&
                existingClient != null &&
                existingClient.state.value !is SendspinState.Idle &&
                existingClient.state.value !is SendspinState.Error) {
                Log.d(TAG, "Already connected, skipping reconnection")
                ensureObserversStarted(existingClient)
                return@launch
            }

            if (apiClient.connectionState.value != ConnectionState.AUTHENTICATED) {
                apiClient.connect(settings.serverUrl, token)
            }

            if (!connectionObserverStarted) {
                connectionObserverStarted = true
                apiClient
                    .connectionState
                    .onEach { state ->
                        if (state == ConnectionState.AUTHENTICATED) {
                            val client = ServiceLocator.getSendspinClient(
                                this@MusicService, playerId, settings.serverUrl, token
                            )
                            if (client.state.value is SendspinState.Idle) {
                                client.start()
                            }
                            ensureObserversStarted(client)
                        }
                    }
                    .launchIn(lifecycleScope)
            }
        }
    }

    private fun ensureObserversStarted(client: SendspinClient) {
        if (!sendspinObserverStarted) {
            sendspinObserverStarted = true
            observeSendspinState(client)
            observeSendspinConnectionState(client)
        }
        if (!eventObserverStarted) {
            eventObserverStarted = true
            observeApiEvents()
        }
    }

    // ── Transport commands ──────────────────────────────────

    /** Reflect play/pause locally (notification + session) without calling the API. */
    private fun setLocalPlaying(playing: Boolean) {
        playingState.value = playing
        mediaPlayer.setStreamPlaying(playing)
        updateNotification()
    }

    private fun handlePlay() {
        val id = activePlayerId ?: return
        markCommandPending("play")
        setLocalPlaying(true)
        lifecycleScope.launch {
            try {
                ServiceLocator.api.playerPlay(id)
            } catch (e: Exception) {
                Log.e(TAG, "Play failed: ${e.message}")
            }
        }
    }

    private fun handlePause() {
        val id = activePlayerId ?: return
        markCommandPending("pause")
        setLocalPlaying(false)
        lifecycleScope.launch {
            try {
                ServiceLocator.api.playerPause(id)
            } catch (e: Exception) {
                Log.e(TAG, "Pause failed: ${e.message}")
            }
        }
    }

    private fun handleNext() {
        val id = activePlayerId ?: return
        markCommandPending("next")
        lifecycleScope.launch {
            try {
                ServiceLocator.api.playerNext(id)
            } catch (e: Exception) {
                Log.e(TAG, "Next failed: ${e.message}")
            }
        }
    }

    private fun handlePrevious() {
        val id = activePlayerId ?: return
        markCommandPending("previous")
        lifecycleScope.launch {
            try {
                ServiceLocator.api.playerPrevious(id)
            } catch (e: Exception) {
                Log.e(TAG, "Previous failed: ${e.message}")
            }
        }
    }

    private fun handleSeek(positionMs: Long) {
        val id = activePlayerId ?: return
        lifecycleScope.launch {
            try {
                ServiceLocator.api.playerSeek(id, positionMs / 1000.0)
            } catch (e: Exception) {
                Log.e(TAG, "Seek failed: ${e.message}")
            }
        }
    }

    // ── Observers ───────────────────────────────────────────

    private fun observePlaybackState() {
        lifecycleScope.launch { playingState.collect { updateNotification() } }
        lifecycleScope.launch { coordinator.snapshot.collect { updateNotification() } }
    }

    private fun observeSendspinState(client: SendspinClient) {
        client.state
            .onEach { state ->
                when (state) {
                    is SendspinState.Synchronized, is SendspinState.Buffering -> {
                        mediaPlayer.setStreamActive(true)
                        playingState.value = true
                        refreshMetadataFromServer()
                    }
                    is SendspinState.Ready, is SendspinState.Idle -> {
                        mediaPlayer.setStreamActive(false)
                        playingState.value = false
                    }
                    is SendspinState.Error -> {
                        Log.e(TAG, "Sendspin error: ${state.error}")
                        mediaPlayer.setStreamActive(false)
                        playingState.value = false
                    }
                    else -> {}
                }
            }
            .launchIn(lifecycleScope)

        // Sendspin stream metadata (e.g. radio track changes). No artwork bytes/URL —
        // the Coordinator preserves the current cover when the trackId is unchanged.
        client.metadata
            .onEach { metadata ->
                if (metadata != null && (metadata.title != null || metadata.artist != null)) {
                    Log.d(TAG, "Sendspin metadata: ${metadata.title} by ${metadata.artist}")
                    coordinator.pushSendspinMetadata(
                        title = metadata.title ?: "",
                        artist = metadata.artist ?: "",
                        album = metadata.album
                    )
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun observeApiEvents() {
        apiClient
            .events
            .onEach { event ->
                when (event.event) {
                    "queue_updated" -> handleQueueUpdated(event)
                    "player_updated" -> handlePlayerUpdated(event)
                }
            }
            .launchIn(lifecycleScope)
    }

    private var playerUpdatedJob: Job? = null
    private var queueUpdatedJob: Job? = null

    private fun tryParsePlayerQueue(data: JsonElement?): PlayerQueue? {
        data ?: return null
        return try {
            json.decodeFromJsonElement(PlayerQueue.serializer(), data)
        } catch (e: Exception) {
            Log.w(TAG, "Parse PlayerQueue from event failed: ${e.message}"); null
        }
    }

    private fun handleQueueUpdated(event: MaApiClient.MaEvent) {
        val queueId = activeQueueId ?: activePlayerId ?: return
        if (event.objectId != null && event.objectId != queueId) return
        if (event.objectId != null) activeQueueId = event.objectId

        val inlineQueue = tryParsePlayerQueue(event.data)

        queueUpdatedJob?.cancel()
        queueUpdatedJob = lifecycleScope.launch {
            if (inlineQueue == null) delay(150)
            try {
                val q = inlineQueue ?: ServiceLocator.api.getPlayerQueue(queueId) ?: return@launch
                val prevItemId = currentQueueItemId
                if (q.currentItem?.queueItemId != prevItemId) {
                    updateMetadataFromQueue(q)
                } else {
                    updateElapsedTimeFromQueue(q)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to handle queue_updated: ${e.message}")
            }
        }
    }

    private fun handlePlayerUpdated(event: MaApiClient.MaEvent) {
        val id = activePlayerId ?: return
        if (event.objectId != null && event.objectId != id) return

        playerUpdatedJob?.cancel()
        playerUpdatedJob = lifecycleScope.launch {
            delay(200)
            try {
                if (isSendspinReconnecting()) {
                    Log.d(TAG, "Skipping player_updated during Sendspin reconnect")
                    return@launch
                }
                val player = ServiceLocator.api.getPlayer(id) ?: return@launch
                when (player.state) {
                    PlayerState.PAUSED, PlayerState.IDLE -> {
                        if (playingState.value && !isCommandEcho("pause")) {
                            Log.d(TAG, "External pause/stop detected, pausing locally")
                            setLocalPlaying(false)
                        }
                    }
                    PlayerState.PLAYING -> {
                        if (!playingState.value && !isCommandEcho("play")) {
                            Log.d(TAG, "External play detected, resuming locally")
                            setLocalPlaying(true)
                        }
                    }
                    else -> {}
                }
                val qId = activeQueueId ?: id
                val q = ServiceLocator.api.getPlayerQueue(qId) ?: return@launch
                val prevItemId = currentQueueItemId
                if (q.currentItem?.queueItemId != prevItemId) {
                    updateMetadataFromQueue(q)
                } else {
                    updateElapsedTimeFromQueue(q)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to handle player_updated: ${e.message}")
            }
        }
    }

    private fun refreshMetadataFromServer() {
        val queueId = activeQueueId ?: activePlayerId ?: return
        lifecycleScope.launch {
            try {
                val q = ServiceLocator.api.getPlayerQueue(queueId) ?: return@launch
                updateMetadataFromQueue(q)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh metadata: ${e.message}")
            }
        }
    }

    // ── Metadata → Coordinator ──────────────────────────────

    /**
     * Build [TrackMetadata] from the queue and push it to the Coordinator, which resolves artwork
     * bytes (download + fallback) and emits the snapshot. For radio/live the now-playing song lives
     * in `player.current_media` — its title/artist AND its `image_url` (the real song cover) are
     * used, with the station image as a fallback (issue 1). Radio pushes with isLive=true so there
     * are no phantom prev/next neighbors (issue 4).
     */
    private fun updateMetadataFromQueue(queue: PlayerQueue) {
        val currentItem = queue.currentItem ?: return
        val media = currentItem.mediaItem ?: return
        currentQueueItemId = currentItem.queueItemId
        val isRadio = media.mediaType == MediaType.RADIO

        lifecycleScope.launch {
            if (isRadio) {
                val player = try {
                    ServiceLocator.api.getPlayer(activePlayerId ?: return@launch)
                } catch (e: Exception) {
                    null
                }
                val cm = player?.currentMedia
                val title = cm?.title?.takeIf { it.isNotBlank() }
                    ?: currentItem.name.ifBlank { media.name }
                val artist = cm?.artist?.takeIf { it.isNotBlank() } ?: media.name
                val album = cm?.album ?: media.name
                // Prefer the now-playing song cover; fall back to the station logo.
                val artworkUrl = cm?.imageUrl?.takeIf { it.isNotBlank() }
                    ?: (media.image ?: currentItem.image)?.let { getImageUrl(it) }

                Log.d(TAG, "radio metadata: title='$title' artist='$artist' artwork=$artworkUrl")
                coordinator.pushQueueUpdate(
                    current = TrackMetadata(title, artist, album, artworkUrl, null),
                    prev = null,
                    next = null,
                    isLive = true
                )
                mediaPlayer.setKnownDuration(C.TIME_UNSET)
            } else {
                val title = media.name
                val artist = media.artists.joinToString(", ") { it.name }
                val album = media.album?.name
                val artworkUrl = (media.image ?: currentItem.image)?.let { getImageUrl(it) }

                val prev = if (previousTrackTitle != null || previousTrackArtist != null) {
                    TrackMetadata(previousTrackTitle ?: "", previousTrackArtist ?: "", null, null, null)
                } else null

                val nextMedia = queue.nextItem?.mediaItem
                val next = if (nextMedia != null) {
                    TrackMetadata(
                        title = nextMedia.name,
                        artist = nextMedia.artists.joinToString(", ") { it.name },
                        album = nextMedia.album?.name,
                        artworkUrl = nextMedia.image?.let { getImageUrl(it) },
                        artworkBytes = null
                    )
                } else null

                Log.d(TAG, "track metadata: title='$title' artist='$artist' prev='$previousTrackTitle' next='${nextMedia?.name}' artwork=$artworkUrl")
                coordinator.pushQueueUpdate(
                    current = TrackMetadata(title, artist, album, artworkUrl, null),
                    prev = prev,
                    next = next,
                    isLive = false
                )

                previousTrackTitle = title
                previousTrackArtist = artist

                if (currentItem.duration > 0) {
                    mediaPlayer.setKnownDuration(currentItem.duration * 1000L)
                } else {
                    mediaPlayer.setKnownDuration(C.TIME_UNSET)
                }
            }

            val elapsedMs = (queue.elapsedTime * 1000).toLong()
            val playing = queue.state == PlayerState.PLAYING
            mediaPlayer.setKnownElapsed(elapsedMs, playing)
            if (playing != playingState.value) setLocalPlaying(playing)
            mediaPlayer.invalidate()
            updateNotification()
        }
    }

    /** Lightweight update — only elapsed time/duration, no metadata rebuild. */
    private fun updateElapsedTimeFromQueue(queue: PlayerQueue) {
        val currentItem = queue.currentItem ?: return
        if (currentItem.duration > 0) {
            mediaPlayer.setKnownDuration(currentItem.duration * 1000L)
        }
        val elapsedMs = (queue.elapsedTime * 1000).toLong()
        val playing = queue.state == PlayerState.PLAYING
        mediaPlayer.setKnownElapsed(elapsedMs, playing)
    }

    private fun getImageUrl(image: MediaItemImage): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return ServiceLocator.api.getImageUrl(image, baseUrl)
    }

    private fun observeSendspinConnectionState(client: SendspinClient) {
        client.state
            .onEach { state ->
                when (state) {
                    is SendspinState.Idle, is SendspinState.Reconnecting -> {
                        sendspinDisconnectedAt = SystemClock.elapsedRealtime()
                        Log.d(TAG, "Sendspin disconnected/reconnecting — grace period started")
                    }
                    is SendspinState.Ready, is SendspinState.Synchronized -> {
                        if (sendspinDisconnectedAt > 0L) {
                            val downtime = SystemClock.elapsedRealtime() - sendspinDisconnectedAt
                            Log.d(TAG, "Sendspin reconnected after ${downtime}ms")
                        }
                    }
                    else -> {}
                }
            }
            .launchIn(lifecycleScope)
    }

    // ── Foreground / notification ───────────────────────────

    private fun startForeground() {
        if (isForeground) return
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else 0
        )
        isForeground = true
    }

    private fun stopForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    /** Decode the current artwork bytes to a Bitmap, caching by byte-array identity. */
    private fun currentArtBitmap(): android.graphics.Bitmap? {
        val bytes = coordinator.snapshot.value.current.artworkBytes ?: return lastArtBitmap
        if (bytes === lastArtBytes && lastArtBitmap != null) return lastArtBitmap
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode notification artwork: ${e.message}"); null
        }
        lastArtBytes = bytes
        lastArtBitmap = bmp
        return bmp
    }

    private fun updateNotification() {
        if (!isForeground) return
        val current = coordinator.snapshot.value.current
        val title = current.title.takeIf { it.isNotBlank() }
        val artist = current.artist.takeIf { it.isNotBlank() }
        val playing = playingState.value
        if (title == lastNotifTitle && artist == lastNotifArtist &&
            playing == lastNotifIsPlaying && current.artworkBytes === lastArtBytes) {
            return
        }
        lastNotifTitle = title
        lastNotifArtist = artist
        lastNotifIsPlaying = playing
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val current = coordinator.snapshot.value.current
        val isPlaying = playingState.value

        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val prevPi = PendingIntent.getService(
            this, 1,
            Intent(this, MusicService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPausePi = PendingIntent.getService(
            this, 2,
            Intent(this, MusicService::class.java).apply {
                action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val nextPi = PendingIntent.getService(
            this, 3,
            Intent(this, MusicService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(current.title.takeIf { it.isNotBlank() } ?: "Music Assistant")
                .setContentText(current.artist.takeIf { it.isNotBlank() } ?: "Connected")
                .setContentIntent(contentIntent)
                .setOngoing(isPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setSilent(true)
                .apply { currentArtBitmap()?.let { setLargeIcon(it) } }
                .addAction(R.drawable.ic_skip_previous, "Previous", prevPi)
                .addAction(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    if (isPlaying) "Pause" else "Play",
                    playPausePi
                )
                .addAction(R.drawable.ic_skip_next, "Next", nextPi)

        builder.setStyle(
            MediaStyleNotificationHelper.MediaStyle(mediaSessionHost.session)
                .setShowActionsInCompactView(0, 1, 2)
        )

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current playback info and controls"
                setShowBadge(false)
            }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}

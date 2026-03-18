package io.musicassistant.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import io.musicassistant.companion.media.NativeMediaManager
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Foreground service that manages the connection to the MA server and audio playback.
 *
 * Key improvements over the old PlayerService:
 * - No WebView, no wake lock, no JS timer polling
 * - Only runs as foreground service during active playback
 * - Event-driven via WebSocket (no polling)
 * - Uses LifecycleService for structured coroutine management
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

    // Shared instances (application-scoped singletons)
    val apiClient: MaApiClient by lazy { ServiceLocator.apiClient }
    val mediaManager: NativeMediaManager by lazy { ServiceLocator.getMediaManager(this) }

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

    // WakeLock to prevent CPU throttling during background playback
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Command-Echo Guard ──────────────────────────────────
    // Prevents server event echoes from re-triggering local actions after we send a command.
    // When we send play/pause/next/prev, we record it here. When the server echoes the state
    // change back via player_updated, we check if it matches a pending command and skip it.
    private data class PendingCommand(val action: String, val timestamp: Long)
    private val pendingCommands = ConcurrentHashMap<String, PendingCommand>()
    private val COMMAND_ECHO_WINDOW_MS = 3000L

    /** Mark that we sent a command, so we can ignore the server echo. */
    private fun markCommandPending(action: String) {
        pendingCommands[action] = PendingCommand(action, SystemClock.elapsedRealtime())
    }

    /** Check if a state change from the server is our own echo. Returns true = ignore it. */
    private fun isCommandEcho(action: String): Boolean {
        val pending = pendingCommands.remove(action) ?: return false
        val isEcho = (SystemClock.elapsedRealtime() - pending.timestamp) < COMMAND_ECHO_WINDOW_MS
        if (isEcho) Log.d(TAG, "Suppressed command echo: $action")
        return isEcho
    }

    // ── Sendspin Reconnect Grace Period ────────────────────
    // When Sendspin WebSocket closes and reconnects, the player briefly disappears from MA.
    // During this window, ignore player_updated events that would falsely pause playback.
    @Volatile private var sendspinDisconnectedAt: Long = 0L
    private val SENDSPIN_RECONNECT_GRACE_MS = 5000L

    /** Check if Sendspin is currently reconnecting (within grace period). */
    private fun isSendspinReconnecting(): Boolean {
        val client = ServiceLocator.sendspinClient ?: return false
        val state = client.state.value
        if (state is SendspinState.Ready || state is SendspinState.Synchronized) {
            // Already reconnected — check if it was very recent
            val elapsed = SystemClock.elapsedRealtime() - sendspinDisconnectedAt
            return elapsed < 2000L // 2s grace after re-registration
        }
        return state is SendspinState.Reconnecting && sendspinDisconnectedAt > 0L
    }

    // Notification state cache to avoid unnecessary rebuilds
    private var lastNotifTitle: String? = null
    private var lastNotifArtist: String? = null
    private var lastNotifIsPlaying: Boolean = false

    // Artwork cache — avoids re-download on play/pause state changes
    private var cachedArtworkUrl: String? = null
    private var cachedArtworkBytes: ByteArray? = null
    private var cachedArtworkBitmap: android.graphics.Bitmap? = null
    private var lastNotifArtworkUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val browseCallback = ServiceLocator.getOrCreateAutoBrowseCallback()
        browseCallback.appContext = applicationContext
        mediaManager.initialize(browseCallback)
        wireMediaCallbacks()
        // Acquire WakeLock to prevent CPU throttling during background playback
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicAssistant::Playback")
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4-hour timeout safety net
        // Start foreground immediately so the service survives in background
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
        // Keep running in the background — don't stop the service
        Log.d(TAG, "Task removed, service continues running")
    }

    override fun onDestroy() {
        mediaManager.onPlayRequested = null
        mediaManager.onPauseRequested = null
        mediaManager.onNextRequested = null
        mediaManager.onPreviousRequested = null
        mediaManager.onSeekRequested = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        apiClient.disconnect()
        lifecycleScope.launch { ServiceLocator.sendspinClient?.stop() }
        mediaManager.release()
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

            // Ensure we have a player ID
            val playerId =
                    settings.playerId.ifEmpty {
                        val newId = SendspinClient.generatePlayerId()
                        SettingsModule.getRepository(this@MusicService).setPlayerId(newId)
                        newId
                    }
            activePlayerId = playerId
            activeQueueId = playerId  // Default: queue ID == player ID; updated by events

            // Already connected — skip reconnection to avoid disrupting active playback
            val existingClient = ServiceLocator.sendspinClient
            if (apiClient.connectionState.value == ConnectionState.AUTHENTICATED &&
                existingClient != null &&
                existingClient.state.value !is SendspinState.Idle &&
                existingClient.state.value !is SendspinState.Error) {
                Log.d(TAG, "Already connected, skipping reconnection")
                ensureObserversStarted(existingClient)
                return@launch
            }

            // Connect API client (only if not already authenticated)
            if (apiClient.connectionState.value != ConnectionState.AUTHENTICATED) {
                apiClient.connect(settings.serverUrl, token)
            }

            // Set up connection state observer (once) to connect Sendspin when API is ready
            if (!connectionObserverStarted) {
                connectionObserverStarted = true
                apiClient
                    .connectionState
                    .onEach { state ->
                        if (state == ConnectionState.AUTHENTICATED) {
                            val client = ServiceLocator.getSendspinClient(
                                this@MusicService, playerId, settings.serverUrl, token
                            )
                            // Only start if idle (new client or disconnected)
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

    /** Ensure Sendspin and API event observers are running (idempotent). */
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

    /** Wire the ForwardingPlayer callbacks so media session controls route through the MA API. */
    private fun wireMediaCallbacks() {
        mediaManager.onPlayRequested = {
            val id = activePlayerId
            if (id != null) {
                markCommandPending("play")
                lifecycleScope.launch {
                    try {
                        ServiceLocator.api.playerPlay(id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Play (session) failed: ${e.message}")
                    }
                }
            }
        }
        mediaManager.onPauseRequested = {
            val id = activePlayerId
            if (id != null) {
                markCommandPending("pause")
                lifecycleScope.launch {
                    try {
                        ServiceLocator.api.playerPause(id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Pause (session) failed: ${e.message}")
                    }
                }
            }
        }
        mediaManager.onNextRequested = { handleNext() }
        mediaManager.onPreviousRequested = { handlePrevious() }
        mediaManager.onSeekRequested = { positionMs -> handleSeek(positionMs) }
    }

    private fun handlePlay() {
        val id = activePlayerId ?: return
        markCommandPending("play")
        mediaManager.play()
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
        mediaManager.pause()
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

    /** Observe playback state to manage foreground service lifecycle. */
    private fun observePlaybackState() {
        mediaManager.isPlaying.onEach { _ -> updateNotification() }.launchIn(lifecycleScope)

        // Also observe metadata changes for notification updates
        combine(
                        mediaManager.currentTrackTitle,
                        mediaManager.currentTrackArtist,
                        mediaManager.currentArtworkUri
                ) { title, artist, artwork -> Triple(title, artist, artwork) }
                .distinctUntilChanged()
                .onEach { updateNotification() }
                .launchIn(lifecycleScope)
    }

    /**
     * Observe the new Sendspin state machine for playback state transitions.
     * Stream start/end/clear and audio data are now handled internally by SendspinClient.
     * We only need to observe state transitions for notification/MediaSession updates.
     */
    private fun observeSendspinState(client: SendspinClient) {
        // Observe state transitions for isPlaying / notification
        client.state
            .onEach { state ->
                when (state) {
                    is SendspinState.Synchronized, is SendspinState.Buffering -> {
                        mediaManager.setStreamActive(true)
                        // Fetch metadata from server so notification shows track info
                        refreshMetadataFromServer()
                    }
                    is SendspinState.Ready, is SendspinState.Idle -> {
                        mediaManager.setStreamActive(false)
                    }
                    is SendspinState.Error -> {
                        Log.e(TAG, "Sendspin error: ${state.error}")
                        mediaManager.setStreamActive(false)
                    }
                    else -> {}
                }
            }
            .launchIn(lifecycleScope)

        // Observe metadata from Sendspin stream (alternative to API events)
        client.metadata
            .onEach { metadata ->
                if (metadata != null && (metadata.title != null || metadata.artist != null)) {
                    Log.d(TAG, "Sendspin metadata: ${metadata.title} by ${metadata.artist}")
                    mediaManager.updateMetadata(
                        title = metadata.title ?: "",
                        artist = metadata.artist ?: "",
                        album = metadata.album,
                        artworkUrl = metadata.artworkUrl
                    )
                }
            }
            .launchIn(lifecycleScope)
    }

    /** Observe MA API events for metadata updates (single source of truth). */
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

    // Debounce jobs for event handlers — cancel previous if a new event arrives quickly
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
        // Update activeQueueId from the event's objectId if present
        if (event.objectId != null) activeQueueId = event.objectId

        // Try to parse inline data first (no API call needed)
        val inlineQueue = tryParsePlayerQueue(event.data)

        // Debounce: cancel previous job if events arrive faster than we can handle them
        queueUpdatedJob?.cancel()
        queueUpdatedJob = lifecycleScope.launch {
            // Short delay to coalesce rapid-fire events
            if (inlineQueue == null) delay(150)
            try {
                val q = inlineQueue ?: ServiceLocator.api.getPlayerQueue(queueId) ?: return@launch
                val prevItemId = currentQueueItemId
                if (q.currentItem?.queueItemId != prevItemId) {
                    // Track changed — full metadata update
                    updateMetadataFromQueue(q)
                } else {
                    // Same track — only update elapsed time
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

        // Debounce: cancel previous job if events arrive faster than we can handle them
        playerUpdatedJob?.cancel()
        playerUpdatedJob = lifecycleScope.launch {
            // Short delay to coalesce rapid-fire events
            delay(200)
            try {
                // Skip during Sendspin reconnect — the player briefly doesn't exist at MA.
                if (isSendspinReconnecting()) {
                    Log.d(TAG, "Skipping player_updated during Sendspin reconnect")
                    return@launch
                }
                val player = ServiceLocator.api.getPlayer(id) ?: return@launch
                when (player.state) {
                    PlayerState.PAUSED, PlayerState.IDLE -> {
                        if (mediaManager.playbackState == PlayerState.PLAYING &&
                                !isCommandEcho("pause")) {
                            Log.d(TAG, "External pause/stop detected, pausing locally")
                            mediaManager.pause()
                        }
                    }
                    PlayerState.PLAYING -> {
                        if (mediaManager.playbackState != PlayerState.PLAYING &&
                                mediaManager.playbackState != PlayerState.BUFFERING &&
                                !isCommandEcho("play")) {
                            Log.d(TAG, "External play detected, resuming locally")
                            mediaManager.play()
                        }
                    }
                    else -> {}
                }
                val qId = activeQueueId ?: id
                val q = ServiceLocator.api.getPlayerQueue(qId) ?: return@launch
                updateMetadataFromQueue(q)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to handle player_updated: ${e.message}")
            }
        }
    }

    /** Fetch queue and update metadata on MediaSession. Called on stream/start. */
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

    /** Full metadata update — title, artist, album, artwork, duration, elapsed time. */
    private fun updateMetadataFromQueue(queue: PlayerQueue) {
        val currentItem = queue.currentItem ?: return
        val media = currentItem.mediaItem ?: return

        currentQueueItemId = currentItem.queueItemId

        val isRadio = media.mediaType == MediaType.RADIO

        // For radio: prefer the player's current_media
        val title: String
        val artist: String
        val album: String?
        if (isRadio) {
            // Fetch player for current_media (radio has actual track in there)
            lifecycleScope.launch {
                try {
                    val player = ServiceLocator.api.getPlayer(activePlayerId ?: return@launch)
                    val pmTitle = player?.currentMedia?.title?.takeIf { it.isNotBlank() }
                    val pmArtist = player?.currentMedia?.artist?.takeIf { it.isNotBlank() }
                    val t = pmTitle ?: currentItem.name.ifBlank { media.name }
                    val a = pmArtist ?: media.name
                    val alb = player?.currentMedia?.album ?: media.name
                    applyMetadata(t, a, alb, media, currentItem, queue, isRadio)
                } catch (e: Exception) {
                    // Fallback to queue media info
                    val t = currentItem.name.ifBlank { media.name }
                    applyMetadata(t, media.name, media.name, media, currentItem, queue, isRadio)
                }
            }
        } else {
            title = media.name
            artist = media.artists.joinToString(", ") { it.name }
            album = media.album?.name
            applyMetadata(title, artist, album, media, currentItem, queue, isRadio)
        }
    }

    private fun applyMetadata(
            title: String,
            artist: String,
            album: String?,
            media: io.musicassistant.companion.data.model.QueueMediaItem,
            currentItem: io.musicassistant.companion.data.model.QueueItem,
            queue: PlayerQueue,
            isRadio: Boolean
    ) {
        val artworkImage = media.image ?: currentItem.image
        val artworkUrl = if (artworkImage != null) getImageUrl(artworkImage) else null

        Log.d(TAG, "updateMetadata: title='$title' artist='$artist' isRadio=$isRadio")

        // Use cached artwork bytes if URL unchanged, otherwise download async
        val hasCachedArtwork = artworkUrl != null && artworkUrl == cachedArtworkUrl && cachedArtworkBytes != null
        // Ensure bitmap exists when using cached artwork bytes
        if (hasCachedArtwork && cachedArtworkBitmap == null && cachedArtworkBytes != null) {
            cachedArtworkBitmap = decodeToBitmap(cachedArtworkBytes!!, 500, 500)
        }

        mediaManager.updateMetadata(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                artworkData = if (hasCachedArtwork) cachedArtworkBytes else null
        )

        // Download artwork bitmap for Bluetooth/AVRCP (can't resolve HTTP URLs)
        if (!hasCachedArtwork) {
            lifecycleScope.launch {
                val artworkBytes = if (artworkUrl != null) downloadArtwork(artworkUrl) else null
                val bytes = artworkBytes ?: getAppIconBytes()
                if (bytes != null) {
                    cachedArtworkUrl = artworkUrl
                    cachedArtworkBytes = bytes
                    cachedArtworkBitmap = decodeToBitmap(bytes, 500, 500)
                    mediaManager.updateArtworkData(bytes)
                    mediaManager.invalidateSessionState()
                    updateNotification()
                }
            }
        }

        // Duration: for live content, reset to TIME_UNSET
        if (currentItem.duration > 0 && !isRadio) {
            mediaManager.setKnownDuration(currentItem.duration * 1000L)
        } else {
            mediaManager.setKnownDuration(C.TIME_UNSET)
        }

        // Elapsed time
        val elapsedMs = (queue.elapsedTime * 1000).toLong()
        val playing = queue.state == PlayerState.PLAYING
        mediaManager.setKnownElapsedTime(elapsedMs, playing)

        // Force MediaSession to re-read state
        mediaManager.invalidateSessionState()
    }

    /** Lightweight update — only elapsed time, no metadata rebuild. */
    private fun updateElapsedTimeFromQueue(queue: PlayerQueue) {
        val currentItem = queue.currentItem ?: return
        if (currentItem.duration > 0) {
            mediaManager.setKnownDuration(currentItem.duration * 1000L)
        }
        val elapsedMs = (queue.elapsedTime * 1000).toLong()
        val playing = queue.state == PlayerState.PLAYING
        mediaManager.setKnownElapsedTime(elapsedMs, playing)
    }

    /** Download artwork image bytes for embedding in MediaSession metadata. */
    private suspend fun downloadArtwork(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder().url(url).apply {
                val token = apiClient.currentAuthToken
                if (!token.isNullOrEmpty()) {
                    addHeader("Authorization", "Bearer $token")
                }
            }.build()
            val response = ServiceLocator.httpClient.newCall(request).execute()
            response.use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download artwork: ${e.message}")
            null
        }
    }

    /** Get app icon as PNG bytes (fallback when no artwork available). */
    private fun getAppIconBytes(): ByteArray? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(
                    256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, 256, 256)
            drawable.draw(canvas)
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app icon: ${e.message}")
            null
        }
    }

    /** Decode raw image bytes into a Bitmap, scaled down to fit maxWidth x maxHeight. */
    private fun decodeToBitmap(bytes: ByteArray, maxWidth: Int, maxHeight: Int): android.graphics.Bitmap? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            var inSampleSize = 1
            if (options.outHeight > maxHeight || options.outWidth > maxWidth) {
                val halfH = options.outHeight / 2
                val halfW = options.outWidth / 2
                while (halfH / inSampleSize >= maxHeight && halfW / inSampleSize >= maxWidth) {
                    inSampleSize *= 2
                }
            }
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode artwork bitmap: ${e.message}")
            null
        }
    }

    private fun getImageUrl(image: MediaItemImage): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return ServiceLocator.api.getImageUrl(image, baseUrl)
    }

    /** Track Sendspin connection state to detect reconnect windows. */
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

    private fun updateNotification() {
        if (!isForeground) return
        val title = mediaManager.currentTrackTitle.value
        val artist = mediaManager.currentTrackArtist.value
        val playing = mediaManager.isPlaying.value
        val artworkUrl = cachedArtworkUrl
        if (title == lastNotifTitle && artist == lastNotifArtist &&
                playing == lastNotifIsPlaying && artworkUrl == lastNotifArtworkUrl) {
            return
        }
        lastNotifTitle = title
        lastNotifArtist = artist
        lastNotifIsPlaying = playing
        lastNotifArtworkUrl = artworkUrl
        val notification = buildNotification()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val session = mediaManager.mediaSession
        val isPlaying = mediaManager.isPlaying.value

        val contentIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        // Action PendingIntents
        val prevPi =
                PendingIntent.getService(
                        this,
                        1,
                        Intent(this, MusicService::class.java).apply { action = ACTION_PREVIOUS },
                        PendingIntent.FLAG_IMMUTABLE
                )
        val playPausePi =
                PendingIntent.getService(
                        this,
                        2,
                        Intent(this, MusicService::class.java).apply {
                            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
                        },
                        PendingIntent.FLAG_IMMUTABLE
                )
        val nextPi =
                PendingIntent.getService(
                        this,
                        3,
                        Intent(this, MusicService::class.java).apply { action = ACTION_NEXT },
                        PendingIntent.FLAG_IMMUTABLE
                )

        val builder =
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(mediaManager.currentTrackTitle.value ?: "Music Assistant")
                        .setContentText(mediaManager.currentTrackArtist.value ?: "Connected")
                        .setContentIntent(contentIntent)
                        .setOngoing(isPlaying)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                        .setSilent(true)
                        .apply {
                            val artwork = cachedArtworkBitmap
                            if (artwork != null) setLargeIcon(artwork)
                        }
                        .addAction(R.drawable.ic_skip_previous, "Previous", prevPi)
                        .addAction(
                                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                                if (isPlaying) "Pause" else "Play",
                                playPausePi
                        )
                        .addAction(R.drawable.ic_skip_next, "Next", nextPi)

        if (session != null) {
            builder.setStyle(
                    MediaStyleNotificationHelper.MediaStyle(session)
                            .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel =
                NotificationChannel(
                                CHANNEL_ID,
                                "Music Playback",
                                NotificationManager.IMPORTANCE_LOW
                        )
                        .apply {
                            description = "Shows current playback info and controls"
                            setShowBadge(false)
                        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}

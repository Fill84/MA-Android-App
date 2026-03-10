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
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.media.NativeMediaManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private var sendspinObserverStarted = false
    private var eventObserverStarted = false
    private var activePlayerId: String? = null

    // Current queue item ID — used to detect track changes
    private var currentQueueItemId: String? = null

    // WakeLock to prevent CPU throttling during background playback
    private var wakeLock: PowerManager.WakeLock? = null

    // Notification state cache to avoid unnecessary rebuilds
    private var lastNotifTitle: String? = null
    private var lastNotifArtist: String? = null
    private var lastNotifIsPlaying: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaManager.initialize()
        wireMediaCallbacks()
        // Acquire WakeLock to prevent CPU throttling during background playback
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicAssistant::Playback")
        wakeLock?.acquire()
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
        ServiceLocator.sendspinClient?.onAudioData = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        apiClient.disconnect()
        ServiceLocator.sendspinClient?.disconnect()
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

            // Connect API client
            apiClient.connect(settings.serverUrl, token)

            // Ensure we have a player ID
            val playerId =
                    settings.playerId.ifEmpty {
                        val newId = SendspinClient.generatePlayerId()
                        SettingsModule.getRepository(this@MusicService).setPlayerId(newId)
                        newId
                    }
            activePlayerId = playerId

            // Wait for API to be connected, then connect Sendspin
            apiClient
                    .connectionState
                    .onEach { state ->
                        if (state == ConnectionState.AUTHENTICATED) {
                            val client =
                                    ServiceLocator.getSendspinClient(this@MusicService, playerId)
                            // Only connect if not already connected/registered
                            if (client.state.value == SendspinClient.State.DISCONNECTED) {
                                client.connect(settings.serverUrl, token)
                            }
                            if (!sendspinObserverStarted) {
                                sendspinObserverStarted = true
                                observeSendspinCommands(client)
                                observePlaybackStateForSendspin()
                            }
                            if (!eventObserverStarted) {
                                eventObserverStarted = true
                                observeApiEvents()
                            }
                        }
                    }
                    .launchIn(lifecycleScope)
        }
    }

    /** Wire the ForwardingPlayer callbacks so media session controls route through the MA API. */
    private fun wireMediaCallbacks() {
        mediaManager.onPlayRequested = {
            val id = activePlayerId
            if (id != null) {
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

    /** Observe Sendspin commands and route them to the media manager. */
    private fun observeSendspinCommands(client: SendspinClient) {
        // Persistent audio data handler — writes directly to AudioTrack.
        // Set once, never nulled during service lifetime. AudioTrack ignores writes
        // when not active (streamPlayer.isActive == false). Zero copy, zero buffering.
        client.onAudioData = { data, offset, length ->
            mediaManager.writeStreamData(data, offset, length)
        }

        client.commands
                .onEach { cmd ->
                    when (cmd.command) {
                        "play_url" -> {
                            val url = cmd.args?.get("url")?.jsonPrimitive?.content
                            if (url != null) mediaManager.playUrl(url)
                        }
                        "stream/start" -> {
                            // Handle both direct stream/start and server/command wrapped formats
                            val player = cmd.args?.get("payload")?.jsonObject
                                    ?.get("player")?.jsonObject
                                    ?: cmd.args // fallback: args IS the player object
                            val sampleRate =
                                    player?.get("sample_rate")?.jsonPrimitive?.intOrNull ?: 48000
                            val channels = player?.get("channels")?.jsonPrimitive?.intOrNull ?: 2
                            val bitDepth = player?.get("bit_depth")?.jsonPrimitive?.intOrNull ?: 16
                            Log.d(
                                    TAG,
                                    "stream/start: rate=$sampleRate ch=$channels bits=$bitDepth"
                            )
                            mediaManager.startStreamDirect(sampleRate, channels, bitDepth)
                            client.reportState(state = "playing")
                            // Fetch metadata from server so notification shows track info
                            refreshMetadataFromServer()
                        }
                        "stream/end" -> {
                            Log.d(TAG, "stream/end received")
                            mediaManager.stopStream()
                            client.reportState(state = "idle")
                        }
                        "stream/clear" -> {
                            // Flush buffer but don't stop — new stream/start will follow
                            Log.d(TAG, "stream/clear received")
                            mediaManager.flushStream()
                        }
                        "play" -> mediaManager.play()
                        "pause" -> mediaManager.pause()
                        "stop" -> {
                            mediaManager.stopStream()
                            mediaManager.stop()
                        }
                        "volume" -> {
                            val level =
                                    cmd.args?.get("volume")?.jsonPrimitive?.int
                                            ?: cmd.args?.get("volume_level")?.jsonPrimitive?.int
                            if (level != null) mediaManager.setVolume(level / 100f)
                        }
                        "seek" -> {
                            val position =
                                    cmd.args
                                            ?.get("position")
                                            ?.jsonPrimitive
                                            ?.content
                                            ?.toDoubleOrNull()
                            if (position != null) mediaManager.seekTo((position * 1000).toLong())
                        }
                        "mute" -> {
                            val muted =
                                    cmd.args?.get("muted")?.jsonPrimitive?.content?.toBoolean()
                                            ?: true
                            mediaManager.setVolume(if (muted) 0f else 1f)
                        }
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

    private fun tryParsePlayerQueue(data: JsonElement?): PlayerQueue? {
        data ?: return null
        return try {
            json.decodeFromJsonElement(PlayerQueue.serializer(), data)
        } catch (e: Exception) {
            Log.w(TAG, "Parse PlayerQueue from event failed: ${e.message}"); null
        }
    }

    private fun handleQueueUpdated(event: MaApiClient.MaEvent) {
        val queueId = activePlayerId ?: return
        if (event.objectId != null && event.objectId != queueId) return
        lifecycleScope.launch {
            try {
                val q = tryParsePlayerQueue(event.data) ?: ServiceLocator.api.getPlayerQueue(queueId)
                val prevItemId = currentQueueItemId
                if (q.currentItem?.queueItemId != prevItemId) {
                    // Track changed — full metadata update
                    updateMetadataFromQueue(q)
                } else {
                    // Same track — only update elapsed time
                    updateElapsedTimeFromQueue(q)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle queue_updated: ${e.message}")
            }
        }
    }

    private fun handlePlayerUpdated(event: MaApiClient.MaEvent) {
        val id = activePlayerId ?: return
        if (event.objectId != null && event.objectId != id) return

        // Sync local playback state with server (handles external pause/stop/play)
        lifecycleScope.launch {
            try {
                val player = ServiceLocator.api.getPlayer(id)
                when (player.state) {
                    PlayerState.PAUSED, PlayerState.IDLE -> {
                        if (mediaManager.playbackState == PlayerState.PLAYING) {
                            Log.d(TAG, "External pause/stop detected, pausing locally")
                            mediaManager.pause()
                        }
                    }
                    PlayerState.PLAYING -> {
                        if (mediaManager.playbackState != PlayerState.PLAYING &&
                                mediaManager.playbackState != PlayerState.BUFFERING) {
                            Log.d(TAG, "External play detected, resuming locally")
                            mediaManager.play()
                        }
                    }
                    else -> {}
                }
                val q = ServiceLocator.api.getPlayerQueue(id)
                updateMetadataFromQueue(q)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle player_updated: ${e.message}")
            }
        }
    }

    /** Fetch queue and update metadata on MediaSession. Called on stream/start. */
    private fun refreshMetadataFromServer() {
        val queueId = activePlayerId ?: return
        lifecycleScope.launch {
            try {
                val q = ServiceLocator.api.getPlayerQueue(queueId)
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
                    val player = ServiceLocator.api.getPlayer(activePlayerId!!)
                    val pmTitle = player.currentMedia?.title?.takeIf { it.isNotBlank() }
                    val pmArtist = player.currentMedia?.artist?.takeIf { it.isNotBlank() }
                    val t = pmTitle ?: currentItem.name.ifBlank { media.name }
                    val a = pmArtist ?: media.name
                    val alb = player.currentMedia?.album ?: media.name
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

        mediaManager.updateMetadata(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl
        )

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

    private fun getImageUrl(image: MediaItemImage): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return ServiceLocator.api.getImageUrl(image, baseUrl)
    }

    /** Report playback state changes back to Sendspin server. */
    private fun observePlaybackStateForSendspin() {
        mediaManager
                .isPlaying
                .onEach { playing ->
                    val client = ServiceLocator.sendspinClient ?: return@onEach
                    val state =
                            when {
                                playing -> "playing"
                                mediaManager.playbackState ==
                                        io.musicassistant.companion.data.model.PlayerState.PAUSED ->
                                        "paused"
                                else -> "idle"
                            }
                    val volume = ((mediaManager.exoPlayer?.volume ?: 1f) * 100).toInt()
                    val position = mediaManager.currentPositionMs / 1000.0
                    client.reportState(
                            state = state,
                            positionSeconds = position,
                            volumeLevel = volume
                    )
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
        if (title == lastNotifTitle && artist == lastNotifArtist && playing == lastNotifIsPlaying) {
            return
        }
        lastNotifTitle = title
        lastNotifArtist = artist
        lastNotifIsPlaying = playing
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

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
import io.musicassistant.companion.data.player.PlayerSession
import io.musicassistant.companion.data.sendspin.SendspinClient
import io.musicassistant.companion.data.sendspin.SendspinState
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.media.MaPlayer
import io.musicassistant.companion.media.MediaMetadataCoordinator
import io.musicassistant.companion.media.MediaSessionHost
import io.musicassistant.companion.media.TrackMetadata
import io.musicassistant.companion.media.resolveNeighbors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

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

    private var isForeground = false
    private var connectionObserverStarted = false
    private var sendspinObserverStarted = false
    private var deviceSessionStarted = false
    /** Raw Sendspin id of this device (`ma_<suffix>`); used only to resolve the universal player. */
    private var deviceRawId: String? = null
    /**
     * Command/now-playing target — the universal `upma_` player, resolved from the device session.
     * Stays null until resolved so transport commands never hit the raw `ma_` sink (which ignores them).
     */
    private var activePlayerId: String? = null
    /** Queue ID — usually equals the active player id, but may differ in sync groups. */
    private var activeQueueId: String? = null

    // Current queue item ID — used to detect track changes (avoids needless metadata rebuilds).
    private var currentQueueItemId: String? = null

    /** Whether the device session's current source is live/radio (gates the Sendspin text overlay). */
    @Volatile private var deviceIsLive = false

    // WakeLock to prevent CPU throttling during background playback
    private var wakeLock: PowerManager.WakeLock? = null

    // Tracks the last Sendspin disconnect for downtime logging on reconnect.
    @Volatile private var sendspinDisconnectedAt: Long = 0L

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
        // Created here but deliberately NOT acquired. The wakelock is held ONLY while this device is
        // actually rendering audio locally (acquireWakeLock/releaseWakeLock, driven by the Sendspin
        // stream state in observeSendspinState). Acquiring it unconditionally for the whole service
        // lifetime pinned the CPU awake 24/7 — even when paused, idle, controlling a remote player,
        // or screen-off — which defeated Android Doze and drained the battery. onDestroy still
        // releases it as a safety net.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicAssistant::Playback")

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
            deviceRawId = playerId

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
        if (!deviceSessionStarted) {
            deviceSessionStarted = true
            observeDeviceSession()
        }
    }

    // ── Transport commands ──────────────────────────────────

    /** Reflect play/pause locally (notification + session) without calling the API. */
    private fun setLocalPlaying(playing: Boolean) {
        playingState.value = playing
        mediaPlayer.setStreamPlaying(playing)
        updateNotification()
    }

    /**
     * Hold a PARTIAL_WAKE_LOCK ONLY while this device is actually decoding/playing audio locally.
     * Acquired on the first Sendspin Synchronized/Buffering, released on Ready/Idle/Error. When
     * paused, idle, controlling a remote player, or screen-off-with-nothing-playing, the CPU is free
     * to enter Doze. The 4-hour cap is only a safety net for a single very long session; releasing
     * on idle is what actually fixes the battery drain.
     */
    private fun acquireWakeLock() {
        val wl = wakeLock ?: return
        if (!wl.isHeld) {
            wl.acquire(4 * 60 * 60 * 1000L)
            Log.d(TAG, "WakeLock acquired (local audio active)")
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        if (wl.isHeld) {
            wl.release()
            Log.d(TAG, "WakeLock released (local audio inactive)")
        }
    }

    private fun handlePlay() {
        val id = activePlayerId ?: return
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
                        acquireWakeLock() // local audio is rendering — keep the CPU awake
                    }
                    is SendspinState.Ready, is SendspinState.Idle -> {
                        mediaPlayer.setStreamActive(false)
                        playingState.value = false
                        releaseWakeLock() // no local audio — let the device sleep/Doze
                    }
                    is SendspinState.Error -> {
                        Log.e(TAG, "Sendspin error: ${state.error}")
                        mediaPlayer.setStreamActive(false)
                        playingState.value = false
                        releaseWakeLock()
                    }
                    else -> {}
                }
            }
            .launchIn(lifecycleScope)

        // Sendspin stream metadata = the low-latency per-song signal for RADIO/live only. For
        // non-radio the device session (server SSOT) is authoritative, so we ignore it there to
        // avoid two sources fighting over the now-playing.
        client.metadata
            .onEach { metadata ->
                if (deviceIsLive && metadata != null && (metadata.title != null || metadata.artist != null)) {
                    val art = metadata.artworkUrl?.let { resolveArtworkUrl(it) }
                    Log.d(TAG, "Sendspin radio metadata: ${metadata.title} by ${metadata.artist} artwork=$art")
                    coordinator.pushSendspinMetadata(
                        title = metadata.title ?: "",
                        artist = metadata.artist ?: "",
                        album = metadata.album,
                        artworkUrl = art
                    )
                }
            }
            .launchIn(lifecycleScope)
    }

    /**
     * Drive the media surface (notification, lock screen, Bluetooth AVRCP, Auto) from the SINGLE
     * server mirror: the device's universal-player session in [PlayerRepository]. The session already
     * reacts to the same MA events and resolves player/queue/items centrally, so there is exactly one
     * source of truth — no more parallel event handling or ad-hoc fetches here. Sendspin remains only
     * the local audio transport (play-state) and the low-latency per-song text overlay for radio.
     */
    private fun observeDeviceSession() {
        val rawId = deviceRawId ?: return
        ServiceLocator.playerRepository
            .deviceSession(rawId)
            .onEach { session -> renderDeviceNowPlaying(session) }
            .launchIn(lifecycleScope)
    }

    private suspend fun renderDeviceNowPlaying(session: PlayerSession) {
        // Re-target commands/queue at the universal player the session resolved to (never raw ma_).
        activePlayerId = session.playerId
        activeQueueId = session.effectiveQueueId
        val np = session.nowPlaying
        if (np == null) {
            // Queue emptied (e.g. "clear queue") — clear the now-playing so the notification/session
            // don't keep showing the last track. Only act if we were actually showing something.
            if (currentQueueItemId != null) {
                currentQueueItemId = null
                deviceIsLive = false
                coordinator.clear()
                mediaPlayer.setKnownDuration(C.TIME_UNSET)
                setLocalPlaying(false)
                updateNotification()
            }
            return
        }
        deviceIsLive = np.isLive

        if (np.currentQueueItemId != currentQueueItemId) {
            currentQueueItemId = np.currentQueueItemId
            val coverUrl = np.currentMediaImageUrl?.let { resolveArtworkUrl(it) }
                ?: np.artworkImage?.let { getImageUrl(it) }
            val current = TrackMetadata(np.title, np.artist, np.album, coverUrl, null)
            if (np.isLive) {
                // Radio: station logo as fallback cover + single-item timeline (no phantom neighbors).
                // Per-song text is refined by the Sendspin metadata overlay.
                coordinator.setLiveContext(true, resolveStationLogo(np.artworkImage?.let { getImageUrl(it) }))
                Log.d(TAG, "device nowplaying (radio): '${np.title}' cover=$coverUrl")
                coordinator.pushQueueUpdate(current = current, prev = null, next = null, isLive = true)
            } else {
                coordinator.setLiveContext(false, null)
                val idx = np.currentIndex
                val total = session.queue?.items ?: session.queueItems.size
                val prevCandidate = if (idx != null && idx > 0)
                    session.queueItems.getOrNull(idx - 1)?.let { queueItemToTrack(it) } else null
                val nextCandidate = if (idx != null)
                    session.queueItems.getOrNull(idx + 1)?.let { queueItemToTrack(it) } else null
                val (prev, next) = resolveNeighbors(
                    isLive = false, currentIndex = idx, total = total,
                    prevCandidate = prevCandidate, nextCandidate = nextCandidate
                )
                Log.d(TAG, "device nowplaying: '${np.title}' cover=$coverUrl idx=$idx/$total prev=${prev?.title} next=${next?.title}")
                coordinator.pushQueueUpdate(current = current, prev = prev, next = next, isLive = false)
            }
            mediaPlayer.invalidate()
        }

        // Timeline on every emit (cheap; no metadata rebuild). Play-state stays Sendspin-driven.
        mediaPlayer.setKnownDuration(if (np.durationMs > 0) np.durationMs else C.TIME_UNSET)
        mediaPlayer.setKnownElapsed(np.elapsedMs, session.isPlaying)
        updateNotification()
    }

    /** Build [TrackMetadata] from a queue item (prefers its media-item fields, falls back to the item). */
    private fun queueItemToTrack(item: io.musicassistant.companion.data.model.QueueItem): TrackMetadata {
        val mi = item.mediaItem
        val img = mi?.image ?: item.image
        return TrackMetadata(
            title = mi?.name?.takeIf { it.isNotBlank() } ?: item.name,
            artist = mi?.artists?.joinToString(", ") { it.name } ?: "",
            album = mi?.album?.name,
            artworkUrl = img?.let { getImageUrl(it) },
            artworkBytes = null
        )
    }

    // Radio station logo cache — fallback cover for radio tracks without per-track art.
    @Volatile private var cachedStationUrl: String? = null
    @Volatile private var cachedStationBytes: ByteArray? = null

    /** Fetch (and cache per URL) the radio station logo bytes used as the radio fallback cover. */
    private suspend fun resolveStationLogo(stationUrl: String?): ByteArray? {
        if (stationUrl == null) return cachedStationBytes
        if (stationUrl != cachedStationUrl) {
            cachedStationBytes = runCatching { ServiceLocator.getArtworkPipeline().fetch(stationUrl) }.getOrNull()
            cachedStationUrl = stationUrl
            Log.d(TAG, "radio station logo: $stationUrl (${cachedStationBytes?.size ?: 0} bytes)")
        }
        return cachedStationBytes
    }

    private fun getImageUrl(image: MediaItemImage): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return ServiceLocator.api.getImageUrl(image, baseUrl)
    }

    /**
     * Resolve a Sendspin artwork URL to one the device can actually reach. MA stream metadata often
     * carries the server's INTERNAL LAN address (e.g. http://192.168.x.x:8095/imageproxy?...), which
     * an off-LAN / reverse-proxied client can't connect to. We re-point such imageproxy URLs to the
     * configured server base (the externally reachable URL the API uses). External cover URLs
     * (e.g. a radio station's own CDN) are left untouched. Relative paths are resolved against base.
     */
    private fun resolveArtworkUrl(url: String): String? {
        if (url.isBlank()) return null
        val base = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        val isHttp = url.startsWith("http://") || url.startsWith("https://")
        return try {
            if (isHttp) {
                val parsed = java.net.URI(url)
                val path = parsed.rawPath ?: ""
                if (path.contains("/imageproxy") && base.isNotBlank()) {
                    val query = parsed.rawQuery?.let { "?$it" } ?: ""
                    base.trimEnd('/') + path + query
                } else {
                    url // external cover (e.g. radio CDN) — use as-is
                }
            } else if (base.isNotBlank()) {
                ServiceLocator.api.getImageUrl(url, base)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveArtworkUrl failed for $url: ${e.message}")
            url
        }
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

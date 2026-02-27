package io.musicassistant.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.MediaStyleNotificationHelper
import io.musicassistant.companion.MainActivity
import io.musicassistant.companion.R
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.media.MediaSessionManager
import io.musicassistant.companion.ui.webview.WebViewHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that keeps the WebView alive for background audio playback
 * and manages the media notification.
 *
 * IMPORTANT: This is a regular Service, NOT MediaSessionService.
 * MediaSessionService manages its own lifecycle based on player state:
 * when the player is in STATE_IDLE (no media), it stops the service,
 * which would kill the WebView. We need full control over the service lifecycle.
 */
class PlayerService : Service() {

    companion object {
        private const val TAG = "PlayerService"
        const val ACTION_START = "io.musicassistant.companion.action.START"
        const val ACTION_STOP = "io.musicassistant.companion.action.STOP"
        private const val MEDIA_ACTION_PREFIX = "io.musicassistant.companion.action.MEDIA_"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ma_player_channel"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when {
            action == ACTION_START -> startKeepAlive()
            action == ACTION_STOP -> stopKeepAlive()
            action?.startsWith(MEDIA_ACTION_PREFIX) == true -> {
                val mediaAction = action.removePrefix(MEDIA_ACTION_PREFIX)
                MediaSessionManager.dispatchMediaAction(mediaAction)
            }
            else -> startKeepAlive()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when the user swipes the app from recents.
     * If background playback is enabled, keep the service running.
     * If disabled, stop everything cleanly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val settings = try {
            val repo = SettingsModule.getRepository(this)
            runBlocking { repo.settingsFlow.first() }
        } catch (_: Exception) {
            null
        }

        if (settings?.backgroundPlaybackEnabled == false) {
            Log.i(TAG, "Task removed - background playback disabled, stopping service")
            stopKeepAlive()
        } else {
            // Keep running - foreground service + notification stay active.
            // Ensure WebView stays alive with JS timers running.
            Log.i(TAG, "Task removed (app swiped from recents) - service continues running")
            ensureWebViewAlive()
        }
    }

    override fun onDestroy() {
        MediaSessionManager.release()
        releaseWakeLock()
        super.onDestroy()
        Log.i(TAG, "PlayerService destroyed")
    }

    private fun startKeepAlive() {
        MediaSessionManager.init(this)
        MediaSessionManager.onMetadataOrStateChanged = { updateNotification() }

        val notification = buildMediaNotification()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else 0
        )
        acquireWakeLock()

        // Ensure WebView exists (handles START_STICKY restart after process kill)
        ensureWebViewAlive()

        Log.i(TAG, "Keep-alive started")
    }

    /**
     * Ensures the WebView is alive and connected.
     * Handles START_STICKY restarts (process was killed, WebView is gone)
     * and task removal (ensures JS timers keep running).
     */
    private fun ensureWebViewAlive() {
        if (WebViewHolder.webView != null) {
            WebViewHolder.webView?.resumeTimers()
            return
        }

        val settings = try {
            runBlocking { SettingsModule.getRepository(this@PlayerService).settingsFlow.first() }
        } catch (_: Exception) { return }

        if (settings.serverUrl.isEmpty()) return

        val serverHost = try {
            java.net.URL(settings.serverUrl).host
        } catch (_: Exception) { "" }

        WebViewHolder.ensureAlive(this, settings.serverUrl, serverHost)
    }

    private fun stopKeepAlive() {
        Log.i(TAG, "Keep-alive stopped")
        MediaSessionManager.release()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)

        // Delete old channel (wrong importance) so existing installs get the new one
        nm.deleteNotificationChannel("ma_player_service")

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Assistant Player",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows when Music Assistant is playing audio"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val notification = buildMediaNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildMediaNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PlayerService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val session = MediaSessionManager.getSession()

        // Use track metadata for notification content
        val notifTitle = MediaSessionManager.title.ifEmpty { "Music Assistant" }
        val notifText = MediaSessionManager.artist.ifEmpty {
            if (MediaSessionManager.isPlaying) "Playing" else "Connected"
        }

        val playPauseIcon = if (MediaSessionManager.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val playPauseAction = if (MediaSessionManager.isPlaying) "pause" else "play"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setColorized(true)

        // Set album art for the media notification background
        MediaSessionManager.artwork?.let { builder.setLargeIcon(it) }

        if (session != null) {
            val prevIntent = createMediaActionIntent("previoustrack")
            val playPauseIntent = createMediaActionIntent(playPauseAction)
            val nextIntent = createMediaActionIntent("nexttrack")

            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_previous, "Previous", prevIntent
                ).build()
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    playPauseIcon, "Play/Pause", playPauseIntent
                ).build()
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_next, "Next", nextIntent
                ).build()
            )

            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
        }

        return builder.build()
    }

    private fun createMediaActionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlayerService::class.java).apply {
            this.action = "io.musicassistant.companion.action.MEDIA_$action"
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    // --- WakeLock ---

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MusicAssistant::PlayerService"
            ).apply {
                acquire(8 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

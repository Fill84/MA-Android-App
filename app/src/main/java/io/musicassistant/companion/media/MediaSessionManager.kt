package io.musicassistant.companion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import io.musicassistant.companion.ui.webview.WebViewHolder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Manages the Media3 MediaSession and MaProxyPlayer.
 *
 * This is NOT using MediaSessionService - we manage our own foreground Service to avoid Media3's
 * automatic lifecycle management (which would stop the service when the proxy player is in
 * STATE_IDLE, killing the WebView).
 */
object MediaSessionManager {

    private const val TAG = "MediaSessionMgr"
    private var mediaSession: MediaSession? = null
    private var proxyPlayer: MaProxyPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var currentArtworkUrl = ""

    var isPlaying = false
        private set

    /** Public accessors for notification metadata (delegates to proxy player) */
    val title: String
        get() = proxyPlayer?.title ?: ""
    val artist: String
        get() = proxyPlayer?.artist ?: ""
    val artwork: Bitmap?
        get() = proxyPlayer?.artwork

    /** Callback for the service to rebuild the notification */
    var onMetadataOrStateChanged: (() -> Unit)? = null

    fun init(context: Context) {
        if (mediaSession != null) return

        val looper = Looper.getMainLooper()
        proxyPlayer =
                MaProxyPlayer(looper).apply {
                    onCommandReceived = { action -> sendMediaActionToWebView(action) }
                    onSeekTo = { positionSec -> sendSeekToWebView(positionSec) }
                }

        mediaSession =
                MediaSession.Builder(context, proxyPlayer!!)
                        .setCallback(MediaSessionCallback())
                        .setId("MusicAssistant")
                        .build()

        // Set custom command buttons for the notification
        setMediaButtonPreferences()

        Log.i(TAG, "MediaSession initialized with Media3")
    }

    fun getSession(): MediaSession? = mediaSession

    private fun setMediaButtonPreferences() {
        val buttons =
                ImmutableList.of(
                        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                                .setDisplayName("Previous")
                                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                                .build(),
                        // Play/Pause is automatically handled by SLOT_CENTER
                        CommandButton.Builder(CommandButton.ICON_NEXT)
                                .setDisplayName("Next")
                                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                                .build()
                )
        mediaSession?.setMediaButtonPreferences(buttons)
    }

    fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
        handler.post {
            proxyPlayer?.updatePlaybackState(playing)
            onMetadataOrStateChanged?.invoke()
        }
    }

    fun updatePositionState(durationMs: Long, positionMs: Long, playbackSpeed: Float) {
        handler.post { proxyPlayer?.updatePositionState(durationMs, positionMs, playbackSpeed) }
    }

    fun updateMetadata(title: String, artist: String, album: String, artworkUrl: String) {
        val metadataChanged =
                title != (proxyPlayer?.title ?: "") ||
                        artist != (proxyPlayer?.artist ?: "") ||
                        album != (proxyPlayer?.album ?: "")

        if (!metadataChanged && artworkUrl == currentArtworkUrl) return

        if (artworkUrl != currentArtworkUrl) {
            currentArtworkUrl = artworkUrl
            if (artworkUrl.isNotEmpty()) {
                executor.execute {
                    val bitmap = downloadBitmap(artworkUrl)
                    handler.post {
                        proxyPlayer?.updateMetadata(title, artist, album, bitmap)
                        onMetadataOrStateChanged?.invoke()
                    }
                }
                return
            } else {
                handler.post {
                    proxyPlayer?.updateMetadata(title, artist, album, null)
                    onMetadataOrStateChanged?.invoke()
                }
                return
            }
        }

        handler.post {
            proxyPlayer?.updateMetadata(title, artist, album, proxyPlayer?.artwork)
            onMetadataOrStateChanged?.invoke()
        }
    }

    /** Dispatch a media action to the web player (used by notification buttons) */
    fun dispatchMediaAction(action: String) = sendMediaActionToWebView(action)

    private fun sendMediaActionToWebView(action: String) {
        val js =
                "window.__ma_handlers && window.__ma_handlers['$action'] && window.__ma_handlers['$action']()"
        handler.post { WebViewHolder.webView?.evaluateJavascript(js, null) }
    }

    private fun sendSeekToWebView(positionSec: Double) {
        val js =
                "window.__ma_handlers && window.__ma_handlers['seekto'] && window.__ma_handlers['seekto']({ seekTime: $positionSec })"
        handler.post { WebViewHolder.webView?.evaluateJavascript(js, null) }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            if (url.startsWith("data:")) {
                val commaIndex = url.indexOf(',')
                if (commaIndex < 0) return null
                val base64Data = url.substring(commaIndex + 1)
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doInput = true
                connection.connect()
                val input = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(input)
                input.close()
                connection.disconnect()
                bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download artwork: ${e.message}")
            null
        }
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
        proxyPlayer?.release()
        proxyPlayer = null
        currentArtworkUrl = ""
        isPlaying = false
        onMetadataOrStateChanged = null
    }

    private class MediaSessionCallback : MediaSession.Callback
}

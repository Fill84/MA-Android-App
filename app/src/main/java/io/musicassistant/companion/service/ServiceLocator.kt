package io.musicassistant.companion.service

import android.content.Context
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.sendspin.SendspinClient
import io.musicassistant.companion.media.NativeMediaManager

/**
 * Simple service locator for application-scoped singletons. All components share the same API
 * client, Sendspin client, and media manager.
 */
object ServiceLocator {

    val apiClient: MaApiClient by lazy { MaApiClient() }
    val api: MaApi by lazy { MaApi(apiClient) }

    @Volatile
    var sendspinClient: SendspinClient? = null
        private set

    @Volatile private var mediaManager: NativeMediaManager? = null

    fun getSendspinClient(context: Context, playerId: String? = null): SendspinClient {
        val id = playerId ?: SendspinClient.generatePlayerId()
        val existing = sendspinClient
        if (existing != null && (playerId == null || existing.playerId == id)) return existing
        return synchronized(this) {
            val current = sendspinClient
            if (current != null && (playerId == null || current.playerId == id)) return current
            // Recreate if player ID changed or first creation
            current?.destroy()
            val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            SendspinClient(playerId = id, playerName = deviceName).also { sendspinClient = it }
        }
    }

    fun getMediaManager(context: Context): NativeMediaManager {
        return mediaManager
                ?: synchronized(this) {
                    mediaManager
                            ?: NativeMediaManager(context.applicationContext).also {
                                mediaManager = it
                            }
                }
    }

    fun destroy() {
        apiClient.destroy()
        sendspinClient?.destroy()
        sendspinClient = null
        mediaManager?.release()
        mediaManager = null
    }
}

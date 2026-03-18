package io.musicassistant.companion.service

import android.content.Context
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.sendspin.ClockSynchronizer
import io.musicassistant.companion.data.sendspin.SendspinClient
import io.musicassistant.companion.data.sendspin.SendspinConfig
import io.musicassistant.companion.data.sendspin.audio.AudioStreamManager
import io.musicassistant.companion.data.sendspin.audio.Codecs
import io.musicassistant.companion.media.NativeMediaManager
import io.musicassistant.companion.media.StreamAudioPlayer
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Simple service locator for application-scoped singletons. All components share the same API
 * client, Sendspin client, and media manager.
 *
 * Shared pipeline pattern: ClockSynchronizer and AudioStreamManager persist across
 * reconnections to avoid audio glitches. SendspinClient is recreated on reconnect
 * but reuses these shared components.
 */
object ServiceLocator {

    /** Shared OkHttpClient for all WebSocket connections. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    val apiClient: MaApiClient by lazy { MaApiClient(httpClient) }
    val api: MaApi by lazy { MaApi(apiClient) }

    @Volatile
    var sendspinClient: SendspinClient? = null
        private set

    @Volatile private var mediaManager: NativeMediaManager? = null

    // Shared pipeline components — persist across reconnections
    @Volatile private var streamAudioPlayer: StreamAudioPlayer? = null
    @Volatile private var clockSynchronizer: ClockSynchronizer? = null
    @Volatile private var audioStreamManager: AudioStreamManager? = null

    fun getStreamAudioPlayer(context: Context): StreamAudioPlayer {
        return streamAudioPlayer ?: synchronized(this) {
            streamAudioPlayer ?: StreamAudioPlayer(context.applicationContext).also {
                streamAudioPlayer = it
            }
        }
    }

    private fun getClockSynchronizer(): ClockSynchronizer {
        return clockSynchronizer ?: synchronized(this) {
            clockSynchronizer ?: ClockSynchronizer().also {
                clockSynchronizer = it
            }
        }
    }

    private fun getAudioStreamManager(context: Context): AudioStreamManager {
        return audioStreamManager ?: synchronized(this) {
            audioStreamManager ?: AudioStreamManager(
                getClockSynchronizer(),
                getStreamAudioPlayer(context)
            ).also {
                audioStreamManager = it
            }
        }
    }

    fun getSendspinClient(context: Context, playerId: String? = null, serverUrl: String? = null, authToken: String? = null): SendspinClient {
        val id = playerId ?: SendspinClient.generatePlayerId()
        return synchronized(this) {
            // Reuse existing client if it's alive and connected (not Idle/Error)
            val current = sendspinClient
            if (current != null) {
                val state = current.state.value
                if (state !is io.musicassistant.companion.data.sendspin.SendspinState.Idle &&
                    state !is io.musicassistant.companion.data.sendspin.SendspinState.Error) {
                    return@synchronized current
                }
                current.destroy()
            }

            val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val player = getStreamAudioPlayer(context)
            val clock = getClockSynchronizer()
            val pipeline = getAudioStreamManager(context)

            // Parse server URL components for SendspinConfig
            val config = buildSendspinConfig(id, deviceName, serverUrl, authToken)

            SendspinClient(
                config = config,
                streamAudioPlayer = player,
                httpClient = httpClient,
                externalPipeline = pipeline,
                externalClockSynchronizer = clock
            ).also { sendspinClient = it }
        }
    }

    private fun buildSendspinConfig(
        clientId: String,
        deviceName: String,
        serverUrl: String?,
        authToken: String?
    ): SendspinConfig {
        if (serverUrl.isNullOrEmpty()) {
            return SendspinConfig(
                clientId = clientId,
                deviceName = deviceName,
                codecPreference = Codecs.default,
                enabled = false
            )
        }

        // Parse URL: https://host:port/path → serverHost, serverPort, serverPath, useTls
        val url = java.net.URL(serverUrl.trimEnd('/'))
        val useTls = url.protocol == "https"
        val host = url.host
        val port = if (url.port != -1) url.port else if (useTls) 443 else 80
        val path = "${url.path}/sendspin"

        return SendspinConfig(
            clientId = clientId,
            deviceName = deviceName,
            codecPreference = Codecs.default,
            serverHost = host,
            serverPort = port,
            serverPath = path,
            useTls = useTls,
            authToken = authToken,
            mainConnectionPort = port // Same port = proxy mode → requires auth
        )
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

    /** Destroy pipeline components (on logout or full cleanup). */
    fun destroyPipeline() {
        audioStreamManager?.close()
        audioStreamManager = null
        clockSynchronizer?.reset()
        clockSynchronizer = null
        streamAudioPlayer?.release()
        streamAudioPlayer = null
    }

    fun destroy() {
        apiClient.destroy()
        sendspinClient?.destroy()
        sendspinClient = null
        destroyPipeline()
        mediaManager?.release()
        mediaManager = null
    }
}

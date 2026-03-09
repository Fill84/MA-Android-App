package io.musicassistant.companion.data.sendspin

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Client for the Music Assistant Sendspin protocol.
 *
 * Connects to ws://{host}:{port}/sendspin and registers the device as a player. Handles audio
 * stream signaling and commands from the server.
 */
class SendspinClient(
        val playerId: String,
        private val playerName: String = "Music Assistant Companion"
) {

    companion object {
        private const val TAG = "SendspinClient"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 60_000L

        /** Generate a new player ID in the ma_xxxx format. */
        fun generatePlayerId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val suffix = (1..10).map { chars.random() }.joinToString("")
            return "ma_$suffix"
        }
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        REGISTERED
    }

    /** Represents a command from the server to the player. */
    data class PlayerCommand(val command: String, val args: JsonObject? = null)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val httpClient =
            OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build()

    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var authToken: String? = null
    private var shouldReconnect = false
    private var backoffMs = INITIAL_BACKOFF_MS

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<PlayerCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<PlayerCommand> = _commands.asSharedFlow()

    /** URL of the audio stream assigned by the server. */
    private val _streamUrl = MutableStateFlow<String?>(null)
    val streamUrl: StateFlow<String?> = _streamUrl.asStateFlow()

    /**
     * Callback for binary audio data from Sendspin streaming. Parameters: (data: ByteArray, offset:
     * Int, length: Int) Called on OkHttp's WebSocket reader thread.
     */
    var onAudioData: ((ByteArray, Int, Int) -> Unit)? = null

    /** Connect to the Sendspin WebSocket endpoint. */
    fun connect(serverBaseUrl: String, token: String? = null) {
        disconnect()
        serverUrl = serverBaseUrl.trimEnd('/')
        authToken = token
        shouldReconnect = true
        backoffMs = INITIAL_BACKOFF_MS
        doConnect()
    }

    /** Disconnect from the server. */
    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _state.value = State.DISCONNECTED
        _streamUrl.value = null
    }

    fun destroy() {
        disconnect()
    }

    private fun doConnect() {
        if (!shouldReconnect) return
        _state.value = State.CONNECTING
        val wsUrl =
                serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/sendspin"
        Log.d(TAG, "Connecting Sendspin to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, WsListener())
    }

    /** Send auth message to the Sendspin proxy (required before hello). */
    private fun sendAuth() {
        val msg = buildJsonObject {
            put("type", "auth")
            put("token", authToken ?: "")
            put("client_id", playerId)
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "Sent auth to Sendspin proxy")
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val delayMs = backoffMs
        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        Log.d(TAG, "Sendspin reconnecting in ${delayMs}ms")
        _state.value = State.DISCONNECTED
        scope.launch {
            delay(delayMs)
            if (shouldReconnect) doConnect()
        }
    }

    /** Send the hello/registration message to identify this player. */
    private fun sendHello() {
        val msg = buildJsonObject {
            put("type", "client/hello")
            putJsonObject("payload") {
                put("client_id", playerId)
                put("name", playerName)
                put("version", 1)
                putJsonArray("supported_roles") {
                    add(kotlinx.serialization.json.JsonPrimitive("player@v1"))
                }
                putJsonObject("device_info") {
                    put("product_name", "Music Assistant Companion")
                    put("manufacturer", "Android")
                    put("software_version", "2.0.2")
                }
                putJsonObject("player_support") {
                    putJsonArray("supported_formats") {
                        add(
                                buildJsonObject {
                                    put("codec", "flac")
                                    put("channels", 2)
                                    put("sample_rate", 48000)
                                    put("bit_depth", 16)
                                }
                        )
                        add(
                                buildJsonObject {
                                    put("codec", "pcm")
                                    put("channels", 2)
                                    put("sample_rate", 48000)
                                    put("bit_depth", 16)
                                }
                        )
                    }
                    put("buffer_capacity", 100000)
                    putJsonArray("supported_commands") {
                        add(kotlinx.serialization.json.JsonPrimitive("volume"))
                        add(kotlinx.serialization.json.JsonPrimitive("mute"))
                    }
                }
            }
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "Sent hello with player_id=$playerId")
    }

    /** Report current player state back to the server. */
    fun reportState(
            state: String, // "playing", "paused", "idle", "buffering"
            currentUrl: String? = null,
            positionSeconds: Double = 0.0,
            volumeLevel: Int = 100,
            volumeMuted: Boolean = false
    ) {
        val msg = buildJsonObject {
            put("type", "client/state")
            putJsonObject("payload") {
                put("state", state)
                put("elapsed_time", positionSeconds)
                putJsonObject("player") {
                    put("volume", volumeLevel)
                    put("muted", volumeMuted)
                }
            }
        }
        webSocket?.send(msg.toString())
    }

    /** Send initial synchronized state after hello. */
    private fun sendInitialState() {
        val msg = buildJsonObject {
            put("type", "client/state")
            putJsonObject("payload") {
                put("state", "synchronized")
                putJsonObject("player") {
                    put("volume", 100)
                    put("muted", false)
                }
            }
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "Sent initial client/state synchronized")
    }

    private fun handleMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content

            when (type) {
                "auth_ok" -> {
                    Log.d(TAG, "Sendspin auth OK, sending hello")
                    sendHello()
                }
                "server/hello" -> {
                    Log.d(TAG, "Sendspin registered successfully (server/hello)")
                    _state.value = State.REGISTERED
                    backoffMs = INITIAL_BACKOFF_MS
                    sendInitialState()
                }
                "welcome" -> {
                    Log.d(TAG, "Sendspin registered successfully")
                    _state.value = State.REGISTERED
                    backoffMs = INITIAL_BACKOFF_MS
                }
                "play_url" -> {
                    val url = obj["url"]?.jsonPrimitive?.content
                    Log.d(TAG, "Received play URL: $url")
                    _streamUrl.value = url
                    scope.launch { _commands.emit(PlayerCommand("play_url", obj)) }
                }
                "command" -> {
                    val command = obj["command"]?.jsonPrimitive?.content ?: return
                    Log.d(TAG, "Received command: $command")
                    scope.launch { _commands.emit(PlayerCommand(command, obj)) }
                }
                "play" -> {
                    scope.launch { _commands.emit(PlayerCommand("play", obj)) }
                }
                "pause" -> {
                    scope.launch { _commands.emit(PlayerCommand("pause", obj)) }
                }
                "stop" -> {
                    scope.launch { _commands.emit(PlayerCommand("stop", obj)) }
                }
                "volume" -> {
                    scope.launch { _commands.emit(PlayerCommand("volume", obj)) }
                }
                "seek" -> {
                    scope.launch { _commands.emit(PlayerCommand("seek", obj)) }
                }
                "server/command" -> {
                    val payload = obj["payload"]?.jsonObject
                    val player = payload?.get("player")?.jsonObject
                    val command = player?.get("command")?.jsonPrimitive?.content
                    if (command != null) {
                        Log.d(TAG, "Received server/command: $command with args=$player")
                        scope.launch { _commands.emit(PlayerCommand(command, player)) }
                    }
                }
                "group/update" -> {
                    Log.d(TAG, "Received group/update: ${obj["payload"]}")
                }
                "stream/start" -> {
                    Log.d(TAG, "Stream start: $text")
                    scope.launch { _commands.emit(PlayerCommand("stream/start", obj)) }
                }
                "stream/end" -> {
                    Log.d(TAG, "Stream end")
                    scope.launch { _commands.emit(PlayerCommand("stream/end", obj)) }
                }
                "stream/clear" -> {
                    Log.d(TAG, "Stream clear")
                    scope.launch { _commands.emit(PlayerCommand("stream/clear", obj)) }
                }
                else -> {
                    Log.d(TAG, "Unknown Sendspin message type: $type")
                    // Pass through as generic command
                    if (type != null) {
                        scope.launch { _commands.emit(PlayerCommand(type, obj)) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Sendspin message: ${e.message}")
        }
    }

    private inner class WsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Sendspin WebSocket opened")
            _state.value = State.CONNECTED
            if (!authToken.isNullOrEmpty()) {
                sendAuth()
            } else {
                sendHello()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Raw Sendspin message: $text")
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Binary frame format: [1 byte type][8 bytes BE i64 timestamp][audio data]
            val data = bytes.toByteArray()
            if (data.size > 9) {
                onAudioData?.invoke(data, 9, data.size - 9)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Sendspin WebSocket closed: $code $reason")
            _state.value = State.DISCONNECTED
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Sendspin WebSocket failure: ${t.message}")
            _state.value = State.DISCONNECTED
            scheduleReconnect()
        }
    }
}

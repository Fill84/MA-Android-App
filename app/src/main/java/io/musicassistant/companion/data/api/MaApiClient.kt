package io.musicassistant.companion.data.api

import android.util.Log
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.ServerInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket client for the Music Assistant server API.
 *
 * Connects to ws://{host}:{port}/ws and communicates via a JSON message protocol. Supports
 * auto-reconnect with exponential backoff.
 */
class MaApiClient(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "MaApiClient"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 60_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var webSocket: WebSocket? = null
    /** Monotonic generation counter — incremented on each connect() to detect stale callbacks. */
    @Volatile private var wsGeneration = 0
    private var serverUrl: String = ""
    private var authToken: String? = null
    @Volatile private var shouldReconnect = false
    private var backoffMs = INITIAL_BACKOFF_MS

    private val messageId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    /** The connection URL that the client is connected to (set by the caller). */
    val connectionUrl: String
        get() = serverUrl

    /** The current auth token, if any. */
    val currentAuthToken: String?
        get() = authToken

    private val _events = MutableSharedFlow<MaEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MaEvent> = _events.asSharedFlow()

    /** An event received from the server. */
    data class MaEvent(
            val event: String,
            val objectId: String? = null,
            val data: JsonElement? = null
    )

    /** Connect to the MA server WebSocket. */
    fun connect(serverBaseUrl: String, token: String? = null) {
        disconnect()
        serverUrl = serverBaseUrl.trimEnd('/')
        authToken = token
        shouldReconnect = true
        backoffMs = INITIAL_BACKOFF_MS
        doConnect()
    }

    /** Disconnect and stop reconnecting. */
    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        // Fail all pending requests
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(Exception("Disconnected"))
        }
        pendingRequests.clear()
    }

    /** Release all resources. */
    fun destroy() {
        disconnect()
        scope.cancel()
    }

    /**
     * Authenticate with the MA server via HTTP and obtain a JWT token. POST to {baseUrl}/auth/login
     * with builtin provider credentials. Returns the token on success, or throws an exception on
     * failure.
     */
    suspend fun login(baseUrl: String, username: String, password: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/auth/login"
            val body = buildJsonObject {
                put("provider_id", "builtin")
                putJsonObject("credentials") {
                    put("username", username)
                    put("password", password)
                }
                put("device_name", "MA Android Companion")
            }
            val request =
                    Request.Builder()
                            .url(url)
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
            val response =
                    httpClient
                            .newBuilder()
                            .readTimeout(10, TimeUnit.SECONDS)
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .build()
                            .newCall(request)
                            .execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) {
                val parsed =
                        try {
                            json.parseToJsonElement(responseBody).jsonObject
                        } catch (_: Exception) {
                            null
                        }
                val error =
                        parsed?.get("error")?.jsonPrimitive?.content
                                ?: "Login failed (${response.code})"
                throw Exception(error)
            }
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val success = parsed["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
            if (!success) {
                val error = parsed["error"]?.jsonPrimitive?.content ?: "Authentication failed"
                throw Exception(error)
            }
            parsed["token"]?.jsonPrimitive?.content ?: throw Exception("No token in response")
        }
    }

    /**
     * Send a command and await the result. The message format follows the MA server protocol: {
     * "message_id": N, "command": "...", "args": { ...params } }
     */
    suspend fun sendCommand(
            command: String,
            args: Map<String, JsonElement> = emptyMap()
    ): JsonElement {
        val ws = webSocket ?: throw IllegalStateException("Not connected")
        val id = messageId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[id] = deferred

        val msg = buildJsonObject {
            put("message_id", id)
            put("command", command)
            if (args.isNotEmpty()) {
                putJsonObject("args") { args.forEach { (key, value) -> put(key, value) } }
            }
        }

        Log.d(TAG, "Sending command: $msg")
        val sent = ws.send(msg.toString())
        if (!sent) {
            pendingRequests.remove(id)
            deferred.completeExceptionally(Exception("Failed to send message"))
        }

        return deferred.await()
    }

    private fun doConnect() {
        if (!shouldReconnect) return
        _connectionState.value = ConnectionState.CONNECTING
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        Log.d(TAG, "Connecting to $wsUrl")

        val gen = ++wsGeneration
        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, WsListener(gen))
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val delay = backoffMs
        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        Log.d(TAG, "Reconnecting in ${delay}ms")
        _connectionState.value = ConnectionState.DISCONNECTED
        scope.launch {
            delay(delay)
            if (shouldReconnect) doConnect()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject

            // Check if it's a server info message (may not have "type" field)
            val type = obj["type"]?.jsonPrimitive?.content

            // Detect server_info by presence of server_id/server_version (server may omit "type")
            if (type == "server_info" ||
                            type == "auth_ok" ||
                            (type == null &&
                                    obj.containsKey("server_id") &&
                                    obj.containsKey("server_version"))
            ) {
                handleServerInfo(obj)
                return
            }

            when (type) {
                "auth_required" -> handleAuthRequired(obj)
                "result" -> handleResult(obj)
                "event" -> handleEvent(obj)
                else -> {
                    // Events from MA server have "event" key but may not have "type"
                    if (obj.containsKey("event")) {
                        handleEvent(obj)
                    } else if (obj.containsKey("message_id")) {
                        handleResult(obj)
                    } else {
                        Log.d(TAG, "Unknown message type: $type")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
        }
    }

    private fun handleServerInfo(obj: JsonObject) {
        try {
            val info = json.decodeFromJsonElement(ServerInfo.serializer(), obj)
            _serverInfo.value = info
        } catch (_: Exception) {
            // Server info fields may vary, that's okay
        }
        _connectionState.value = ConnectionState.CONNECTED
        scope.launch {
            try {
                if (!authToken.isNullOrEmpty()) {
                    Log.d(TAG, "Sending auth with token (redacted)")
                    val result =
                            sendCommand(
                                    "auth",
                                    mapOf(
                                            "token" to
                                                    kotlinx.serialization.json.JsonPrimitive(
                                                            authToken
                                                    )
                                    )
                            )
                    Log.d(TAG, "Auth result: $result")
                }
                // Events are auto-subscribed by the server after auth
                _connectionState.value = ConnectionState.AUTHENTICATED
                backoffMs = INITIAL_BACKOFF_MS
                Log.d(TAG, "Connected and authenticated")
            } catch (e: Exception) {
                Log.e(TAG, "Auth failed: ${e.message}")
                _connectionState.value = ConnectionState.AUTH_FAILED
                shouldReconnect = false
            }
        }
    }

    private fun handleAuthRequired(obj: JsonObject) {
        _connectionState.value = ConnectionState.CONNECTED
        scope.launch {
            if (authToken.isNullOrEmpty()) {
                Log.e(TAG, "Server requires authentication but no token available")
                _connectionState.value = ConnectionState.AUTH_FAILED
                shouldReconnect = false
                return@launch
            }
            try {
                val result =
                        sendCommand(
                                "auth",
                                mapOf(
                                        "token" to
                                                kotlinx.serialization.json.JsonPrimitive(authToken)
                                )
                        )
                Log.d(TAG, "Auth result: $result")
                // Events are auto-subscribed by the server after auth
                _connectionState.value = ConnectionState.AUTHENTICATED
                backoffMs = INITIAL_BACKOFF_MS
                Log.d(TAG, "Authenticated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Auth/subscribe failed: ${e.message}")
                _connectionState.value = ConnectionState.AUTH_FAILED
                shouldReconnect = false
            }
        }
    }

    private fun handleResult(obj: JsonObject) {
        // message_id can be int or string from server
        val id =
                try {
                    obj["message_id"]?.jsonPrimitive?.int
                } catch (_: Exception) {
                    obj["message_id"]?.jsonPrimitive?.content?.toIntOrNull()
                } ?: return
        val deferred = pendingRequests.remove(id) ?: return

        // Check for error_code (MA server error format)
        val errorCode = obj["error_code"]?.jsonPrimitive?.int
        if (errorCode != null) {
            val details = obj["details"]?.jsonPrimitive?.content ?: "Error code $errorCode"
            deferred.completeExceptionally(Exception(details))
            return
        }

        val success = obj["success"]?.jsonPrimitive?.content?.toBoolean()
        if (success == false) {
            val error = obj["error"]?.toString() ?: "Unknown error"
            deferred.completeExceptionally(Exception(error))
            return
        }

        val result = obj["result"] ?: obj
        deferred.complete(result)
    }

    private fun handleEvent(obj: JsonObject) {
        val event = obj["event"]?.jsonPrimitive?.content ?: return
        val objectId = obj["object_id"]?.jsonPrimitive?.content
        val data = obj["data"]

        scope.launch { _events.emit(MaEvent(event = event, objectId = objectId, data = data)) }
    }

    private inner class WsListener(private val gen: Int) : WebSocketListener() {
        private fun isCurrent() = gen == wsGeneration

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) { webSocket.close(1000, "stale"); return }
            Log.d(TAG, "WebSocket opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) { Log.d(TAG, "Ignoring stale onClosed (gen=$gen)"); return }
            Log.d(TAG, "WebSocket closed: $code $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
            pendingRequests.forEach { (_, deferred) ->
                deferred.completeExceptionally(Exception("Connection closed"))
            }
            pendingRequests.clear()
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) { Log.d(TAG, "Ignoring stale onFailure (gen=$gen)"); return }
            Log.e(TAG, "WebSocket failure: ${t.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            pendingRequests.forEach { (_, deferred) -> deferred.completeExceptionally(t) }
            pendingRequests.clear()
            scheduleReconnect()
        }
    }
}

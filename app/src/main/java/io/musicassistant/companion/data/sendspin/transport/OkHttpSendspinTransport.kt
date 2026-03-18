package io.musicassistant.companion.data.sendspin.transport

import android.util.Log
import io.musicassistant.companion.data.sendspin.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.CoroutineContext

/**
 * OkHttp WebSocket implementation of [SendspinTransport].
 *
 * Features:
 * - SharedFlows for text/binary messages (buffered to absorb slow consumers)
 * - Two-phase reconnect backoff (quick 0-4s, then patient 8-60s)
 * - Explicit disconnect vs network drop distinction
 */
class OkHttpSendspinTransport(
    private val serverUrl: String,
    private val httpClient: OkHttpClient
) : SendspinTransport, CoroutineScope {

    companion object {
        private const val TAG = "OkHttpSendspinTransport"
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private val supervisorJob = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + supervisorJob

    private var webSocket: WebSocket? = null
    private var explicitDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    override val connectionState: Flow<WebSocketState> = _connectionState.asStateFlow()

    private val _textMessages = MutableSharedFlow<String>(extraBufferCapacity = 50)
    override val textMessages: Flow<String> = _textMessages.asSharedFlow()

    private val _binaryMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 100)
    override val binaryMessages: Flow<ByteArray> = _binaryMessages.asSharedFlow()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connected to $serverUrl")
            reconnectAttempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
            _connectionState.value = WebSocketState.Connected
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _textMessages.tryEmit(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            _binaryMessages.tryEmit(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            handleDisconnection()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (explicitDisconnect) {
                Log.i(TAG, "Explicit disconnect, not reconnecting")
                handleDisconnection()
                return
            }

            Log.e(TAG, "WebSocket error: ${t.message} - will auto-reconnect")
            _connectionState.value = WebSocketState.Reconnecting(reconnectAttempts)
            attemptReconnect()
        }
    }

    override suspend fun connect() {
        val current = _connectionState.value
        if (current is WebSocketState.Connected || current is WebSocketState.Connecting) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        explicitDisconnect = false
        _connectionState.value = WebSocketState.Connecting
        Log.i(TAG, "Connecting to $serverUrl")

        doConnect()
    }

    private fun doConnect() {
        // Convert https/http URL to wss/ws for WebSocket
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, listener)
    }

    override suspend fun sendText(message: String) {
        val ws = webSocket ?: throw IllegalStateException("WebSocket not connected")
        if (!ws.send(message)) {
            throw IllegalStateException("Failed to enqueue text message")
        }
    }

    override suspend fun sendBinary(data: ByteArray) {
        val ws = webSocket ?: throw IllegalStateException("WebSocket not connected")
        if (!ws.send(data.toByteString())) {
            throw IllegalStateException("Failed to enqueue binary message")
        }
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting WebSocket (explicit)")
        explicitDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null

        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = WebSocketState.Disconnected
    }

    private fun handleDisconnection() {
        if (_connectionState.value !is WebSocketState.Disconnected) {
            Log.i(TAG, "WebSocket disconnected")
            _connectionState.value = WebSocketState.Disconnected
        }
        webSocket = null
    }

    private fun attemptReconnect() {
        reconnectJob?.cancel()
        reconnectJob = launch {
            for (attempt in 0 until MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts = attempt + 1
                Log.i(TAG, "Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
                _connectionState.value = WebSocketState.Reconnecting(reconnectAttempts)

                delay(reconnectBackoffMs(attempt))

                try {
                    doConnect()
                    // Wait briefly for onOpen callback
                    delay(2000)
                    if (_connectionState.value is WebSocketState.Connected) {
                        Log.i(TAG, "Reconnected successfully after $reconnectAttempts attempts")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt $reconnectAttempts failed: ${e.message}")
                }
            }

            Log.e(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            webSocket = null
            _connectionState.value = WebSocketState.Error(
                Exception("Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
            )
        }
    }

    override fun close() {
        Log.i(TAG, "Closing OkHttpSendspinTransport")
        explicitDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Transport closing")
        webSocket = null
        supervisorJob.cancel()
    }
}

/**
 * Two-phase reconnection backoff.
 * Phase 1 (quick recovery, attempts 0-4): 0 → 500ms → 1s → 2s → 4s
 * Phase 2 (patient recovery, attempts 5-9): 8s → 15s → 30s → 60s → 60s
 */
private fun reconnectBackoffMs(attempt: Int): Long = when (attempt) {
    0 -> 0L
    1 -> 500L
    2 -> 1_000L
    3 -> 2_000L
    4 -> 4_000L
    5 -> 8_000L
    6 -> 15_000L
    7 -> 30_000L
    8 -> 60_000L
    else -> 60_000L
}

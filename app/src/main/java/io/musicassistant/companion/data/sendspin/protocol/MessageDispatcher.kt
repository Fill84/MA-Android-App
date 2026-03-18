package io.musicassistant.companion.data.sendspin.protocol

import android.util.Log
import io.musicassistant.companion.data.sendspin.ClockSynchronizer
import io.musicassistant.companion.data.sendspin.model.ClientAuthMessage
import io.musicassistant.companion.data.sendspin.model.ClientCommandMessage
import io.musicassistant.companion.data.sendspin.model.ClientGoodbyeMessage
import io.musicassistant.companion.data.sendspin.model.ClientHelloMessage
import io.musicassistant.companion.data.sendspin.model.ClientHelloPayload
import io.musicassistant.companion.data.sendspin.model.ClientStateMessage
import io.musicassistant.companion.data.sendspin.model.ClientStatePayload
import io.musicassistant.companion.data.sendspin.model.ClientTimeMessage
import io.musicassistant.companion.data.sendspin.model.ClientTimePayload
import io.musicassistant.companion.data.sendspin.model.CommandPayload
import io.musicassistant.companion.data.sendspin.model.CommandValue
import io.musicassistant.companion.data.sendspin.model.GoodbyePayload
import io.musicassistant.companion.data.sendspin.model.GroupUpdateMessage
import io.musicassistant.companion.data.sendspin.model.PlayerStateObject
import io.musicassistant.companion.data.sendspin.model.PlayerStateValue
import io.musicassistant.companion.data.sendspin.model.ServerCommandMessage
import io.musicassistant.companion.data.sendspin.model.ServerHelloMessage
import io.musicassistant.companion.data.sendspin.model.ServerHelloPayload
import io.musicassistant.companion.data.sendspin.model.ServerStateMessage
import io.musicassistant.companion.data.sendspin.model.ServerTimeMessage
import io.musicassistant.companion.data.sendspin.model.SessionUpdateMessage
import io.musicassistant.companion.data.sendspin.model.StreamClearMessage
import io.musicassistant.companion.data.sendspin.model.StreamEndMessage
import io.musicassistant.companion.data.sendspin.model.StreamMetadataMessage
import io.musicassistant.companion.data.sendspin.model.StreamMetadataPayload
import io.musicassistant.companion.data.sendspin.model.StreamStartMessage
import io.musicassistant.companion.data.sendspin.transport.SendspinTransport
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext

class MessageDispatcher(
    private val transport: SendspinTransport,
    private val clockSynchronizer: ClockSynchronizer,
    private val config: MessageDispatcherConfig
) : CoroutineScope {

    companion object {
        private const val TAG = "MessageDispatcher"

        private val json = Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // Convenience accessors for config properties
    private val clientCapabilities: ClientHelloPayload get() = config.clientCapabilities
    private val initialVolume: Int get() = config.initialVolume
    private val authToken: String? get() = config.authToken
    private val requiresAuth: Boolean get() = config.requiresAuth

    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + supervisorJob

    private var messageListenerJob: Job? = null
    private var clockSyncJob: Job? = null

    private val _serverHelloEvent = MutableSharedFlow<ServerHelloPayload>(extraBufferCapacity = 1)
    val serverHelloEvent: Flow<ServerHelloPayload> = _serverHelloEvent.asSharedFlow()

    private val _serverInfo = MutableStateFlow<ServerHelloPayload?>(null)
    val serverInfo: StateFlow<ServerHelloPayload?> = _serverInfo.asStateFlow()

    private val _streamMetadata = MutableStateFlow<StreamMetadataPayload?>(null)
    val streamMetadata: StateFlow<StreamMetadataPayload?> = _streamMetadata.asStateFlow()

    private val _streamStartEvent = MutableSharedFlow<StreamStartMessage>(extraBufferCapacity = 1)
    val streamStartEvent: Flow<StreamStartMessage> = _streamStartEvent.asSharedFlow()

    private val _streamEndEvent = MutableSharedFlow<StreamEndMessage>(extraBufferCapacity = 1)
    val streamEndEvent: Flow<StreamEndMessage> = _streamEndEvent.asSharedFlow()

    private val _streamClearEvent = MutableSharedFlow<StreamClearMessage>(extraBufferCapacity = 1)
    val streamClearEvent: Flow<StreamClearMessage> = _streamClearEvent.asSharedFlow()

    private val _serverCommandEvent =
        MutableSharedFlow<ServerCommandMessage>(extraBufferCapacity = 5)
    val serverCommandEvent: Flow<ServerCommandMessage> = _serverCommandEvent.asSharedFlow()

    fun start() {
        Log.i(TAG, "Starting MessageDispatcher")
        startMessageListener()
    }

    fun stop() {
        Log.i(TAG, "Stopping MessageDispatcher")
        messageListenerJob?.cancel()
        clockSyncJob?.cancel()
    }

    private fun startMessageListener() {
        messageListenerJob?.cancel()
        messageListenerJob = launch {
            try {
                transport.textMessages.collect { text ->
                    try {
                        handleTextMessage(text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling text message: ${text.take(200)}", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error", e)
            } finally {
                clockSyncJob?.cancel()
            }
        }
    }

    private suspend fun handleTextMessage(text: String) {
        Log.d(TAG, "Handling message: ${text.take(200)}")

        try {
            val jsonObj = json.parseToJsonElement(text).jsonObject
            val type = jsonObj["type"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Message missing 'type' field")

            when (type) {
                "auth_ok" -> handleAuthOk()

                "server/hello" -> {
                    val message = json.decodeFromJsonElement<ServerHelloMessage>(jsonObj)
                    handleServerHello(message)
                }

                "server/time" -> {
                    val message = json.decodeFromJsonElement<ServerTimeMessage>(jsonObj)
                    handleServerTime(message)
                }

                "stream/start" -> {
                    val message = json.decodeFromJsonElement<StreamStartMessage>(jsonObj)
                    handleStreamStart(message)
                }

                "stream/end" -> {
                    val message = json.decodeFromJsonElement<StreamEndMessage>(jsonObj)
                    handleStreamEnd(message)
                }

                "stream/clear" -> {
                    val message = json.decodeFromJsonElement<StreamClearMessage>(jsonObj)
                    handleStreamClear(message)
                }

                "stream/metadata" -> {
                    val message = json.decodeFromJsonElement<StreamMetadataMessage>(jsonObj)
                    handleStreamMetadata(message)
                }

                "session/update" -> {
                    val message = json.decodeFromJsonElement<SessionUpdateMessage>(jsonObj)
                    handleSessionUpdate(message)
                }

                "server/command" -> {
                    val message = json.decodeFromJsonElement<ServerCommandMessage>(jsonObj)
                    handleServerCommand(message)
                }

                "group/update" -> {
                    val message = json.decodeFromJsonElement<GroupUpdateMessage>(jsonObj)
                    handleGroupUpdate(message)
                }

                "server/state" -> {
                    val message = json.decodeFromJsonElement<ServerStateMessage>(jsonObj)
                    handleServerState(message)
                }

                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${text.take(200)}", e)
        }
    }

    // Outgoing messages

    suspend fun sendAuth() {
        val token = authToken
        if (!requiresAuth || token == null) {
            Log.w(TAG, "sendAuth called but auth not required or token missing")
            return
        }

        Log.i(TAG, "Sending auth message (proxy mode)")

        val message = ClientAuthMessage(
            token = token,
            clientId = clientCapabilities.clientId
        )
        val text = json.encodeToString(message)
        transport.sendText(text)
    }

    suspend fun sendHello() {
        Log.i(TAG, "Sending client/hello")

        val message = ClientHelloMessage(payload = clientCapabilities)
        val text = json.encodeToString(message)
        transport.sendText(text)
    }

    suspend fun sendTime() {
        val clientTransmitted = getCurrentTimeMicros()
        val message = ClientTimeMessage(
            payload = ClientTimePayload(clientTransmitted = clientTransmitted)
        )
        val text = json.encodeToString(message)
        transport.sendText(text)
    }

    suspend fun sendState(state: PlayerStateObject) {
        val message = ClientStateMessage(
            payload = ClientStatePayload(player = state)
        )
        val text = json.encodeToString(message)
        Log.d(TAG, "Sending client/state: $text")
        transport.sendText(text)
    }

    suspend fun sendGoodbye(reason: String) {
        Log.i(TAG, "Sending client/goodbye: $reason")
        val message = ClientGoodbyeMessage(
            payload = GoodbyePayload(reason = reason)
        )
        val text = json.encodeToString(message)
        transport.sendText(text)
    }

    suspend fun sendCommand(command: String, value: CommandValue?) {
        Log.d(TAG, "Sending client/command: $command")
        val message = ClientCommandMessage(
            payload = CommandPayload(command = command, value = value)
        )
        val text = json.encodeToString(message)
        transport.sendText(text)
    }

    // Message handlers

    private suspend fun handleAuthOk() {
        Log.i(TAG, "Received auth_ok - authentication successful")
        sendHello()
    }

    private suspend fun handleServerHello(message: ServerHelloMessage) {
        Log.i(TAG, "Received server/hello from ${message.payload.name}")
        _serverInfo.value = message.payload

        sendInitialState()
        startClockSync()

        _serverHelloEvent.emit(message.payload)
    }

    private suspend fun sendInitialState() {
        val initialState = PlayerStateObject(
            state = PlayerStateValue.SYNCHRONIZED,
            volume = initialVolume,
            muted = false
        )
        sendState(initialState)
    }

    private fun startClockSync() {
        clockSyncJob?.cancel()
        clockSyncJob = launch {
            while (isActive) {
                try {
                    sendTime()
                    delay(1000)
                } catch (_: IllegalStateException) {
                    Log.w(TAG, "Clock sync stopped: Transport not connected")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clock sync", e)
                    break
                }
            }
        }
    }

    private fun handleServerTime(message: ServerTimeMessage) {
        val clientReceived = getCurrentTimeMicros()
        val payload = message.payload

        clockSynchronizer.processServerTime(
            clientTransmitted = payload.clientTransmitted,
            serverReceived = payload.serverReceived,
            serverTransmitted = payload.serverTransmitted,
            clientReceived = clientReceived
        )

        Log.d(TAG, "Clock sync: offset=${clockSynchronizer.currentOffset}us, quality=${clockSynchronizer.currentQuality}")
    }

    private suspend fun handleStreamStart(message: StreamStartMessage) {
        Log.i(TAG, "Received stream/start")
        _streamStartEvent.emit(message)
    }

    private suspend fun handleStreamEnd(message: StreamEndMessage) {
        Log.i(TAG, "Received stream/end")
        _streamEndEvent.emit(message)
    }

    private suspend fun handleStreamClear(message: StreamClearMessage) {
        Log.i(TAG, "Received stream/clear")
        _streamClearEvent.emit(message)
    }

    private fun handleStreamMetadata(message: StreamMetadataMessage) {
        Log.i(TAG, "Received stream/metadata: ${message.payload.title}")
        _streamMetadata.value = message.payload
    }

    private fun handleSessionUpdate(message: SessionUpdateMessage) {
        Log.d(TAG, "Received session/update: ${message.payload.metadata?.title}")
        message.payload.metadata?.let { metadata ->
            _streamMetadata.value = StreamMetadataPayload(
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                artworkUrl = metadata.artworkUrl
            )
        }
    }

    private suspend fun handleServerCommand(message: ServerCommandMessage) {
        Log.d(TAG, "Received server/command: ${message.payload.player.command}")
        _serverCommandEvent.emit(message)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleGroupUpdate(message: GroupUpdateMessage) {
        Log.d(TAG, "Received group/update: ${message.payload.groupName}")
    }

    private fun handleServerState(message: ServerStateMessage) {
        Log.d(TAG, "Received server/state")

        message.payload?.let { payload ->
            try {
                val metadataElement = payload.jsonObject["metadata"]
                if (metadataElement != null) {
                    val metadata = metadataElement.jsonObject
                    val title = metadata["title"]?.jsonPrimitive?.contentOrNull
                    val artist = metadata["artist"]?.jsonPrimitive?.contentOrNull
                    val album = metadata["album"]?.jsonPrimitive?.contentOrNull
                    val artworkUrl = metadata["artwork_url"]?.jsonPrimitive?.contentOrNull

                    if (title != null || artist != null) {
                        _streamMetadata.value = StreamMetadataPayload(
                            title = title,
                            artist = artist,
                            album = album,
                            artworkUrl = artworkUrl
                        )
                        Log.d(TAG, "Updated stream metadata from server/state: $title by $artist")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse server/state metadata: ${e.message}")
            }
        }
    }

    private fun getCurrentTimeMicros(): Long = clockSynchronizer.getCurrentTimeMicros()

    fun close() {
        Log.i(TAG, "Closing MessageDispatcher")
        stop()
        supervisorJob.cancel()
    }
}

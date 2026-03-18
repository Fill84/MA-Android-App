package io.musicassistant.companion.data.sendspin

import android.util.Log
import io.musicassistant.companion.data.sendspin.audio.AudioPipeline
import io.musicassistant.companion.data.sendspin.audio.AudioStreamManager
import io.musicassistant.companion.data.sendspin.model.CommandValue
import io.musicassistant.companion.data.sendspin.model.PlayerStateValue
import io.musicassistant.companion.data.sendspin.model.ServerCommandMessage
import io.musicassistant.companion.data.sendspin.model.StreamMetadataPayload
import io.musicassistant.companion.data.sendspin.protocol.MessageDispatcher
import io.musicassistant.companion.data.sendspin.protocol.MessageDispatcherConfig
import io.musicassistant.companion.data.sendspin.transport.OkHttpSendspinTransport
import io.musicassistant.companion.data.sendspin.transport.SendspinTransport
import io.musicassistant.companion.media.StreamAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.coroutines.CoroutineContext

/**
 * Sendspin protocol client — state machine that composes transport, protocol,
 * audio pipeline, clock sync, and state reporting.
 *
 * State machine: Idle → Connecting → Authenticating → Handshaking → Ready → Buffering → Synchronized
 *
 * Shared pipeline pattern: ClockSynchronizer and AudioStreamManager can be passed in
 * externally so they persist across reconnections (avoids audio glitches).
 */
class SendspinClient(
    private val config: SendspinConfig,
    private val streamAudioPlayer: StreamAudioPlayer,
    private val httpClient: OkHttpClient,
    private val externalPipeline: AudioPipeline? = null,
    private val externalClockSynchronizer: ClockSynchronizer? = null
) : CoroutineScope {

    companion object {
        private const val TAG = "SendspinClient"

        /** Generate a new player ID in the ma_xxxx format. */
        fun generatePlayerId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val suffix = (1..10).map { chars.random() }.joinToString("")
            return "ma_$suffix"
        }
    }

    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + supervisorJob

    // If external pipeline/clock provided, use them; otherwise own them
    private val ownsAudioPipeline = externalPipeline == null
    private val clockSynchronizer = externalClockSynchronizer ?: ClockSynchronizer()
    private val audioPipeline: AudioPipeline =
        externalPipeline ?: AudioStreamManager(clockSynchronizer, streamAudioPlayer)

    // Components
    private var transport: SendspinTransport? = null
    private var messageDispatcher: MessageDispatcher? = null
    private var stateReporter: StateReporter? = null

    // Unified state
    private val _state = MutableStateFlow<SendspinState>(SendspinState.Idle)
    val state: StateFlow<SendspinState> = _state.asStateFlow()

    // Exposed event for when playback stops due to error
    private val _playbackStoppedDueToError = MutableStateFlow<Throwable?>(null)
    val playbackStoppedDueToError: StateFlow<Throwable?> = _playbackStoppedDueToError.asStateFlow()

    // Track current volume/mute state
    private var currentVolume: Int = streamAudioPlayer.getCurrentSystemVolume()
    private var currentMuted: Boolean = false

    val metadata: StateFlow<StreamMetadataPayload?>
        get() = messageDispatcher?.streamMetadata ?: MutableStateFlow(null)

    /** Server command events (volume, mute) — observed by MusicService */
    val serverCommandEvent get() = messageDispatcher?.serverCommandEvent

    suspend fun start() {
        if (!config.isValid) {
            Log.w(TAG, "Sendspin config invalid: enabled=${config.enabled}, host=${config.serverHost}, device=${config.deviceName}")
            return
        }

        Log.i(TAG, "Starting Sendspin client: ${config.deviceName}")

        try {
            val serverUrl = config.buildServerUrl()
            val sendspinTransport = OkHttpSendspinTransport(serverUrl, httpClient)
            connectWithTransport(sendspinTransport)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Sendspin client", e)
            _state.update {
                SendspinState.Error(
                    SendspinError.Permanent(
                        cause = e,
                        userAction = "Check Sendspin settings and server connection"
                    )
                )
            }
        }
    }

    suspend fun connectWithTransport(sendspinTransport: SendspinTransport) {
        Log.i(TAG, "Connecting to Sendspin with transport")

        try {
            // Clean up existing connection
            disconnectFromServer()

            // Update current volume from system right before connecting
            currentVolume = streamAudioPlayer.getCurrentSystemVolume()
            Log.i(TAG, "Initializing with system volume: $currentVolume%")

            // Store transport
            transport = sendspinTransport

            // Create message dispatcher
            val capabilities = SendspinCapabilities.buildClientHello(config, config.codecPreference)
            val dispatcherConfig = MessageDispatcherConfig(
                clientCapabilities = capabilities,
                initialVolume = currentVolume,
                authToken = config.authToken,
                requiresAuth = config.requiresAuth
            )
            val dispatcher = MessageDispatcher(
                transport = sendspinTransport,
                clockSynchronizer = clockSynchronizer,
                config = dispatcherConfig
            )
            messageDispatcher = dispatcher

            // Create state reporter (uses unified state)
            val reporter = StateReporter(
                messageDispatcher = dispatcher,
                volumeProvider = { currentVolume },
                mutedProvider = { currentMuted },
                stateProvider = { _state.value }
            )
            stateReporter = reporter

            // Mark as connecting
            _state.update { SendspinState.Connecting }

            // Connect transport
            sendspinTransport.connect()

            // Start message dispatcher (listens for text messages)
            dispatcher.start()

            // Run the unified state machine
            runStateMachine(sendspinTransport, dispatcher)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server", e)
            _state.update {
                SendspinState.Error(
                    SendspinError.Permanent(
                        cause = e,
                        userAction = "Verify server is running and accessible"
                    )
                )
            }
        }
    }

    /**
     * Single state machine that reacts to:
     * - transport connection state changes
     * - server/hello events from MessageDispatcher
     * - stream start/end/clear events
     * - binary audio messages
     * - server commands
     * - audio pipeline errors
     */
    private fun runStateMachine(
        sendspinTransport: SendspinTransport,
        dispatcher: MessageDispatcher
    ) {
        // --- Transport state ---
        launch {
            sendspinTransport.connectionState.collect { wsState ->
                when (wsState) {
                    WebSocketState.Connected -> {
                        when (_state.value) {
                            is SendspinState.Connecting, is SendspinState.Reconnecting -> {
                                try {
                                    if (config.requiresAuth) {
                                        _state.update { SendspinState.Authenticating }
                                        dispatcher.sendAuth()
                                    } else {
                                        _state.update { SendspinState.Handshaking }
                                        dispatcher.sendHello()
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to send auth/hello: ${e.message}")
                                }
                            }
                            else -> Unit
                        }
                    }

                    is WebSocketState.Reconnecting -> {
                        val current = _state.value
                        val wasStreaming = current is SendspinState.Buffering ||
                                current is SendspinState.Synchronized
                        _state.update { SendspinState.Reconnecting(wasStreaming, wsState.attempt) }
                    }

                    is WebSocketState.Error -> {
                        val isPermanent =
                            wsState.error.message?.contains("Failed to reconnect") == true

                        if (isPermanent) {
                            val current = _state.value
                            val wasStreaming = current is SendspinState.Reconnecting &&
                                    current.wasStreaming
                            if (wasStreaming) {
                                audioPipeline.stopStream()
                                stateReporter?.stop()
                            }
                            _state.update {
                                SendspinState.Error(
                                    SendspinError.Permanent(
                                        cause = wsState.error,
                                        userAction = "Check network connection and server availability"
                                    )
                                )
                            }
                        } else {
                            _state.update {
                                SendspinState.Error(
                                    SendspinError.Transient(
                                        cause = wsState.error,
                                        willRetry = false
                                    )
                                )
                            }
                        }
                    }

                    WebSocketState.Disconnected -> {
                        val current = _state.value
                        if (current !is SendspinState.Reconnecting) {
                            _state.update { SendspinState.Idle }
                        }
                    }

                    WebSocketState.Connecting -> Unit
                }
            }
        }

        // --- server/hello event → Ready ---
        launch {
            dispatcher.serverHelloEvent.collect { payload ->
                val current = _state.value
                if (current is SendspinState.Handshaking ||
                    current is SendspinState.Authenticating
                ) {
                    Log.i(TAG, "server/hello received - transitioning to Ready")
                    _state.update {
                        SendspinState.Ready(
                            serverId = payload.serverId,
                            serverName = payload.name
                        )
                    }
                }
            }
        }

        // --- Stream events ---
        launch {
            dispatcher.streamStartEvent.collect { event ->
                event.payload.player?.let { playerConfig ->
                    audioPipeline.startStream(playerConfig)
                    _state.update { SendspinState.Buffering }
                    stateReporter?.start()
                }
            }
        }

        launch {
            dispatcher.streamEndEvent.collect {
                audioPipeline.stopStream()
                val current = _state.value
                if (current is SendspinState.Buffering || current is SendspinState.Synchronized) {
                    val serverInfo = messageDispatcher?.serverInfo?.value
                    val nextState = if (serverInfo != null) {
                        SendspinState.Ready(serverInfo.serverId, serverInfo.name)
                    } else {
                        SendspinState.Idle
                    }
                    _state.update { nextState }
                }
                stateReporter?.stop()
            }
        }

        launch {
            dispatcher.streamClearEvent.collect {
                audioPipeline.clearStream()
            }
        }

        // --- Binary audio messages ---
        launch {
            sendspinTransport.binaryMessages.collect { data ->
                audioPipeline.processBinaryMessage(data)

                if (clockSynchronizer.currentQuality == SyncQuality.GOOD) {
                    if (_state.value is SendspinState.Buffering) {
                        _state.update { SendspinState.Synchronized }
                        stateReporter?.reportNow(PlayerStateValue.SYNCHRONIZED)
                    }
                }
            }
        }

        // --- Server commands ---
        launch {
            dispatcher.serverCommandEvent.collect { command ->
                handleServerCommand(command)
            }
        }

        // --- Audio pipeline errors ---
        launch {
            audioPipeline.streamError.collect { error ->
                val serverInfo = messageDispatcher?.serverInfo?.value
                val nextState = if (serverInfo != null) {
                    SendspinState.Ready(serverInfo.serverId, serverInfo.name)
                } else {
                    SendspinState.Idle
                }
                Log.w(TAG, "Pipeline error: ${error.message}, transitioning to $nextState")
                _state.update { nextState }
                stateReporter?.stop()
                _playbackStoppedDueToError.update { error }
                delay(100)
                _playbackStoppedDueToError.update { null }
            }
        }
    }

    private suspend fun handleServerCommand(command: ServerCommandMessage) {
        val playerCmd = command.payload.player
        Log.i(TAG, "Handling server command: ${playerCmd.command}")

        when (playerCmd.command) {
            "volume" -> {
                playerCmd.volume?.let { volume ->
                    Log.i(TAG, "Setting volume to $volume")
                    currentVolume = volume
                    streamAudioPlayer.setVolume(volume)
                    stateReporter?.reportNow(PlayerStateValue.SYNCHRONIZED)
                }
            }

            "mute" -> {
                playerCmd.mute?.let { muted ->
                    Log.i(TAG, "Setting mute to $muted")
                    currentMuted = muted
                    streamAudioPlayer.setMuted(muted)
                    stateReporter?.reportNow(PlayerStateValue.SYNCHRONIZED)
                }
            }

            else -> {
                Log.w(TAG, "Unknown server command: ${playerCmd.command}")
            }
        }
    }

    suspend fun sendCommand(command: String, value: CommandValue?) {
        messageDispatcher?.sendCommand(command, value)
    }

    suspend fun stop() {
        val current = _state.value
        stateReporter?.stop()

        // Send goodbye if connected
        if (current is SendspinState.Ready ||
            current is SendspinState.Buffering ||
            current is SendspinState.Synchronized
        ) {
            try {
                messageDispatcher?.sendGoodbye("shutdown")
                delay(100)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending goodbye", e)
            }
        }

        disconnectFromServer()
        _state.update { SendspinState.Idle }
    }

    private suspend fun disconnectFromServer() {
        if (ownsAudioPipeline) {
            audioPipeline.stopStream()
        }
        stateReporter?.close()
        stateReporter = null
        messageDispatcher?.stop()
        messageDispatcher?.close()
        messageDispatcher = null

        transport?.disconnect()
        transport?.close()
        transport = null

        if (ownsAudioPipeline) {
            clockSynchronizer.reset()
        }
    }

    fun close() {
        Log.i(TAG, "Closing Sendspin client")
        if (ownsAudioPipeline) {
            audioPipeline.close()
        }
        supervisorJob.cancel()
    }

    fun destroy() {
        close()
    }
}

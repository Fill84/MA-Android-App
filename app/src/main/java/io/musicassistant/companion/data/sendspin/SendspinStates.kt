package io.musicassistant.companion.data.sendspin

/**
 * Unified Sendspin state machine.
 *
 * Replaces the former simple enum with a single sealed hierarchy
 * so there is exactly one source of truth.
 */
sealed class SendspinState {
    /** Client created but not yet started. */
    object Idle : SendspinState()

    /** Transport is connecting (initial or after explicit start). */
    object Connecting : SendspinState()

    /** Transport connected; waiting for auth_ok (proxy mode). */
    object Authenticating : SendspinState()

    /** auth_ok received (or direct mode); waiting for server/hello. */
    object Handshaking : SendspinState()

    /** server/hello received — protocol ready, no active stream. */
    data class Ready(val serverId: String, val serverName: String) : SendspinState()

    /** stream/start received — pipeline running, pre-buffering. */
    object Buffering : SendspinState()

    /** Clock sync quality is GOOD — audio is playing in sync. */
    object Synchronized : SendspinState()

    /** Transport reconnecting after a drop. */
    data class Reconnecting(val wasStreaming: Boolean, val attempt: Int) : SendspinState()

    /** Unrecoverable or categorised error. */
    data class Error(val error: SendspinError) : SendspinState()
}

sealed class WebSocketState {
    object Disconnected : WebSocketState()
    object Connecting : WebSocketState()
    data class Reconnecting(val attempt: Int) : WebSocketState()
    object Connected : WebSocketState()
    data class Error(val error: Throwable) : WebSocketState()
}

data class BufferState(
    val bufferedDuration: Long, // microseconds
    val isUnderrun: Boolean,
    val droppedChunks: Int,
    val targetBufferDuration: Long = 0L,
    val currentPrebufferThreshold: Long = 0L,
    val smoothedRTT: Double = 0.0,
    val jitter: Double = 0.0,
    val dropRate: Double = 0.0
)

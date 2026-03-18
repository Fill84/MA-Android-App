package io.musicassistant.companion.data.sendspin.transport

import io.musicassistant.companion.data.sendspin.WebSocketState
import kotlinx.coroutines.flow.Flow

/**
 * Transport abstraction for Sendspin protocol.
 * Allows SendspinClient to work over WebSocket or other transports.
 */
interface SendspinTransport {
    /** Connection state flow. */
    val connectionState: Flow<WebSocketState>

    /** Text message flow (JSON protocol messages). */
    val textMessages: Flow<String>

    /** Binary message flow (audio chunks). */
    val binaryMessages: Flow<ByteArray>

    /** Connect to the transport. */
    suspend fun connect()

    /** Send a text message (JSON). */
    suspend fun sendText(message: String)

    /** Send a binary message. */
    suspend fun sendBinary(data: ByteArray)

    /** Disconnect from the transport. */
    suspend fun disconnect()

    /** Close and cleanup resources. */
    fun close()
}

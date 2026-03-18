package io.musicassistant.companion.data.sendspin.audio

import io.musicassistant.companion.data.sendspin.BufferState
import io.musicassistant.companion.data.sendspin.model.StreamStartPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for the audio playback pipeline.
 * Decouples SendspinClient from concrete AudioStreamManager implementation,
 * enabling testing and future alternative audio backends.
 */
interface AudioPipeline {
    suspend fun startStream(config: StreamStartPlayer)
    suspend fun stopStream()
    suspend fun clearStream()
    suspend fun processBinaryMessage(data: ByteArray)
    fun close()

    val bufferState: StateFlow<BufferState>
    val playbackPosition: StateFlow<Long>
    val streamError: Flow<Throwable>
}

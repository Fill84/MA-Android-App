package io.musicassistant.companion.data.sendspin.audio

import android.util.Log
import io.musicassistant.companion.data.sendspin.BufferState
import io.musicassistant.companion.data.sendspin.ClockSynchronizer
import io.musicassistant.companion.data.sendspin.MediaPlayerListener
import io.musicassistant.companion.data.sendspin.model.AudioCodec
import io.musicassistant.companion.data.sendspin.model.AudioFormatSpec
import io.musicassistant.companion.data.sendspin.model.BinaryMessage
import io.musicassistant.companion.data.sendspin.model.BinaryMessageType
import io.musicassistant.companion.data.sendspin.model.StreamStartPlayer
import io.musicassistant.companion.media.StreamAudioPlayer
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Manages the complete audio playback pipeline for Sendspin streaming.
 *
 * ## Architecture: Producer-Consumer with Reorder Buffer
 *
 * Audio chunks arrive via WebSocket with server-assigned timestamps.
 * Out-of-order delivery would corrupt stateful codecs like Opus.
 * A sorted reorder buffer absorbs OOO packets before decoding.
 *
 * **Producer** (caller's coroutine via [processBinaryMessage]):
 * - Parses binary message header
 * - Sorted-inserts raw encoded frame into shared queue by server timestamp
 *
 * **Consumer** (dedicated high-priority [audioDispatcher] thread):
 * - Takes oldest frame once queue depth exceeds [reorderDepth]
 * - Decodes (Opus/FLAC → PCM) under [decoderLock]
 * - Writes PCM to [StreamAudioPlayer] — AudioTrack.write() blocks until
 *   the hardware ring buffer accepts data, which IS the playback clock
 *
 * No wall-clock scheduling, no adaptive thresholds, no prebuffer wait.
 * The blocking write is the only pacing mechanism needed.
 */
class AudioStreamManager(
    private val clockSynchronizer: ClockSynchronizer,
    private val streamAudioPlayer: StreamAudioPlayer
) : AudioPipeline, CoroutineScope {

    companion object {
        private const val TAG = "AudioStreamManager"
    }

    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + supervisorJob

    // Serializes startStream/stopStream to prevent race where stopStream nulls
    // audioTrack after startStream has already decided to reuse it.
    private val streamLifecycleLock = Mutex()

    // Lock protecting audioDecoder lifecycle (startStream/stopStream/processBinaryMessage/close)
    private val decoderLock = Mutex()
    private var audioDecoder: AudioDecoder? = null

    private var playbackJob: Job? = null

    private val _bufferState = MutableStateFlow(BufferState(0L, false, 0))
    override val bufferState: StateFlow<BufferState> = _bufferState.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    override val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    // Error events — SharedFlow(replay=0) so new subscribers never see stale errors
    private val _streamError = MutableSharedFlow<Throwable>(replay = 0, extraBufferCapacity = 1)
    override val streamError: Flow<Throwable> = _streamError.asSharedFlow()

    private var streamConfig: StreamStartPlayer? = null
    private var isStreaming = false

    // Shared sorted queue between producer (processBinaryMessage) and consumer (playback thread)
    private class RawFrame(val timestamp: Long, val data: ByteArray)

    private val queue = ArrayList<RawFrame>(64)
    private val queueLock = Mutex()
    private val reorderDepth = 32  // minimum queue depth before consumer starts draining

    // Network disconnection tracking for starvation handling
    private var isNetworkDisconnected = false

    // Tracks current AudioTrack format to enable reuse across reconnections
    private data class SinkConfig(
        val outputCodec: AudioCodec,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int
    )

    private var currentSinkConfig: SinkConfig? = null

    /**
     * Signal that the network transport has dropped.
     * Cleared automatically when startStream() is called on reconnect.
     */
    fun onNetworkDisconnected() {
        Log.i(TAG, "Network disconnected")
        isNetworkDisconnected = true
    }

    override suspend fun startStream(config: StreamStartPlayer) = streamLifecycleLock.withLock {
        Log.i(TAG, "Starting stream: ${config.codec}, ${config.sampleRate}Hz, ${config.channels}ch, ${config.bitDepth}bit")

        isNetworkDisconnected = false
        streamConfig = config
        isStreaming = true

        // Create and configure decoder atomically under lock
        val outputCodec = decoderLock.withLock {
            audioDecoder?.release()
            audioDecoder = null

            val newDecoder = createDecoder(config)
            val formatSpec = AudioFormatSpec(
                codec = AudioCodec.valueOf(config.codec.uppercase()),
                channels = config.channels,
                sampleRate = config.sampleRate,
                bitDepth = config.bitDepth
            )
            newDecoder.configure(formatSpec, config.codecHeader)
            audioDecoder = newDecoder
            newDecoder.getOutputCodec()
        }

        // Reuse existing AudioTrack if format unchanged (avoids click on track transitions)
        val newSinkConfig =
            SinkConfig(outputCodec, config.sampleRate, config.channels, config.bitDepth)
        if (newSinkConfig == currentSinkConfig) {
            Log.i(TAG, "Reusing existing AudioTrack (same format: $newSinkConfig)")
            streamAudioPlayer.flush()
            streamAudioPlayer.resumeSink()
        } else {
            Log.i(TAG, "Creating new AudioTrack: $newSinkConfig")
            streamAudioPlayer.prepareStream(
                codec = outputCodec,
                sampleRate = config.sampleRate,
                channels = config.channels,
                bitDepth = config.bitDepth,
                codecHeader = config.codecHeader,
                listener = object : MediaPlayerListener {
                    override fun onReady() {
                        Log.i(TAG, "StreamAudioPlayer ready for stream ($outputCodec)")
                    }

                    override fun onAudioCompleted() {
                        Log.i(TAG, "Audio completed")
                    }

                    override fun onError(error: Throwable?) {
                        Log.e(TAG, "StreamAudioPlayer error - stopping stream", error as? Exception)
                        launch {
                            _streamError.emit(error ?: Exception("Unknown StreamAudioPlayer error"))
                            stopStream()
                        }
                    }
                }
            )
            currentSinkConfig = newSinkConfig
        }

        queueLock.withLock { queue.clear() }
        startPlaybackThread()
    }

    private fun createDecoder(config: StreamStartPlayer): AudioDecoder {
        val codec = codecByName(config.codec.uppercase())
        Log.i(TAG, "Creating decoder for codec: $codec")
        return codec?.decoderInitializer?.invoke() ?: PcmDecoder()
    }

    /**
     * Producer: parse binary message, sorted-insert raw frame into shared queue.
     */
    override suspend fun processBinaryMessage(data: ByteArray) {
        if (!isStreaming) {
            return
        }

        val binaryMessage = BinaryMessage.decode(data) ?: run {
            Log.w(TAG, "Failed to decode binary message")
            return
        }

        if (binaryMessage.type != BinaryMessageType.AUDIO_CHUNK) {
            Log.d(TAG, "Ignoring non-audio binary message: ${binaryMessage.type}")
            return
        }

        val ts = binaryMessage.timestamp

        // Sorted insert into reorder queue
        val frame = RawFrame(ts, binaryMessage.data)
        queueLock.withLock {
            val pos = queue.binarySearchBy(frame.timestamp) { it.timestamp }
            queue.add(if (pos < 0) -(pos + 1) else pos, frame)
        }
    }

    /**
     * Consumer: decode oldest frame from sorted queue and write PCM to AudioTrack.
     * Runs on high-priority [audioDispatcher]. Paced by blocking AudioTrack.write().
     */
    private fun startPlaybackThread() {
        playbackJob?.cancel()
        playbackJob = CoroutineScope(audioDispatcher + SupervisorJob()).launch {
            Log.i(TAG, "Playback consumer started (reorderDepth=$reorderDepth)")

            try {
                while (isActive && isStreaming) {
                    val frame = queueLock.withLock {
                        if (queue.size > reorderDepth) queue.removeAt(0) else null
                    }

                    if (frame != null) {
                        val pcmData = decoderLock.withLock {
                            audioDecoder?.decode(frame.data)
                        }
                        if (pcmData != null && pcmData.isNotEmpty()) {
                            streamAudioPlayer.writeRawPcm(pcmData)
                        }
                    } else {
                        delay(2)
                    }
                }
            } catch (_: CancellationException) {
                // Normal shutdown
            } catch (e: Exception) {
                Log.e(TAG, "Consumer error: ${e.message}", e)
            }
            Log.i(TAG, "Playback consumer stopped")
        }
    }

    override suspend fun clearStream() {
        Log.i(TAG, "Clearing stream")
        queueLock.withLock { queue.clear() }
        _playbackPosition.update { 0L }
    }

    override suspend fun stopStream() = streamLifecycleLock.withLock {
        Log.i(TAG, "Stopping stream")
        isStreaming = false
        isNetworkDisconnected = false
        playbackJob?.cancel()
        playbackJob = null

        queueLock.withLock { queue.clear() }
        decoderLock.withLock { audioDecoder?.reset() }
        streamAudioPlayer.stopRawPcmStream()
        currentSinkConfig = null
        _playbackPosition.update { 0L }
        _bufferState.update { BufferState(0L, false, 0) }
    }

    override fun close() {
        Log.i(TAG, "Closing AudioStreamManager")
        playbackJob?.cancel()
        runBlocking {
            decoderLock.withLock {
                audioDecoder?.release()
                audioDecoder = null
            }
        }
        supervisorJob.cancel()
    }
}

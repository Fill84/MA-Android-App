package io.musicassistant.companion.media

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A pipe that accepts audio data written from WebSocket binary frames and exposes an InputStream
 * for ExoPlayer to read. For PCM codec, a WAV header is prepended so ExoPlayer's WavExtractor can
 * handle the stream. For other codecs (FLAC, etc.), data is passed through as-is.
 */
class AudioStreamPipe(
        codec: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray? = null,
        bufferSize: Int = 64 * 1024 // 64KB pipe buffer
) {
    private val pipeOut = PipedOutputStream()
    val inputStream = PipedInputStream(pipeOut, bufferSize)

    @Volatile
    var isClosed = false
        private set

    init {
        when {
            codecHeader != null -> {
                // Write the codec header (e.g. FLAC streaminfo) before audio data
                pipeOut.write(codecHeader)
                pipeOut.flush()
            }
            codec == "pcm" -> {
                pipeOut.write(buildWavHeader(sampleRate, channels, bitDepth))
                pipeOut.flush()
            }
        }
    }

    /** Write a chunk of audio data from a WebSocket binary frame. */
    fun writeAudioData(data: ByteArray, offset: Int, length: Int) {
        if (isClosed) return
        try {
            pipeOut.write(data, offset, length)
            pipeOut.flush()
        } catch (_: IOException) {
            isClosed = true
        }
    }

    /** Close the pipe, signaling end of stream to ExoPlayer. */
    fun close() {
        isClosed = true
        try {
            pipeOut.close()
        } catch (_: IOException) {}
    }

    private fun buildWavHeader(sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val blockAlign = channels * (bitDepth / 8)
        return ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt(0x7FFFFFFF) // Unknown file size (streaming)
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16) // fmt chunk size
                    putShort(1) // PCM audio format
                    putShort(channels.toShort())
                    putInt(sampleRate)
                    putInt(byteRate)
                    putShort(blockAlign.toShort())
                    putShort(bitDepth.toShort())
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(0x7FFFFFFF) // Unknown data size (streaming)
                }
                .array()
    }
}

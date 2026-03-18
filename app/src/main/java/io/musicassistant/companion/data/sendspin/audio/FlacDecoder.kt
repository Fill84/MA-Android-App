package io.musicassistant.companion.data.sendspin.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.musicassistant.companion.data.sendspin.model.AudioCodec
import io.musicassistant.companion.data.sendspin.model.AudioFormatSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Android implementation of FLAC audio decoder using MediaCodec.
 *
 * Uses the platform's native FLAC decoder (available on API 26+) to decode
 * FLAC-encoded audio chunks into raw PCM data for AudioTrack playback.
 *
 * Note: MediaCodec FLAC decoder always outputs 16-bit PCM samples. Conversion
 * to 24/32-bit formats is performed but doesn't increase audio quality.
 */
@OptIn(ExperimentalEncodingApi::class)
class FlacDecoder : AudioDecoder {
    companion object {
        private const val TAG = "FlacDecoder"
    }

    private val decoderLock = Any()
    private var codec: MediaCodec? = null
    private var channels: Int = 0
    private var sampleRate: Int = 0
    private var bitDepth: Int = 0
    private val TIMEOUT_US = 10_000L
    private val MAX_INPUT_RETRIES = 3

    override fun configure(config: AudioFormatSpec, codecHeader: String?) {
        synchronized(decoderLock) {
            Log.i(TAG, "Configuring FLAC decoder: ${config.sampleRate}Hz, ${config.channels}ch, ${config.bitDepth}bit")

            require(config.channels in 1..8) { "FLAC supports 1-8 channels, got ${config.channels}" }
            require(config.sampleRate in 1..655350) { "Invalid sample rate: ${config.sampleRate}" }

            codec?.let { existing ->
                try {
                    existing.stop()
                    existing.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing existing codec during reconfigure", e)
                }
                codec = null
            }

            sampleRate = config.sampleRate
            channels = config.channels
            bitDepth = config.bitDepth

            try {
                val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)

                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_FLAC,
                    sampleRate,
                    channels
                ).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)

                    if (!codecHeader.isNullOrEmpty()) {
                        try {
                            val headerBytes = Base64.decode(codecHeader)
                            val csd = ByteBuffer.wrap(headerBytes)
                            setByteBuffer("csd-0", csd)
                            Log.i(TAG, "Set FLAC STREAMINFO from codec_header (${headerBytes.size} bytes)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to base64-decode codec header, continuing without it")
                        }
                    } else {
                        Log.w(TAG, "No codec_header provided for FLAC - decoder may fail")
                    }
                }

                newCodec.configure(format, null, null, 0)
                newCodec.start()
                codec = newCodec

                Log.i(TAG, "FLAC decoder initialized successfully")
            } catch (e: IOException) {
                throw IllegalStateException("FLAC decoder not available on this device", e)
            } catch (e: Exception) {
                throw IllegalStateException("FLAC decoder initialization failed", e)
            }
        }
    }

    override fun decode(encodedData: ByteArray): ByteArray {
        return synchronized(decoderLock) {
            val currentCodec = codec ?: return@synchronized ByteArray(0)

            if (encodedData.isEmpty()) return@synchronized ByteArray(0)

            try {
                val outputStream = ByteArrayOutputStream()

                var submitted = false
                for (attempt in 0..MAX_INPUT_RETRIES) {
                    val inputIndex = currentCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = currentCodec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Input buffer is null")
                        inputBuffer.clear()
                        inputBuffer.put(encodedData)
                        currentCodec.queueInputBuffer(inputIndex, 0, encodedData.size, 0, 0)
                        submitted = true
                        break
                    }
                    if (attempt < MAX_INPUT_RETRIES) {
                        drainOutput(currentCodec, outputStream)
                    }
                }

                if (!submitted) {
                    Log.e(TAG, "Failed to submit input after ${MAX_INPUT_RETRIES + 1} attempts")
                }

                drainOutput(currentCodec, outputStream)

                val pcm16bit = outputStream.toByteArray()
                if (pcm16bit.isNotEmpty()) convertPcmBitDepth(pcm16bit) else pcm16bit
            } catch (e: Exception) {
                Log.e(TAG, "Error during decode", e)
                ByteArray(0)
            }
        }
    }

    private fun drainOutput(codec: MediaCodec, outputStream: ByteArrayOutputStream) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    val outBuffer = codec.getOutputBuffer(outputIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val pcmData = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.get(pcmData, 0, bufferInfo.size)
                        outputStream.write(pcmData)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format changed: ${codec.outputFormat}")
                }
                @Suppress("DEPRECATION")
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* continue */ }
                else -> break
            }
        }
    }

    private fun convertPcmBitDepth(pcm16bit: ByteArray): ByteArray {
        val sampleCount = pcm16bit.size / 2
        return when (bitDepth) {
            16 -> pcm16bit
            24 -> {
                val buffer = ByteBuffer.allocate(sampleCount * 3)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = ByteBuffer.wrap(pcm16bit).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                repeat(sampleCount) { i ->
                    val sample24 = shortBuffer.get(i).toInt() shl 8
                    buffer.put((sample24 and 0xFF).toByte())
                    buffer.put(((sample24 shr 8) and 0xFF).toByte())
                    buffer.put(((sample24 shr 16) and 0xFF).toByte())
                }
                buffer.array()
            }
            32 -> {
                val buffer = ByteBuffer.allocate(sampleCount * 4)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = ByteBuffer.wrap(pcm16bit).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                repeat(sampleCount) { i ->
                    val sample32 = shortBuffer.get(i).toInt() shl 16
                    buffer.putInt(sample32)
                }
                buffer.array()
            }
            else -> pcm16bit
        }
    }

    override fun reset() {
        synchronized(decoderLock) {
            val currentCodec = codec ?: return
            try {
                currentCodec.flush()
            } catch (e: IllegalStateException) {
                try {
                    currentCodec.stop()
                    currentCodec.start()
                } catch (e2: Exception) {
                    try { currentCodec.release() } catch (_: Exception) {}
                    codec = null
                }
            }
        }
    }

    override fun release() {
        synchronized(decoderLock) {
            try {
                codec?.let { c ->
                    c.stop()
                    c.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing codec", e)
            } finally {
                codec = null
            }
        }
    }

    override fun getOutputCodec(): AudioCodec = AudioCodec.PCM
}

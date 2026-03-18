package io.musicassistant.companion.data.sendspin.audio

import android.util.Log
import io.musicassistant.companion.data.sendspin.model.AudioCodec
import io.musicassistant.companion.data.sendspin.model.AudioFormatSpec
import io.github.jaredmdobson.concentus.OpusDecoder as ConcentusOpusDecoder
import io.github.jaredmdobson.concentus.OpusException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android implementation of Opus audio decoder using the Concentus library.
 * Concentus is a pure Java/Kotlin port of libopus — no JNI required.
 *
 * Note: Opus always decodes to 16-bit PCM samples. Conversion to 24/32-bit
 * formats is performed but doesn't increase audio quality.
 */
class OpusDecoder : AudioDecoder {
    companion object {
        private const val TAG = "OpusDecoder"
    }

    private var decoder: ConcentusOpusDecoder? = null
    private var channels: Int = 0
    private var sampleRate: Int = 0
    private var bitDepth: Int = 0
    private var pcmBuffer: ShortArray? = null
    private val decoderLock = Any()

    override fun configure(config: AudioFormatSpec, codecHeader: String?) {
        Log.i(TAG, "Configuring Opus decoder: ${config.sampleRate}Hz, ${config.channels}ch, ${config.bitDepth}bit")

        require(config.channels in 1..2) { "Opus only supports 1 or 2 channels, got ${config.channels}" }

        val validSampleRates = setOf(8000, 12000, 16000, 24000, 48000)
        require(config.sampleRate in validSampleRates) { "Opus supports sample rates: $validSampleRates, got ${config.sampleRate}" }

        sampleRate = config.sampleRate
        channels = config.channels
        bitDepth = config.bitDepth

        try {
            decoder = ConcentusOpusDecoder(sampleRate, channels)
            val maxFrameSize = 5760
            pcmBuffer = ShortArray(maxFrameSize * channels)
            Log.i(TAG, "Opus decoder initialized successfully")
        } catch (e: OpusException) {
            throw IllegalStateException("Opus decoder initialization failed", e)
        }

        if (codecHeader != null) {
            Log.d(TAG, "Codec header provided (length=${codecHeader.length}), currently ignored")
        }
    }

    override fun decode(encodedData: ByteArray): ByteArray {
        return synchronized(decoderLock) {
            val currentDecoder = decoder
                ?: throw IllegalStateException("Decoder not configured. Call configure() first.")
            val currentPcmBuffer = pcmBuffer
                ?: throw IllegalStateException("PCM buffer not allocated")

            if (encodedData.isEmpty()) return@synchronized ByteArray(0)

            try {
                val samplesDecoded = currentDecoder.decode(
                    encodedData, 0, encodedData.size,
                    currentPcmBuffer, 0, currentPcmBuffer.size / channels,
                    false
                )

                if (samplesDecoded <= 0) return@synchronized ByteArray(0)

                val totalSamples = samplesDecoded * channels
                convertShortArrayToByteArray(currentPcmBuffer, totalSamples)
            } catch (e: OpusException) {
                Log.e(TAG, "Opus decoding error", e)
                ByteArray(0)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during decode", e)
                ByteArray(0)
            }
        }
    }

    private fun convertShortArrayToByteArray(samples: ShortArray, sampleCount: Int): ByteArray {
        return when (bitDepth) {
            16 -> {
                val buffer = ByteBuffer.allocate(sampleCount * 2)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    buffer.putShort(samples[i])
                }
                buffer.array()
            }
            24 -> {
                val buffer = ByteBuffer.allocate(sampleCount * 3)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    val sample24 = samples[i].toInt() shl 8
                    buffer.put((sample24 and 0xFF).toByte())
                    buffer.put(((sample24 shr 8) and 0xFF).toByte())
                    buffer.put(((sample24 shr 16) and 0xFF).toByte())
                }
                buffer.array()
            }
            32 -> {
                val buffer = ByteBuffer.allocate(sampleCount * 4)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    val sample32 = samples[i].toInt() shl 16
                    buffer.putInt(sample32)
                }
                buffer.array()
            }
            else -> {
                val buffer = ByteBuffer.allocate(sampleCount * 2)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    buffer.putShort(samples[i])
                }
                buffer.array()
            }
        }
    }

    override fun reset() {
        synchronized(decoderLock) {
            try {
                decoder?.resetState()
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting decoder", e)
            }
        }
    }

    override fun release() {
        synchronized(decoderLock) {
            decoder = null
            pcmBuffer = null
        }
    }

    override fun getOutputCodec(): AudioCodec = AudioCodec.PCM
}

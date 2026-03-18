package io.musicassistant.companion.data.sendspin.audio

import io.musicassistant.companion.data.sendspin.model.AudioCodec
import io.musicassistant.companion.data.sendspin.model.AudioFormatSpec

interface AudioDecoder {
    fun configure(config: AudioFormatSpec, codecHeader: String?)
    fun decode(encodedData: ByteArray): ByteArray
    fun reset()
    fun release()

    /**
     * Returns the audio codec format that this decoder outputs.
     * - PCM: Decoder converts encoded data to raw PCM
     * - OPUS/FLAC/etc: Decoder passes through encoded data
     */
    fun getOutputCodec(): AudioCodec
}

class PcmDecoder : AudioDecoder {
    override fun configure(config: AudioFormatSpec, codecHeader: String?) {
        // PCM needs no configuration
    }

    override fun decode(encodedData: ByteArray): ByteArray {
        // PCM is already decoded, just pass through
        return encodedData
    }

    override fun reset() {}
    override fun release() {}
    override fun getOutputCodec(): AudioCodec = AudioCodec.PCM
}

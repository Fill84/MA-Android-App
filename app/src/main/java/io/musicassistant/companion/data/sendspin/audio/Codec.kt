package io.musicassistant.companion.data.sendspin.audio

import io.musicassistant.companion.data.sendspin.model.AudioCodec

enum class Codec(
    val decoderInitializer: () -> AudioDecoder,
    val sendspinAudioCodec: AudioCodec
) {
    PCM({ PcmDecoder() }, AudioCodec.PCM),
    FLAC({ FlacDecoder() }, AudioCodec.FLAC),
    OPUS({ OpusDecoder() }, AudioCodec.OPUS);

    fun uiTitle() = when (this) {
        OPUS -> "Opus (compressed, lowest bandwidth)"
        FLAC -> "FLAC (lossless, medium bandwidth)"
        PCM -> "PCM (lossless, high bandwidth)"
    }
}

object Codecs {
    val default = Codec.OPUS
    val list: List<Codec> = listOf(Codec.OPUS, Codec.FLAC, Codec.PCM)
}

fun codecByName(name: String): Codec? =
    try {
        Codec.valueOf(name)
    } catch (_: Exception) {
        null
    }

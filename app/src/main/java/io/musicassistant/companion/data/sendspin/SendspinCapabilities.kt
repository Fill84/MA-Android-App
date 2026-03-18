package io.musicassistant.companion.data.sendspin

import io.musicassistant.companion.data.sendspin.audio.Codec
import io.musicassistant.companion.data.sendspin.model.AudioFormatSpec
import io.musicassistant.companion.data.sendspin.model.ClientHelloPayload
import io.musicassistant.companion.data.sendspin.model.DeviceInfo
import io.musicassistant.companion.data.sendspin.model.MetadataSupport
import io.musicassistant.companion.data.sendspin.model.PlayerSupport
import io.musicassistant.companion.data.sendspin.model.VersionedRole

object SendspinCapabilities {
    fun buildClientHello(config: SendspinConfig, codecPreference: Codec): ClientHelloPayload {
        return ClientHelloPayload(
            clientId = config.clientId,
            name = config.deviceName,
            deviceInfo = DeviceInfo.current,
            version = 1,
            supportedRoles = listOf(
                VersionedRole.PLAYER_V1,
                VersionedRole.CONTROLLER_V1,
                VersionedRole.METADATA_V1
            ),
            playerV1Support = PlayerSupport(
                supportedFormats = buildSupportedFormats(codecPreference),
                bufferCapacity = config.bufferCapacityMicros,
                supportedCommands = listOf()
            ),
            metadataV1Support = MetadataSupport(
                supportedPictureFormats = emptyList()
            ),
            artworkV1Support = null,
            visualizerV1Support = null
        )
    }

    private fun buildSupportedFormats(codecPreference: Codec): List<AudioFormatSpec> {
        val sampleRates = listOf(44100, 48000)
        val bitDepths = listOf(16, 24, 32)

        return buildList {
            for (sampleRate in sampleRates) {
                for (bitDepth in bitDepths) {
                    add(
                        AudioFormatSpec(
                            codec = codecPreference.sendspinAudioCodec,
                            channels = 2,
                            sampleRate = sampleRate,
                            bitDepth = bitDepth
                        )
                    )
                }
            }
        }
    }
}

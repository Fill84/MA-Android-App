package io.musicassistant.companion.data.sendspin

import io.musicassistant.companion.data.sendspin.model.AudioCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class SendspinCapabilitiesTest {

    /**
     * The client advertises *every* codec as a capability (the format is a server-side setting now,
     * not a local preference). The server then offers one option per advertised format and decides.
     */
    @Test
    fun `client hello advertises every codec`() {
        val config = SendspinConfig(clientId = "c", deviceName = "Pixel")

        val formats = SendspinCapabilities.buildClientHello(config).playerV1Support!!.supportedFormats

        assertEquals(
            setOf(AudioCodec.PCM, AudioCodec.FLAC, AudioCodec.OPUS),
            formats.map { it.codec }.toSet(),
        )
        // 3 codecs × 2 sample rates (44100/48000) × 3 bit depths (16/24/32)
        assertEquals(18, formats.size)
    }
}

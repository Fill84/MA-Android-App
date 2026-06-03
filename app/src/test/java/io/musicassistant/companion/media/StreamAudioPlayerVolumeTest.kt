package io.musicassistant.companion.media

import io.musicassistant.companion.media.StreamAudioPlayer.Companion.volumePercentToStreamIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for the MA-volume → system-stream-index mapping (issue 3 fix). The mapping is the
 * single point where the player's volume is translated to the system volume, so it must be exact.
 */
class StreamAudioPlayerVolumeTest {

    @Test
    fun `0 percent maps to 0`() {
        assertEquals(0, volumePercentToStreamIndex(0, 15))
    }

    @Test
    fun `100 percent maps to max index`() {
        assertEquals(15, volumePercentToStreamIndex(100, 15))
    }

    @Test
    fun `50 percent of 15 rounds up to 8`() {
        assertEquals(8, volumePercentToStreamIndex(50, 15))
    }

    @Test
    fun `50 percent of 14 is 7`() {
        assertEquals(7, volumePercentToStreamIndex(50, 14))
    }

    @Test
    fun `33 percent of 30 rounds to 10`() {
        assertEquals(10, volumePercentToStreamIndex(33, 30))
    }

    @Test
    fun `over 100 percent clamps to max`() {
        assertEquals(15, volumePercentToStreamIndex(150, 15))
    }

    @Test
    fun `negative percent clamps to 0`() {
        assertEquals(0, volumePercentToStreamIndex(-10, 15))
    }

    @Test
    fun `zero max index returns 0`() {
        assertEquals(0, volumePercentToStreamIndex(50, 0))
    }
}

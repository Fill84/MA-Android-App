package io.musicassistant.companion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMetadataTest {

    @Test
    fun `trackId lowercases and trims artist and title`() {
        val m = TrackMetadata(title = "  Hello WORLD ", artist = " The Artist ", album = null, artworkUrl = null, artworkBytes = null)
        assertEquals("the artist::hello world", m.trackId)
    }

    @Test
    fun `empty artist and title produce stable trackId`() {
        val m = TrackMetadata.EMPTY
        assertEquals("::", m.trackId)
    }

    @Test
    fun `equals compares bytes by content, not reference`() {
        val a = TrackMetadata("t", "a", null, null, byteArrayOf(1, 2, 3))
        val b = TrackMetadata("t", "a", null, null, byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different bytes are not equal`() {
        val a = TrackMetadata("t", "a", null, null, byteArrayOf(1, 2, 3))
        val b = TrackMetadata("t", "a", null, null, byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `null bytes equal null bytes`() {
        val a = TrackMetadata("t", "a", null, null, null)
        val b = TrackMetadata("t", "a", null, null, null)
        assertEquals(a, b)
    }

    @Test
    fun `hasArtwork true only when bytes non-null and non-empty`() {
        assertTrue(TrackMetadata("t", "a", null, null, byteArrayOf(1)).hasArtwork)
        assertEquals(false, TrackMetadata("t", "a", null, null, null).hasArtwork)
        assertEquals(false, TrackMetadata("t", "a", null, null, ByteArray(0)).hasArtwork)
    }
}

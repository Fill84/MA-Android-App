package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.ItemMapping
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.model.QueueItem
import io.musicassistant.companion.data.model.QueueMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingDerivationTest {

    private fun trackItem(
        name: String = "Song",
        duration: Int = 200,
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
        mediaType: MediaType = MediaType.TRACK,
    ) = QueueItem(
        queueItemId = "qi1",
        name = name,
        duration = duration,
        mediaItem = QueueMediaItem(
            name = name,
            mediaType = mediaType,
            artists = artists.map { ItemMapping(name = it) },
            album = album?.let { ItemMapping(name = it) },
        ),
    )

    private fun queue(
        item: QueueItem?,
        state: PlayerState = PlayerState.PLAYING,
        index: Int? = 3,
        elapsed: Double = 12.0,
    ) = PlayerQueue(
        queueId = "q",
        currentItem = item,
        currentIndex = index,
        state = state,
        elapsedTime = elapsed,
    )

    @Test
    fun `track derivation maps title artist album and duration`() {
        val np = NowPlayingDerivation.deriveNowPlaying(player = null, queue = queue(trackItem()))
        requireNotNull(np)
        assertEquals("Song", np.title)
        assertEquals("Artist", np.artist)
        assertEquals("Album", np.album)
        assertFalse(np.isLive)
        assertEquals(200_000L, np.durationMs)
        assertEquals(12_000L, np.elapsedMs)
        assertEquals(3, np.currentIndex)
        assertEquals("qi1", np.currentQueueItemId)
    }

    @Test
    fun `radio derivation prefers currentMedia title and artist`() {
        val player = Player(
            playerId = "ma_1",
            currentMedia = io.musicassistant.companion.data.model.CurrentMedia(
                mediaType = "radio", title = "Live Song", artist = "Live Artist",
                imageUrl = "http://192.168.1.2:8095/imageproxy?x=1",
            ),
        )
        val item = trackItem(name = "Station", duration = 0, mediaType = MediaType.RADIO)
        val np = NowPlayingDerivation.deriveNowPlaying(player, queue(item, index = null))
        requireNotNull(np)
        assertEquals("Live Song", np.title)
        assertEquals("Live Artist", np.artist)
        assertTrue(np.isLive)
        assertEquals(0L, np.durationMs)
        assertEquals("http://192.168.1.2:8095/imageproxy?x=1", np.currentMediaImageUrl)
    }

    @Test
    fun `live when duration is zero even for non-radio`() {
        val np = NowPlayingDerivation.deriveNowPlaying(null, queue(trackItem(duration = 0)))
        requireNotNull(np)
        assertTrue(np.isLive)
        assertEquals(0L, np.durationMs)
    }

    @Test
    fun `null when no current item`() {
        assertNull(NowPlayingDerivation.deriveNowPlaying(null, queue(item = null)))
        assertNull(NowPlayingDerivation.deriveNowPlaying(null, null))
    }

    @Test
    fun `isPlaying from queue state, falls back to player state`() {
        assertTrue(NowPlayingDerivation.deriveIsPlaying(queue(trackItem(), state = PlayerState.PLAYING), null))
        assertFalse(NowPlayingDerivation.deriveIsPlaying(queue(trackItem(), state = PlayerState.PAUSED), null))
        assertTrue(NowPlayingDerivation.deriveIsPlaying(null, Player(state = PlayerState.PLAYING)))
        assertFalse(NowPlayingDerivation.deriveIsPlaying(null, null))
    }
}

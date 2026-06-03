package io.musicassistant.companion.media

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Device-free verification of what a MediaController / Bluetooth AVRCP actually reads from the
 * session: the real [MaPlayer] (a Media3 SimpleBasePlayer) is driven through the public Player API
 * via Robolectric. Asserts the timeline shape, per-slot metadata, artwork bytes, available commands,
 * and prev/next routing — the behavior that needs a car to test manually otherwise (issues 4 & 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MaPlayerTest {

    private val artBytes = byteArrayOf(1, 2, 3)

    private fun track(title: String, artist: String, art: ByteArray? = artBytes) =
        TrackMetadata(title, artist, null, null, art)

    private fun newActivePlayer(snap: QueueSnapshot): MaPlayer {
        val flow = MutableStateFlow(snap)
        val player = MaPlayer(Looper.getMainLooper(), flow)
        player.setStreamActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        return player
    }

    @Test
    fun `normal track exposes 3-item timeline at index 1 with per-slot metadata and artwork`() {
        val player = newActivePlayer(
            QueueSnapshot(track("Prev", "PA"), track("Cur", "CA"), track("Next", "NA"), isLive = false)
        )
        assertEquals(3, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("Prev", player.getMediaItemAt(0).mediaMetadata.title)
        assertEquals("Cur", player.getMediaItemAt(1).mediaMetadata.title)
        assertEquals("Next", player.getMediaItemAt(2).mediaMetadata.title)
        assertEquals("CA", player.getMediaItemAt(1).mediaMetadata.artist)
        // Bluetooth AVRCP needs embedded bytes on the current item.
        assertNotNull(player.getMediaItemAt(1).mediaMetadata.artworkData)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `radio exposes a single-item timeline and disables track navigation`() {
        val player = newActivePlayer(
            QueueSnapshot(null, track("RadioSong", "Station"), null, isLive = true)
        )
        assertEquals(1, player.mediaItemCount)
        assertEquals("RadioSong", player.getMediaItemAt(0).mediaMetadata.title)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `seekToNext routes to onNextRequested (not previous)`() {
        val player = newActivePlayer(QueueSnapshot(track("P", "A"), track("C", "A"), track("N", "A")))
        var next = false
        var prev = false
        player.onNextRequested = { next = true }
        player.onPreviousRequested = { prev = true }
        player.seekToNextMediaItem()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(next)
        assertFalse(prev)
    }

    @Test
    fun `seekToPrevious routes to onPreviousRequested (not a restart, not next)`() {
        val player = newActivePlayer(QueueSnapshot(track("P", "A"), track("C", "A"), track("N", "A")))
        var next = false
        var prev = false
        var seek = false
        player.onNextRequested = { next = true }
        player.onPreviousRequested = { prev = true }
        player.onSeekRequested = { seek = true }
        player.seekToPreviousMediaItem()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prev)
        assertFalse(next)
        assertFalse(seek)
    }

    @Test
    fun `radio ignores the next command`() {
        val player = newActivePlayer(QueueSnapshot(null, track("S", "St"), null, isLive = true))
        var next = false
        player.onNextRequested = { next = true }
        player.seekToNextMediaItem() // command unavailable for radio → ignored
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(next)
    }
}

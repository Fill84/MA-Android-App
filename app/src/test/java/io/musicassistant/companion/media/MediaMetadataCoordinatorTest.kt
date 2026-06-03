package io.musicassistant.companion.media

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaMetadataCoordinatorTest {

    private val fallbackBytes = byteArrayOf(0x00, 0x01, 0x02)

    private fun makeCoordinator(
        pipeline: ArtworkPipeline = mockk(relaxed = true),
        fallback: ByteArray = fallbackBytes,
        dispatcher: TestDispatcher
    ): MediaMetadataCoordinator =
        MediaMetadataCoordinator(pipeline = pipeline, fallbackBytes = fallback, scopeDispatcher = dispatcher)

    @Test
    fun `initial snapshot is EMPTY`() = runTest {
        val c = makeCoordinator(dispatcher = StandardTestDispatcher(testScheduler))
        c.snapshot.test {
            assertEquals(QueueSnapshot.EMPTY, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `pushQueueUpdate without artwork emits snapshot with fallback bytes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.fetch(null) } returns null
        coEvery { pipeline.cachedOrNull(any()) } returns null
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(
            current = TrackMetadata("Song", "Artist", null, null, null),
            prev = null,
            next = null
        )
        advanceUntilIdle()

        val snap = c.snapshot.value
        assertEquals("Song", snap.current.title)
        assertArrayEquals(fallbackBytes, snap.current.artworkBytes)
    }

    @Test
    fun `pushQueueUpdate with successful pipeline fetch uses fresh bytes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fresh = byteArrayOf(0x10, 0x20)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch("http://x/img.png") } returns fresh
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(
            current = TrackMetadata("Song", "Artist", null, "http://x/img.png", null),
            prev = null,
            next = null
        )
        advanceUntilIdle()

        assertArrayEquals(fresh, c.snapshot.value.current.artworkBytes)
    }

    @Test
    fun `same URL on second update does not refetch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bytes = byteArrayOf(0xA)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull("http://x/img.png") } returnsMany listOf(null, bytes)
        coEvery { pipeline.fetch("http://x/img.png") } returns bytes
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(TrackMetadata("A", "B", null, "http://x/img.png", null), null, null)
        advanceUntilIdle()
        c.pushQueueUpdate(TrackMetadata("A", "B", null, "http://x/img.png", null), null, null)
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { pipeline.fetch("http://x/img.png") }
    }

    @Test
    fun `sendspin update preserves artwork bytes for same trackId`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch(any()) } returns byteArrayOf(0xBE.toByte(), 0xEF.toByte())
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(TrackMetadata("Song", "Artist", null, "http://x/img.png", null), null, null)
        advanceUntilIdle()
        val bytesBefore = c.snapshot.value.current.artworkBytes

        c.pushSendspinMetadata(title = "Song", artist = "Artist", album = null)
        advanceUntilIdle()

        assertArrayEquals(bytesBefore, c.snapshot.value.current.artworkBytes)
    }

    @Test
    fun `sendspin update for new track without URL falls back`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushSendspinMetadata(title = "NewSong", artist = "NewArtist", album = null)
        advanceUntilIdle()

        assertArrayEquals(fallbackBytes, c.snapshot.value.current.artworkBytes)
        assertEquals("NewSong", c.snapshot.value.current.title)
    }

    @Test
    fun `sendspin metadata with artworkUrl fetches and emits real bytes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val real = byteArrayOf(0x11, 0x22)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch("http://radio/cover.jpg") } returns real
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        // Radio track change arrives via Sendspin with a real cover URL.
        c.pushSendspinMetadata("RadioSong", "Station", null, artworkUrl = "http://radio/cover.jpg")
        advanceUntilIdle()

        assertArrayEquals(real, c.snapshot.value.current.artworkBytes)
        assertEquals("RadioSong", c.snapshot.value.current.title)
    }

    @Test
    fun `live context makes radio without per-track art use station logo and single-item`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val station = byteArrayOf(0x55, 0x66)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch(any()) } returns null
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.setLiveContext(isLive = true, stationBytes = station)
        c.pushSendspinMetadata("So Good", "CamelPhat", null, artworkUrl = null)
        advanceUntilIdle()

        val snap = c.snapshot.value
        assertTrue(snap.isLive)
        assertNull(snap.prev)
        assertNull(snap.next)
        assertArrayEquals(station, snap.current.artworkBytes)
        assertEquals("So Good", snap.current.title)
    }

    @Test
    fun `late live context upgrades app-icon fallback to station logo in place`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val station = byteArrayOf(0x55, 0x66)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch(any()) } returns null
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        // Track arrives with no art before we know it's radio → app-icon fallback.
        c.pushSendspinMetadata("So Good", "CamelPhat", null, artworkUrl = null)
        advanceUntilIdle()
        assertArrayEquals(fallbackBytes, c.snapshot.value.current.artworkBytes)

        // Probe completes: radio + station logo → upgrade the current track in place.
        c.setLiveContext(isLive = true, stationBytes = station)
        assertArrayEquals(station, c.snapshot.value.current.artworkBytes)
        assertTrue(c.snapshot.value.isLive)
    }

    @Test
    fun `stale fetch result is discarded when newer update has arrived`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch("http://old") } coAnswers {
            kotlinx.coroutines.delay(100)
            byteArrayOf(0xAA.toByte())
        }
        coEvery { pipeline.fetch("http://new") } returns byteArrayOf(0xBB.toByte())
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(TrackMetadata("A", "X", null, "http://old", null), null, null)
        c.pushQueueUpdate(TrackMetadata("B", "Y", null, "http://new", null), null, null)
        advanceUntilIdle()

        val snap = c.snapshot.value
        assertEquals("B", snap.current.title)
        assertArrayEquals(byteArrayOf(0xBB.toByte()), snap.current.artworkBytes)
    }

    // ── Radio / live behavior (issue 4: no phantom prev/next) ──────────────

    @Test
    fun `live update forces prev and next to null and sets isLive`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch(any()) } returns null
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(
            current = TrackMetadata("Now Playing", "Radio Station", null, null, null),
            prev = TrackMetadata("Should Not Show", "X", null, null, null),
            next = TrackMetadata("Should Not Show Either", "Y", null, null, null),
            isLive = true
        )
        advanceUntilIdle()

        val snap = c.snapshot.value
        assertTrue(snap.isLive)
        assertNull(snap.prev)
        assertNull(snap.next)
        assertEquals("Now Playing", snap.current.title)
    }

    @Test
    fun `sendspin update preserves isLive flag and null neighbors`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline: ArtworkPipeline = mockk()
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch(any()) } returns null
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(
            current = TrackMetadata("Song 1", "Radio Station", null, null, null),
            prev = null, next = null, isLive = true
        )
        advanceUntilIdle()

        // Radio advances to a new song via Sendspin metadata only.
        c.pushSendspinMetadata(title = "Song 2", artist = "Radio Station", album = null)
        advanceUntilIdle()

        val snap = c.snapshot.value
        assertEquals("Song 2", snap.current.title)
        assertTrue(snap.isLive)
        assertNull(snap.prev)
        assertNull(snap.next)
    }

    @Test
    fun `non-live update keeps isLive false`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline: ArtworkPipeline = mockk(relaxed = true)
        coEvery { pipeline.cachedOrNull(any()) } returns null
        coEvery { pipeline.fetch(any()) } returns null
        val c = makeCoordinator(pipeline = pipeline, dispatcher = dispatcher)

        c.pushQueueUpdate(
            current = TrackMetadata("T", "A", null, null, null),
            prev = TrackMetadata("P", "A", null, null, null),
            next = TrackMetadata("N", "A", null, null, null)
        )
        advanceUntilIdle()

        val snap = c.snapshot.value
        assertFalse(snap.isLive)
        assertEquals("P", snap.prev?.title)
        assertEquals("N", snap.next?.title)
    }
}

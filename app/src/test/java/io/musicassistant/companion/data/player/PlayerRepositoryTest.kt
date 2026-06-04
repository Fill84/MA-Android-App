package io.musicassistant.companion.data.player

import app.cash.turbine.test
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.model.QueueItem
import io.musicassistant.companion.data.model.QueueMediaItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRepositoryTest {

    private val events = MutableSharedFlow<MaApiClient.MaEvent>(extraBufferCapacity = 64)
    private val connState = MutableStateFlow(ConnectionState.AUTHENTICATED)

    private fun queue(state: PlayerState, id: String = "ma_1") = PlayerQueue(
        queueId = id,
        state = state,
        currentItem = QueueItem(
            queueItemId = "qi1", name = "Song", duration = 100,
            mediaItem = QueueMediaItem(name = "Song", mediaType = MediaType.TRACK),
        ),
        currentIndex = 0,
    )

    private fun fakes(): Pair<MaApi, MaApiClient> {
        val api = mockk<MaApi>(relaxed = true)
        val client = mockk<MaApiClient>(relaxed = true)
        every { client.events } returns events
        every { client.connectionState } returns connState
        coEvery { api.getPlayer("ma_1") } returns Player(playerId = "ma_1", state = PlayerState.PLAYING)
        coEvery { api.getPlayerQueue("ma_1") } returns queue(PlayerState.PLAYING)
        coEvery { api.getPlayerQueueItems("ma_1", any(), any()) } returns emptyList<QueueItem>()
        return api to client
    }

    @Test
    fun `emits derived session after authenticated`() = runTest {
        val (api, client) = fakes()
        val repo = PlayerRepository(api, client, backgroundScope)

        repo.session("ma_1").test {
            val s = awaitItem()
            assertEquals("ma_1", s.playerId)
            assertTrue(s.isPlaying)
            assertEquals("Song", s.nowPlaying?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `re-emits on queue_updated with inline data`() = runTest {
        val (api, client) = fakes()
        val repo = PlayerRepository(api, client, backgroundScope)

        repo.session("ma_1").test {
            assertTrue(awaitItem().isPlaying)            // initial PLAYING
            // Server reports PAUSED via a queue_updated event (no inline data → fallback fetch).
            coEvery { api.getPlayerQueue("ma_1") } returns queue(PlayerState.PAUSED)
            events.emit(MaApiClient.MaEvent(event = "queue_updated", objectId = "ma_1", data = null))
            assertEquals(false, awaitItem().isPlaying)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `player_updated with new active_source switches queue id`() = runTest {
        val (api, client) = fakes()
        coEvery { api.getPlayer("ma_1") } returns
            Player(playerId = "ma_1", state = PlayerState.PLAYING, activeSource = "upma_1")
        coEvery { api.getPlayerQueue("upma_1") } returns queue(PlayerState.PLAYING, id = "upma_1")
        coEvery { api.getPlayerQueueItems("upma_1", any(), any()) } returns emptyList<QueueItem>()
        val repo = PlayerRepository(api, client, backgroundScope)

        repo.session("ma_1").test {
            val s = awaitItem()
            assertEquals("upma_1", s.effectiveQueueId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

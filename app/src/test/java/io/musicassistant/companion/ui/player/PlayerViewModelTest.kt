package io.musicassistant.companion.ui.player

import android.app.Application
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.player.PlayerRepository
import io.musicassistant.companion.data.player.PlayerSession
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.data.settings.SettingsRepository
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the queue-mutation glue in [PlayerViewModel]. The ViewModel pulls its
 * dependencies from [ServiceLocator] / [SettingsModule], so those singletons are stubbed with
 * MockK; the active queue is seeded through the public `selectPlayer` path (which calls the private
 * `loadQueue`). The init-time connection/event observers receive flows that never emit, so no
 * initial load runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val api: MaApi = mockk(relaxed = true)
    private val apiClient: MaApiClient = mockk(relaxed = true)
    private val application: Application = mockk(relaxed = true)
    private val repo: PlayerRepository = mockk(relaxed = true)
    private val sessionFlow = MutableSharedFlow<PlayerSession>(replay = 1)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ServiceLocator)
        mockkObject(SettingsModule)
        every { ServiceLocator.api } returns api
        every { ServiceLocator.apiClient } returns apiClient
        every { ServiceLocator.playerRepository } returns repo
        every { repo.session(any()) } returns sessionFlow
        every { apiClient.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { apiClient.events } returns MutableSharedFlow()
        every { SettingsModule.getRepository(any()) } returns mockk<SettingsRepository>(relaxed = true)
        mockkStatic(android.os.SystemClock::class)
        every { android.os.SystemClock.elapsedRealtime() } returns 10_000_000L
    }

    @After
    fun teardown() {
        unmockkStatic(android.os.SystemClock::class)
        unmockkObject(ServiceLocator, SettingsModule)
        Dispatchers.resetMain()
    }

    /** Seeds `_queue` with the given id by emitting a session for the selected player. */
    private suspend fun TestScope.seedQueue(vm: PlayerViewModel, queueId: String) {
        vm.selectPlayer(queueId) // sets selectedId → subscribes to repo.session(queueId) == sessionFlow
        advanceUntilIdle()
        sessionFlow.emit(
            io.musicassistant.companion.data.player.PlayerSession
                .empty(queueId)
                .copy(queue = PlayerQueue(queueId = queueId))
        )
        advanceUntilIdle()
    }

    @Test
    fun `clearQueue clears the active queue via the API`() = runTest(dispatcher) {
        val vm = PlayerViewModel(application)
        seedQueue(vm, "upma_q1")

        vm.clearQueue()
        advanceUntilIdle()

        coVerify(exactly = 1) { api.queueClear("upma_q1") }
    }

    @Test
    fun `clearQueue emits a user message when the API fails`() = runTest(dispatcher) {
        val vm = PlayerViewModel(application)
        seedQueue(vm, "upma_q1")
        coEvery { api.queueClear("upma_q1") } throws RuntimeException("boom")

        vm.userMessage.test {
            vm.clearQueue()
            advanceUntilIdle()
            assertEquals("Could not clear queue", awaitItem())
        }
    }

    @Test
    fun `clearQueue does nothing when there is no active queue`() = runTest(dispatcher) {
        val vm = PlayerViewModel(application)

        vm.clearQueue()
        advanceUntilIdle()

        coVerify(exactly = 0) { api.queueClear(any()) }
    }

    @Test
    fun `deleteQueueItem forwards to the API for the active queue`() = runTest(dispatcher) {
        val vm = PlayerViewModel(application)
        seedQueue(vm, "upma_q1")

        vm.deleteQueueItem("item-abc")
        advanceUntilIdle()

        coVerify(exactly = 1) { api.queueDeleteItem("upma_q1", "item-abc") }
    }

    @Test
    fun `queue and items reflect the selected session`() = runTest(dispatcher) {
        val vm = PlayerViewModel(application)
        vm.selectPlayer("upma_q1")
        advanceUntilIdle()

        val items = listOf(
            io.musicassistant.companion.data.model.QueueItem(queueItemId = "a", name = "A"),
            io.musicassistant.companion.data.model.QueueItem(queueItemId = "b", name = "B"),
        )
        sessionFlow.emit(
            io.musicassistant.companion.data.player.PlayerSession
                .empty("upma_q1")
                .copy(queue = PlayerQueue(queueId = "upma_q1", currentIndex = 1), queueItems = items)
        )
        advanceUntilIdle()

        assertEquals("upma_q1", vm.queue.value?.queueId)
        assertEquals(1, vm.queue.value?.currentIndex)
        assertEquals(listOf("a", "b"), vm.queueItems.value.map { it.queueItemId })
    }

    @Test
    fun `queue clears when the session has no queue`() = runTest(dispatcher) {
        val vm = PlayerViewModel(application)
        seedQueue(vm, "upma_q1")
        assertEquals("upma_q1", vm.queue.value?.queueId)

        sessionFlow.emit(
            io.musicassistant.companion.data.player.PlayerSession.empty("upma_q1") // queue = null
        )
        advanceUntilIdle()

        assertEquals(null, vm.queue.value)
        assertEquals(emptyList<io.musicassistant.companion.data.model.QueueItem>(), vm.queueItems.value)
    }

    /**
     * SSOT rule: this device is the default active player. The list exposes this device only as its
     * universal wrapper `upma_<suffix>` (raw `ma_<suffix>` is the sink and never appears), so it must
     * be resolved by suffix and selected by default — even while idle and even when another player is
     * playing — unless the user picked another. Also guards against the empty mini-player on restart.
     */
    @Test
    fun `auto-selects this device by default when the list loads`() = runTest(dispatcher) {
        val conn = MutableStateFlow(ConnectionState.DISCONNECTED)
        every { apiClient.connectionState } returns conn
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        val appSettings = mockk<io.musicassistant.companion.data.settings.AppSettings>(relaxed = true)
        every { appSettings.playerId } returns "ma_x"
        every { settingsRepo.settingsFlow } returns kotlinx.coroutines.flow.flowOf(appSettings)
        every { SettingsModule.getRepository(any()) } returns settingsRepo

        val other = Player(playerId = "spk", name = "Speaker", state = PlayerState.PLAYING)
        val device = Player(playerId = "upma_x", name = "Pixel 5", state = PlayerState.IDLE, activeSource = "upma_x")
        coEvery { api.getPlayers() } returns listOf(other, device)
        coEvery { api.getPlayerQueue("upma_x") } returns PlayerQueue(queueId = "upma_x")
        coEvery { api.getPlayerQueueItems("upma_x") } returns emptyList()

        val vm = PlayerViewModel(application)
        conn.value = ConnectionState.AUTHENTICATED
        advanceUntilIdle()

        // This device (upma_x) wins by default over the other playing player.
        assertEquals("upma_x", vm.activePlayer.value?.playerId)
    }

    /**
     * Task 5: isPlaying must follow the server-mirror session once a player is selected.
     * With the fixed clock at 10_000_000 ms and no prior user action (lastUserActionMs=0),
     * the debounce is NOT active (delta >> 1500 ms), so the server value is accepted.
     */
    @Test
    fun `isPlaying follows the selected session`() = runTest(dispatcher) {
        // seedQueue stubs for selectPlayer's else-branch loadQueue call
        coEvery { api.getPlayerQueue("ma_1") } returns PlayerQueue(queueId = "ma_1")
        coEvery { api.getPlayerQueueItems("ma_1") } returns emptyList()

        val vm = PlayerViewModel(application)
        vm.selectPlayer("ma_1")
        advanceUntilIdle()

        sessionFlow.emit(PlayerSession.empty("ma_1").copy(isPlaying = true))
        advanceUntilIdle()

        assertEquals(true, vm.isPlaying.value)
    }

    /**
     * Task 5: The optimistic debounce overlay must block a contradicting server event that arrives
     * within 1500 ms of a user action.
     * Clock is fixed at 10_000_000 ms. vm.pause() sets lastUserActionMs = 10_000_000.
     * Then the server session emits isPlaying=true (still same clock tick → delta=0 < 1500).
     * The debounce must reject this and keep isPlaying=false.
     *
     * Note: pause() requires an activePlayer (it guards on playerId ?: return).
     * We seed one via applyActivePlayer through the auto-select path.
     */
    @Test
    fun `optimistic pause wins over a stale playing event within debounce`() = runTest(dispatcher) {
        // Provide a player in the list so auto-select calls applyActivePlayer (activePlayer != null)
        val conn = MutableStateFlow(ConnectionState.DISCONNECTED)
        every { apiClient.connectionState } returns conn
        val player = Player(playerId = "ma_1", state = PlayerState.PAUSED)
        coEvery { api.getPlayers() } returns listOf(player)
        coEvery { api.getPlayerQueue("ma_1") } returns PlayerQueue(queueId = "ma_1")
        coEvery { api.getPlayerQueueItems("ma_1") } returns emptyList()

        val vm = PlayerViewModel(application)
        conn.value = ConnectionState.AUTHENTICATED
        advanceUntilIdle()
        // Now activePlayer = player (auto-selected via applyActivePlayer); selectedId = "ma_1"

        vm.pause() // sets lastUserActionMs = 10_000_000L, _isPlaying = false
        advanceUntilIdle()

        // Server session says PLAYING — debounce must reject this (delta = 10_000_000 - 10_000_000 = 0 < 1500)
        sessionFlow.emit(PlayerSession.empty("ma_1").copy(isPlaying = true))
        advanceUntilIdle()

        assertEquals(false, vm.isPlaying.value)
    }
}

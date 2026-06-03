package io.musicassistant.companion.ui.player

import android.app.Application
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.PlayerQueue
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

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ServiceLocator)
        mockkObject(SettingsModule)
        every { ServiceLocator.api } returns api
        every { ServiceLocator.apiClient } returns apiClient
        every { apiClient.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { apiClient.events } returns MutableSharedFlow()
        every { SettingsModule.getRepository(any()) } returns mockk<SettingsRepository>(relaxed = true)
    }

    @After
    fun teardown() {
        unmockkObject(ServiceLocator, SettingsModule)
        Dispatchers.resetMain()
    }

    /** Seeds `_queue` with the given id via the public selectPlayer -> loadQueue path. */
    private fun TestScope.seedQueue(vm: PlayerViewModel, queueId: String) {
        coEvery { api.getPlayerQueue(queueId) } returns PlayerQueue(queueId = queueId)
        coEvery { api.getPlayerQueueItems(queueId) } returns emptyList()
        vm.selectPlayer(queueId)
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
}

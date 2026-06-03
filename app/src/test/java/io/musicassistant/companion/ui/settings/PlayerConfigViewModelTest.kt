package io.musicassistant.companion.ui.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.model.PlayerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val api: MaApi = mockk()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun fixture(): PlayerConfig {
        val text = checkNotNull(javaClass.getResource("/config_player_sendspin.json")).readText()
        return json.decodeFromString(PlayerConfig.serializer(), text)
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load populates config and clears loading`() = runTest(dispatcher) {
        coEvery { api.getPlayerConfig("p1") } returns fixture()
        val vm = PlayerConfigViewModel(api)

        vm.load("p1")
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertNotNull(s.config)
        assertEquals("sendspin", s.config!!.provider)
    }

    @Test
    fun `load failure surfaces an error message`() = runTest(dispatcher) {
        coEvery { api.getPlayerConfig("p1") } throws RuntimeException("boom")
        val vm = PlayerConfigViewModel(api)

        vm.load("p1")
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("boom", s.error)
    }

    @Test
    fun `editing then reverting clears the dirty flag`() = runTest(dispatcher) {
        coEvery { api.getPlayerConfig("p1") } returns fixture()
        val vm = PlayerConfigViewModel(api)
        vm.load("p1")
        advanceUntilIdle()

        vm.setValue("expose_player_to_ha", JsonPrimitive(false))
        assertTrue(vm.state.value.isDirty)

        vm.setValue("expose_player_to_ha", JsonPrimitive(true)) // back to server value
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun `save sends only dirty values and refreshes from the returned config`() = runTest(dispatcher) {
        val original = fixture()
        val updated = original.copy(
            values = original.values.toMutableMap().also {
                it["expose_player_to_ha"] = it.getValue("expose_player_to_ha").copy(value = JsonPrimitive(false))
            },
        )
        coEvery { api.getPlayerConfig("p1") } returns original
        coEvery {
            api.savePlayerConfig("p1", mapOf("expose_player_to_ha" to JsonPrimitive(false)))
        } returns updated

        val vm = PlayerConfigViewModel(api)
        vm.load("p1")
        advanceUntilIdle()
        vm.setValue("expose_player_to_ha", JsonPrimitive(false))
        vm.save()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            api.savePlayerConfig("p1", mapOf("expose_player_to_ha" to JsonPrimitive(false)))
        }
        val s = vm.state.value
        assertFalse(s.saving)
        assertFalse(s.isDirty)
        assertEquals(JsonPrimitive(false), s.config!!.values.getValue("expose_player_to_ha").value)
    }

    @Test
    fun `save with no changes does not call the API`() = runTest(dispatcher) {
        coEvery { api.getPlayerConfig("p1") } returns fixture()
        val vm = PlayerConfigViewModel(api)
        vm.load("p1")
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { api.savePlayerConfig(any(), any()) }
    }

    @Test
    fun `save failure keeps edits and surfaces an error`() = runTest(dispatcher) {
        coEvery { api.getPlayerConfig("p1") } returns fixture()
        coEvery { api.savePlayerConfig(any(), any()) } throws RuntimeException("nope")
        val vm = PlayerConfigViewModel(api)
        vm.load("p1")
        advanceUntilIdle()
        vm.setValue("expose_player_to_ha", JsonPrimitive(false))

        vm.save()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.saving)
        assertEquals("nope", s.error)
        assertTrue(s.isDirty)
    }
}

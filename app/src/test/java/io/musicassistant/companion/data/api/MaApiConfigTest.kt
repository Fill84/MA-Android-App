package io.musicassistant.companion.data.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.musicassistant.companion.data.model.ConfigEntryType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the player-config API wrappers send the exact Music Assistant commands/args confirmed in
 * Phase 0 (`config/players/get`, `config/players/save` with a `values` dict — NOT `set_value`) and
 * decode the response into [io.musicassistant.companion.data.model.PlayerConfig].
 */
class MaApiConfigTest {

    private val client: MaApiClient = mockk()
    private val api = MaApi(client)

    private val sampleConfig: JsonElement = Json.parseToJsonElement(
        """
        {"values":{"expose_player_to_ha":{"key":"expose_player_to_ha","type":"boolean",
         "label":"Expose this player to Home Assistant","value":true}},
         "provider":"sendspin","player_id":"p1","enabled":true,"name":null,
         "default_name":"Test","player_type":"player"}
        """.trimIndent(),
    )

    @Test
    fun `getPlayerConfig sends config-players-get and decodes PlayerConfig`() = runTest {
        coEvery {
            client.sendCommand("config/players/get", mapOf("player_id" to JsonPrimitive("p1")))
        } returns sampleConfig

        val cfg = api.getPlayerConfig("p1")

        assertEquals("p1", cfg.playerId)
        assertEquals("sendspin", cfg.provider)
        val expose = cfg.values.getValue("expose_player_to_ha")
        assertEquals(ConfigEntryType.BOOLEAN, expose.type)
        assertTrue(expose.value!!.jsonPrimitive.boolean)
        coVerify(exactly = 1) {
            client.sendCommand("config/players/get", mapOf("player_id" to JsonPrimitive("p1")))
        }
    }

    @Test
    fun `savePlayerConfig sends config-players-save with a values dict and decodes the result`() = runTest {
        coEvery { client.sendCommand("config/players/save", any()) } returns sampleConfig

        val result = api.savePlayerConfig("p1", mapOf("expose_player_to_ha" to JsonPrimitive(true)))

        assertEquals("p1", result.playerId)
        coVerify(exactly = 1) {
            client.sendCommand(
                "config/players/save",
                mapOf(
                    "player_id" to JsonPrimitive("p1"),
                    "values" to JsonObject(mapOf("expose_player_to_ha" to JsonPrimitive(true))),
                ),
            )
        }
    }
}

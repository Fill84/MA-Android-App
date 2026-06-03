package io.musicassistant.companion.ui.settings

import io.musicassistant.companion.data.model.PlayerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure visibility / dirty-tracking logic for the dynamic player-config screen, exercised against
 * the real captured config fixture so the `depends_on` rules match the live server semantics.
 */
class PlayerConfigLogicTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun fixture(): PlayerConfig {
        val text = checkNotNull(javaClass.getResource("/config_player_sendspin.json")).readText()
        return json.decodeFromString(PlayerConfig.serializer(), text)
    }

    @Test
    fun `entry without a dependency is visible`() {
        val cfg = fixture()
        assertTrue(isConfigEntryVisible(cfg.values.getValue("expose_player_to_ha"), cfg, emptyMap()))
    }

    @Test
    fun `hidden entry is never visible`() {
        val cfg = fixture()
        assertFalse(isConfigEntryVisible(cfg.values.getValue("preferred_output_protocol"), cfg, emptyMap()))
    }

    @Test
    fun `equals-value dependency stays hidden until the dependency matches`() {
        val cfg = fixture()
        val crossfade = cfg.values.getValue("crossfade_duration") // depends_on smart_fades_mode == standard_crossfade
        assertFalse(isConfigEntryVisible(crossfade, cfg, emptyMap()))
        val edited = mapOf("smart_fades_mode" to JsonPrimitive("standard_crossfade"))
        assertTrue(isConfigEntryVisible(crossfade, cfg, edited))
    }

    @Test
    fun `not-equals dependency hides the entry when the value matches the excluded one`() {
        val cfg = fixture()
        val autoPlay = cfg.values.getValue("auto_play") // depends_on power_control != none
        assertFalse(isConfigEntryVisible(autoPlay, cfg, emptyMap())) // default power_control == none
        val edited = mapOf("power_control" to JsonPrimitive("fake"))
        assertTrue(isConfigEntryVisible(autoPlay, cfg, edited))
    }

    @Test
    fun `truthy dependency follows the boolean value`() {
        val cfg = fixture()
        val target = cfg.values.getValue("volume_normalization_target") // depends_on volume_normalization (truthy)
        assertTrue(isConfigEntryVisible(target, cfg, emptyMap())) // default true
        val edited = mapOf("volume_normalization" to JsonPrimitive(false))
        assertFalse(isConfigEntryVisible(target, cfg, edited))
    }

    @Test
    fun `currentConfigValue prefers an edit over the server value`() {
        val cfg = fixture()
        assertEquals(JsonPrimitive(true), currentConfigValue(cfg, emptyMap(), "expose_player_to_ha"))
        assertEquals(
            JsonPrimitive(false),
            currentConfigValue(cfg, mapOf("expose_player_to_ha" to JsonPrimitive(false)), "expose_player_to_ha"),
        )
    }

    @Test
    fun `dirtyValues returns only entries that differ from the server`() {
        val cfg = fixture()
        val edited = mapOf(
            "expose_player_to_ha" to JsonPrimitive(true), // equal to server -> not dirty
            "auto_play" to JsonPrimitive(true), // server is false -> dirty
        )
        assertEquals(setOf("auto_play"), configDirtyValues(cfg, edited).keys)
    }

    @Test
    fun `jsonTruthy handles booleans nulls and the none sentinel`() {
        assertTrue(jsonTruthy(JsonPrimitive(true)))
        assertFalse(jsonTruthy(JsonPrimitive(false)))
        assertFalse(jsonTruthy(null))
        assertFalse(jsonTruthy(JsonNull))
        assertFalse(jsonTruthy(JsonPrimitive("none")))
        assertTrue(jsonTruthy(JsonPrimitive("fake")))
    }
}

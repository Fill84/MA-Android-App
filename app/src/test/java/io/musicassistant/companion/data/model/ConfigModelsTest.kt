package io.musicassistant.companion.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the [PlayerConfig] / [ConfigEntry] models deserialize the REAL Music Assistant
 * `config/players/get` response captured from the live server (Phase 0). Fixture:
 * `src/test/resources/config_player_sendspin.json` (player id/name anonymised, schema verbatim).
 */
class ConfigModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun loadConfig(): PlayerConfig {
        val text = checkNotNull(javaClass.getResource("/config_player_sendspin.json")) {
            "fixture config_player_sendspin.json not found on test classpath"
        }.readText()
        return json.decodeFromString(PlayerConfig.serializer(), text)
    }

    @Test
    fun `deserializes top-level PlayerConfig fields`() {
        val cfg = loadConfig()
        assertEquals("sendspin", cfg.provider)
        assertEquals("ma_companion_test", cfg.playerId)
        assertEquals("player", cfg.playerType)
        assertTrue(cfg.enabled)
        assertNull(cfg.name)
        assertEquals("Test Player", cfg.defaultName)
    }

    @Test
    fun `parses all entries in the values map`() {
        val cfg = loadConfig()
        assertEquals(21, cfg.values.size)
        assertTrue(cfg.values.containsKey("expose_player_to_ha"))
        assertTrue(cfg.values.containsKey("smart_fades_mode"))
    }

    @Test
    fun `expose_player_to_ha is a boolean entry whose current value is true`() {
        val e = loadConfig().values.getValue("expose_player_to_ha")
        assertEquals(ConfigEntryType.BOOLEAN, e.type)
        assertEquals("Expose this player to Home Assistant", e.label)
        assertEquals("generic", e.category)
        assertTrue(e.value!!.jsonPrimitive.boolean)
        assertFalse(e.defaultValue!!.jsonPrimitive.boolean)
    }

    @Test
    fun `string entry with options carries the dropdown choices`() {
        val e = loadConfig().values.getValue("smart_fades_mode")
        assertEquals(ConfigEntryType.STRING, e.type)
        assertEquals(3, e.options.size)
        assertEquals("Disabled", e.options[0].title)
        assertEquals("disabled", e.options[0].value!!.jsonPrimitive.content)
        assertEquals("smart_crossfade", e.options[1].value!!.jsonPrimitive.content)
        assertEquals("disabled", e.value!!.jsonPrimitive.content)
    }

    @Test
    fun `integer entry parses its range as min and max`() {
        val e = loadConfig().values.getValue("crossfade_duration")
        assertEquals(ConfigEntryType.INTEGER, e.type)
        assertEquals(listOf(1.0, 15.0), e.range)
        assertEquals(8, e.value!!.jsonPrimitive.int)
    }

    @Test
    fun `depends_on with an equals value is parsed`() {
        val e = loadConfig().values.getValue("crossfade_duration")
        assertEquals("smart_fades_mode", e.dependsOn)
        assertEquals("standard_crossfade", e.dependsOnValue!!.jsonPrimitive.content)
        assertNull(e.dependsOnValueNot)
    }

    @Test
    fun `depends_on with a not-equals value is parsed`() {
        val e = loadConfig().values.getValue("auto_play")
        assertEquals("power_control", e.dependsOn)
        assertNull(e.dependsOnValue)
        assertEquals("none", e.dependsOnValueNot!!.jsonPrimitive.content)
    }

    @Test
    fun `depends_on truthy dependency has null compare values`() {
        val e = loadConfig().values.getValue("volume_normalization_target")
        assertEquals("volume_normalization", e.dependsOn)
        assertNull(e.dependsOnValue)
        assertNull(e.dependsOnValueNot)
    }

    @Test
    fun `hidden flag is parsed`() {
        val cfg = loadConfig()
        assertTrue(cfg.values.getValue("preferred_output_protocol").hidden)
        assertFalse(cfg.values.getValue("expose_player_to_ha").hidden)
    }

    @Test
    fun `icon entry type is parsed`() {
        val e = loadConfig().values.getValue("icon")
        assertEquals(ConfigEntryType.ICON, e.type)
        assertEquals("mdi-speaker", e.value!!.jsonPrimitive.content)
    }

    @Test
    fun `advanced flag distinguishes basic and advanced entries`() {
        val cfg = loadConfig()
        assertTrue(cfg.values.getValue("crossfade_duration").advanced)
        assertFalse(cfg.values.getValue("volume_normalization").advanced)
    }

    @Test
    fun `unknown config entry type falls back to UNKNOWN instead of throwing`() {
        val raw = """{"key":"x","type":"some_future_type","label":"X"}"""
        val e = json.decodeFromString(ConfigEntry.serializer(), raw)
        assertEquals(ConfigEntryType.UNKNOWN, e.type)
        assertEquals("x", e.key)
    }
}

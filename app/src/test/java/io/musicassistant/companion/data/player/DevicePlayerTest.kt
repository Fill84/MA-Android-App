package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicePlayerTest {

    private fun player(id: String) = Player(playerId = id)

    @Test
    fun `suffix strips ma and upma prefixes`() {
        assertEquals("jt06pzq30f", DevicePlayer.suffixOf("ma_jt06pzq30f"))
        assertEquals("jt06pzq30f", DevicePlayer.suffixOf("upma_jt06pzq30f"))
        assertEquals("plain", DevicePlayer.suffixOf("plain"))
    }

    @Test
    fun `resolves raw ma id to the universal upma player`() {
        val players = listOf(player("upma_g6zhz2qivi"), player("upma_jt06pzq30f"))
        assertEquals("upma_jt06pzq30f", DevicePlayer.resolveId("ma_jt06pzq30f", players))
    }

    @Test
    fun `matches by suffix when prefix differs`() {
        val players = listOf(player("upma_jt06pzq30f"))
        assertEquals("upma_jt06pzq30f", DevicePlayer.resolveId("jt06pzq30f", players))
    }

    @Test
    fun `returns null when device player is absent`() {
        val players = listOf(player("upma_g6zhz2qivi"))
        assertNull(DevicePlayer.resolveId("ma_jt06pzq30f", players))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(DevicePlayer.resolveId("", listOf(player("upma_jt06pzq30f"))))
    }
}

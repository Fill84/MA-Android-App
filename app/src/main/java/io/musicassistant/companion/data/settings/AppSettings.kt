package io.musicassistant.companion.data.settings

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

data class AppSettings(
        val serverUrl: String = "",
        val serverName: String = "",
        val isConfigured: Boolean = false,
        val authToken: String = "",
        val username: String = "",
        val backgroundPlaybackEnabled: Boolean = true,
        val keepScreenOn: Boolean = false,
        val themeMode: ThemeMode = ThemeMode.DARK,
        val playerId: String = "",
        val playerName: String = "",
        val codecPreference: String = "OPUS",
        val playerEnabled: Boolean = true
)

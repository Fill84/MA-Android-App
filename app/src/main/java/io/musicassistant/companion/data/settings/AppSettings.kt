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
        val backgroundPlaybackEnabled: Boolean = true,
        val keepScreenOn: Boolean = false,
        val themeMode: ThemeMode = ThemeMode.DARK
)

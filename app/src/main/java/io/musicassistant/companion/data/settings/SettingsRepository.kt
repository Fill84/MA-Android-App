package io.musicassistant.companion.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val IS_CONFIGURED = booleanPreferencesKey("is_configured")
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PLAYER_ID = stringPreferencesKey("player_id")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val USERNAME = stringPreferencesKey("username")
        val PLAYER_NAME = stringPreferencesKey("player_name")
        val CODEC_PREFERENCE = stringPreferencesKey("codec_preference")
        val PLAYER_ENABLED = booleanPreferencesKey("player_enabled")
    }

    val settingsFlow: Flow<AppSettings> =
            context.dataStore.data.map { prefs ->
                AppSettings(
                        serverUrl = prefs[Keys.SERVER_URL] ?: "",
                        serverName = prefs[Keys.SERVER_NAME] ?: "",
                        isConfigured = prefs[Keys.IS_CONFIGURED] ?: false,
                        backgroundPlaybackEnabled = prefs[Keys.BACKGROUND_PLAYBACK] ?: true,
                        keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
                        themeMode =
                                prefs[Keys.THEME_MODE]?.let {
                                    try {
                                        ThemeMode.valueOf(it)
                                    } catch (_: Exception) {
                                        ThemeMode.DARK
                                    }
                                }
                                        ?: ThemeMode.DARK,
                        playerId = prefs[Keys.PLAYER_ID] ?: "",
                        authToken = prefs[Keys.AUTH_TOKEN] ?: "",
                        username = prefs[Keys.USERNAME] ?: "",
                        playerName = prefs[Keys.PLAYER_NAME] ?: "",
                        codecPreference = prefs[Keys.CODEC_PREFERENCE] ?: "OPUS",
                        playerEnabled = prefs[Keys.PLAYER_ENABLED] ?: true
                )
            }

    suspend fun updateServer(url: String, name: String, token: String = "", username: String = "") {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = url
            prefs[Keys.SERVER_NAME] = name
            prefs[Keys.IS_CONFIGURED] = true
            prefs[Keys.AUTH_TOKEN] = token
            prefs[Keys.USERNAME] = username
        }
    }

    suspend fun clearServer() {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = ""
            prefs[Keys.SERVER_NAME] = ""
            prefs[Keys.IS_CONFIGURED] = false
            prefs[Keys.AUTH_TOKEN] = ""
            prefs[Keys.USERNAME] = ""
        }
    }

    suspend fun setAuthToken(token: String) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTH_TOKEN] = token }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTH_TOKEN] = ""
            prefs[Keys.USERNAME] = ""
        }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BACKGROUND_PLAYBACK] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setPlayerId(id: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PLAYER_ID] = id }
    }

    suspend fun setPlayerName(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PLAYER_NAME] = name }
    }

    suspend fun setCodecPreference(codec: String) {
        context.dataStore.edit { prefs -> prefs[Keys.CODEC_PREFERENCE] = codec }
    }

    suspend fun setPlayerEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.PLAYER_ENABLED] = enabled }
    }
}

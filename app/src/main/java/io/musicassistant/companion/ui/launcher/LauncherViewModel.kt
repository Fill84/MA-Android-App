package io.musicassistant.companion.ui.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.musicassistant.companion.data.discovery.DiscoveredServer
import io.musicassistant.companion.data.discovery.ServerDiscovery
import io.musicassistant.companion.data.settings.AppSettings
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.service.ServiceLocator
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR
}

/** The single source of truth for what the launcher screen should display. */
enum class LauncherState {
    /** Initial state: loading settings from DataStore. */
    LOADING,
    /** Settings loaded, auto-connecting to known server. */
    CONNECTING,
    /** No saved config — show the setup form. */
    SETUP,
    /** Successfully connected — ready to navigate to main. */
    CONNECTED,
    /** Connection/login failed — show setup form with error. */
    ERROR
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsModule.getRepository(application)
    private val serverDiscovery = ServerDiscovery(application)

    val settings: StateFlow<AppSettings> =
            settingsRepository.settingsFlow.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    AppSettings()
            )

    private val _launcherState = MutableStateFlow(LauncherState.LOADING)
    val launcherState: StateFlow<LauncherState> = _launcherState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val discoveredServers: StateFlow<List<DiscoveredServer>> = serverDiscovery.servers
    val isSearching: StateFlow<Boolean> = serverDiscovery.isSearching

    init {
        serverDiscovery.startDiscovery()
        // Load real settings, then decide: auto-connect or show setup
        viewModelScope.launch {
            val s = settingsRepository.settingsFlow.first()
            if (s.isConfigured && s.serverUrl.isNotEmpty()) {
                _launcherState.value = LauncherState.CONNECTING
                doAutoConnect(s)
            } else {
                _launcherState.value = LauncherState.SETUP
            }
        }
    }

    fun connectWithUrl(input: String, username: String = "", password: String = "") {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        val url =
                when {
                    trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                    else -> "http://$trimmed"
                }

        val host =
                try {
                    URL(url).host
                } catch (_: Exception) {
                    _errorMessage.value = "Invalid server address"
                    _launcherState.value = LauncherState.ERROR
                    return
                }

        connectToServer(url, host, username, password)
    }

    fun connectToServer(url: String, name: String?, username: String = "", password: String = "") {
        if (_launcherState.value == LauncherState.CONNECTING) return

        viewModelScope.launch {
            _launcherState.value = LauncherState.CONNECTING
            _errorMessage.value = null

            val reachable = withContext(Dispatchers.IO) { checkServerReachable(url) }

            if (!reachable) {
                _launcherState.value = LauncherState.ERROR
                _errorMessage.value = "Cannot connect to $url"
                return@launch
            }

            // If credentials are provided, login to get a token
            if (username.isNotBlank() && password.isNotBlank()) {
                try {
                    val token = ServiceLocator.apiClient.login(url, username, password)
                    settingsRepository.updateServer(url, name ?: url, token, username)
                    _launcherState.value = LauncherState.CONNECTED
                } catch (e: Exception) {
                    _launcherState.value = LauncherState.ERROR
                    _errorMessage.value = e.message ?: "Login failed"
                }
            } else {
                // No credentials — try connecting without auth (local network)
                settingsRepository.updateServer(url, name ?: url)
                _launcherState.value = LauncherState.CONNECTED
            }
        }
    }

    /** Retry connecting with saved settings. */
    fun retryConnect() {
        viewModelScope.launch {
            val s = settingsRepository.settingsFlow.first()
            if (s.isConfigured && s.serverUrl.isNotEmpty()) {
                _launcherState.value = LauncherState.CONNECTING
                doAutoConnect(s)
            }
        }
    }

    private suspend fun doAutoConnect(s: AppSettings) {
        _errorMessage.value = null
        val reachable = withContext(Dispatchers.IO) { checkServerReachable(s.serverUrl) }
        if (reachable) {
            _launcherState.value = LauncherState.CONNECTED
        } else {
            _launcherState.value = LauncherState.ERROR
            _errorMessage.value = "Cannot reach ${s.serverUrl}"
        }
    }

    fun refreshDiscovery() {
        serverDiscovery.refresh()
    }

    override fun onCleared() {
        super.onCleared()
        serverDiscovery.stopDiscovery()
    }

    private fun checkServerReachable(serverUrl: String): Boolean {
        return try {
            val connection = URL(serverUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.connect()
            connection.responseCode
            connection.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }
}

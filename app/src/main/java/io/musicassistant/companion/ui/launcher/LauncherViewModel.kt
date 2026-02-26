package io.musicassistant.companion.ui.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.musicassistant.companion.data.discovery.DiscoveredServer
import io.musicassistant.companion.data.discovery.ServerDiscovery
import io.musicassistant.companion.data.settings.AppSettings
import io.musicassistant.companion.data.settings.SettingsModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class ConnectionState {
    IDLE, CONNECTING, CONNECTED, ERROR
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsModule.getRepository(application)
    private val serverDiscovery = ServerDiscovery(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val discoveredServers: StateFlow<List<DiscoveredServer>> = serverDiscovery.servers
    val isSearching: StateFlow<Boolean> = serverDiscovery.isSearching

    init {
        serverDiscovery.startDiscovery()
    }

    fun connectWithUrl(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }

        val host = try {
            URL(url).host
        } catch (_: Exception) {
            _errorMessage.value = "Invalid server address"
            _connectionState.value = ConnectionState.ERROR
            return
        }

        connectToServer(url, host)
    }

    fun connectToServer(url: String, name: String?) {
        if (_connectionState.value == ConnectionState.CONNECTING) return

        viewModelScope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = null

            val reachable = withContext(Dispatchers.IO) {
                checkServerReachable(url)
            }

            if (reachable) {
                settingsRepository.updateServer(url, name ?: url)
                _connectionState.value = ConnectionState.CONNECTED
            } else {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Cannot connect to $url"
            }
        }
    }

    fun autoConnect() {
        if (_connectionState.value != ConnectionState.IDLE) return
        val currentSettings = settings.value
        if (currentSettings.isConfigured && currentSettings.serverUrl.isNotEmpty()) {
            connectToServer(currentSettings.serverUrl, currentSettings.serverName)
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

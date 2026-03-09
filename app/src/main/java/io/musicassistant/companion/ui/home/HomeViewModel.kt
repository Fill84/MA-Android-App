package io.musicassistant.companion.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.Artist
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val api = ServiceLocator.api
    private val apiClient = ServiceLocator.apiClient

    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recentlyPlayed.asStateFlow()

    private val _recentAlbums = MutableStateFlow<List<Album>>(emptyList())
    val recentAlbums: StateFlow<List<Album>> = _recentAlbums.asStateFlow()

    private val _recentTracks = MutableStateFlow<List<Track>>(emptyList())
    val recentTracks: StateFlow<List<Track>> = _recentTracks.asStateFlow()

    private val _randomArtists = MutableStateFlow<List<Artist>>(emptyList())
    val randomArtists: StateFlow<List<Artist>> = _randomArtists.asStateFlow()

    private val _randomAlbums = MutableStateFlow<List<Album>>(emptyList())
    val randomAlbums: StateFlow<List<Album>> = _randomAlbums.asStateFlow()

    private val _favoriteTracks = MutableStateFlow<List<Track>>(emptyList())
    val favoriteTracks: StateFlow<List<Track>> = _favoriteTracks.asStateFlow()

    private val _favoriteRadios = MutableStateFlow<List<Radio>>(emptyList())
    val favoriteRadios: StateFlow<List<Radio>> = _favoriteRadios.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loaded = false

    init {
        // Observe connection state and load when authenticated
        apiClient
                .connectionState
                .onEach { state ->
                    if (state == ConnectionState.AUTHENTICATED && !loaded) {
                        loadHome()
                    }
                }
                .launchIn(viewModelScope)

        // Refresh players when player_added/player_updated events fire
        apiClient
                .events
                .onEach { event ->
                    when (event.event) {
                        "player_added", "player_updated", "player_removed" -> {
                            try {
                                _players.value = api.getPlayers()
                            } catch (_: Exception) {}
                        }
                    }
                }
                .launchIn(viewModelScope)
    }

    fun loadHome() {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                _players.value = api.getPlayers()
                _recentlyPlayed.value =
                        api.getLibraryTracks(limit = 20, orderBy = "last_played desc")
                _recentAlbums.value =
                        api.getLibraryAlbums(limit = 20, orderBy = "timestamp_added desc")
                _recentTracks.value =
                        api.getLibraryTracks(limit = 20, orderBy = "timestamp_added desc")
                _randomArtists.value = api.getLibraryArtists(limit = 20, orderBy = "random")
                _randomAlbums.value = api.getLibraryAlbums(limit = 20, orderBy = "random")
                _favoriteTracks.value =
                        api.getLibraryTracks(
                                limit = 20,
                                orderBy = "timestamp_added desc",
                                favorite = true
                        )
                _favoriteRadios.value = api.getLibraryRadios(limit = 20, favorite = true)
                loaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load home: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getImageUrl(imagePath: String): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return api.getImageUrl(imagePath, baseUrl)
    }

    fun getImageUrl(image: MediaItemImage): String {
        val baseUrl = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }
        return api.getImageUrl(image, baseUrl)
    }
}

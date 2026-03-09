package io.musicassistant.companion.ui.library

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.Artist
import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.Playlist
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LibraryViewModel"
        private const val PAGE_SIZE = 50
    }

    private val api = ServiceLocator.api

    // Artists
    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()
    private var artistsOffset = 0
    private var artistsHasMore = true
    private val _artistsLoading = MutableStateFlow(false)
    val artistsLoading: StateFlow<Boolean> = _artistsLoading.asStateFlow()

    // Albums
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()
    private var albumsOffset = 0
    private var albumsHasMore = true
    private val _albumsLoading = MutableStateFlow(false)
    val albumsLoading: StateFlow<Boolean> = _albumsLoading.asStateFlow()

    // Tracks
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private var tracksOffset = 0
    private var tracksHasMore = true
    private val _tracksLoading = MutableStateFlow(false)
    val tracksLoading: StateFlow<Boolean> = _tracksLoading.asStateFlow()

    // Playlists
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private var playlistsOffset = 0
    private var playlistsHasMore = true
    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    // Radio
    private val _radios = MutableStateFlow<List<Radio>>(emptyList())
    val radios: StateFlow<List<Radio>> = _radios.asStateFlow()
    private var radiosOffset = 0
    private var radiosHasMore = true
    private val _radiosLoading = MutableStateFlow(false)
    val radiosLoading: StateFlow<Boolean> = _radiosLoading.asStateFlow()

    // Artist detail
    private val _artistDetail = MutableStateFlow<Artist?>(null)
    val artistDetail: StateFlow<Artist?> = _artistDetail.asStateFlow()
    private val _artistAlbums = MutableStateFlow<List<Album>>(emptyList())
    val artistAlbums: StateFlow<List<Album>> = _artistAlbums.asStateFlow()
    private val _artistTracks = MutableStateFlow<List<Track>>(emptyList())
    val artistTracks: StateFlow<List<Track>> = _artistTracks.asStateFlow()
    private val _artistDetailLoading = MutableStateFlow(false)
    val artistDetailLoading: StateFlow<Boolean> = _artistDetailLoading.asStateFlow()

    // Album detail
    private val _albumDetail = MutableStateFlow<Album?>(null)
    val albumDetail: StateFlow<Album?> = _albumDetail.asStateFlow()
    private val _albumTracks = MutableStateFlow<List<Track>>(emptyList())
    val albumTracks: StateFlow<List<Track>> = _albumTracks.asStateFlow()
    private val _albumDetailLoading = MutableStateFlow(false)
    val albumDetailLoading: StateFlow<Boolean> = _albumDetailLoading.asStateFlow()

    // Playlist detail
    private val _playlistDetail = MutableStateFlow<Playlist?>(null)
    val playlistDetail: StateFlow<Playlist?> = _playlistDetail.asStateFlow()
    private val _playlistTracks = MutableStateFlow<List<Track>>(emptyList())
    val playlistTracks: StateFlow<List<Track>> = _playlistTracks.asStateFlow()
    private val _playlistDetailLoading = MutableStateFlow(false)
    val playlistDetailLoading: StateFlow<Boolean> = _playlistDetailLoading.asStateFlow()

    fun getImageUrl(imagePath: String): String {
        val baseUrl =
                ServiceLocator.apiClient.connectionUrl.ifEmpty {
                    ServiceLocator.apiClient.serverInfo.value?.baseUrl ?: ""
                }
        return api.getImageUrl(imagePath, baseUrl)
    }

    fun getImageUrl(image: MediaItemImage): String {
        val baseUrl =
                ServiceLocator.apiClient.connectionUrl.ifEmpty {
                    ServiceLocator.apiClient.serverInfo.value?.baseUrl ?: ""
                }
        return api.getImageUrl(image, baseUrl)
    }

    // ── Artists ──────────────────────────────────────────────

    fun loadArtists(refresh: Boolean = false) {
        if (_artistsLoading.value) return
        if (!refresh && !artistsHasMore) return
        if (refresh) {
            artistsOffset = 0
            artistsHasMore = true
        }
        _artistsLoading.value = true
        viewModelScope.launch {
            try {
                val items = api.getLibraryArtists(limit = PAGE_SIZE, offset = artistsOffset)
                if (refresh) _artists.value = items else _artists.value = _artists.value + items
                artistsHasMore = items.size == PAGE_SIZE
                artistsOffset += items.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artists: ${e.message}")
            } finally {
                _artistsLoading.value = false
            }
        }
    }

    // ── Albums ───────────────────────────────────────────────

    fun loadAlbums(refresh: Boolean = false) {
        if (_albumsLoading.value) return
        if (!refresh && !albumsHasMore) return
        if (refresh) {
            albumsOffset = 0
            albumsHasMore = true
        }
        _albumsLoading.value = true
        viewModelScope.launch {
            try {
                val items = api.getLibraryAlbums(limit = PAGE_SIZE, offset = albumsOffset)
                if (refresh) _albums.value = items else _albums.value = _albums.value + items
                albumsHasMore = items.size == PAGE_SIZE
                albumsOffset += items.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load albums: ${e.message}")
            } finally {
                _albumsLoading.value = false
            }
        }
    }

    // ── Tracks ───────────────────────────────────────────────

    fun loadTracks(refresh: Boolean = false) {
        if (_tracksLoading.value) return
        if (!refresh && !tracksHasMore) return
        if (refresh) {
            tracksOffset = 0
            tracksHasMore = true
        }
        _tracksLoading.value = true
        viewModelScope.launch {
            try {
                val items = api.getLibraryTracks(limit = PAGE_SIZE, offset = tracksOffset)
                if (refresh) _tracks.value = items else _tracks.value = _tracks.value + items
                tracksHasMore = items.size == PAGE_SIZE
                tracksOffset += items.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load tracks: ${e.message}")
            } finally {
                _tracksLoading.value = false
            }
        }
    }

    // ── Playlists ────────────────────────────────────────────

    fun loadPlaylists(refresh: Boolean = false) {
        if (_playlistsLoading.value) return
        if (!refresh && !playlistsHasMore) return
        if (refresh) {
            playlistsOffset = 0
            playlistsHasMore = true
        }
        _playlistsLoading.value = true
        viewModelScope.launch {
            try {
                val items = api.getLibraryPlaylists(limit = PAGE_SIZE, offset = playlistsOffset)
                if (refresh) _playlists.value = items
                else _playlists.value = _playlists.value + items
                playlistsHasMore = items.size == PAGE_SIZE
                playlistsOffset += items.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load playlists: ${e.message}")
            } finally {
                _playlistsLoading.value = false
            }
        }
    }

    // ── Radios ───────────────────────────────────────────────

    fun loadRadios(refresh: Boolean = false) {
        if (_radiosLoading.value) return
        if (!refresh && !radiosHasMore) return
        if (refresh) {
            radiosOffset = 0
            radiosHasMore = true
        }
        _radiosLoading.value = true
        viewModelScope.launch {
            try {
                val items = api.getLibraryRadios(limit = PAGE_SIZE, offset = radiosOffset)
                if (refresh) _radios.value = items else _radios.value = _radios.value + items
                radiosHasMore = items.size == PAGE_SIZE
                radiosOffset += items.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load radios: ${e.message}")
            } finally {
                _radiosLoading.value = false
            }
        }
    }

    // ── Detail loaders ───────────────────────────────────────

    fun loadArtistDetail(itemId: String) {
        _artistDetailLoading.value = true
        _artistDetail.value = null
        _artistAlbums.value = emptyList()
        _artistTracks.value = emptyList()
        viewModelScope.launch {
            try {
                _artistDetail.value = api.getArtist(itemId)
                _artistAlbums.value = api.getArtistAlbums(itemId)
                _artistTracks.value = api.getArtistTracks(itemId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artist detail: ${e.message}")
            } finally {
                _artistDetailLoading.value = false
            }
        }
    }

    fun loadAlbumDetail(itemId: String) {
        _albumDetailLoading.value = true
        _albumDetail.value = null
        _albumTracks.value = emptyList()
        viewModelScope.launch {
            try {
                _albumDetail.value = api.getAlbum(itemId)
                _albumTracks.value = api.getAlbumTracks(itemId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load album detail: ${e.message}")
            } finally {
                _albumDetailLoading.value = false
            }
        }
    }

    fun loadPlaylistDetail(itemId: String) {
        _playlistDetailLoading.value = true
        _playlistDetail.value = null
        _playlistTracks.value = emptyList()
        viewModelScope.launch {
            try {
                _playlistDetail.value = api.getPlaylist(itemId)
                _playlistTracks.value = api.getPlaylistTracks(itemId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load playlist detail: ${e.message}")
            } finally {
                _playlistDetailLoading.value = false
            }
        }
    }
}

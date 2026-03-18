package io.musicassistant.companion.auto

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.Artist
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.Playlist
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.data.settings.SettingsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * MediaLibrarySession.Callback that provides the browsing tree and search for Android Auto.
 * Fetches data from the MA server API and converts to MediaItems.
 */
class AutoBrowseCallback(
        private val api: MaApi,
        private val apiClient: MaApiClient
) : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        private const val TAG = "AutoBrowseCallback"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cached search results
    private var lastSearchQuery: String? = null
    private var lastSearchResults: List<MediaItem> = emptyList()

    private val baseUrl: String
        get() = apiClient.connectionUrl.ifEmpty { apiClient.serverInfo.value?.baseUrl ?: "" }

    private val isConnected: Boolean
        get() = apiClient.connectionState.value == ConnectionState.AUTHENTICATED

    // ── Root ────────────────────────────────────────────────

    override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = MediaItem.Builder()
                .setMediaId(MediaIdHelper.ROOT)
                .setMediaMetadata(
                        MediaMetadata.Builder()
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .setTitle("Music Assistant")
                                .build()
                )
                .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    // ── Children ────────────────────────────────────────────

    override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (parentId == MediaIdHelper.ROOT) {
            return Futures.immediateFuture(LibraryResult.ofItemList(rootCategories(), params))
        }

        if (!isConnected) {
            return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(), params)
            )
        }

        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        executor.execute {
            try {
                val offset = page * pageSize
                val limit = pageSize.coerceIn(1, 100)
                val items = runBlocking { fetchChildren(parentId, limit, offset) }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch children for $parentId: ${e.message}")
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }
        return future
    }

    private suspend fun fetchChildren(parentId: String, limit: Int, offset: Int): List<MediaItem> {
        return when (parentId) {
            MediaIdHelper.RECENTLY_PLAYED ->
                api.getLibraryTracks(limit = limit, offset = offset, orderBy = "last_played desc")
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.FAVORITES ->
                api.getLibraryTracks(limit = limit, offset = offset, favorite = true)
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.ARTISTS ->
                api.getLibraryArtists(limit = limit, offset = offset)
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.ALBUMS ->
                api.getLibraryAlbums(limit = limit, offset = offset)
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.PLAYLISTS ->
                api.getLibraryPlaylists(limit = limit, offset = offset)
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.RADIOS ->
                api.getLibraryRadios(limit = limit, offset = offset)
                        .map { it.toAutoRadioItem() }

            else -> fetchItemChildren(parentId, limit, offset)
        }
    }

    private suspend fun fetchItemChildren(parentId: String, limit: Int, offset: Int): List<MediaItem> {
        val parsed = MediaIdHelper.parse(parentId) ?: return emptyList()

        return when (parsed.type) {
            MediaIdHelper.IdType.ARTIST -> {
                // Show sub-categories: Albums and Tracks
                listOf(
                        buildBrowsableItem(
                                MediaIdHelper.artistAlbumsId(parsed.value),
                                "Albums",
                                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        ),
                        buildBrowsableItem(
                                MediaIdHelper.artistTracksId(parsed.value),
                                "Tracks",
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        )
                )
            }

            MediaIdHelper.IdType.ARTIST_ALBUMS ->
                api.getArtistAlbums(parsed.value)
                        .let { if (offset < it.size) it.subList(offset, (offset + limit).coerceAtMost(it.size)) else emptyList() }
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.IdType.ARTIST_TRACKS ->
                api.getArtistTracks(parsed.value)
                        .let { if (offset < it.size) it.subList(offset, (offset + limit).coerceAtMost(it.size)) else emptyList() }
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.IdType.ALBUM ->
                api.getAlbumTracks(parsed.value)
                        .let { if (offset < it.size) it.subList(offset, (offset + limit).coerceAtMost(it.size)) else emptyList() }
                        .map { it.toAutoMediaItem() }

            MediaIdHelper.IdType.PLAYLIST ->
                api.getPlaylistTracks(parsed.value, limit = limit, offset = offset)
                        .map { it.toAutoMediaItem() }

            else -> emptyList()
        }
    }

    // ── Search ──────────────────────────────────────────────

    override fun onSearch(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        if (!isConnected) {
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        scope.launch {
            try {
                val results = api.search(query, limit = 25)
                val items = mutableListOf<MediaItem>()
                results.tracks.forEach { items.add(it.toAutoMediaItem()) }
                results.albums.forEach { items.add(it.toAutoMediaItem()) }
                results.artists.forEach { items.add(it.toAutoMediaItem()) }
                results.playlists.forEach { items.add(it.toAutoMediaItem()) }
                results.radio.forEach { items.add(it.toAutoRadioItem()) }

                lastSearchQuery = query
                lastSearchResults = items

                session.notifySearchResultChanged(browser, query, items.size, params)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}")
            }
        }

        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val results = if (query == lastSearchQuery) lastSearchResults else emptyList()
        val offset = page * pageSize
        val paged = if (offset < results.size) {
            results.subList(offset, (offset + pageSize).coerceAtMost(results.size))
        } else {
            emptyList()
        }
        return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
        )
    }

    // ── Playback (onAddMediaItems) ──────────────────────────

    /** Application context, set by AutoMediaService on creation. */
    var appContext: android.content.Context? = null

    override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> {
        // When user taps a playable item in Android Auto, trigger playback via MA API
        val ctx = appContext
        if (ctx != null) {
            for (item in mediaItems) {
                playMediaId(item.mediaId, ctx)
            }
        }

        // Return the items as-is — SimpleBasePlayer doesn't actually play them
        // The MA server handles playback via Sendspin
        return Futures.immediateFuture(mediaItems)
    }

    /**
     * Trigger playback for a media ID. Called from AutoMediaService or externally.
     */
    fun playMediaId(mediaId: String, context: android.content.Context) {
        val uri = MediaIdHelper.uriFor(mediaId) ?: return
        val mediaType = MediaIdHelper.mediaTypeFor(mediaId)

        scope.launch {
            try {
                val settingsRepo = SettingsModule.getRepository(context)
                val settings = settingsRepo.settingsFlow.first()
                val queueId = settings.playerId.ifEmpty { return@launch }
                api.playMedia(queueId, uri, mediaType, "play")
            } catch (e: Exception) {
                Log.e(TAG, "playMediaId failed: ${e.message}")
            }
        }
    }

    // ── Notify on reconnect ─────────────────────────────────

    fun notifyReconnected(session: MediaLibraryService.MediaLibrarySession) {
        val categories = listOf(
                MediaIdHelper.RECENTLY_PLAYED, MediaIdHelper.FAVORITES,
                MediaIdHelper.ARTISTS, MediaIdHelper.ALBUMS,
                MediaIdHelper.PLAYLISTS, MediaIdHelper.RADIOS
        )
        for (cat in categories) {
            session.notifyChildrenChanged(cat, 0, null)
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun rootCategories(): ImmutableList<MediaItem> {
        return ImmutableList.of(
                buildBrowsableItem(MediaIdHelper.RECENTLY_PLAYED, "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                buildBrowsableItem(MediaIdHelper.FAVORITES, "Favorites", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                buildBrowsableItem(MediaIdHelper.ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                buildBrowsableItem(MediaIdHelper.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                buildBrowsableItem(MediaIdHelper.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                buildBrowsableItem(MediaIdHelper.RADIOS, "Radio Stations", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
        )
    }

    private fun buildBrowsableItem(mediaId: String, title: String, mediaType: Int): MediaItem {
        return MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                        MediaMetadata.Builder()
                                .setTitle(title)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(mediaType)
                                .build()
                )
                .build()
    }

    private fun resolveArtworkUri(image: MediaItemImage?): Uri? {
        if (image == null) return null
        val url = api.getImageUrl(image, baseUrl)
        return if (url.isNotEmpty()) Uri.parse(url) else null
    }

    private fun resolveArtworkUri(image: String?): Uri? {
        if (image.isNullOrEmpty()) return null
        val url = api.getImageUrl(image, baseUrl)
        return if (url.isNotEmpty()) Uri.parse(url) else null
    }

    // ── Model → MediaItem converters ────────────────────────

    private fun Track.toAutoMediaItem(): MediaItem {
        val artworkUri = resolveArtworkUri(resolvedImage)
        return MediaItem.Builder()
                .setMediaId(MediaIdHelper.trackId(uri))
                .setMediaMetadata(
                        MediaMetadata.Builder()
                                .setTitle(name)
                                .setArtist(artists.joinToString(", ") { it.name })
                                .setAlbumTitle(album?.name)
                                .setArtworkUri(artworkUri)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                )
                .build()
    }

    private fun Album.toAutoMediaItem(): MediaItem {
        val artworkUri = resolveArtworkUri(resolvedImage)
        return MediaItem.Builder()
                .setMediaId(MediaIdHelper.albumId(itemId))
                .setMediaMetadata(
                        MediaMetadata.Builder()
                                .setTitle(name)
                                .setArtist(artists.joinToString(", ") { it.name })
                                .setArtworkUri(artworkUri)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                                .build()
                )
                .build()
    }

    private fun Artist.toAutoMediaItem(): MediaItem {
        val artworkUri = resolveArtworkUri(resolvedImage)
        return MediaItem.Builder()
                .setMediaId(MediaIdHelper.artistId(itemId))
                .setMediaMetadata(
                        MediaMetadata.Builder()
                                .setTitle(name)
                                .setArtworkUri(artworkUri)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                .build()
                )
                .build()
    }

    private fun Playlist.toAutoMediaItem(): MediaItem {
        val artworkUri = resolveArtworkUri(resolvedImage)
        return MediaItem.Builder()
                .setMediaId(MediaIdHelper.playlistId(itemId))
                .setMediaMetadata(
                        MediaMetadata.Builder()
                                .setTitle(name)
                                .setArtworkUri(artworkUri)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                                .build()
                )
                .build()
    }

    private fun Radio.toAutoRadioItem(): MediaItem {
        val artworkUri = resolveArtworkUri(resolvedImage)
        return MediaItem.Builder()
                .setMediaId(MediaIdHelper.radioId(uri))
                .setMediaMetadata(
                        MediaMetadata.Builder()
                                .setTitle(name)
                                .setArtworkUri(artworkUri)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                .build()
                )
                .build()
    }
}

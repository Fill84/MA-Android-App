package io.musicassistant.companion.data.api

import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.Artist
import io.musicassistant.companion.data.model.BrowseItem
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.Playlist
import io.musicassistant.companion.data.model.QueueItem
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.SearchResults
import io.musicassistant.companion.data.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Typed API wrapper around [MaApiClient] for Music Assistant server commands. All methods are
 * suspend functions that return deserialized model objects.
 */
class MaApi(private val client: MaApiClient) {

        private val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
        }

        // ── Players ─────────────────────────────────────────────

        suspend fun getPlayers(): List<Player> {
                val result = client.sendCommand("players/all")
                return json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(Player.serializer()),
                        result
                )
        }

        suspend fun getPlayer(playerId: String): Player {
                val result =
                        client.sendCommand(
                                "players/get",
                                mapOf("player_id" to JsonPrimitive(playerId))
                        )
                return json.decodeFromJsonElement(Player.serializer(), result)
        }

        suspend fun playerCommand(
                playerId: String,
                command: String,
                vararg args: Pair<String, JsonElement>
        ) {
                client.sendCommand(
                        "players/cmd/$command",
                        mapOf("player_id" to JsonPrimitive(playerId)) + args.toMap()
                )
        }

        suspend fun playerPlay(playerId: String) = playerCommand(playerId, "play")
        suspend fun playerPause(playerId: String) = playerCommand(playerId, "pause")
        suspend fun playerStop(playerId: String) = playerCommand(playerId, "stop")
        suspend fun playerNext(playerId: String) = playerCommand(playerId, "next")
        suspend fun playerPrevious(playerId: String) = playerCommand(playerId, "previous")

        suspend fun playerVolumeSet(playerId: String, level: Int) {
                playerCommand(playerId, "volume_set", "volume_level" to JsonPrimitive(level))
        }

        suspend fun playerVolumeMute(playerId: String, muted: Boolean) {
                playerCommand(playerId, "volume_mute", "muted" to JsonPrimitive(muted))
        }

        suspend fun playerSeek(playerId: String, positionSeconds: Double) {
                client.sendCommand(
                        "players/cmd/seek",
                        mapOf(
                                "player_id" to JsonPrimitive(playerId),
                                "position" to JsonPrimitive(positionSeconds)
                        )
                )
        }

        // ── Player Queue ────────────────────────────────────────

        suspend fun getPlayerQueue(queueId: String): PlayerQueue {
                val result =
                        client.sendCommand(
                                "player_queues/get",
                                mapOf("queue_id" to JsonPrimitive(queueId))
                        )
                return json.decodeFromJsonElement(PlayerQueue.serializer(), result)
        }

        suspend fun getPlayerQueueItems(
                queueId: String,
                limit: Int = 50,
                offset: Int = 0
        ): List<QueueItem> {
                val result =
                        client.sendCommand(
                                "player_queues/items",
                                mapOf(
                                        "queue_id" to JsonPrimitive(queueId),
                                        "limit" to JsonPrimitive(limit),
                                        "offset" to JsonPrimitive(offset)
                                )
                        )
                return json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(QueueItem.serializer()),
                        result
                )
        }

        suspend fun queueCommandShuffle(queueId: String, enabled: Boolean) {
                client.sendCommand(
                        "player_queues/shuffle",
                        mapOf(
                                "queue_id" to JsonPrimitive(queueId),
                                "shuffle_enabled" to JsonPrimitive(enabled)
                        )
                )
        }

        suspend fun queueCommandRepeat(queueId: String, repeatMode: String) {
                client.sendCommand(
                        "player_queues/repeat",
                        mapOf(
                                "queue_id" to JsonPrimitive(queueId),
                                "repeat_mode" to JsonPrimitive(repeatMode)
                        )
                )
        }

        suspend fun queuePlayIndex(queueId: String, index: Int) {
                client.sendCommand(
                        "player_queues/play_index",
                        mapOf("queue_id" to JsonPrimitive(queueId), "index" to JsonPrimitive(index))
                )
        }

        suspend fun queueMoveItem(queueId: String, queueItemId: String, positionShift: Int) {
                client.sendCommand(
                        "player_queues/move_item",
                        mapOf(
                                "queue_id" to JsonPrimitive(queueId),
                                "queue_item_id" to JsonPrimitive(queueItemId),
                                "pos_shift" to JsonPrimitive(positionShift)
                        )
                )
        }

        suspend fun queueDeleteItem(queueId: String, queueItemId: String) {
                client.sendCommand(
                        "player_queues/delete_item",
                        mapOf(
                                "queue_id" to JsonPrimitive(queueId),
                                "queue_item_id" to JsonPrimitive(queueItemId)
                        )
                )
        }

        // ── Play Media ──────────────────────────────────────────

        suspend fun playMedia(
                queueId: String,
                mediaUri: String,
                mediaType: MediaType? = null,
                option: String = "play" // play, replace, next, add
        ) {
                val args =
                        mutableMapOf<String, JsonElement>(
                                "queue_id" to JsonPrimitive(queueId),
                                "media" to JsonPrimitive(mediaUri),
                                "option" to JsonPrimitive(option)
                        )
                if (mediaType != null) {
                        args["media_type"] = JsonPrimitive(mediaType.name.lowercase())
                }
                client.sendCommand("player_queues/play_media", args)
        }

        // ── Library: Artists ────────────────────────────────────

        suspend fun getLibraryArtists(
                limit: Int = 50,
                offset: Int = 0,
                search: String? = null,
                orderBy: String = "sort_name"
        ): List<Artist> {
                val args =
                        mutableMapOf<String, JsonElement>(
                                "limit" to JsonPrimitive(limit),
                                "offset" to JsonPrimitive(offset),
                                "order_by" to JsonPrimitive(orderBy)
                        )
                if (search != null) args["search"] = JsonPrimitive(search)
                val result = client.sendCommand("music/artists/library_items", args)
                return decodeList(result) { json.decodeFromJsonElement(Artist.serializer(), it) }
        }

        suspend fun getArtist(itemId: String, provider: String = "library"): Artist {
                val result =
                        client.sendCommand(
                                "music/artists/get",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider)
                                )
                        )
                return json.decodeFromJsonElement(Artist.serializer(), result)
        }

        suspend fun getArtistAlbums(itemId: String, provider: String = "library"): List<Album> {
                val result =
                        client.sendCommand(
                                "music/artists/artist_albums",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider)
                                )
                        )
                return decodeList(result) { json.decodeFromJsonElement(Album.serializer(), it) }
        }

        suspend fun getArtistTracks(itemId: String, provider: String = "library"): List<Track> {
                val result =
                        client.sendCommand(
                                "music/artists/artist_tracks",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider)
                                )
                        )
                return decodeList(result) { json.decodeFromJsonElement(Track.serializer(), it) }
        }

        // ── Library: Albums ─────────────────────────────────────

        suspend fun getLibraryAlbums(
                limit: Int = 50,
                offset: Int = 0,
                search: String? = null,
                orderBy: String = "sort_name"
        ): List<Album> {
                val args =
                        mutableMapOf<String, JsonElement>(
                                "limit" to JsonPrimitive(limit),
                                "offset" to JsonPrimitive(offset),
                                "order_by" to JsonPrimitive(orderBy)
                        )
                if (search != null) args["search"] = JsonPrimitive(search)
                val result = client.sendCommand("music/albums/library_items", args)
                return decodeList(result) { json.decodeFromJsonElement(Album.serializer(), it) }
        }

        suspend fun getAlbum(itemId: String, provider: String = "library"): Album {
                val result =
                        client.sendCommand(
                                "music/albums/get",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider)
                                )
                        )
                return json.decodeFromJsonElement(Album.serializer(), result)
        }

        suspend fun getAlbumTracks(itemId: String, provider: String = "library"): List<Track> {
                val result =
                        client.sendCommand(
                                "music/albums/album_tracks",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider)
                                )
                        )
                return decodeList(result) { json.decodeFromJsonElement(Track.serializer(), it) }
        }

        // ── Library: Tracks ─────────────────────────────────────

        suspend fun getLibraryTracks(
                limit: Int = 50,
                offset: Int = 0,
                search: String? = null,
                orderBy: String = "sort_name",
                favorite: Boolean? = null
        ): List<Track> {
                val args =
                        mutableMapOf<String, JsonElement>(
                                "limit" to JsonPrimitive(limit),
                                "offset" to JsonPrimitive(offset),
                                "order_by" to JsonPrimitive(orderBy)
                        )
                if (search != null) args["search"] = JsonPrimitive(search)
                if (favorite != null) args["favorite"] = JsonPrimitive(favorite)
                val result = client.sendCommand("music/tracks/library_items", args)
                return decodeList(result) { json.decodeFromJsonElement(Track.serializer(), it) }
        }

        suspend fun getTrack(itemId: String, provider: String = "library"): Track {
                val result =
                        client.sendCommand(
                                "music/tracks/get",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider)
                                )
                        )
                return json.decodeFromJsonElement(Track.serializer(), result)
        }

        // ── Library: Playlists ──────────────────────────────────

        suspend fun getLibraryPlaylists(
                limit: Int = 50,
                offset: Int = 0,
                search: String? = null,
                orderBy: String = "sort_name"
        ): List<Playlist> {
                val args =
                        mutableMapOf<String, JsonElement>(
                                "limit" to JsonPrimitive(limit),
                                "offset" to JsonPrimitive(offset),
                                "order_by" to JsonPrimitive(orderBy)
                        )
                if (search != null) args["search"] = JsonPrimitive(search)
                val result = client.sendCommand("music/playlists/library_items", args)
                return decodeList(result) { json.decodeFromJsonElement(Playlist.serializer(), it) }
        }

        suspend fun getPlaylist(itemId: String, provider: String = "library"): Playlist {
                val result =
                        client.sendCommand(
                                "music/playlists/get",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider)
                                )
                        )
                return json.decodeFromJsonElement(Playlist.serializer(), result)
        }

        suspend fun getPlaylistTracks(
                itemId: String,
                provider: String = "library",
                limit: Int = 50,
                offset: Int = 0
        ): List<Track> {
                val result =
                        client.sendCommand(
                                "music/playlists/playlist_tracks",
                                mapOf(
                                        "item_id" to JsonPrimitive(itemId),
                                        "provider_instance_id_or_domain" to JsonPrimitive(provider),
                                        "limit" to JsonPrimitive(limit),
                                        "offset" to JsonPrimitive(offset)
                                )
                        )
                return decodeList(result) { json.decodeFromJsonElement(Track.serializer(), it) }
        }

        // ── Library: Radio ──────────────────────────────────────

        suspend fun getLibraryRadios(
                limit: Int = 50,
                offset: Int = 0,
                search: String? = null,
                orderBy: String = "sort_name",
                favorite: Boolean? = null
        ): List<Radio> {
                val args =
                        mutableMapOf<String, JsonElement>(
                                "limit" to JsonPrimitive(limit),
                                "offset" to JsonPrimitive(offset),
                                "order_by" to JsonPrimitive(orderBy)
                        )
                if (search != null) args["search"] = JsonPrimitive(search)
                if (favorite != null) args["favorite"] = JsonPrimitive(favorite)
                val result = client.sendCommand("music/radios/library_items", args)
                return decodeList(result) { json.decodeFromJsonElement(Radio.serializer(), it) }
        }

        // ── Search ──────────────────────────────────────────────

        suspend fun search(
                query: String,
                mediaTypes: List<MediaType>? = null,
                limit: Int = 25
        ): SearchResults {
                val args =
                        mutableMapOf<String, JsonElement>(
                                "search_query" to JsonPrimitive(query),
                                "limit" to JsonPrimitive(limit)
                        )
                if (mediaTypes != null) {
                        args["media_types"] =
                                kotlinx.serialization.json.buildJsonArray {
                                        mediaTypes.forEach {
                                                add(JsonPrimitive(it.name.lowercase()))
                                        }
                                }
                }
                val result = client.sendCommand("music/search", args)
                return json.decodeFromJsonElement(SearchResults.serializer(), result)
        }

        // ── Browse ──────────────────────────────────────────────

        suspend fun browse(path: String? = null): List<BrowseItem> {
                val args = mutableMapOf<String, JsonElement>()
                if (path != null) args["path"] = JsonPrimitive(path)
                val result = client.sendCommand("music/browse", args)
                return decodeList(result) {
                        json.decodeFromJsonElement(BrowseItem.serializer(), it)
                }
        }

        // ── Image URL helper ────────────────────────────────────

        /**
         * Build a full image URL from a [io.musicassistant.companion.data.model.MediaItemImage]. If
         * the image path is relative, prepend the server base URL.
         */
        fun getImageUrl(imagePath: String, serverBaseUrl: String): String {
                if (imagePath.startsWith("http://") || imagePath.startsWith("https://"))
                        return imagePath
                return "${serverBaseUrl.trimEnd('/')}/$imagePath"
        }

        /**
         * Build a full image URL from a [io.musicassistant.companion.data.model.MediaItemImage]
         * using the server's imageproxy endpoint.
         */
        fun getImageUrl(
                image: io.musicassistant.companion.data.model.MediaItemImage,
                serverBaseUrl: String
        ): String {
                val path = image.path
                if (path.startsWith("http://") || path.startsWith("https://")) return path
                if (image.remotelyAccessible) return path
                val base = serverBaseUrl.trimEnd('/')
                val encodedPath = android.net.Uri.encode(path, "")
                val encodedProvider = android.net.Uri.encode(image.provider, "")
                return "$base/imageproxy?size=500&path=$encodedPath&provider=$encodedProvider"
        }

        // ── Helpers ─────────────────────────────────────────────

        private fun <T> decodeList(result: JsonElement, decoder: (JsonElement) -> T): List<T> {
                // Result can be either a JSON array directly, or an object with "items" key
                return when {
                        result is kotlinx.serialization.json.JsonArray -> result.map(decoder)
                        result is kotlinx.serialization.json.JsonObject && "items" in result ->
                                result["items"]!!.jsonArray.map(decoder)
                        else -> emptyList()
                }
        }
}

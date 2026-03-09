package io.musicassistant.companion.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Extract the first image from a media item's metadata JSON. */
fun extractImageFromMetadata(metadata: JsonElement?): MediaItemImage? {
    val obj = metadata as? JsonObject ?: return null
    val images = obj["images"] as? JsonArray ?: return null
    val first = images.firstOrNull() as? JsonObject ?: return null
    return MediaItemImage(
            type = first["type"]?.jsonPrimitive?.contentOrNull ?: "",
            path = first["path"]?.jsonPrimitive?.contentOrNull ?: "",
            provider = first["provider"]?.jsonPrimitive?.contentOrNull ?: "",
            remotelyAccessible = first["remotely_accessible"]?.jsonPrimitive?.booleanOrNull ?: false
    )
}

/** Represents the type of a media item in Music Assistant. */
@Serializable
enum class MediaType {
    @SerialName("artist") ARTIST,
    @SerialName("album") ALBUM,
    @SerialName("track") TRACK,
    @SerialName("playlist") PLAYLIST,
    @SerialName("radio") RADIO,
    @SerialName("folder") FOLDER,
    @SerialName("unknown") UNKNOWN
}

/** Image metadata for media items. */
@Serializable
data class MediaItemImage(
        val type: String = "",
        val path: String = "",
        @SerialName("provider") val provider: String = "",
        @SerialName("remotely_accessible") val remotelyAccessible: Boolean = false
)

/** Provider mapping for a media item. */
@Serializable
data class ProviderMapping(
        @SerialName("item_id") val itemId: String = "",
        @SerialName("provider_domain") val providerDomain: String = "",
        @SerialName("provider_instance") val providerInstance: String = "",
        val available: Boolean = true,
        val url: String? = null
)

/** Base fields shared by all media items. */
@Serializable
data class ItemMapping(
        @SerialName("item_id") val itemId: String = "",
        val provider: String = "",
        val name: String = "",
        @SerialName("media_type") val mediaType: MediaType = MediaType.UNKNOWN,
        val uri: String = "",
        @SerialName("image") val image: MediaItemImage? = null
)

@Serializable
data class Artist(
        @SerialName("item_id") val itemId: String = "",
        val provider: String = "",
        val name: String = "",
        @SerialName("provider_mappings") val providerMappings: List<ProviderMapping> = emptyList(),
        val metadata: JsonElement? = null,
        @SerialName("media_type") val mediaType: MediaType = MediaType.ARTIST,
        val uri: String = "",
        @SerialName("image") val image: MediaItemImage? = null,
        @SerialName("sort_name") val sortName: String = "",
        @SerialName("in_library") val inLibrary: Boolean = false
) {
    val resolvedImage: MediaItemImage?
        get() = image ?: extractImageFromMetadata(metadata)
}

@Serializable
data class Album(
        @SerialName("item_id") val itemId: String = "",
        val provider: String = "",
        val name: String = "",
        @SerialName("provider_mappings") val providerMappings: List<ProviderMapping> = emptyList(),
        val metadata: JsonElement? = null,
        @SerialName("media_type") val mediaType: MediaType = MediaType.ALBUM,
        val uri: String = "",
        @SerialName("image") val image: MediaItemImage? = null,
        @SerialName("sort_name") val sortName: String = "",
        val version: String = "",
        val year: Int? = null,
        val artists: List<ItemMapping> = emptyList(),
        @SerialName("album_type") val albumType: String = "",
        @SerialName("in_library") val inLibrary: Boolean = false
) {
    val resolvedImage: MediaItemImage?
        get() = image ?: extractImageFromMetadata(metadata)
}

@Serializable
data class Track(
        @SerialName("item_id") val itemId: String = "",
        val provider: String = "",
        val name: String = "",
        @SerialName("provider_mappings") val providerMappings: List<ProviderMapping> = emptyList(),
        val metadata: JsonElement? = null,
        @SerialName("media_type") val mediaType: MediaType = MediaType.TRACK,
        val uri: String = "",
        @SerialName("image") val image: MediaItemImage? = null,
        @SerialName("sort_name") val sortName: String = "",
        val version: String = "",
        val duration: Int = 0,
        val artists: List<ItemMapping> = emptyList(),
        val album: ItemMapping? = null,
        @SerialName("disc_number") val discNumber: Int = 0,
        @SerialName("track_number") val trackNumber: Int = 0,
        @SerialName("in_library") val inLibrary: Boolean = false
) {
    val resolvedImage: MediaItemImage?
        get() = image ?: extractImageFromMetadata(metadata)
}

@Serializable
data class Playlist(
        @SerialName("item_id") val itemId: String = "",
        val provider: String = "",
        val name: String = "",
        @SerialName("provider_mappings") val providerMappings: List<ProviderMapping> = emptyList(),
        val metadata: JsonElement? = null,
        @SerialName("media_type") val mediaType: MediaType = MediaType.PLAYLIST,
        val uri: String = "",
        @SerialName("image") val image: MediaItemImage? = null,
        val owner: String = "",
        @SerialName("is_editable") val isEditable: Boolean = false,
        @SerialName("in_library") val inLibrary: Boolean = false
) {
    val resolvedImage: MediaItemImage?
        get() = image ?: extractImageFromMetadata(metadata)
}

@Serializable
data class Radio(
        @SerialName("item_id") val itemId: String = "",
        val provider: String = "",
        val name: String = "",
        @SerialName("provider_mappings") val providerMappings: List<ProviderMapping> = emptyList(),
        val metadata: JsonElement? = null,
        @SerialName("media_type") val mediaType: MediaType = MediaType.RADIO,
        val uri: String = "",
        @SerialName("image") val image: MediaItemImage? = null,
        @SerialName("in_library") val inLibrary: Boolean = false
) {
    val resolvedImage: MediaItemImage?
        get() = image ?: extractImageFromMetadata(metadata)
}

@Serializable
data class BrowseItem(
        val path: String = "",
        val name: String = "",
        @SerialName("is_folder") val isFolder: Boolean = false,
        @SerialName("media_type") val mediaType: MediaType = MediaType.UNKNOWN,
        val uri: String = "",
        @SerialName("image") val image: MediaItemImage? = null
)

@Serializable
data class SearchResults(
        val artists: List<Artist> = emptyList(),
        val albums: List<Album> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val radio: List<Radio> = emptyList()
)

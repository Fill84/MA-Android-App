package io.musicassistant.companion.auto

import io.musicassistant.companion.data.model.MediaType

/** Media ID constants and parsing for Android Auto browsing tree. */
object MediaIdHelper {

    // Root categories
    const val ROOT = "__ROOT__"
    const val RECENTLY_PLAYED = "__RECENTLY_PLAYED__"
    const val FAVORITES = "__FAVORITES__"
    const val ARTISTS = "__ARTISTS__"
    const val ALBUMS = "__ALBUMS__"
    const val PLAYLISTS = "__PLAYLISTS__"
    const val RADIOS = "__RADIOS__"

    // Prefixes for item-level media IDs
    private const val PREFIX_ARTIST = "artist:"
    private const val PREFIX_ARTIST_ALBUMS = "artist_albums:"
    private const val PREFIX_ARTIST_TRACKS = "artist_tracks:"
    private const val PREFIX_ALBUM = "album:"
    private const val PREFIX_PLAYLIST = "playlist:"
    private const val PREFIX_TRACK = "track:"
    private const val PREFIX_RADIO = "radio:"

    // Builders
    fun artistId(itemId: String) = "$PREFIX_ARTIST$itemId"
    fun artistAlbumsId(itemId: String) = "$PREFIX_ARTIST_ALBUMS$itemId"
    fun artistTracksId(itemId: String) = "$PREFIX_ARTIST_TRACKS$itemId"
    fun albumId(itemId: String) = "$PREFIX_ALBUM$itemId"
    fun playlistId(itemId: String) = "$PREFIX_PLAYLIST$itemId"
    fun trackId(uri: String) = "$PREFIX_TRACK$uri"
    fun radioId(uri: String) = "$PREFIX_RADIO$uri"

    // Parsing
    data class ParsedId(val type: IdType, val value: String)

    enum class IdType {
        ROOT_CATEGORY,
        ARTIST, ARTIST_ALBUMS, ARTIST_TRACKS,
        ALBUM, PLAYLIST,
        TRACK, RADIO
    }

    fun parse(mediaId: String): ParsedId? {
        return when {
            mediaId == ROOT || mediaId == RECENTLY_PLAYED || mediaId == FAVORITES ||
            mediaId == ARTISTS || mediaId == ALBUMS || mediaId == PLAYLISTS ||
            mediaId == RADIOS -> ParsedId(IdType.ROOT_CATEGORY, mediaId)

            mediaId.startsWith(PREFIX_ARTIST_ALBUMS) ->
                ParsedId(IdType.ARTIST_ALBUMS, mediaId.removePrefix(PREFIX_ARTIST_ALBUMS))
            mediaId.startsWith(PREFIX_ARTIST_TRACKS) ->
                ParsedId(IdType.ARTIST_TRACKS, mediaId.removePrefix(PREFIX_ARTIST_TRACKS))
            mediaId.startsWith(PREFIX_ARTIST) ->
                ParsedId(IdType.ARTIST, mediaId.removePrefix(PREFIX_ARTIST))
            mediaId.startsWith(PREFIX_ALBUM) ->
                ParsedId(IdType.ALBUM, mediaId.removePrefix(PREFIX_ALBUM))
            mediaId.startsWith(PREFIX_PLAYLIST) ->
                ParsedId(IdType.PLAYLIST, mediaId.removePrefix(PREFIX_PLAYLIST))
            mediaId.startsWith(PREFIX_TRACK) ->
                ParsedId(IdType.TRACK, mediaId.removePrefix(PREFIX_TRACK))
            mediaId.startsWith(PREFIX_RADIO) ->
                ParsedId(IdType.RADIO, mediaId.removePrefix(PREFIX_RADIO))
            else -> null
        }
    }

    /** Extract MediaType from a playable media ID. */
    fun mediaTypeFor(mediaId: String): MediaType? {
        return when {
            mediaId.startsWith(PREFIX_TRACK) -> MediaType.TRACK
            mediaId.startsWith(PREFIX_RADIO) -> MediaType.RADIO
            mediaId.startsWith(PREFIX_ALBUM) -> MediaType.ALBUM
            mediaId.startsWith(PREFIX_PLAYLIST) -> MediaType.PLAYLIST
            mediaId.startsWith(PREFIX_ARTIST) -> MediaType.ARTIST
            else -> null
        }
    }

    /** Extract the playable URI from a media ID. */
    fun uriFor(mediaId: String): String? {
        val parsed = parse(mediaId) ?: return null
        return when (parsed.type) {
            IdType.TRACK, IdType.RADIO -> parsed.value
            else -> null
        }
    }
}

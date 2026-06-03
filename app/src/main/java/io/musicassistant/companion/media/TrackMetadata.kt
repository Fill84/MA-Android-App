package io.musicassistant.companion.media

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val artworkBytes: ByteArray?
) {
    val trackId: String
        get() = "${artist.trim().lowercase()}::${title.trim().lowercase()}"

    val hasArtwork: Boolean
        get() = artworkBytes != null && artworkBytes.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackMetadata) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artworkUrl == other.artworkUrl &&
            artworkBytes.contentEqualsOrBothNull(other.artworkBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artworkUrl?.hashCode() ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        val EMPTY = TrackMetadata(title = "", artist = "", album = null, artworkUrl = null, artworkBytes = null)
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null) other == null else other != null && this.contentEquals(other)

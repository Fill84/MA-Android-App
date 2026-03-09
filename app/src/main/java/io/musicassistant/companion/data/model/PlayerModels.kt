package io.musicassistant.companion.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Player state as reported by Music Assistant. */
@Serializable
enum class PlayerState {
        @SerialName("idle") IDLE,
        @SerialName("playing") PLAYING,
        @SerialName("paused") PAUSED,
        @SerialName("buffering") BUFFERING
}

/** Repeat mode for player queue. */
@Serializable
enum class RepeatMode {
        @SerialName("off") OFF,
        @SerialName("one") ONE,
        @SerialName("all") ALL
}

/** Current media info reported by the server on a player. */
@Serializable
data class CurrentMedia(
        val uri: String = "",
        @SerialName("media_type") val mediaType: String = "",
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        @SerialName("image_url") val imageUrl: String? = null,
        val duration: Double? = null,
        @SerialName("elapsed_time") val elapsedTime: Double? = null,
        @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double? = null
)

/** A Music Assistant player. */
@Serializable
data class Player(
        @SerialName("player_id") val playerId: String = "",
        val provider: String = "",
        val type: String = "",
        val name: String = "",
        val available: Boolean = false,
        val powered: Boolean = false,
        val state: PlayerState = PlayerState.IDLE,
        @SerialName("volume_level") val volumeLevel: Int = 0,
        @SerialName("volume_muted") val volumeMuted: Boolean = false,
        @SerialName("elapsed_time") val elapsedTime: Double = 0.0,
        @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double = 0.0,
        @SerialName("current_item_id") val currentItemId: String? = null,
        @SerialName("active_source") val activeSource: String? = null,
        @SerialName("group_childs") val groupChilds: List<String> = emptyList(),
        @SerialName("can_sync_with") val canSyncWith: List<String> = emptyList(),
        @SerialName("synced_to") val syncedTo: String? = null,
        @SerialName("icon") val icon: String = "",
        @SerialName("current_media") val currentMedia: CurrentMedia? = null
)

/** An item in the player queue. */
@Serializable
data class QueueItem(
        @SerialName("queue_item_id") val queueItemId: String = "",
        @SerialName("queue_id") val queueId: String = "",
        val name: String = "",
        val duration: Int = 0,
        @SerialName("sort_index") val sortIndex: Int = 0,
        @SerialName("streamdetails") val streamDetails: JsonElement? = null,
        @SerialName("media_item") val mediaItem: QueueMediaItem? = null,
        @SerialName("image") val image: MediaItemImage? = null
)

/** Simplified media item reference within a queue item. */
@Serializable
data class QueueMediaItem(
        val uri: String = "",
        val name: String = "",
        @SerialName("media_type") val mediaType: MediaType = MediaType.TRACK,
        @SerialName("image") val image: MediaItemImage? = null,
        val artists: List<ItemMapping> = emptyList(),
        val album: ItemMapping? = null
)

/** Current state of the player's queue. */
@Serializable
data class PlayerQueue(
        @SerialName("queue_id") val queueId: String = "",
        val active: Boolean = false,
        @SerialName("display_name") val displayName: String = "",
        @SerialName("available") val available: Boolean = false,
        @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
        @SerialName("repeat_mode") val repeatMode: RepeatMode = RepeatMode.OFF,
        @SerialName("current_index") val currentIndex: Int? = null,
        @SerialName("current_item") val currentItem: QueueItem? = null,
        @SerialName("next_item") val nextItem: QueueItem? = null,
        @SerialName("elapsed_time") val elapsedTime: Double = 0.0,
        @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double = 0.0,
        val state: PlayerState = PlayerState.IDLE,
        val items: Int = 0
)

package io.musicassistant.companion.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server information returned after successful WebSocket authentication. */
@Serializable
data class ServerInfo(
        @SerialName("server_id") val serverId: String = "",
        @SerialName("server_version") val serverVersion: String = "",
        @SerialName("schema_version") val schemaVersion: Int = 0,
        @SerialName("min_supported_schema_version") val minSupportedSchemaVersion: Int = 0,
        @SerialName("base_url") val baseUrl: String = "",
        @SerialName("homeassistant_addon") val homeassistantAddon: Boolean = false
)

/** Connection state of the API client. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    AUTH_FAILED
}

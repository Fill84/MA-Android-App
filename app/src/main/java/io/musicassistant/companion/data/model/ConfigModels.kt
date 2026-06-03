package io.musicassistant.companion.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/**
 * Type of a Music Assistant [ConfigEntry]. Mirrors `ConfigEntryType` in `music-assistant/models`
 * (`enums.py`). Unknown / future values decode to [UNKNOWN] rather than throwing, matching the
 * server's own `_missing_` fallback — so a newer server can't break the settings screen.
 */
@Serializable(with = ConfigEntryTypeSerializer::class)
enum class ConfigEntryType(val wire: String) {
    BOOLEAN("boolean"),
    STRING("string"),
    SECURE_STRING("secure_string"),
    INTEGER("integer"),
    FLOAT("float"),
    LABEL("label"),
    SPLITTED_STRING("splitted_string"),
    DIVIDER("divider"),
    ACTION("action"),
    ICON("icon"),
    ALERT("alert"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): ConfigEntryType = entries.firstOrNull { it.wire == raw } ?: UNKNOWN
    }
}

object ConfigEntryTypeSerializer : KSerializer<ConfigEntryType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ConfigEntryType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ConfigEntryType) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): ConfigEntryType =
        ConfigEntryType.fromWire(decoder.decodeString())
}

/** A selectable option for a [ConfigEntry] that has non-empty [ConfigEntry.options]. */
@Serializable
data class ConfigValueOption(
    val title: String = "",
    val value: JsonElement? = null,
)

/**
 * One Music Assistant config entry: its schema plus the current [value]. Mirrors `ConfigEntry` in
 * `music-assistant/models` (`config_entries.py`). The `translation_*` fields are intentionally
 * omitted — the UI renders the already-human-readable [label]/[description], and unknown JSON keys
 * are ignored by the decoding `Json` instance.
 */
@Serializable
data class ConfigEntry(
    val key: String = "",
    val type: ConfigEntryType = ConfigEntryType.UNKNOWN,
    val label: String = "",
    @SerialName("default_value") val defaultValue: JsonElement? = null,
    val required: Boolean = true,
    val options: List<ConfigValueOption> = emptyList(),
    /** Inclusive `[min, max]` for numeric entries, or null. A JSON array, not an object. */
    val range: List<Double>? = null,
    val description: String? = null,
    @SerialName("help_link") val helpLink: String? = null,
    @SerialName("multi_value") val multiValue: Boolean = false,
    @SerialName("depends_on") val dependsOn: String? = null,
    @SerialName("depends_on_value") val dependsOnValue: JsonElement? = null,
    @SerialName("depends_on_value_not") val dependsOnValueNot: JsonElement? = null,
    val hidden: Boolean = false,
    @SerialName("read_only") val readOnly: Boolean = false,
    val category: String = "generic",
    val action: String? = null,
    @SerialName("action_label") val actionLabel: String? = null,
    @SerialName("immediate_apply") val immediateApply: Boolean = false,
    @SerialName("requires_reload") val requiresReload: Boolean = false,
    val advanced: Boolean = false,
    val value: JsonElement? = null,
)

/**
 * A player's full configuration, as returned by `config/players/get`. [values] holds every
 * [ConfigEntry] keyed by [ConfigEntry.key]. [name] and [enabled] are top-level fields (not entries
 * in [values]); the display name is [name] ?: [defaultName].
 */
@Serializable
data class PlayerConfig(
    val values: Map<String, ConfigEntry> = emptyMap(),
    val provider: String = "",
    @SerialName("player_id") val playerId: String = "",
    val enabled: Boolean = true,
    val name: String? = null,
    @SerialName("default_name") val defaultName: String? = null,
    @SerialName("player_type") val playerType: String = "player",
)

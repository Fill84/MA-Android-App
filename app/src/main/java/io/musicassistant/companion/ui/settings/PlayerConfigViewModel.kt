package io.musicassistant.companion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.model.ConfigEntry
import io.musicassistant.companion.data.model.PlayerConfig
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// ── Pure config logic (shared by the ViewModel and the Compose screen) ──────────────────────────

/** Current value of [key]: the user's pending edit if present, otherwise the server value. */
internal fun currentConfigValue(
    config: PlayerConfig,
    edited: Map<String, JsonElement>,
    key: String,
): JsonElement? = edited[key] ?: config.values[key]?.value

/**
 * Whether a [ConfigEntry] should be shown, given the current (edited-over-server) values. Mirrors
 * Music Assistant's `depends_on` semantics confirmed in Phase 0:
 * - [ConfigEntry.dependsOnValue] set  → show when the dependency equals it
 * - [ConfigEntry.dependsOnValueNot] set → show when the dependency differs from it
 * - only [ConfigEntry.dependsOn] set  → show when the dependency is truthy
 * Hidden entries are never shown.
 */
internal fun isConfigEntryVisible(
    entry: ConfigEntry,
    config: PlayerConfig,
    edited: Map<String, JsonElement>,
): Boolean {
    if (entry.hidden) return false
    val dependsOn = entry.dependsOn ?: return true
    val depValue = currentConfigValue(config, edited, dependsOn)
    return when {
        entry.dependsOnValue != null -> depValue == entry.dependsOnValue
        entry.dependsOnValueNot != null -> depValue != entry.dependsOnValueNot
        else -> jsonTruthy(depValue)
    }
}

/** Edits that genuinely differ from the server value — i.e. what needs to be saved. */
internal fun configDirtyValues(
    config: PlayerConfig,
    edited: Map<String, JsonElement>,
): Map<String, JsonElement> = edited.filter { (key, value) -> config.values[key]?.value != value }

/** Loose truthiness for `depends_on` checks: booleans by value, the `none` sentinel and blanks are false. */
internal fun jsonTruthy(value: JsonElement?): Boolean {
    // NB: JsonNull is itself a JsonPrimitive in kotlinx (content == "null"), so reject it first.
    if (value == null || value is JsonNull) return false
    val primitive = value as? JsonPrimitive ?: return true
    primitive.booleanOrNull?.let { return it }
    return primitive.content.isNotEmpty() && primitive.content != "none"
}

// ── ViewModel ────────────────────────────────────────────────────────────────────────────────

/**
 * Backs the dynamic player-config screen: loads a player's [PlayerConfig], tracks pending edits as
 * a diff over the server values, and saves only the changed keys via `config/players/save`.
 */
class PlayerConfigViewModel internal constructor(
    private val api: MaApi,
) : ViewModel() {

    /** No-arg constructor used by the default `viewModel()` factory. */
    constructor() : this(ServiceLocator.api)

    data class UiState(
        val loading: Boolean = false,
        val saving: Boolean = false,
        val error: String? = null,
        val config: PlayerConfig? = null,
        val edited: Map<String, JsonElement> = emptyMap(),
    ) {
        val dirtyValues: Map<String, JsonElement>
            get() = config?.let { configDirtyValues(it, edited) } ?: emptyMap()
        val isDirty: Boolean get() = dirtyValues.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var playerId: String = ""

    fun load(playerId: String) {
        this.playerId = playerId
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val config = api.getPlayerConfig(playerId)
                _state.update {
                    it.copy(loading = false, config = config, edited = emptyMap(), error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load configuration") }
            }
        }
    }

    /** Set [key] to [value]; an edit equal to the server value is dropped so it isn't counted dirty. */
    fun setValue(key: String, value: JsonElement) {
        _state.update { s ->
            val serverValue = s.config?.values?.get(key)?.value
            val edited = s.edited.toMutableMap()
            if (value == serverValue) edited.remove(key) else edited[key] = value
            s.copy(edited = edited)
        }
    }

    fun save() {
        val current = _state.value
        val dirty = current.dirtyValues
        if (dirty.isEmpty() || current.config == null) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val updated = api.savePlayerConfig(playerId, dirty)
                _state.update {
                    it.copy(saving = false, config = updated, edited = emptyMap(), error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Failed to save configuration") }
            }
        }
    }
}

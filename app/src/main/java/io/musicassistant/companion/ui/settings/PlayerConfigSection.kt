package io.musicassistant.companion.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.musicassistant.companion.data.model.ConfigEntry
import io.musicassistant.companion.data.model.ConfigEntryType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * Renders the Music Assistant server-side player config (the dynamic `config/players/get` schema)
 * grouped by category, one control per [ConfigEntry] type. Visibility (`depends_on`/`hidden`) and
 * `read_only` are honoured; edits are reported via [onValueChange] and committed via [onSave].
 */
@Composable
internal fun PlayerConfigSection(
    state: PlayerConfigViewModel.UiState,
    onValueChange: (String, JsonElement) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    val config = state.config
    when {
        config == null && state.loading -> {
            ConfigSectionHeader("MUSIC ASSISTANT")
            ConfigCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading player settings…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        config == null -> {
            ConfigSectionHeader("MUSIC ASSISTANT")
            ConfigCard {
                Text(
                    text = state.error ?: "Settings unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
        }

        else -> {
            // The audio-format entry is surfaced as the "Audio codec" dropdown in PlayerSettingsScreen,
            // so hide it here to avoid showing the same control twice.
            val visible = config.values.values.filter {
                isConfigEntryVisible(it, config, state.edited) && !it.key.endsWith(CODEC_FORMAT_SUFFIX)
            }
            val grouped = visible.groupByTo(LinkedHashMap()) { it.category }
            grouped.forEach { (category, entries) ->
                ConfigSectionHeader(categoryTitle(category))
                ConfigCard {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        ConfigEntryField(
                            entry = entry,
                            currentValue = currentConfigValue(config, state.edited, entry.key),
                            onChange = { onValueChange(entry.key, it) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            ConfigSaveBar(
                isDirty = state.isDirty,
                saving = state.saving,
                error = state.error,
                onSave = onSave,
            )
        }
    }
}

@Composable
private fun ConfigSaveBar(isDirty: Boolean, saving: Boolean, error: String?, onSave: () -> Unit) {
    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Button(
        onClick = onSave,
        enabled = isDirty && !saving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (saving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(if (isDirty) "Save changes" else "Saved")
    }
}

/** Dispatches a single [ConfigEntry] to the right control based on its type / options. */
@Composable
private fun ConfigEntryField(entry: ConfigEntry, currentValue: JsonElement?, onChange: (JsonElement) -> Unit) {
    val enabled = !entry.readOnly
    when {
        entry.type == ConfigEntryType.LABEL -> ConfigLabel(entry)
        entry.type == ConfigEntryType.DIVIDER -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        entry.type == ConfigEntryType.ALERT -> ConfigAlert(entry)

        entry.type == ConfigEntryType.BOOLEAN -> ConfigSwitchItem(
            title = entry.label,
            description = entry.description.orEmpty(),
            checked = currentValue.asBool(entry.defaultValue.asBool(false)),
            onCheckedChange = { onChange(JsonPrimitive(it)) },
            enabled = enabled,
        )

        entry.options.isNotEmpty() -> ConfigDropdownField(entry, currentValue, enabled, onChange)

        entry.type == ConfigEntryType.INTEGER || entry.type == ConfigEntryType.FLOAT ->
            if (entry.range?.size == 2) ConfigSliderField(entry, currentValue, enabled, onChange)
            else ConfigNumberField(entry, currentValue, enabled, onChange)

        entry.type == ConfigEntryType.STRING ||
            entry.type == ConfigEntryType.SECURE_STRING ||
            entry.type == ConfigEntryType.ICON -> ConfigTextField(entry, currentValue, enabled, onChange)

        else -> ConfigReadOnly(entry, currentValue) // UNKNOWN, ACTION, etc.
    }
}

@Composable
private fun ConfigLabel(entry: ConfigEntry) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(entry.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        entry.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfigAlert(entry: ConfigEntry) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = entry.description ?: entry.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ConfigReadOnly(entry: ConfigEntry, currentValue: JsonElement?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(entry.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            text = currentValue.asString(entry.description.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigDropdownField(
    entry: ConfigEntry,
    currentValue: JsonElement?,
    enabled: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = entry.options.firstOrNull { it.value == currentValue }?.title
        ?: currentValue.asString(entry.label)

    // Vertical layout: label/description on top, full-width selector below. Avoids squeezing the
    // label when an option title is long (e.g. "Volume increase by fixed percentage").
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(entry.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        entry.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(selectedTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                entry.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.title) },
                        onClick = {
                            option.value?.let(onChange)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigSliderField(
    entry: ConfigEntry,
    currentValue: JsonElement?,
    enabled: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    val min = entry.range!![0]
    val max = entry.range[1]
    val isInt = entry.type == ConfigEntryType.INTEGER
    val current = if (isInt) currentValue.asInt(entry.defaultValue.asInt(min.roundToInt())).toFloat()
    else currentValue.asDouble(entry.defaultValue.asDouble(min)).toFloat()

    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isInt) current.roundToInt().toString() else "%.1f".format(current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        entry.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = current.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { v ->
                if (isInt) onChange(JsonPrimitive(v.roundToInt())) else onChange(JsonPrimitive(v.toDouble()))
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = if (isInt) (max - min).toInt().coerceAtLeast(1) - 1 else 0,
            enabled = enabled,
        )
    }
}

@Composable
private fun ConfigNumberField(
    entry: ConfigEntry,
    currentValue: JsonElement?,
    enabled: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    val isInt = entry.type == ConfigEntryType.INTEGER
    // Key on the server value so external reloads reset the field, but typing doesn't.
    var text by remember(entry.value) { mutableStateOf(currentValue.asString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            if (isInt) new.toIntOrNull()?.let { onChange(JsonPrimitive(it)) }
            else new.toDoubleOrNull()?.let { onChange(JsonPrimitive(it)) }
        },
        label = { Text(entry.label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = entry.description?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun ConfigTextField(
    entry: ConfigEntry,
    currentValue: JsonElement?,
    enabled: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    var text by remember(entry.value) { mutableStateOf(currentValue.asString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(JsonPrimitive(it))
        },
        label = { Text(entry.label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (entry.type == ConfigEntryType.SECURE_STRING) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        supportingText = entry.description?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// ── value helpers ──────────────────────────────────────────────────────────────────────────────

private fun JsonElement?.asBool(default: Boolean): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: default
private fun JsonElement?.asInt(default: Int): Int = (this as? JsonPrimitive)?.intOrNull ?: default
private fun JsonElement?.asDouble(default: Double): Double = (this as? JsonPrimitive)?.doubleOrNull ?: default
private fun JsonElement?.asString(default: String = ""): String {
    if (this == null || this is JsonNull) return default
    return (this as? JsonPrimitive)?.contentOrNull ?: default
}

private fun categoryTitle(category: String): String = when (category) {
    "generic" -> "General"
    "playback" -> "Playback"
    "announcements" -> "Announcements"
    "player_controls" -> "Player controls"
    "protocol_general" -> "Protocol"
    "protocol_sendspin" -> "Sendspin protocol"
    else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

// ── local styled helpers (mirrors the per-screen pattern in PlayerSettingsScreen/SettingsScreen) ──

@Composable
private fun ConfigSectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ConfigCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun ConfigSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

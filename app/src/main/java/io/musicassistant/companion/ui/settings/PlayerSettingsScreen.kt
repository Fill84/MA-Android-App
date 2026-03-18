package io.musicassistant.companion.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.sendspin.audio.Codec
import io.musicassistant.companion.data.sendspin.audio.Codecs
import io.musicassistant.companion.data.sendspin.audio.codecByName
import io.musicassistant.companion.data.settings.SettingsModule
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsScreen(
        player: Player?,
        onBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsModule.getRepository(context) }
    val settings by settingsRepository.settingsFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val currentSettings = settings ?: return

    val topBarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            .compositeOver(MaterialTheme.colorScheme.background)

    val displayName = player?.name ?: currentSettings.playerName.ifEmpty { "Local Player" }

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text(text = displayName, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = topBarColor
                        ),
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
                )
            },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp).background(
                            Brush.verticalGradient(
                                    listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                            MaterialTheme.colorScheme.background,
                                    )
                            )
                    )
            )
            Column(
                    modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
            ) {
                // PLAYER INFO
                SectionHeader("PLAYER")
                SettingsCard {
                    // Player icon + ID
                    Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                                Icons.Default.Speaker,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                            )
                            if (currentSettings.playerId.isNotEmpty()) {
                                Text(
                                        text = currentSettings.playerId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Player name edit
                    var editName by remember(currentSettings.playerName) {
                        mutableStateOf(currentSettings.playerName)
                    }
                    OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Player name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text("Name shown in Music Assistant")
                            }
                    )
                    // Auto-save on focus loss / value change with debounce
                    if (editName != currentSettings.playerName) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                                onClick = {
                                    scope.launch { settingsRepository.setPlayerName(editName.trim()) }
                                }
                        ) { Text("Save") }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Player enabled toggle
                    SwitchItem(
                            title = "Local player enabled",
                            description = "Register this device as a player in Music Assistant",
                            checked = currentSettings.playerEnabled,
                            onCheckedChange = {
                                scope.launch { settingsRepository.setPlayerEnabled(it) }
                            }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // AUDIO SECTION
                SectionHeader("AUDIO")
                SettingsCard {
                    // Codec preference
                    CodecDropdown(
                            currentCodec = codecByName(currentSettings.codecPreference) ?: Codecs.default,
                            onCodecSelected = { codec ->
                                scope.launch { settingsRepository.setCodecPreference(codec.name) }
                            }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Info about current codec
                    val currentCodec = codecByName(currentSettings.codecPreference) ?: Codecs.default
                    Text(
                            text = currentCodec.uiTitle(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // INFO SECTION
                SectionHeader("INFO")
                SettingsCard {
                    InfoItem(title = "Provider", value = player?.provider ?: "sendspin")
                    InfoItem(title = "Type", value = player?.type ?: "sendspin")
                    if (player != null) {
                        InfoItem(
                                title = "Status",
                                value = when {
                                    !player.available -> "Unavailable"
                                    !player.powered -> "Powered off"
                                    else -> "Available"
                                }
                        )
                        InfoItem(
                                title = "Volume",
                                value = "${player.volumeLevel}%${if (player.volumeMuted) " (muted)" else ""}"
                        )
                        if (player.groupChilds.isNotEmpty()) {
                            InfoItem(
                                    title = "Group members",
                                    value = "${player.groupChilds.size} players"
                            )
                        }
                        if (player.syncedTo != null) {
                            InfoItem(title = "Synced to", value = player.syncedTo)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    ) {
        Box(
                modifier = Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
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
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) { Column(modifier = Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun InfoItem(title: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
        )
        Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchItem(
        title: String,
        description: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
) {
    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
            )
            Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
        )
    }
}

@Composable
private fun CodecDropdown(currentCodec: Codec, onCodecSelected: (Codec) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = "Audio codec",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
            )
            Text(
                    text = "Preferred streaming codec",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))

        OutlinedButton(onClick = { expanded = true }) {
            Text(text = currentCodec.name)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Codecs.list.forEach { codec ->
                DropdownMenuItem(
                        text = {
                            Column {
                                Text(codec.name, fontWeight = FontWeight.Medium)
                                Text(
                                        codec.uiTitle(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onCodecSelected(codec)
                            expanded = false
                        }
                )
            }
        }
    }
}

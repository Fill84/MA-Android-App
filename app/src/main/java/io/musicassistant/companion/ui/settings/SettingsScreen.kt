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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.musicassistant.companion.BuildConfig
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.data.settings.ThemeMode
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsModule.getRepository(context) }
    val settings by settingsRepository.settingsFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val currentSettings = settings ?: return

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text(text = "Settings", fontWeight = FontWeight.Bold) },
                        colors =
                                TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                )
                )
            }
    ) { innerPadding ->
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .padding(innerPadding)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
        ) {
            // CONNECTION SECTION
            SectionHeader("CONNECTION")
            SettingsCard {
                InfoItem(
                        title = "Server",
                        value = currentSettings.serverName.ifEmpty { "Not connected" }
                )
                InfoItem(title = "URL", value = currentSettings.serverUrl.ifEmpty { "-" })

                Spacer(modifier = Modifier.height(12.dp))

                // Login/Logout
                if (currentSettings.authToken.isNotEmpty()) {
                    if (currentSettings.username.isNotEmpty()) {
                        InfoItem(title = "Logged in as", value = currentSettings.username)
                    } else {
                        InfoItem(title = "Status", value = "Logged in")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                            onClick = {
                                scope.launch {
                                    settingsRepository.logout()
                                    ServiceLocator.apiClient.disconnect()
                                    ServiceLocator.apiClient.connect(
                                            currentSettings.serverUrl,
                                            null
                                    )
                                }
                            }
                    ) { Text("Logout") }
                } else {
                    var loginUsername by remember { mutableStateOf(currentSettings.username) }
                    var loginPassword by remember { mutableStateOf("") }
                    var loginError by remember { mutableStateOf<String?>(null) }
                    var isLoggingIn by remember { mutableStateOf(false) }

                    OutlinedTextField(
                            value = loginUsername,
                            onValueChange = { loginUsername = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                            value = loginPassword,
                            onValueChange = { loginPassword = it },
                            label = { Text("Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions =
                                    KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done
                                    )
                    )
                    loginError?.let { msg ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                            onClick = {
                                if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
                                    isLoggingIn = true
                                    loginError = null
                                    scope.launch {
                                        try {
                                            val token =
                                                    withContext(Dispatchers.IO) {
                                                        ServiceLocator.apiClient.login(
                                                                currentSettings.serverUrl,
                                                                loginUsername.trim(),
                                                                loginPassword
                                                        )
                                                    }
                                            settingsRepository.updateServer(
                                                    currentSettings.serverUrl,
                                                    currentSettings.serverName,
                                                    token,
                                                    loginUsername.trim()
                                            )
                                            ServiceLocator.apiClient.disconnect()
                                            ServiceLocator.apiClient.connect(
                                                    currentSettings.serverUrl,
                                                    token
                                            )
                                        } catch (e: Exception) {
                                            loginError = e.message ?: "Login failed"
                                        } finally {
                                            isLoggingIn = false
                                        }
                                    }
                                }
                            },
                            enabled =
                                    loginUsername.isNotBlank() &&
                                            loginPassword.isNotBlank() &&
                                            !isLoggingIn,
                            modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoggingIn) "Logging in..." else "Login")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onBack) { Text("Switch Server") }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // BEHAVIOR SECTION
            SectionHeader("BEHAVIOR")
            SettingsCard {
                SwitchItem(
                        title = "Background playback",
                        description = "Keep playing audio when the app is in the background",
                        checked = currentSettings.backgroundPlaybackEnabled,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setBackgroundPlayback(it) }
                        }
                )
                SwitchItem(
                        title = "Keep screen on",
                        description = "Prevent screen from turning off while playing",
                        checked = currentSettings.keepScreenOn,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setKeepScreenOn(it) }
                        }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // APPEARANCE SECTION
            SectionHeader("APPEARANCE")
            SettingsCard {
                ThemeDropdown(
                        currentMode = currentSettings.themeMode,
                        onModeSelected = { scope.launch { settingsRepository.setThemeMode(it) } }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ABOUT SECTION
            SectionHeader("ABOUT")
            SettingsCard {
                InfoItem(title = "Version", value = BuildConfig.VERSION_NAME)
                InfoItem(
                        title = "Application",
                        value = "Music Assistant Companion v${BuildConfig.VERSION_NAME}"
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
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
            colors =
                    CardDefaults.cardColors(
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
                colors =
                        SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
        )
    }
}

@Composable
private fun ThemeDropdown(currentMode: ThemeMode, onModeSelected: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = "Theme",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
            )
            Text(
                    text = "Choose the app theme",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))

        FilledTonalButton(onClick = { expanded = true }) {
            Text(
                    text =
                            when (currentMode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.DARK -> "Dark"
                                ThemeMode.LIGHT -> "Light"
                            }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                        text = {
                            Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.DARK -> "Dark"
                                        ThemeMode.LIGHT -> "Light"
                                    }
                            )
                        },
                        onClick = {
                            onModeSelected(mode)
                            expanded = false
                        }
                )
            }
        }
    }
}

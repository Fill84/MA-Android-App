package io.musicassistant.companion.ui.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun LauncherScreen(onServerConnected: () -> Unit, viewModel: LauncherViewModel = viewModel()) {
    val context = LocalContext.current
    val launcherState by viewModel.launcherState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    var serverAddress by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // Request notification permission on Android 13+
    val notificationPermissionLauncher =
            rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
            ) { /* Permission result handled by system */}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(launcherState) {
        if (launcherState == LauncherState.CONNECTED) {
            onServerConnected()
        }
    }

    // LOADING or CONNECTING → show loading spinner
    // SETUP or ERROR → show setup form
    // CONNECTED → LaunchedEffect above navigates away
    when (launcherState) {
        LauncherState.LOADING, LauncherState.CONNECTING -> {
            Scaffold { innerPadding ->
                Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                                text = "Music Assistant",
                                style = MaterialTheme.typography.headlineLarge
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                                text = "Connecting...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            return
        }
        LauncherState.CONNECTED -> return // navigation happens via LaunchedEffect
        LauncherState.SETUP, LauncherState.ERROR -> {
            /* fall through to setup UI below */
        }
    }

    Scaffold { innerPadding ->
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .padding(innerPadding)
                                .padding(horizontal = 24.dp)
                                .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Logo & title
            Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Music Assistant", style = MaterialTheme.typography.headlineLarge)
            Text(
                    text = "Companion App",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Reconnect card (if previously configured)
            if (settings.isConfigured && settings.serverUrl.isNotEmpty()) {
                Card(
                        onClick = { viewModel.retryConnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                ) {
                    Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                    text = "Reconnect",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                    text = settings.serverName.ifEmpty { settings.serverUrl },
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.7f
                                            )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Discovered servers section
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        text = "Servers on network",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { viewModel.refreshDiscovery() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }

            if (discoveredServers.isEmpty()) {
                if (isSearching) {
                    Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                                text = "Searching for servers...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                            text = "No servers found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                discoveredServers.forEach { server ->
                    Card(
                            onClick = { viewModel.connectToServer(server.url, server.name) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Cloud, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(server.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                        server.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Manual connection
            Text(
                    text = "Manual connection",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it },
                    label = { Text("Server address") },
                    placeholder = { Text("http://192.168.1.100:8095") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                            KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next
                            )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                            KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                            ),
                    keyboardActions =
                            KeyboardActions(
                                    onDone = {
                                        if (serverAddress.isNotBlank()) {
                                            viewModel.connectWithUrl(
                                                    serverAddress.trim(),
                                                    username.trim(),
                                                    password
                                            )
                                        }
                                    }
                            )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Error message
            errorMessage?.let { msg ->
                Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Connect button
            Button(
                    onClick = {
                        if (serverAddress.isNotBlank()) {
                            viewModel.connectWithUrl(
                                    serverAddress.trim(),
                                    username.trim(),
                                    password
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled =
                            serverAddress.isNotBlank() && launcherState != LauncherState.CONNECTING
            ) {
                if (launcherState == LauncherState.CONNECTING) {
                    CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (launcherState == LauncherState.CONNECTING) "Connecting..." else "Connect")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

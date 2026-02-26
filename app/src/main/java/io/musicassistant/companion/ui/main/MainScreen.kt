package io.musicassistant.companion.ui.main

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.media.MediaSessionManager
import io.musicassistant.companion.ui.webview.WebViewScreen
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onSwitchServer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsModule.getRepository(context) }
    val settings by settingsRepository.settingsFlow.collectAsState(initial = null)

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                serverName = settings?.serverName ?: "",
                serverUrl = settings?.serverUrl ?: "",
                nowPlayingTitle = "", // Will be populated from MediaSessionManager state
                nowPlayingArtist = "",
                isPlaying = MediaSessionManager.isPlaying,
                onHomeClick = {
                    scope.launch { drawerState.close() }
                },
                onSwitchServer = {
                    scope.launch { drawerState.close() }
                    onSwitchServer()
                },
                onSettingsClick = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                }
            )
        },
        gesturesEnabled = true
    ) {
        WebViewScreen(
            onDisconnect = onSwitchServer
        )
    }
}

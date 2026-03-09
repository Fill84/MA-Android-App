package io.musicassistant.companion.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.media.MediaSessionManager
import io.musicassistant.companion.ui.webview.WebViewScreen
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun MainScreen(onSwitchServer: () -> Unit, onOpenSettings: () -> Unit) {
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
                        nowPlayingTitle = MediaSessionManager.title,
                        nowPlayingArtist = MediaSessionManager.artist,
                        isPlaying = MediaSessionManager.isPlaying,
                        onHomeClick = { scope.launch { drawerState.close() } },
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
            gesturesEnabled =
                    drawerState.isOpen // Only enable built-in gestures when open (for closing)
    ) {
        Box(modifier = Modifier.fillMaxSize().edgeSwipeToOpen(drawerState, scope)) {
            WebViewScreen(onDisconnect = onSwitchServer)
        }
    }
}

/**
 * Custom edge swipe detector that opens the drawer only on horizontal swipes starting from the left
 * edge of the screen. Vertical swipes are not intercepted, allowing the WebView to scroll normally.
 */
private fun Modifier.edgeSwipeToOpen(drawerState: DrawerState, scope: CoroutineScope): Modifier =
        pointerInput(Unit) {
            val edgeWidth = 24.dp.toPx()
            val swipeThreshold = 48.dp.toPx()
            val maxAngleDegrees = 25f // Max deviation from horizontal

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                // Only handle touches starting from the left edge when drawer is closed
                if (down.position.x > edgeWidth || drawerState.isOpen) return@awaitEachGesture

                var totalX = 0f
                var totalY = 0f
                var decided = false

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break

                    val delta = change.positionChange()
                    totalX += delta.x
                    totalY += delta.y

                    if (!decided) {
                        val distance = sqrt(totalX * totalX + totalY * totalY)
                        if (distance > viewConfiguration.touchSlop) {
                            val angle =
                                    atan2(abs(totalY).toDouble(), abs(totalX).toDouble()) * 180.0 /
                                            PI
                            if (totalX > 0 && angle < maxAngleDegrees) {
                                decided = true
                                // Fall through to consume this event
                            } else {
                                break // Not horizontal enough, let WebView handle it
                            }
                        } else {
                            continue // Not enough movement yet
                        }
                    }

                    // Horizontal edge swipe confirmed - consume events
                    change.consume()
                    if (totalX > swipeThreshold) {
                        scope.launch { drawerState.open() }
                        break
                    }
                }
            }
        }

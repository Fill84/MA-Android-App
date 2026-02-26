package io.musicassistant.companion.ui.webview

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.service.PlayerService

@Composable
fun WebViewScreen(
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsModule.getRepository(context) }
    val settings by settingsRepository.settingsFlow.collectAsState(initial = null)
    val isDarkTheme = isSystemInDarkTheme()

    var settingsLoaded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(WebViewHolder.webView == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var rendererVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(settings) {
        if (settings != null) settingsLoaded = true
    }

    val serverUrl = settings?.serverUrl ?: ""
    val keepScreenOn = settings?.keepScreenOn ?: false
    val serverHost = remember(serverUrl) {
        try { java.net.URL(serverUrl).host } catch (_: Exception) { "" }
    }

    // Keep screen on setting
    LaunchedEffect(keepScreenOn) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        if (keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Handle back button
    BackHandler(enabled = true) {
        val wv = WebViewHolder.webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    if (!settingsLoaded) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (serverUrl.isEmpty()) {
        onDisconnect()
        return
    }

    // Start foreground service
    LaunchedEffect(serverUrl) {
        val intent = Intent(context, PlayerService::class.java).apply {
            action = PlayerService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Connection Lost",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    errorMessage = null
                    isLoading = true
                    WebViewHolder.webView?.loadUrl(serverUrl)
                }) {
                    Text("Retry")
                }
            }
        } else {
            @Suppress("UNUSED_EXPRESSION")
            rendererVersion // Force recomposition after renderer crash

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebViewHolder.getOrCreate(
                        context = ctx,
                        serverUrl = serverUrl,
                        serverHost = serverHost,
                        isDarkTheme = isDarkTheme,
                        onPageLoaded = { isLoading = false },
                        onError = { msg -> errorMessage = msg },
                        onRendererGone = {
                            errorMessage = "WebView renderer stopped"
                            rendererVersion++
                        }
                    )
                },
                update = { /* WebView handles its own state */ }
            )

            // Loading overlay - prevents seeing dark WebView background
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            WebViewHolder.detach()
        }
    }
}

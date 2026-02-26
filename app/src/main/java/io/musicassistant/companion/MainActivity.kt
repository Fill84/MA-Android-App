package io.musicassistant.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.data.settings.ThemeMode
import io.musicassistant.companion.ui.navigation.AppNavigation
import io.musicassistant.companion.ui.theme.MaCompanionTheme
import io.musicassistant.companion.ui.webview.WebViewHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settingsRepository = remember { SettingsModule.getRepository(context) }
            val settings by settingsRepository.settingsFlow.collectAsState(initial = null)
            val themeMode = settings?.themeMode ?: ThemeMode.DARK

            MaCompanionTheme(themeMode = themeMode) {
                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure WebView timers are running when returning to the app
        WebViewHolder.webView?.resumeTimers()
    }
}

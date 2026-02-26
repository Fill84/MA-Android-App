package io.musicassistant.companion.ui.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.musicassistant.companion.media.MEDIA_BRIDGE_SCRIPT
import io.musicassistant.companion.media.MediaSessionManager

/**
 * JavaScript interface that receives playback state and metadata
 * from the MA web frontend's Sendspin player and forwards to native MediaSession.
 *
 * Registered as `__MA_ANDROID__` — this name is NOT checked by the MA frontend
 * (unlike `__COMPANION__` which disables Sendspin entirely).
 */
private class MaAndroidBridge {

    companion object {
        private const val TAG = "MaAndroidBridge"
    }

    @JavascriptInterface
    fun onPlaybackStateChanged(state: String) {
        val playing = state == "playing"
        Log.d(TAG, "Playback state: $state (playing=$playing)")
        MediaSessionManager.updatePlaybackState(playing)
    }

    @JavascriptInterface
    fun onMetadataChanged(title: String, artist: String, album: String, artworkUrl: String) {
        Log.d(TAG, "Metadata: $title - $artist ($album)")
        MediaSessionManager.updateMetadata(title, artist, album, artworkUrl)
    }
}

/**
 * Application-scoped singleton that keeps the WebView alive independently of the
 * Activity lifecycle. Uses MutableContextWrapper for Activity↔Application context swap.
 *
 * The WebView loads the MA frontend and runs the Sendspin web player for audio.
 * The JS bridge forwards playback state and metadata to the native MediaSession
 * for notification, lock screen, and Bluetooth media controls.
 */
@SuppressLint("SetJavaScriptEnabled")
object WebViewHolder {

    private const val TAG = "WebViewHolder"

    var webView: WebView? = null
        private set

    private var contextWrapper: MutableContextWrapper? = null
    private var currentUrl: String? = null

    /**
     * Stable user agent: no `; wv` suffix (allows Sendspin web player to activate),
     * fixed Chrome version (keeps player ID stable across WebView updates).
     */
    private fun buildStableUserAgent(): String {
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE
        return "Mozilla/5.0 (Linux; Android $androidVersion; $model) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    fun getOrCreate(
        context: Context,
        serverUrl: String,
        serverHost: String,
        isDarkTheme: Boolean,
        onPageLoaded: () -> Unit,
        onError: (String) -> Unit,
        onRendererGone: () -> Unit
    ): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            contextWrapper?.setBaseContext(context)
            applyDarkTheme(existing, isDarkTheme)
            return existing
        }

        val wrapper = MutableContextWrapper(context)
        contextWrapper = wrapper

        return WebView(wrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.allowContentAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = buildStableUserAgent()

            applyDarkTheme(this, isDarkTheme)

            setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false
            )

            addJavascriptInterface(MaAndroidBridge(), "__MA_ANDROID__")

            webChromeClient = MaWebChromeClient()

            webViewClient = MaWebViewClient(
                serverHost = serverHost,
                onPageLoaded = onPageLoaded,
                onError = onError,
                onRendererGone = {
                    webView = null
                    contextWrapper = null
                    currentUrl = null
                    onRendererGone()
                }
            )

            loadUrl(serverUrl)
            currentUrl = serverUrl

            webView = this
        }
    }

    /** Detach WebView from Activity and swap to application context */
    fun detach() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        contextWrapper?.setBaseContext(wv.context.applicationContext)
    }

    private fun applyDarkTheme(wv: WebView, isDark: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, isDark)
        }
    }

    fun destroy() {
        webView?.destroy()
        webView = null
        contextWrapper = null
        currentUrl = null
    }
}

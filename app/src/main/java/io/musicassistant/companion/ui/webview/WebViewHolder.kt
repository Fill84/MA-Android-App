package io.musicassistant.companion.ui.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.musicassistant.companion.media.MEDIA_BRIDGE_SCRIPT
import io.musicassistant.companion.media.MediaSessionManager

/**
 * JavaScript interface that receives playback state and metadata from the MA web frontend's
 * Sendspin player and forwards to native MediaSession.
 *
 * Registered as `__MA_ANDROID__` — this name is NOT checked by the MA frontend (unlike
 * `__COMPANION__` which disables Sendspin entirely).
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

    @JavascriptInterface
    fun onPositionStateChanged(durationSec: Double, positionSec: Double, playbackRate: Double) {
        val durationMs = (durationSec * 1000).toLong()
        val positionMs = (positionSec * 1000).toLong()
        Log.v(TAG, "Position: ${positionSec.toInt()}s / ${durationSec.toInt()}s @ ${playbackRate}x")
        MediaSessionManager.updatePositionState(durationMs, positionMs, playbackRate.toFloat())
    }
}

/**
 * Application-scoped singleton that keeps the WebView alive independently of the Activity
 * lifecycle. Uses MutableContextWrapper for Activity↔Application context swap.
 *
 * The WebView loads the MA frontend and runs the Sendspin web player for audio. The JS bridge
 * forwards playback state and metadata to the native MediaSession for notification, lock screen,
 * and Bluetooth media controls.
 */
@SuppressLint("SetJavaScriptEnabled")
object WebViewHolder {

    private const val TAG = "WebViewHolder"

    var webView: WebView? = null
        private set

    private var contextWrapper: MutableContextWrapper? = null
    private var currentUrl: String? = null

    /**
     * Stable user agent: no `; wv` suffix (allows Sendspin web player to activate), fixed Chrome
     * version (keeps player ID stable across WebView updates).
     */
    private fun buildStableUserAgent(): String {
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE
        val buildId = Build.ID
        return "Mozilla/5.0 (Linux; Android $androidVersion; $model Build/$buildId) " +
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
            layoutParams =
                    ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )

            configureWebView(this)
            applyDarkTheme(this, isDarkTheme)

            webChromeClient = MaWebChromeClient()

            webViewClient =
                    MaWebViewClient(
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

    /**
     * Ensures the WebView is alive and JS timers are running. Creates a headless WebView if needed
     * (for service-initiated restarts after process kill).
     */
    fun ensureAlive(context: Context, serverUrl: String, serverHost: String) {
        if (webView != null) {
            webView?.resumeTimers()
            return
        }

        Log.i(TAG, "Recreating WebView headlessly for background operation")
        val appContext = context.applicationContext
        val wrapper = MutableContextWrapper(appContext)
        contextWrapper = wrapper

        webView =
                WebView(wrapper).apply {
                    configureWebView(this)
                    webChromeClient = MaWebChromeClient()
                    webViewClient =
                            MaWebViewClient(
                                    serverHost = serverHost,
                                    onPageLoaded = { Log.i(TAG, "Headless WebView page loaded") },
                                    onError = { msg -> Log.w(TAG, "Headless WebView error: $msg") },
                                    onRendererGone = {
                                        webView = null
                                        contextWrapper = null
                                        currentUrl = null
                                        Log.w(TAG, "Headless WebView renderer gone")
                                    }
                            )

                    loadUrl(serverUrl)
                    currentUrl = serverUrl
                }
    }

    /** Common WebView settings, renderer priority, JS bridge, and document-start script. */
    private fun configureWebView(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.allowContentAccess = true
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.userAgentString = buildStableUserAgent()

        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        wv.addJavascriptInterface(MaAndroidBridge(), "__MA_ANDROID__")
        injectBridgeAtDocumentStart(wv)
    }

    /**
     * Injects the media bridge script at document start (before any page scripts). This ensures we
     * intercept navigator.mediaSession before Sendspin initializes, capturing all metadata,
     * playback state, and action handlers immediately. Falls back to onPageFinished injection on
     * older WebViews.
     */
    private fun injectBridgeAtDocumentStart(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, MEDIA_BRIDGE_SCRIPT, setOf("*"))
            Log.i(TAG, "Bridge script registered at document start")
        } else {
            Log.i(TAG, "DOCUMENT_START_SCRIPT not supported, falling back to onPageFinished")
        }
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

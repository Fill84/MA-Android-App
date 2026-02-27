package io.musicassistant.companion.ui.webview

import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.musicassistant.companion.media.MEDIA_BRIDGE_SCRIPT

class MaWebViewClient(
    private val serverHost: String,
    private val onPageLoaded: () -> Unit,
    private val onError: (String) -> Unit,
    private val onRendererGone: (() -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "MaWebViewClient"
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // Fallback: inject bridge after page load for older WebViews that don't
        // support DOCUMENT_START_SCRIPT. The script checks __ma_bridge_initialized
        // and skips if the early injection already ran.
        view?.evaluateJavascript(MEDIA_BRIDGE_SCRIPT, null)
        onPageLoaded()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onError("Connection error: ${error?.description}")
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        if (detail?.didCrash() == true) {
            Log.e(TAG, "WebView renderer crashed")
        } else {
            Log.w(TAG, "WebView renderer killed by system to reclaim memory")
        }

        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }

        onRendererGone?.invoke()
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        // Keep MA server URLs in the WebView, open external links in system browser
        if (url.contains(serverHost)) return false
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
            view?.context?.startActivity(intent)
        } catch (_: Exception) {}
        return true
    }
}

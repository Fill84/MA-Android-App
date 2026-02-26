package io.musicassistant.companion.ui.webview

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient

class MaWebChromeClient : WebChromeClient() {

    companion object {
        private const val TAG = "MaWebChromeClient"
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        // Grant all permissions — the WebView only loads the trusted MA server
        request?.grant(request.resources)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        consoleMessage?.let {
            Log.d(TAG, "${it.sourceId()}:${it.lineNumber()} ${it.message()}")
        }
        return true
    }
}

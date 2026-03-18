package io.musicassistant.companion.data.sendspin

interface MediaPlayerListener {
    fun onReady()
    fun onAudioCompleted()
    fun onError(error: Throwable? = null)
}

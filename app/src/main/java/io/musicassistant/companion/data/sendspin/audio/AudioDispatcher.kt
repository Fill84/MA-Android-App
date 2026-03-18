package io.musicassistant.companion.data.sendspin.audio

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * High-priority audio dispatcher for the playback consumer thread.
 * Uses THREAD_PRIORITY_AUDIO for smooth playback even when app is backgrounded.
 *
 * ONLY used for the playback loop — reading PCM from buffer and writing to AudioTrack.
 * All other operations (binary message processing, monitoring, commands) use Dispatchers.Default.
 */
val audioDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        runnable.run()
    }, "AudioThread-${System.currentTimeMillis()}").apply {
        priority = Thread.MAX_PRIORITY
        setUncaughtExceptionHandler { thread, exception ->
            android.util.Log.e("AudioDispatcher", "Uncaught exception in $thread", exception)
        }
    }
}.asCoroutineDispatcher()

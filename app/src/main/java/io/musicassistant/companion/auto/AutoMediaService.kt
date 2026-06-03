package io.musicassistant.companion.auto

import android.content.Intent
import android.util.Log
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import io.musicassistant.companion.service.MusicService
import io.musicassistant.companion.service.ServiceLocator

/**
 * MediaLibraryService for Android Auto integration.
 *
 * Returns the shared MediaLibrarySession from ServiceLocator's MediaSessionHost so Android
 * Auto can browse the MA library and control playback. MusicService remains responsible for
 * connections, Sendspin audio, and notification management.
 */
class AutoMediaService : MediaLibraryService() {

    companion object {
        private const val TAG = "AutoMediaService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AutoMediaService created")

        // Ensure MusicService is running for API connections and audio
        val intent = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_START
        }
        startService(intent)

        // Give the browse callback an app context so it can read settings.
        ServiceLocator.getOrCreateAutoBrowseCallback().appContext = applicationContext
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return ServiceLocator.getMediaSessionHost(this).session
    }

    override fun onDestroy() {
        Log.d(TAG, "AutoMediaService destroyed — keeping session alive for MusicService")
        // Do NOT release the session — MusicService still uses it for notifications
        super.onDestroy()
    }
}

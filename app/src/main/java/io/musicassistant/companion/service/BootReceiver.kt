package io.musicassistant.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.musicassistant.companion.data.settings.SettingsModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Starts the MusicService when the device boots, so the app stays connected to Music Assistant in
 * the background.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Only start if the user has configured a server
        val settings =
                try {
                    runBlocking { SettingsModule.getRepository(context).settingsFlow.first() }
                } catch (_: Exception) {
                    return
                }
        if (!settings.isConfigured || settings.serverUrl.isEmpty()) return

        Log.d("BootReceiver", "Boot completed – starting MusicService")
        val serviceIntent =
                Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_START
                }
        context.startForegroundService(serviceIntent)
    }
}

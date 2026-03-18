package io.musicassistant.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.musicassistant.companion.data.settings.SettingsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the MusicService when the device boots, so the app stays connected to Music Assistant in
 * the background.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsModule.getRepository(context).settingsFlow.first()
                if (settings.isConfigured && settings.serverUrl.isNotEmpty()) {
                    Log.d("BootReceiver", "Boot completed – starting MusicService")
                    val serviceIntent =
                            Intent(context, MusicService::class.java).apply {
                                action = MusicService.ACTION_START
                            }
                    context.startForegroundService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to check settings: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}

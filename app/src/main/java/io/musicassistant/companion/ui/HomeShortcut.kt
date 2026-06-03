package io.musicassistant.companion.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.musicassistant.companion.MainActivity
import io.musicassistant.companion.R

/**
 * Adds a Music Assistant icon to the device home screen.
 *
 * On Android 8.0+ an app can no longer silently drop a launcher icon on the home screen (the old
 * INSTALL_SHORTCUT broadcast was removed). The sanctioned API is [ShortcutManagerCompat.requestPinShortcut],
 * which asks the launcher to show its "Add to Home screen" confirmation (some launchers add it
 * directly). The app icon is always present in the app drawer regardless.
 */
object HomeShortcut {

    private const val TAG = "HomeShortcut"
    private const val PREFS = "ma_prefs"
    private const val KEY_REQUESTED = "home_shortcut_requested"
    private const val SHORTCUT_ID = "ma_home"

    fun isSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Ask the launcher to pin a home-screen shortcut. Returns false if it can't be requested. */
    fun request(context: Context): Boolean {
        if (!isSupported(context)) {
            Log.w(TAG, "Launcher does not support pinning shortcuts")
            return false
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.app_name))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        return try {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        } catch (e: Exception) {
            Log.e(TAG, "requestPinShortcut failed: ${e.message}")
            false
        }
    }

    /** Request the pin once (first launch), guarded by a prefs flag so it never nags. */
    fun requestOnceOnFirstLaunch(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REQUESTED, false)) return
        if (isSupported(context)) {
            val requested = request(context)
            if (requested) prefs.edit().putBoolean(KEY_REQUESTED, true).apply()
        }
    }
}

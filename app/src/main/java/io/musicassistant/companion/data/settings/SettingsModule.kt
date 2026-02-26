package io.musicassistant.companion.data.settings

import android.content.Context

object SettingsModule {
    @Volatile
    private var repository: SettingsRepository? = null

    fun getRepository(context: Context): SettingsRepository {
        return repository ?: synchronized(this) {
            repository ?: SettingsRepository(context.applicationContext).also {
                repository = it
            }
        }
    }
}

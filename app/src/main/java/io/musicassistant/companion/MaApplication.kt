package io.musicassistant.companion

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.musicassistant.companion.service.ServiceLocator
import okhttp3.OkHttpClient

/**
 * Application class that configures Coil image loading with authentication headers so that images
 * from the MA server can be loaded when auth is required.
 */
class MaApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val okHttpClient =
                OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val token = ServiceLocator.apiClient.currentAuthToken
                            val request =
                                    if (!token.isNullOrEmpty()) {
                                        chain.request()
                                                .newBuilder()
                                                .addHeader("Authorization", "Bearer $token")
                                                .build()
                                    } else {
                                        chain.request()
                                    }
                            chain.proceed(request)
                        }
                        .build()

        return ImageLoader.Builder(this).okHttpClient(okHttpClient).crossfade(true).build()
    }
}

package io.musicassistant.companion.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for album artwork bytes.
 *
 * fetch(url) -> cache hit? yes: return bytes. No: download -> decode -> resize to [maxImagePx] -> encode JPEG -> cache.
 *
 * Retry policy: up to [maxAttempts] attempts. Backoff 1s between attempt 1 and 2, 2s between 2 and 3.
 * HTTP 4xx = no retry, immediate null. IO/5xx/timeout = retry.
 *
 * Decoding may fail on JVM unit tests where Android's BitmapFactory stubs return null; in that case
 * we fall back to returning the raw bytes if they look like a valid image (magic-byte check).
 */
class ArtworkPipeline(
    baseHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxImagePx: Int = 300,
    private val jpegQuality: Int = 90,
    private val maxAttempts: Int = 3,
    private val timeoutMs: Long = 5_000,
    cacheSize: Int = 10,
    /** Supplies the MA auth token for same-origin imageproxy URLs. Default: no auth. */
    private val authTokenProvider: () -> String? = { null }
) {
    private val client: OkHttpClient = baseHttpClient.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    // Pure-JVM access-ordered LRU (not android.util.LruCache) so the pipeline is
    // unit-testable on the JVM. Access is guarded by `synchronized(cache)` because
    // fetch() runs on [ioDispatcher] while cachedOrNull() may be read from the main thread.
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > cacheSize
    }

    fun cachedOrNull(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        val key = normalize(url)
        return synchronized(cache) { cache[key] }
    }

    suspend fun fetch(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        val key = normalize(url)
        synchronized(cache) { cache[key] }?.let { return it }
        val downloaded = download(url) ?: return null
        val encoded = transcode(downloaded) ?: if (looksLikeImage(downloaded)) downloaded else return null
        synchronized(cache) { cache[key] = encoded }
        return encoded
    }

    fun evictAll() = synchronized(cache) { cache.clear() }

    private suspend fun download(url: String): ByteArray? = withContext(ioDispatcher) {
        var attempt = 0
        val token = authTokenProvider()?.takeIf { it.isNotBlank() }
        while (attempt < maxAttempts) {
            attempt++
            val request = Request.Builder().url(url)
                .apply { if (token != null) addHeader("Authorization", "Bearer $token") }
                .build()
            val outcome = runCatching {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body?.bytes()
                        response.code in 400..499 -> {
                            Log.w(TAG, "download $url HTTP ${response.code} (no retry)")
                            return@withContext null
                        }
                        else -> {
                            Log.w(TAG, "download $url HTTP ${response.code} (will retry)")
                            null
                        }
                    }
                }
            }
            val bytes = outcome.getOrNull()
            if (outcome.isSuccess && bytes != null && bytes.isNotEmpty()) return@withContext bytes
            if (outcome.isFailure) {
                Log.w(TAG, "download attempt $attempt failed: ${outcome.exceptionOrNull()?.message}")
            }
            if (attempt < maxAttempts) delay(backoffMs(attempt))
        }
        null
    }

    private fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        else -> 2_000L
    }

    private fun transcode(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        val scale = if (longest > maxImagePx) maxImagePx.toFloat() / longest else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    /**
     * Magic-byte check used when running on the JVM (no Android Bitmap decoder).
     * Accepts PNG (89 50 4E 47), JPEG (FF D8), and WebP ("RIFF" + later "WEBP").
     */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) return true
        return false
    }

    private fun normalize(url: String): String = url.trim()

    companion object {
        private const val TAG = "ArtworkPipeline"
    }
}

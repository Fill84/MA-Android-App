package io.musicassistant.companion.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Direct AudioTrack wrapper for Sendspin PCM streaming.
 * Writes audio data directly to hardware — no buffering, no decoding overhead.
 * Thread-safe: write() can be called from any thread (including the WebSocket thread).
 *
 * Uses minimal buffer for low latency — audio comes out almost instantly.
 */
class StreamAudioPlayer {

    companion object {
        private const val TAG = "StreamAudioPlayer"
    }

    private var audioTrack: AudioTrack? = null

    @Volatile
    var isActive = false
        private set

    /**
     * Configure and start the AudioTrack for the given format.
     * Releases any previous AudioTrack first.
     */
    fun configure(sampleRate: Int, channels: Int, bitDepth: Int) {
        release()

        val channelConfig = if (channels == 1)
                AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val encoding = when (bitDepth) {
            24, 32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        // Small buffer for low latency — just enough to prevent underruns
        val bufferSize = minBuffer * 4

        val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

        val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build()

        audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        audioTrack?.play()
        isActive = true
        Log.d(TAG, "Configured: rate=$sampleRate ch=$channels bits=$bitDepth buf=$bufferSize minBuf=$minBuffer")
    }

    /** Write audio data directly to AudioTrack. Thread-safe, may block briefly if buffer full. */
    fun write(data: ByteArray, offset: Int, length: Int) {
        if (!isActive) return
        try {
            audioTrack?.write(data, offset, length)
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}")
        }
    }

    /** Pause playback immediately and clear buffer — instant silence. */
    fun pause() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
    }

    /** Resume playback after pause. */
    fun resume() {
        if (isActive) {
            try {
                audioTrack?.play()
            } catch (_: Exception) {}
        }
    }

    /** Flush the AudioTrack buffer (used on stream/clear for track changes). */
    fun flush() {
        try {
            audioTrack?.flush()
        } catch (_: Exception) {}
    }

    /** Stop playback and clear buffer. */
    fun stop() {
        isActive = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: Exception) {}
    }

    /** Stop and release the AudioTrack. */
    fun release() {
        isActive = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }
}

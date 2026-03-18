package io.musicassistant.companion.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import io.musicassistant.companion.data.sendspin.MediaPlayerListener
import io.musicassistant.companion.data.sendspin.model.AudioCodec

/**
 * Direct AudioTrack wrapper for Sendspin PCM streaming.
 * Ported from mobile-app's MediaPlayerController.android.kt.
 *
 * Handles:
 * - Raw PCM streaming via AudioTrack (16/24/32-bit)
 * - Audio focus management (Android Auto, phone calls, etc.)
 * - Headphone disconnection detection
 * - Volume/mute control (0-100 scale)
 */
class StreamAudioPlayer(context: Context) {

    companion object {
        private const val TAG = "StreamAudioPlayer"
    }

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioTrack: AudioTrack? = null
    private var audioTrackCreationTime: Long = 0
    private var currentListener: MediaPlayerListener? = null

    // Audio focus
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // Playback state
    private var shouldPlayAudio = false

    // Volume state (0-100)
    private var currentVolume: Int = 100
    private var isMuted: Boolean = false

    // Legacy compatibility
    @Volatile
    var isActive = false
        private set

    // Noisy audio receiver (headphone unplug)
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.w(TAG, "Audio becoming noisy (headphones unplugged) - stopping playback")
                handleAudioOutputDisconnected()
            }
        }
    }
    private var isNoisyReceiverRegistered = false

    // Audio focus listener
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "AudioFocus gained")
                hasAudioFocus = true
                shouldPlayAudio = true
                audioTrack?.let { track ->
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.flush()
                        track.play()
                    }
                }
                applyVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "AudioFocus lost permanently")
                hasAudioFocus = false
                handleAudioOutputDisconnected()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val timeSinceCreation = System.currentTimeMillis() - audioTrackCreationTime
                if (timeSinceCreation < 1000) {
                    Log.i(TAG, "AudioFocus lost temporarily, ignoring (track just created ${timeSinceCreation}ms ago)")
                    return@OnAudioFocusChangeListener
                }
                Log.i(TAG, "AudioFocus lost temporarily")
                hasAudioFocus = false
                shouldPlayAudio = false
                audioTrack?.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "AudioFocus lost temporarily (can duck)")
                audioTrack?.setVolume(0.2f)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun releaseAudioFocus() {
        if (!hasAudioFocus) return
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
        audioFocusRequest = null
    }

    private fun handleAudioOutputDisconnected() {
        shouldPlayAudio = false
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing AudioTrack on disconnection", e)
            }
        }
        releaseAudioFocus()
        currentListener?.onError(Exception("Audio output disconnected"))
    }

    /**
     * Prepare and start audio stream. Creates AudioTrack with proper encoding.
     * This is the new API replacing the old configure() method.
     */
    @Suppress("UNUSED_PARAMETER")
    fun prepareStream(
        codec: AudioCodec,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        listener: MediaPlayerListener
    ) {
        Log.i(TAG, "Preparing stream: ${sampleRate}Hz, ${channels}ch, ${bitDepth}bit, codec=$codec")

        currentListener = listener

        if (!requestAudioFocus()) {
            Log.w(TAG, "Failed to gain audio focus, continuing anyway")
        }

        registerNoisyAudioReceiver()

        // Release existing AudioTrack
        audioTrack?.release()

        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Log.w(TAG, "Unsupported channel count: $channels, using stereo")
                AudioFormat.CHANNEL_OUT_STEREO
            }
        }

        val encoding = when {
            bitDepth == 8 -> AudioFormat.ENCODING_PCM_8BIT
            bitDepth == 16 -> AudioFormat.ENCODING_PCM_16BIT
            bitDepth == 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            bitDepth == 32 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> AudioFormat.ENCODING_PCM_32BIT
            else -> {
                Log.w(TAG, "Unsupported bit depth: $bitDepth, using 16-bit")
                AudioFormat.ENCODING_PCM_16BIT
            }
        }

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = minBufferSize * 16

        Log.i(TAG, "AudioTrack buffer: $bufferSize bytes (min: $minBufferSize)")

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrackCreationTime = System.currentTimeMillis()
            audioTrack?.play()
            shouldPlayAudio = true
            isActive = true
            applyVolume()
            listener.onReady()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            listener.onError(e)
        }
    }

    /**
     * Write raw PCM data to AudioTrack. Returns bytes written.
     * Blocks until hardware accepts the data (this IS the playback clock).
     */
    fun writeRawPcm(data: ByteArray): Int {
        val track = audioTrack ?: return 0

        if (!shouldPlayAudio) {
            // Return full size to prevent buffer backup
            return data.size
        }

        return try {
            val written = track.write(data, 0, data.size)
            if (written < 0) {
                Log.w(TAG, "AudioTrack write error: $written")
                0
            } else {
                written
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PCM data", e)
            0
        }
    }

    /** Lightweight pause without destroying AudioTrack. */
    fun pauseSink() {
        audioTrack?.pause()
    }

    /** Resume after pauseSink. */
    fun resumeSink() {
        if (shouldPlayAudio) audioTrack?.play()
    }

    /** Flush AudioTrack buffer. */
    fun flush() {
        try {
            audioTrack?.flush()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack flush failed", e)
        }
    }

    /** Stop and release AudioTrack. */
    fun stopRawPcmStream() {
        Log.i(TAG, "Stopping raw PCM stream")
        shouldPlayAudio = false
        isActive = false
        currentListener = null

        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack", e)
            }
        }
        audioTrack = null
    }

    fun setVolume(volume: Int) {
        currentVolume = volume.coerceIn(0, 100)
        applyVolume()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        applyVolume()
    }

    fun getCurrentSystemVolume(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (current * 100 / max).coerceIn(0, 100) else 0
    }

    private fun applyVolume() {
        val track = audioTrack ?: return
        val volumeFloat = if (isMuted) 0f else (currentVolume / 100f).coerceIn(0f, 1f)
        try {
            track.setVolume(volumeFloat)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }

    private fun registerNoisyAudioReceiver() {
        if (!isNoisyReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            appContext.registerReceiver(noisyAudioReceiver, filter)
            isNoisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyAudioReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                appContext.unregisterReceiver(noisyAudioReceiver)
                isNoisyReceiverRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering noisy audio receiver", e)
            }
        }
    }

    /** Full release — called when service stops. */
    fun release() {
        unregisterNoisyAudioReceiver()
        stopRawPcmStream()
        releaseAudioFocus()
    }

}

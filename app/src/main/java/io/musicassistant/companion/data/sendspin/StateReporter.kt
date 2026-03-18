package io.musicassistant.companion.data.sendspin

import android.util.Log
import io.musicassistant.companion.data.sendspin.model.PlayerStateObject
import io.musicassistant.companion.data.sendspin.model.PlayerStateValue
import io.musicassistant.companion.data.sendspin.protocol.MessageDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Handles periodic state reporting to the Sendspin server.
 * Reports player state (SYNCHRONIZED), volume, and mute status every 2 seconds.
 */
class StateReporter(
    private val messageDispatcher: MessageDispatcher,
    private val volumeProvider: () -> Int,
    private val mutedProvider: () -> Boolean,
    private val stateProvider: () -> SendspinState
) : CoroutineScope {

    companion object {
        private const val TAG = "StateReporter"
    }

    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + supervisorJob

    private var reportingJob: Job? = null

    /** Start periodic state reporting (every 2 seconds). */
    fun start() {
        Log.i(TAG, "Starting periodic state reporting")
        reportingJob?.cancel()
        reportingJob = launch {
            while (isActive) {
                try {
                    delay(2000)

                    when (stateProvider()) {
                        is SendspinState.Synchronized,
                        is SendspinState.Buffering -> {
                            reportNow(PlayerStateValue.SYNCHRONIZED)
                        }
                        else -> { /* Not streaming — don't report */ }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in state reporting", e)
                }
            }
        }
    }

    /** Stop periodic state reporting. */
    fun stop() {
        Log.i(TAG, "Stopping periodic state reporting")
        reportingJob?.cancel()
        reportingJob = null
    }

    /** Send immediate state report to server (event-driven). */
    suspend fun reportNow(state: PlayerStateValue) {
        val volume = volumeProvider()
        val muted = mutedProvider()

        val playerState = PlayerStateObject(
            state = state,
            volume = volume,
            muted = muted
        )

        Log.d(TAG, "Reporting state: state=$state, volume=$volume, muted=$muted")
        messageDispatcher.sendState(playerState)
    }

    fun close() {
        stop()
        supervisorJob.cancel()
    }
}

@file:OptIn(ExperimentalTime::class)

package io.musicassistant.companion.data.sendspin

import kotlin.math.abs
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

enum class SyncQuality {
    GOOD,
    DEGRADED,
    LOST
}

data class ClockStats(
    val offset: Long,
    val rtt: Long,
    val quality: SyncQuality
)

class ClockSynchronizer {
    // Shared monotonic time base — created once so MessageDispatcher and AudioStreamManager
    // both read from the same epoch.
    private val startMark = TimeSource.Monotonic.markNow()

    fun getCurrentTimeMicros(): Long = startMark.elapsedNow().inWholeMicroseconds

    // Clock synchronization state
    private var offset: Long = 0 // μs (server - client)
    private var drift: Double = 0.0 // μs/μs
    private var rawOffset: Long = 0
    private var rtt: Long = 0
    private var quality: SyncQuality = SyncQuality.LOST
    private var lastSyncTimeMs: Long = 0
    private var lastSyncMicros: Long = 0
    private var sampleCount: Int = 0
    private val smoothingRate: Double = 0.1

    // Server loop origin tracking - use monotonic time base
    private var serverLoopOriginLocal: Long = 0

    val currentOffset: Long get() = offset
    val currentQuality: SyncQuality get() = quality

    fun getStats() = ClockStats(offset, rtt, quality)

    fun processServerTime(
        clientTransmitted: Long, // t1
        serverReceived: Long, // t2
        serverTransmitted: Long, // t3
        clientReceived: Long // t4
    ) {
        val (calculatedRtt, measuredOffset) = calculateOffset(
            clientTransmitted, serverReceived,
            serverTransmitted, clientReceived
        )

        rtt = calculatedRtt
        rawOffset = measuredOffset
        lastSyncTimeMs = System.currentTimeMillis()

        // Discard invalid samples
        if (calculatedRtt !in 0..100_000) return

        // First sync: initialize
        if (sampleCount == 0) {
            offset = measuredOffset
            lastSyncMicros = clientReceived
            serverLoopOriginLocal = clientReceived - serverTransmitted
            sampleCount++
            quality = SyncQuality.GOOD
            return
        }

        // Second sync: calculate initial drift
        if (sampleCount == 1) {
            val deltaTime = (clientReceived - lastSyncMicros).toDouble()
            if (deltaTime > 0) {
                drift = (measuredOffset - offset).toDouble() / deltaTime
            }
            offset = measuredOffset
            lastSyncMicros = clientReceived
            serverLoopOriginLocal = clientReceived - serverTransmitted
            sampleCount++
            quality = SyncQuality.GOOD
            return
        }

        // Subsequent syncs: Kalman filter update
        val deltaTime = (clientReceived - lastSyncMicros).toDouble()
        if (deltaTime <= 0) return

        val predictedOffset = offset + (drift * deltaTime).toLong()
        val residual = measuredOffset - predictedOffset

        // Reject outliers
        if (abs(residual) > 50_000) return

        // Update offset and drift
        offset = predictedOffset + (smoothingRate * residual).toLong()
        val driftCorrection = residual.toDouble() / deltaTime
        drift += smoothingRate * driftCorrection

        lastSyncMicros = clientReceived
        sampleCount++
        serverLoopOriginLocal = clientReceived - serverTransmitted

        quality = if (calculatedRtt < 50_000) SyncQuality.GOOD else SyncQuality.DEGRADED
    }

    private fun calculateOffset(
        clientTx: Long,
        serverRx: Long,
        serverTx: Long,
        clientRx: Long
    ): Pair<Long, Long> {
        val rtt = (clientRx - clientTx) - (serverTx - serverRx)
        val offset = ((serverRx - clientTx) + (serverTx - clientRx)) / 2
        return rtt to offset
    }

    fun serverTimeToLocal(serverTime: Long): Long {
        if (sampleCount == 0) return 0
        return serverLoopOriginLocal + serverTime
    }

    fun localTimeToServer(localTime: Long): Long {
        if (sampleCount == 0) return 0
        return localTime - serverLoopOriginLocal
    }

    fun checkQuality(): SyncQuality {
        if (lastSyncTimeMs > 0) {
            val elapsed = System.currentTimeMillis() - lastSyncTimeMs
            if (elapsed > 5000) {
                quality = SyncQuality.LOST
            }
        }
        return quality
    }

    fun reset() {
        offset = 0
        drift = 0.0
        rawOffset = 0
        rtt = 0
        quality = SyncQuality.LOST
        lastSyncTimeMs = 0
        lastSyncMicros = 0
        serverLoopOriginLocal = 0
        sampleCount = 0
    }
}

package io.musicassistant.companion.data.sendspin

/**
 * Categorized error types for Sendspin integration.
 * Helps UI distinguish between recoverable, permanent, and degraded states.
 */
sealed class SendspinError {
    /**
     * Transient error that may be automatically recovered.
     * Examples: Network interruption, brief server unavailability, reconnection in progress.
     */
    data class Transient(
        val cause: Throwable,
        val willRetry: Boolean
    ) : SendspinError()

    /**
     * Permanent error requiring user intervention.
     * Examples: Invalid configuration, authentication failure, unsupported codec.
     */
    data class Permanent(
        val cause: Throwable,
        val userAction: String
    ) : SendspinError()

    /**
     * Degraded operation - functionality is limited but not completely broken.
     * Examples: High latency, frequent packet drops, audio quality reduced.
     */
    data class Degraded(
        val reason: String,
        val impact: String
    ) : SendspinError()
}

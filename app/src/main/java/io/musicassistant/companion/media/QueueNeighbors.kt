package io.musicassistant.companion.media

/**
 * Pure gating rules for which queue neighbors the MediaSession (and thus Bluetooth AVRCP) should
 * expose, derived from the authoritative MA queue position. The caller resolves the actual prev/next
 * candidates; this only applies the product rules:
 *
 *  - radio/live → never show prev/next (no real neighbors).
 *  - first item (index 0) → no prev.
 *  - last item (index == total - 1) → no next.
 *
 * [currentIndex] is the 0-based position in the queue; [total] is the queue length. A null index or
 * empty queue yields no neighbors (we can't know first/last, so we show nothing rather than guess).
 */
fun <T> resolveNeighbors(
    isLive: Boolean,
    currentIndex: Int?,
    total: Int,
    prevCandidate: T?,
    nextCandidate: T?
): Pair<T?, T?> {
    if (isLive || currentIndex == null || total <= 0) return null to null
    val prev = if (currentIndex > 0) prevCandidate else null
    val next = if (currentIndex < total - 1) nextCandidate else null
    return prev to next
}

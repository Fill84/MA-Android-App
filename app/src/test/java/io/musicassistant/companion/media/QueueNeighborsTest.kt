package io.musicassistant.companion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueNeighborsTest {

    @Test
    fun `middle of queue shows both neighbors`() {
        val (prev, next) = resolveNeighbors(isLive = false, currentIndex = 6, total = 10, prevCandidate = "P", nextCandidate = "N")
        assertEquals("P", prev)
        assertEquals("N", next)
    }

    @Test
    fun `first item hides prev, keeps next`() {
        val (prev, next) = resolveNeighbors(isLive = false, currentIndex = 0, total = 10, prevCandidate = "P", nextCandidate = "N")
        assertNull(prev)
        assertEquals("N", next)
    }

    @Test
    fun `last item hides next, keeps prev`() {
        val (prev, next) = resolveNeighbors(isLive = false, currentIndex = 9, total = 10, prevCandidate = "P", nextCandidate = "N")
        assertEquals("P", prev)
        assertNull(next)
    }

    @Test
    fun `single-item queue hides both`() {
        val (prev, next) = resolveNeighbors(isLive = false, currentIndex = 0, total = 1, prevCandidate = "P", nextCandidate = "N")
        assertNull(prev)
        assertNull(next)
    }

    @Test
    fun `radio hides both regardless of index`() {
        val (prev, next) = resolveNeighbors(isLive = true, currentIndex = 6, total = 10, prevCandidate = "P", nextCandidate = "N")
        assertNull(prev)
        assertNull(next)
    }

    @Test
    fun `null index yields no neighbors`() {
        val (prev, next) = resolveNeighbors(isLive = false, currentIndex = null, total = 10, prevCandidate = "P", nextCandidate = "N")
        assertNull(prev)
        assertNull(next)
    }

    @Test
    fun `empty queue yields no neighbors`() {
        val (prev, next) = resolveNeighbors(isLive = false, currentIndex = 0, total = 0, prevCandidate = "P", nextCandidate = "N")
        assertNull(prev)
        assertNull(next)
    }
}

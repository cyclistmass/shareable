package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 3D board: one that shows nothing falls back to the flat board instead of staying blank. */
class LiveStallTest {
    private val s = 1_000_000_000L

    @Test
    fun `a board that never draws stalls after the limit, not before`() {
        assertFalse(liveStalled(100 * s + LIVE_STALL_NANOS, 100 * s, 0L))
        assertTrue(liveStalled(100 * s + LIVE_STALL_NANOS + 1, 100 * s, 0L))
    }

    @Test
    fun `a drawing board never stalls, and one that stops drawing does`() {
        val start = 50 * s
        for (t in 0L..60L) assertFalse(liveStalled(start + t * s, start, start + t * s - s / 60), "frame at $t s")
        assertTrue(liveStalled(start + 20 * s, start, start + 10 * s))
    }

    @Test
    fun `restarting (back from the background) gives the board a fresh allowance`() {
        // The last frame was long ago, before the pause; it has only just started running again.
        assertFalse(liveStalled(500 * s + s, 500 * s, 100 * s))
    }

    @Test
    fun `a board that never started running never stalls`() {
        assertFalse(liveStalled(1_000 * s, 0L, 0L))
    }
}

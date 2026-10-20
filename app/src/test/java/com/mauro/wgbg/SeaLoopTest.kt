package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * #134 (the sea froze once on an emulator): both sea loops come back after a pause, a resume and time in the
 * background. The 3D board's frames run on [FrameLoop]; the 2D sea (and the 3D board's water, boats and gulls, which it
 * drives) steps by [frameStep].
 */
class SeaLoopTest {
    /** Choreographer as the loop sees it: callbacks posted and not yet run. */
    private class Clock {
        var queued = 0
        val loop = FrameLoop({ queued++ }, { queued = 0 })
        /** Runs the posted frame, if any: true when the loop drew it. */
        fun tick(): Boolean { if (queued == 0) return false; queued--; return loop.frame() }
    }

    @Test
    fun `frames run while started, stop on pause and come back on resume, every time`() {
        val c = Clock()
        c.loop.start(1L)
        repeat(10) { assertTrue(c.tick()) }
        for (cycle in 1..50) {
            c.loop.stop()                        // onStop: Home, screen off, another app
            assertFalse(c.tick(), "no frame while paused (cycle $cycle)")
            c.loop.start(cycle * 10L)            // onStart
            assertEquals(1, c.queued, "exactly one frame posted on resume")
            repeat(3) { assertTrue(c.tick(), "the sea moves again after resume $cycle") }
        }
    }

    @Test
    fun `a resume after a long time away is not taken for a stall, and the step stays small`() {
        val c = Clock()
        c.loop.start(1_000L)
        val lastFrame = 2_000L
        c.loop.stop()
        val back = lastFrame + 600_000_000_000L      // ten minutes in the background
        c.loop.start(back)
        assertEquals(back, c.loop.since)
        assertFalse(liveStalled(back + 1_000_000_000L, c.loop.since, lastFrame), "the 3D board fell back to flat on resume")
        assertTrue(liveStalled(back + LIVE_STALL_NANOS + 1, c.loop.since, lastFrame), "a real stall is still caught")
        assertEquals(0.05f, frameStep(back, lastFrame, 0.05f))
    }

    @Test
    fun `the frame step - none for the first frame, never negative, capped`() {
        assertEquals(0f, frameStep(5_000_000_000L, 0L, 0.05f))
        assertEquals(0.016f, frameStep(1_016_000_000L, 1_000_000_000L, 0.05f), 1e-6f)
        assertEquals(0f, frameStep(1_000_000_000L, 1_002_000_000L, 0.05f), "a clock that steps back")
        assertEquals(0.1f, frameStep(9_000_000_000L, 1_000_000_000L, 0.1f))
    }

    @Test
    fun `fuzz - in random starts, stops, frames and closes, at most one frame is ever pending and one always is while running`() {
        val rnd = Random(134)
        repeat(200) {
            val c = Clock()
            var drawn = 0
            repeat(500) { step ->
                when (rnd.nextInt(10)) {
                    0, 1 -> c.loop.start(step.toLong() + 1)
                    2 -> c.loop.stop()
                    3 -> if (rnd.nextInt(20) == 0) c.loop.close()
                    else -> if (c.tick()) drawn++
                }
                assertTrue(c.queued <= 1, "two frames pending")
                assertEquals(c.loop.running && !c.loop.closed, c.queued == 1, "running ${c.loop.running} with ${c.queued} pending")
                if (c.loop.closed) assertFalse(c.loop.running)
            }
            assertTrue(drawn > 0 || c.loop.closed)
        }
    }

    @Test
    fun `break it - nothing runs after close, and stray stops and frames do nothing`() {
        val c = Clock()
        c.loop.stop(); assertFalse(c.loop.running); assertEquals(0, c.queued)
        assertFalse(c.loop.frame(), "a frame that was never posted")
        assertEquals(0, c.queued)
        c.loop.start(1L); c.loop.start(2L)
        assertEquals(1, c.queued, "a second start doesn't double the frames")
        assertEquals(1L, c.loop.since, "nor restart the stall clock")
        c.loop.close()
        assertEquals(0, c.queued)
        c.loop.start(3L)
        assertEquals(0, c.queued, "a closed board never draws again")
        assertFalse(c.loop.running)
    }

    @Test
    fun `the 3D board follows the screen's lifecycle and the 2D sea loop never ends`() {
        val view = File("src/main/java/com/mauro/wgbg/LiveBoardView.kt").readText()
        assertTrue("holder[0]?.running = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)" in view, "every lifecycle event sets running")
        val frame = view.substringAfter("override fun doFrame").substringBefore("\n    }\n")
        assertTrue("if (!loop.frame()) return" in frame)
        assertTrue("liveStalled(System.nanoTime(), loop.since, lastFrameAt)" in frame)
        assertTrue("frameStep(now, critterTime, .1f)" in view, "Space critters step like the sea")
        val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        val sea = main.substringAfter("LaunchedEffect(sea) {").substringBefore("\n    }\n")
        assertTrue("while (true)" in sea)
        assertFalse("break" in sea || "return@LaunchedEffect" in sea, "the sea loop can end")
        assertTrue("sea.update(frameStep(now, last, 0.05f))" in sea)
    }
}

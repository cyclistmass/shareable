package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The box clasp on the setup screen's pickers (0.3.5): it swings open with the pull, and opens the picker only when all the way open. */
class ClaspTest {
    @Test
    fun `the clasp swings closed, a third, two thirds, open as the pull grows`() {
        assertEquals(listOf(0, 1, 2, 3), listOf(0f, 0.34f, 0.67f, 1f).map { claspFrame(it) })
        assertEquals(0, claspFrame(-5f)); assertEquals(3, claspFrame(9f))
        assertEquals(4, CLASP_FRAMES.size)
    }

    @Test
    fun `only a pull let go fully open opens the picker`() {
        assertFalse(claspOpens(0f)); assertFalse(claspOpens(0.5f))
        assertTrue(claspOpens(1f))
    }

    @Test
    fun `no picker on the setup screen still shows the text arrow`() {
        val src = java.io.File("src/main/java/com/mauro/wgbg/NewGameScreen.kt").readText(Charsets.UTF_8)
        assertFalse("▾" in src, "the text arrow is the clasp now")
        assertEquals(2, Regex("claspPull\\(").findAll(src).count(), "seat colour and seat name pull open")
    }
}

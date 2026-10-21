package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Mauro's panel pick D (0.3.4): hammered brass with rounded corners on aged wood, on every panel. */
class PanelFrameTest {
    // px at the test phone's 2.625 density: a 10 dp band, a 14 dp corner, 2 dp at least.
    private val band = 26.25f
    private val corner = 36.75f
    private val least = 5.25f
    private fun frame(w: Float, h: Float) = frameBand(w, h, band, corner, least)

    @Test
    fun `a big panel gets the full band and corner`() {
        val (f, r, ri) = frame(1500f, 1000f)
        assertEquals(band, f)
        assertEquals(corner, r)
        assertEquals(corner - band, ri, 1e-3f)
    }

    @Test
    fun `a small panel keeps a slim band, never more than 6 percent of its short side`() {
        for ((w, h) in listOf(400f to 120f, 300f to 200f, 900f to 150f)) {
            val (f, _, _) = frame(w, h)
            assertTrue(f <= 0.06f * minOf(w, h) + 1e-3f, "$w x $h: band $f")
            assertTrue(f < band, "$w x $h")
        }
    }

    @Test
    fun `break-it - tiny and degenerate panels still draw a sane frame`() {
        for ((w, h) in listOf(10f to 10f, 1f to 400f, 0f to 0f, 60f to 4f)) {
            val (f, r, ri) = frame(w, h)
            assertTrue(f >= least, "$w x $h: band $f")
            assertTrue(r <= minOf(w, h) / 2 + 1e-3f, "$w x $h: corner $r wider than the panel")
            assertTrue(ri >= least, "$w x $h: inner corner $ri")
            assertFalse(f.isNaN() || r.isNaN() || ri.isNaN(), "$w x $h")
        }
    }

    @Test
    fun `the corners round, the band is hammered brass, and no look switch is left behind`() {
        val src = File("src/main/java/com/mauro/wgbg/Tabletop.kt").readText()
        assertTrue("R.drawable.hammered_brass" in src)
        assertTrue("RoundRect(" in src && "PathOperation.Difference" in src, "one rounded band, not mitred rails")
        assertFalse("PanelLook" in src || "brushed_brass" in src, "the option switch and the rejected looks are gone")
        val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        assertFalse("\"panels\"" in main, "the debug launch extra is gone")
        assertTrue("\"inventory-panel\"" in main, "the Inventory shows a framed sample panel")
        assertTrue(File("src/main/res/drawable-nodpi/hammered_brass.webp").isFile)
        assertTrue("hammered_brass.webp" in File("../CREDITS.md").readText(), "credited as our own art")
    }
}

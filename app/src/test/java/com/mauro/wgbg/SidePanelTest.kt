package com.mauro.wgbg

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The left side panel: the turn panel's dice sit under the player list, never over it (0.3.4, 640x320). */
class SidePanelTest {
    // The turn panel sits 8 dp up from the bottom edge, so its top is screen - 8 - height.
    private fun hudTop(screen: Dp, stripBottom: Dp) = screen - 8.dp - turnHudHeight(screen, stripBottom)

    @Test
    fun `the dice sit under the player list whenever the screen has room for them`() {
        for (screen in listOf(320.dp, 360.dp, 411.dp, 900.dp)) for (strip in listOf(150.dp, 200.dp, 230.dp, 240.dp, 250.dp)) {
            if (screen - strip - 10.dp < TURN_HUD_MIN) continue
            assertTrue(hudTop(screen, strip) >= strip, "screen $screen, list ends at $strip: panel top ${hudTop(screen, strip)}")
        }
    }

    @Test
    fun `at 640x320 four players' list leaves the dice clear (it was forced to 110 dp and covered White)`() {
        // Age of Chivalry's list (with the Raiders track) ends about 240 dp down on CI's 320 dp tall screen.
        for (strip in listOf(200.dp, 240.dp)) assertTrue(hudTop(320.dp, strip) >= strip)
        assertTrue(turnHudHeight(320.dp, 240.dp) < 110.dp, "the old 110 dp floor is gone")
    }

    @Test
    fun `the panel always keeps the dice and a line of news`() {
        assertTrue(TURN_HUD_MIN >= (DIE_DP + 2 + 3 + 10 + 14).dp)
        assertEquals(TURN_HUD_MIN, turnHudHeight(320.dp, 320.dp))
        assertEquals(TURN_HUD_MIN, turnHudHeight(320.dp, 1000.dp))
    }

    @Test
    fun `before the screen is measured it keeps today's 210 dp`() {
        assertEquals(210.dp, turnHudHeight(0.dp, 0.dp))
        assertEquals(210.dp, turnHudHeight((-5).dp, 100.dp))
    }

    @Test
    fun `ci_reach finds the player list and the dice by name`() {
        assertTrue("contentDescription = \"Players\"" in File("src/main/java/com/mauro/wgbg/BoardHud.kt").readText())
        assertTrue("content-desc=\"Players\"" in File("../tools/ci_reach.sh").readText())
    }

    @Test
    fun `the player list stops where the dice panel's least height begins`() {
        for (h in listOf(320, 360, 411, 800)) {
            val max = stripMaxHeight(h.dp)
            assertTrue(8.dp + max + 10.dp + TURN_HUD_MIN <= h.dp, "screen $h: list $max")
            // A list that fills its limit still leaves the turn panel its least height, so the dice keep clear of it.
            assertEquals(TURN_HUD_MIN, turnHudHeight(h.dp, 8.dp + max))
        }
        assertEquals(Dp.Infinity, stripMaxHeight(0.dp))
        assertTrue(STAT_BADGE_W * 2 + 6.dp <= 64.dp, "two badges fit their column")
    }

    @Test
    fun `a list taller than its room shrinks evenly to fit, never scrolls, and one that fits keeps its size`() {
        assertEquals(1f, fitScale(200, 236))
        assertEquals(1f, fitScale(236, 236))
        // 0.3.4 at 640x320, font 1.3, Age of Chivalry: four 67 dp rows, the Raiders track and its warning.
        assertEquals(236f / 368, fitScale(368, 236), 1e-6f)
        assertEquals(1f, fitScale(0, 0))
        assertEquals(1f, fitScale(500, Int.MAX_VALUE))
    }

    @Test
    fun `the turn prompt and the notices stay right of the player list and left of Menu`() {
        // 0.3.4 at 640x320: centred on the screen, "Tap a glowing spot to build, ..." ran over the first player's name.
        val panel = 265.dp
        assertTrue(promptStart(panel) >= panel + 8.dp + 8.dp, "clear of the panel (8 dp in from the edge)")
        assertTrue(640.dp - promptStart(panel) - PROMPT_END >= 200.dp, "room for two lines of prompt on CI's screen")
        assertEquals(376.dp, promptStart(0.dp), "before the panel is measured")
        val hud = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readLines()
        for (tag in listOf("TurnPrompt(", "OnlineConnectionStatus", "hit-notice")) {
            val i = hud.indexOfFirst { tag in it }
            assertTrue(hud.subList(maxOf(0, i - 1), minOf(hud.size, i + 2)).any { "rightOfPanel" in it }, tag)
        }
    }
}

package com.mauro.wgbg

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The side panel uses the strip beside the camera and steps round only the hole (0.3.6, players' reports). */
class CutoutHolesTest {
    // Pixel 8 landscape, camera on the left: the hole's rect is 136 px deep and about 110 px tall, at mid height.
    private val hole = Rect(0f, 485f, 136f, 595f)
    private val m = 10f

    @Test
    fun `a row level with the hole steps right of it, by the margin more`() {
        assertEquals(136f + m - 52f, holeIndent(52f, 470f, 560f, listOf(hole), m))
    }

    @Test
    fun `rows above and below the hole, or right of it, keep their place`() {
        assertEquals(0f, holeIndent(52f, 100f, 470f, listOf(hole), m))      // ends above the hole and its margin
        assertEquals(0f, holeIndent(52f, 610f, 700f, listOf(hole), m))      // starts below it
        assertEquals(0f, holeIndent(200f, 470f, 560f, listOf(hole), m))     // already right of it
        assertEquals(0f, holeIndent(52f, 470f, 560f, emptyList(), m))       // no cutout
    }

    @Test
    fun `just inside the margin still steps`() {
        assertTrue(holeIndent(52f, 400f, 480f, listOf(hole), m) > 0f)   // 5 px above the hole: within the margin
        assertTrue(holeIndent(140f, 470f, 560f, listOf(hole), m) > 0f)  // 4 px right of it: within the margin
    }

    @Test
    fun `a tall cutout or two cutouts, the row clears the deepest one level with it`() {
        val tall = Rect(0f, 300f, 160f, 800f)
        val small = Rect(0f, 500f, 60f, 560f)
        assertEquals(160f + m - 20f, holeIndent(20f, 520f, 540f, listOf(small, tall), m))
        assertEquals(60f + m - 20f, holeIndent(20f, 520f, 540f, listOf(small), m))
    }

    @Test
    fun `a hole on the far side (phone turned the other way) moves nothing on the left`() {
        val right = Rect(2264f, 485f, 2400f, 595f)
        assertEquals(0f, holeIndent(52f, 470f, 560f, listOf(right), m))
    }

    @Test
    fun `the name column gives up the step, down to 48 dp`() {
        assertEquals(74.dp, nameColumn(0.dp))
        assertEquals(50.dp, nameColumn(24.dp))
        assertEquals(48.dp, nameColumn(40.dp))
    }

    @Test
    fun `the HUD pads only the right and the ends for the cutout, and the panel and the turn panel get the holes`() {
        val game = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        assertTrue("WindowInsets.displayCutout.only(WindowInsetsSides.Right + WindowInsetsSides.Vertical)" in game)
        assertTrue("cameos = cameos, holes = holes)" in game, "the player list steps round the hole")
        assertTrue(Regex("""\n\s*holes = holes\)""").containsMatchIn(game), "the turn panel steps round the hole")
        assertTrue("376.dp.toPx() } + cutoutLeft" !in game, "the board no longer leaves the cutout's strip empty")
        val hud = File("src/main/java/com/mauro/wgbg/Hud.kt").readText()
        assertTrue("Modifier.dodgeHoles(holes).testTag(\"dice-row\")" in hud && "fillMaxWidth().dodgeHoles(holes)" in hud)
        val strip = File("src/main/java/com/mauro/wgbg/BoardHud.kt").readText()
        assertTrue("dodge.track).padding(start = step)" in strip && "nameColumn(step)" in strip)
    }
}

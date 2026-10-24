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

    // The phone turned the other way (rotation 270): the same hole on the right edge of a 2404 px wide screen.
    private val right = Rect(2268f, 485f, 2404f, 595f)

    @Test
    fun `camera on the right, a button level with the hole steps left of it, by the margin more`() {
        // A build plate flush with the edge, 8 dp (20 px) in: it ends at 2384 px.
        assertEquals(2384f + m - 2268f, holeIndentEnd(2384f, 470f, 560f, listOf(right), m))
    }

    @Test
    fun `camera on the right, buttons above, below or left of the hole keep their place`() {
        assertEquals(0f, holeIndentEnd(2384f, 40f, 160f, listOf(right), m))     // Menu, top right
        assertEquals(0f, holeIndentEnd(2384f, 900f, 1060f, listOf(right), m))   // Roll / End, bottom right
        assertEquals(0f, holeIndentEnd(2200f, 470f, 560f, listOf(right), m))    // already left of the hole
        assertEquals(0f, holeIndentEnd(2384f, 470f, 560f, emptyList(), m))      // no cutout
    }

    @Test
    fun `camera on the left moves nothing on the right edge, and the other way round`() {
        assertEquals(0f, holeIndentEnd(2384f, 470f, 560f, listOf(hole), m))
        assertEquals(0f, holeIndent(20f, 470f, 560f, listOf(right), m))
    }

    @Test
    fun `camera on the right, a tall cutout, the deepest hole level with it wins`() {
        val tall = Rect(2244f, 300f, 2404f, 800f)
        assertEquals(2384f + m - 2244f, holeIndentEnd(2384f, 520f, 540f, listOf(right, tall), m))
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
        assertTrue("WindowInsets.displayCutout.only(WindowInsetsSides.Vertical)" in game, "the HUD pads no side")
        assertTrue("cameos = cameos, holes = holes)" in game, "the player list steps round the hole")
        assertTrue(Regex("""\n\s*holes = holes\)""").containsMatchIn(game), "the turn panel steps round the hole")
        assertTrue("376.dp.toPx() } + cutoutLeft" !in game, "the board no longer leaves the cutout's strip empty")
        val hud = File("src/main/java/com/mauro/wgbg/Hud.kt").readText()
        assertTrue("Modifier.dodgeHoles(holes).testTag(\"dice-row\")" in hud && "fillMaxWidth().dodgeHoles(holes)" in hud)
        // Camera on the right: every right-edge control steps round the hole, none pads the whole edge.
        for (tag in listOf("\"menu-toggle\"", "\"bug-report\"", "\"card-pick\"")) {
            val line = game.lines().first { tag in it }
            assertTrue("dodgeHolesEnd(holes)" in line, line.trim())
        }
        assertTrue("end = if (env.viewBars) 156.dp else 8.dp), holes = holes)" in game, "the build row gets the holes")
        assertTrue("onFortuneDone = { fortuneAsk = null }, holes = holes," in game, "the move buttons get the holes")
        assertTrue("dodgeHolesEnd(holes).padding(top = 104.dp" in game, "the view bars")
        assertTrue("dodgeHolesEnd(holes).padding(top = 60.dp, end = 8.dp)," in game, "Settings")
        val build = File("src/main/java/com/mauro/wgbg/BuildMenu.kt").readText()
        assertTrue(Regex("""dodgeHolesEnd\(holes\)""").findAll(build).count() >= 2 && "horizontalAlignment = androidx.compose.ui.Alignment.End" in build)
        val play = File("src/main/java/com/mauro/wgbg/Play.kt").readText()
        assertTrue(Regex("""dodgeHolesEnd\(holes\)""").findAll(play).count() >= 2)
        val strip = File("src/main/java/com/mauro/wgbg/BoardHud.kt").readText()
        assertTrue("dodge.track).padding(start = step)" in strip && "nameColumn(step)" in strip)
    }
}

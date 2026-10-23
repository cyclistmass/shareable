package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rules
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * The turn plates (0.3.5 reports): Roll and End turn share one plate, dice then hourglass, and the shut Menu is the
 * three bars' plate. The scripts find them by the names a screen reader reads, so those names stay.
 */
class TurnPlateTest {
    private fun src(name: String) = File("src/main/java/com/mauro/wgbg/$name").readText().replace("\r\n", "\n")

    @Test
    fun `Roll and End turn are one plate that keeps the names and tags the scripts tap`() {
        val play = src("Play.kt")
        assertTrue("ArtPlateButton(R.drawable.icon_roll_on, R.drawable.icon_roll_off, \"Roll\", \"move-Roll\"" in play)
        assertTrue("ArtPlateButton(R.drawable.icon_end_turn_on, R.drawable.icon_end_turn_off, \"End turn\", \"move-End turn\"" in play)
        assertFalse("BrassButton(\"Roll\"" in play, "the worded Roll button is gone")
    }

    @Test
    fun `the shut Menu is the bars plate, still called Menu and tagged menu-toggle`() {
        assertTrue("MenuButton(if (panelOpen || settingsOpen) \"Hide\" else if (lookAndArt) \"Settings\" else \"Menu\", \"menu-toggle\"" in src("MainActivity.kt"))
        val menu = src("BrassPlate.kt").substringAfter("fun MenuButton(").substringBefore("\n}\n")
        assertTrue("if (label == \"Menu\") ArtPlateButton(R.drawable.icon_menu_on, R.drawable.icon_menu_off, label, tag" in menu, menu)
        // The plate carries its name for the screen reader and the scripts' dump.
        val plate = src("BrassPlate.kt").substringAfter("fun ArtPlateButton(").substringBefore("\n}\n")
        assertTrue("contentDescription = label" in plate && ".testTag(tag)" in plate, plate)
    }

    @Test
    fun `on a short phone the turn plate moves out from under the build menu, and only there`() {
        println("build menu: chivalry ${buildMenuHeight(true)}, base ${buildMenuHeight(false)}")
        // CI's 640x320 at density 160: the Age of Chivalry menu ran over the Roll plate and touchplay stuck at the roll.
        assertTrue(buildMenuReaches(true, 320.dp, TURN_PLATE))
        assertFalse(buildMenuReaches(false, 320.dp, TURN_PLATE), "base's four plates stop well above it")
        // A Pixel 6 held sideways (411 dp): the plate stays in the corner, as Mauro saw it.
        assertFalse(buildMenuReaches(true, 411.dp, TURN_PLATE))
        assertTrue("Row(Modifier.padding(end = if (clear) BUILD_PLATE + 10.dp else 0.dp)" in src("Play.kt"))
    }

    @Test
    fun `the plates' art ships, lit and engraved`() {
        for (n in listOf("roll", "end_turn", "menu")) for (s in listOf("on", "off"))
            assertTrue(File("src/main/res/drawable-nodpi/icon_${n}_$s.webp").isFile, "icon_${n}_$s")
    }

    @Test
    fun `the rules never offer Roll and End turn together, so one plate is enough`() {
        val buf = ActionBuffer()
        for (seed in 1..8L) {
            val chivalry = seed % 2 == 0L; val n = if (seed % 3 == 0L) 3 else 4
            val s = Rules.newGame(GameConfig(expansion = if (chivalry) Expansion.CHIVALRY else Expansion.BASE, numPlayers = n), seed)
            val bots = List(n) { GreedyBot(seed * 7 + it) }
            var moves = 0
            while (!s.isOver && moves++ < 20_000) {
                Rules.legalActions(s, buf)
                assertFalse(Action.of(ActionType.ROLL) in buf && Action.of(ActionType.END_TURN) in buf, "seed $seed, move $moves")
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
    }
}

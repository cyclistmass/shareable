package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.engine.Rules
import java.io.File

/** The debug stress state tools/ci_layout.sh checks the player list with: it must really be the fullest list. */
class LayoutStressTest {
    private fun game(chivalry: Boolean, n: Int = 4) =
        Rules.newGame(GameConfig(expansion = if (chivalry) Expansion.CHIVALRY else Expansion.BASE, numPlayers = n), 3)

    @Test
    fun `everyone gets double-digit counts and player 0 every badge, one point short of winning`() {
        for (chivalry in listOf(false, true)) for (n in 3..4) {
            val s = game(chivalry, n); layoutStress(s, discard = false)
            for (p in 0 until n) {
                assertTrue(s.cardCount(p) >= 10, "cards of $p")
                assertTrue(s.roadLength[p] >= 10, "road of $p")
                if (!chivalry) assertTrue(knightsShown(s, p) >= 10, "knights of $p")
            }
            assertEquals(0, s.longestRoadOwner)
            if (!chivalry) assertEquals(0, s.largestArmyOwner)
            // One short of winning, so the scripted moves after it don't end the game (0.3.4: the offer step met the end screen).
            assertEquals(s.config.vpToWin - 1, s.victoryPoints(0))
            if (chivalry) assertTrue(s.victoryPoints(0) >= 10, "two-digit score")
            if (chivalry) {
                assertTrue(cardsLabel(s, 0).length >= 4, "cards/progress like 20/4: ${cardsLabel(s, 0)}")
                // The Raiders warning line shows on player 0's turn.
                s.currentPlayer = 0
                assertTrue(raidersWarning(s, 0) != null)
            }
        }
    }

    @Test
    fun `discard leaves player 0 owing half, as after a rolled 7`() {
        for (chivalry in listOf(false, true)) {
            val s = game(chivalry); layoutStress(s, discard = true)
            assertEquals(Phase.DISCARD, s.phase)
            assertEquals(0, s.actingPlayer)
            assertEquals(s.cardCount(0) / 2, s.discardOwed[0])
        }
    }

    @Test
    fun `the stress names are as long as a name may be, one per seat up to six`() {
        assertEquals(6, STRESS_NAMES.size)
        assertTrue(STRESS_NAMES.all { it.length == 16 })
    }

    @Test
    fun `ci_layout checks every step with the shared checker, at CI's screen and both font scales`() {
        val sh = File("../tools/ci_layout.sh").readText()
        assertTrue("layout_check.py" in sh && "320x640:160" in sh && "1.3" in sh)
        assertTrue("trap restore EXIT" in sh, "screen and font scale are put back on any exit")
        assertTrue("--es stress full" in sh && "--es stress discard" in sh)
        // Every step checks White's row and the camera cutout; the cutout pass turns the phone both ways.
        assertTrue("--players 4" in sh && "--cutout" in sh && "user_rotation" in sh && "fixed-to-user-rotation enabled" in sh)
        for (cut in listOf("hole", "tall", "corner", "double")) assertTrue(cut in sh, cut)
        assertTrue("1080x2340" in sh && "1440x3200" in sh)
    }

    @Test
    fun `every full-screen page keeps its buttons clear of the camera cutout`() {
        // A wood page under the cutout is fine; its content isn't (0.3.4: the lens sat on the dice). The game screen
        // (Look & art too) pads its HUD itself.
        val src = File("src/main/java/com/mauro/wgbg").listFiles()!!.filter { it.extension == "kt" }
        val pages = src.flatMap { f -> f.readLines().filter { "fillMaxSize().tableTop()" in it && "WatchGameScreen(" !in it }.map { f.name to it.trim() } }
        assertTrue(pages.size >= 5, "found the pages: $pages")
        for ((file, line) in pages) assertTrue("displayCutoutPadding()" in line, "$file: $line")
        val game = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        assertTrue(Regex("""displayCutoutPadding\(\)""").findAll(game).count() >= 3, "the HUD, the Menu panel and the buttons over the board")
    }
}

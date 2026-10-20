package wgbg.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wgbg.engine.Action
import wgbg.engine.ActionType
import wgbg.engine.DiceFairness
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Rules
import java.io.File
import kotlin.random.Random

/**
 * #26 in the play-by-file client: an offer a seat can't pay is declined for it and says so in Recent moves, and the
 * dice it records are the fair true-random ones.
 */
class CliTradeAndDiceTest {
    @TempDir lateinit var dir: File

    /** Plays [seed]'s game with Ann (seat 0) choosing random legal moves the way a person would at `act`. */
    private fun play(seed: Long, onAnn: (GameState) -> Unit): Record {
        val r = Record(dir, listOf("Ann", "bot", "bot", "bot"), seed, 10, mutableListOf())
        val s = r.replay()
        val rnd = Random(seed)
        runBots(r, s)
        while (!s.isOver) {
            assertEquals(0, s.actingPlayer, "the client only stops for Ann")
            onAnn(s)
            var a = Rules.legalActions(s).random(rnd)
            if (a.type == ActionType.ROLL) a = r.roll()
            Rules.applyChecked(s, a)
            r.actions += a
            runBots(r, s)
        }
        return r
    }

    @Test
    fun `fuzz - Ann is never left waiting on an offer she can't pay, and each automatic No says what she lacked`() {
        var autoNo = 0; var answered = 0; var games = 0
        for (seed in 1L..12L) {
            val r = play(seed) { s ->
                assertFalse(Rules.cantPayOffer(s, 0), "Ann asked to answer an offer she can't pay")
                if (s.phase == Phase.DECIDE_TRADE) answered++
            }
            games++
            // The record replays: every automatic No is a legal move, and Recent moves explains it.
            val moves = recentMoves(r, Int.MAX_VALUE)
            assertEquals(r.actions.size, moves.size)
            val auto = moves.filter { "declined the offer automatically: doesn't hold " in it }
            assertTrue(auto.none { it.endsWith("hold nothing") })
            autoNo += auto.count { it.startsWith("Ann ") }
        }
        assertEquals(12, games)
        assertTrue(autoNo >= 5, "only $autoNo automatic Noes for Ann")
        assertTrue(answered >= 1, "Ann never got an offer she could pay or counter")
    }

    @Test
    fun `an offer Ann can pay, or an open ask, still waits for her`() {
        var waited = 0
        for (seed in 13L..20L) play(seed) { s ->
            if (s.phase == Phase.DECIDE_TRADE) {
                waited++
                assertTrue(Action.ACCEPT_TRADE in Rules.legalActions(s) || !Rules.isTwoSided(s))
            }
        }
        assertTrue(waited > 0)
    }

    @Test
    fun `the client's true-random dice are fair over 10,000 throws`() {
        val r = Record(dir, listOf("Ann", "bot"), 1, 10, mutableListOf())
        val rolls = List(10_000) { r.roll() }
        assertTrue(rolls.all { it.type == ActionType.ROLL && it.a in 1..6 && it.b in 1..6 })
        val text = DiceFairness.check(rolls.map { it.a + it.b }, rolls.map { it.a }, rolls.map { it.b })
        File("build/dice-fairness").apply { mkdirs() }.resolve("cli.txt").writeText(text)
        println("== dice fairness, cli ==")
        print(text)
    }
}

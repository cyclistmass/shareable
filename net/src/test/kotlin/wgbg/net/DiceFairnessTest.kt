package wgbg.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ActionType
import wgbg.engine.DiceFairness
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Rules
import java.io.File
import kotlin.random.Random

/** #26: 10,000 throws from the true-random dice an online game uses ([rollFromHistory] with no deck), checked for fairness. */
class DiceFairnessTest {
    /** A game at its first roll, set up by random legal placements. */
    private fun atFirstRoll(expansion: Expansion): GameState {
        val s = Rules.newGame(GameConfig(expansion = expansion), 26)
        val rnd = Random(26)
        while (!Rules.isLegal(s, Action.ROLL)) Rules.apply(s, Rules.legalActions(s).random(rnd))
        return s
    }

    @Test
    fun `online true-random dice are fair over 10,000 throws, event die included`() {
        for (expansion in listOf(Expansion.BASE, Expansion.CITIES_AND_KNIGHTS)) {
            val s = atFirstRoll(expansion)
            val hash = s.stateHash()
            val rolls = List(10_000) { rollFromHistory(s, emptyList(), 0) }
            assertEquals(hash, s.stateHash(), "drawing dice leaves the game alone")
            assertTrue(rolls.all { it.type == ActionType.ROLL && Rules.isLegal(s, it) })
            val events = rolls.map { ((it.payload ushr 16) and 0xff).toInt() }
            val dice = mutableListOf(rolls.map { it.a }, rolls.map { it.b })
            if (expansion == Expansion.BASE) assertTrue(events.all { it == 0 }, "no event die in the base game")
            else { assertTrue(events.all { it in 1..6 }); dice += events }
            val text = DiceFairness.check(rolls.map { it.a + it.b }, *dice.toTypedArray())
            File("build/dice-fairness").apply { mkdirs() }.resolve("online-${expansion.name.lowercase()}.txt").writeText(text)
            println("== dice fairness, online $expansion ==")
            print(text)
        }
    }

    @Test
    fun `break it - no dice are drawn when it isn't time to roll`() {
        val s = Rules.newGame(GameConfig(), 26)
        val hash = s.stateHash()
        assertThrows(IllegalArgumentException::class.java) { rollFromHistory(s, emptyList(), 0) }
        assertEquals(hash, s.stateHash())
    }
}

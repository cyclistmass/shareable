package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #26: 10,000 rolls from the dice a local app game really throws ([DiceDeck.of] RANDOM, with the event die in Age of
 * Chivalry), checked for fairness. The online path is checked in net's DiceFairnessTest and the CLI's in its own.
 */
class DiceFairnessTest {
    @Test
    fun `the app's true-random dice are fair over 10,000 throws - totals, each die and the event die`() {
        val dice = DiceDeck.of(DiceMode.RANDOM)
        assertTrue(dice is TrueRandomDice)
        val rolls = List(10_000) { dice.next(withEvent = true) }
        val report = DiceFairness.check(rolls.map { it.sum }, rolls.map { it.d1 }, rolls.map { it.d2 }, rolls.map { it.event + 1 })
        saveFairness("app-local", report)
        assertEquals(10_000, rolls.size)
        assertTrue(dice.next().event == -1, "no event die in the base game")
    }

    @Test
    fun `break it - the checker catches a loaded die`() {
        val rng = java.util.Random(26)
        val loaded = List(10_000) { if (rng.nextInt(5) == 0) 6 else rng.nextInt(6) + 1 }
        val fair = List(10_000) { rng.nextInt(6) + 1 }
        assertTrue(DiceFairness.faceChiSquare(loaded) > DiceFairness.FACE_CRITICAL, "a die showing 6 a third of the time passed")
        assertTrue(runCatching { DiceFairness.check(fair.zip(loaded).map { (a, b) -> a + b }, fair, loaded) }.isFailure)
    }
}

/** Writes [text] to build/dice-fairness/[name].txt and prints it. */
internal fun saveFairness(name: String, text: String) {
    java.io.File("build/dice-fairness").apply { mkdirs() }.resolve("$name.txt").writeText(text)
    println("== dice fairness, $name ==")
    print(text)
}

package com.mauro.wgbg

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import wgbg.engine.DiceRoll

class TestDiceTest {
    @AfterEach fun realDice() = TestDice.use(null)

    @Test
    fun `the debug dice come in order, over and over, and the event die only where asked`() {
        TestDice.use("3,3 4,4,2")
        val d = TestDice.source()!!
        assertEquals(listOf(DiceRoll(3, 3), DiceRoll(4, 4), DiceRoll(3, 3)), List(3) { d.next() })
        assertEquals(DiceRoll(4, 4, 2), d.next(withEvent = true))
        assertEquals(DiceRoll(3, 3), d.next(withEvent = true), "no face given: the engine throws the event die")
    }

    @Test
    fun `no extra means real dice`() {
        TestDice.use(null)
        assertNull(TestDice.source())
        TestDice.use("  ")
        assertNull(TestDice.source())
    }

    @Test
    fun `ci_trade's dice never roll a 7 and roll every other number`() {
        val text = java.io.File("../tools/ci_trade.sh").readText().substringAfter("DICE=\"\${DICE-").substringBefore("}\"")
        TestDice.use(text)
        val sums = TestDice.rolls!!.map { it.sum }
        assertEquals((2..12).filter { it != 7 }.toSet(), sums.toSet(), text)
    }
}

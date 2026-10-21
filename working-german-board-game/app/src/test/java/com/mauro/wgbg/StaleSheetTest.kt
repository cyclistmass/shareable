package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action

/** #105: a picker left open after its moves stopped being legal hid the Roll button under "roll the dice". */
class StaleSheetTest {
    private val roll = listOf(Action.roll(3, 4))

    @Test
    fun `a Windfall or Corner the Market picker closes once it has nothing legal to pick`() {
        assertFalse(sheetStillLegal("yop", null, roll))
        assertFalse(sheetStillLegal("mono", null, roll))
        assertTrue(sheetStillLegal("yop", null, roll + Action.yearOfPlenty(1, 2)))
        assertTrue(sheetStillLegal("mono", null, roll + Action.monopoly(3)))
    }

    @Test
    fun `an Age of Chivalry picker closes when none of its choices is legal any more`() {
        val choices = listOf(Action.monopoly(0), Action.monopoly(1))
        assertFalse(sheetStillLegal("chivalry", choices, roll))
        assertFalse(sheetStillLegal("chivalry", null, roll))
        assertTrue(sheetStillLegal("chivalry", choices, roll + Action.monopoly(1)))
        assertFalse(sheetStillLegal(null, null, roll))
    }
}

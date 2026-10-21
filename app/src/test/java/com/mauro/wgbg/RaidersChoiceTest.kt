package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ChivalryActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.engine.Rules

/** The corner choices of Age of Chivalry say what they cost, and their confirm box never covers the spot (0.2.7 online test). */
class RaidersChoiceTest {
    private val names = listOf("Red", "Blue", "Orange")
    private val corner = Action.of(ChivalryActionType.CHOOSE_VERTEX, 28L)

    @Test
    fun `losing a town to the Raiders is named as such on the button and in the question`() {
        val s = Rules.newGame(GameConfig(Expansion.CHIVALRY, numPlayers = 3), 1)
        s.phase = Phase.BARBARIAN_LOSS
        assertEquals("Lose this town", chivalryLabel(s, corner, names))
        assertEquals("The Raiders won: lose this town to them?", confirmTitle(s, corner))
        s.phase = Phase.DESERTER_REMOVE
        assertEquals("Remove this knight", chivalryLabel(s, corner, names))
        assertEquals("Turncoat: remove this knight of yours?", confirmTitle(s, corner))
    }

    @Test
    fun `the confirm box moves to the bottom when the spot is high on the board, and stays on top otherwise`() {
        assertTrue(confirmBelow(100f, 1000f))
        assertTrue(confirmBelow(449f, 1000f))
        assertFalse(confirmBelow(450f, 1000f))
        assertFalse(confirmBelow(900f, 1000f))
        assertFalse(confirmBelow(null, 1000f))
        assertFalse(confirmBelow(100f, 0f))
    }
}

package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.DevCard
import wgbg.engine.GameConfig
import wgbg.engine.Expansion
import wgbg.engine.Progress
import wgbg.engine.Rules

class CardHintsTest {
    private val board = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 1).board

    @Test
    fun `every card has a hint`() {
        assertEquals(Progress.COUNT, PROGRESS_HINTS.size)
        assertTrue(PROGRESS_HINTS.all { it.isNotBlank() })
        assertEquals(DevCard.COUNT, FORTUNE_HINTS.size)
    }

    @Test
    fun `board cards glow on their spot and a tap there picks them`() {
        val envoy = listOf(Action.playProgress(Progress.DIPLOMAT, 5), Action.playProgress(Progress.DIPLOMAT, 9))
        assertTrue(isBoardCard(envoy))
        assertEquals(setOf(5, 9), Targets.ofCards(envoy).edges)
        assertEquals(listOf(envoy[1]), movesAt(board, envoy, edgeLattice(board, 9)))
        val abbot = listOf(Action.playProgress(Progress.BISHOP, 3))
        assertEquals(setOf(3), Targets.ofCards(abbot).hexes)
        assertEquals(abbot, movesAt(board, abbot, hexLattice(board, 3)))
        assertFalse(isBoardCard(listOf(Action.playProgress(Progress.SPY, 1))))
    }

    @Test
    fun `the Tinkerer picks two tiles - first tap narrows, second tap chooses`() {
        val swaps = listOf(Action.playProgress(Progress.INVENTOR, 2, 7), Action.playProgress(Progress.INVENTOR, 2, 11), Action.playProgress(Progress.INVENTOR, 4, 7))
        val choices = pickChoices(swaps)
        // Every tile in any swap glows for the first tap, the higher ones too (report 6akrzuq: "only highlights some").
        assertEquals(setOf(2, 4, 7, 11), Targets.ofCards(choices).hexes)
        val first = movesAt(board, choices, hexLattice(board, 2))
        assertEquals(setOf(7, 11), Targets.ofCards(first, second = true).hexes)
        assertEquals(listOf(swaps[1]), movesAt(board, first, hexLattice(board, 11), second = true).map(::asLegalPick))
        // Starting from the higher tile: 7 pairs with 2 and 4, and the pick comes back in the engine's order.
        val fromSeven = movesAt(board, choices, hexLattice(board, 7))
        assertEquals(setOf(2, 4), Targets.ofCards(fromSeven, second = true).hexes)
        assertEquals(listOf(swaps[2]), movesAt(board, fromSeven, hexLattice(board, 4), second = true).map(::asLegalPick))
    }

    @Test
    fun `a Tinkerer pick from a real game offers every movable tile and only legal swaps`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 7)
        val swaps = (0 until s.board.hexCount).flatMap { h1 -> (h1 + 1 until s.board.hexCount).map { h2 -> h1 to h2 } }
            .filter { (h1, h2) -> s.numberAt(h1) != s.numberAt(h2) && Progress.inventorMovable(s.numberAt(h1)) && Progress.inventorMovable(s.numberAt(h2)) }
            .map { (h1, h2) -> Action.playProgress(Progress.INVENTOR, h1, h2) }
        val movable = (0 until s.board.hexCount).filter { Progress.inventorMovable(s.numberAt(it)) }.toSet()
        assertEquals(movable, Targets.ofCards(pickChoices(swaps)).hexes)
        // Whatever two tiles are tapped, in either order, the move sent is one the engine listed.
        for (a in pickChoices(swaps)) assertTrue(asLegalPick(a) in swaps)
        assertEquals(swaps, swaps.map(::asLegalPick))
    }

    @Test
    fun `player choices show names, not numbers`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 1)
        assertEquals("Blue", progressOption(s, Action.playProgress(Progress.SPY, 1), listOf("You", "Blue", "Orange")))
    }
}

package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.ai.RandomBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ChivalryActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Rules

/** Feast against you: both cards are chosen first, then given with one press, as a card per engine move. */
class FeastSheetTest {
    /** Games paused each time a player owes two cards to a Feast. */
    private fun feasts(): List<GameState> = (1L..80L).mapNotNull { seed ->
        val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 4), seed)
        val bots = List(4) { if (it % 2 == 0) GreedyBot(seed + it) else RandomBot(seed * 5 + it) }
        val buf = ActionBuffer()
        while (!s.isOver && s.turn < 300) {
            if (s.phase == Phase.WEDDING && s.chivalry!!.giveOwed[s.actingPlayer] == 2) return@mapNotNull s
            Rules.legalActions(s, buf)
            Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
        }
        null
    }

    @Test
    fun `a full pile of two gives exactly those cards to the Feast's player`() {
        val found = feasts()
        assertTrue(found.size >= 3, "Feasts found: ${found.size}")
        for (s in found) {
            val p = s.actingPlayer; val to = s.currentPlayer
            val cards = (0 until tradeCards(s)).flatMap { c -> List(minOf(2, holding(s, p, c))) { c } }
            var pile = IntArray(tradeCards(s))
            for (c in cards.take(2)) pile = discardPick(pile, c, true, holding(s, p, c), 2)
            assertEquals(2, pile.sum())
            val moves = feastMoves(pile)
            assertTrue(moves.all { it.type == ChivalryActionType.CHOOSE_CARD })
            val had = (0 until tradeCards(s)).map { holding(s, p, it) }
            val got = (0 until tradeCards(s)).map { holding(s, to, it) }
            for (m in moves) { assertTrue(Rules.isLegal(s, m), "$m in ${s.phase}"); Rules.apply(s, m) }
            for (c in 0 until tradeCards(s)) {
                assertEquals(had[c] - pile[c], holding(s, p, c), "gave card $c")
                assertEquals(got[c] + pile[c], holding(s, to, c), "got card $c")
            }
            assertEquals(0, s.chivalry!!.giveOwed[p])
            assertFalse(s.phase == Phase.WEDDING && s.actingPlayer == p, "done giving")
        }
    }

    @Test
    fun `a card you don't hold can't be given and changes nothing`() {
        val s = feasts().first()
        val p = s.actingPlayer
        val none = (0 until tradeCards(s)).firstOrNull { holding(s, p, it) == 0 } ?: return
        var pile = IntArray(tradeCards(s))
        pile = discardPick(pile, none, true, holding(s, p, none), 2)
        assertEquals(0, pile.sum(), "the pile never takes a card you don't have")
        val before = s.stateHash()
        val junk = Action.chooseCard(none)
        assertFalse(Rules.isLegal(s, junk))
        runCatching { Rules.applyChecked(s, junk) }
        assertEquals(before, s.stateHash())
    }
}

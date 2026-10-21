package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ActionType
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.engine.Rules

class DiscardSheetTest {
    @Test
    fun `picking stops at what you hold and what you owe, hold takes one back`() {
        var pile = IntArray(5)
        repeat(5) { pile = discardPick(pile, 0, add = true, have = 2, owed = 4) }
        assertEquals(2, pile[0])                                        // only 2 held
        repeat(5) { pile = discardPick(pile, 3, add = true, have = 9, owed = 4) }
        assertEquals(4, pile.sum()); assertEquals(2, pile[3])          // stops at 4 owed
        pile = discardPick(pile, 0, add = false, have = 2, owed = 4)
        assertEquals(1, pile[0]); assertEquals(3, pile.sum())
        assertEquals(3, discardPick(pile, 2, add = false, have = 1, owed = 4).sum())   // taking back a card not in the pile does nothing
    }

    @Test
    fun `#109 for commodities - a discarded Parchment, Fabric or Silver steps back to none`() {
        val n = wgbg.engine.CHIVALRY_CARD_TYPES
        for (r in wgbg.engine.CARD_TYPES until n) {
            var pile = IntArray(n)
            repeat(3) { pile = discardPick(pile, r, add = true, have = 3, owed = 5) }
            assertEquals(3, pile[r])
            repeat(4) { pile = discardPick(pile, r, add = false, have = 3, owed = 5) }
            assertTrue(pile.all { it == 0 }, "commodity $r back to none")
        }
    }

    @Test
    fun `a full pile becomes legal discard moves that pay exactly what is owed`() {
        // Hand a player 9 cards and roll a 7 so they owe 4.
        val s = Rules.newGame(GameConfig(numPlayers = 3), 21)
        val buf = wgbg.engine.ActionBuffer()
        val bots = List(3) { wgbg.ai.GreedyBot(it.toLong()) }
        while (s.phase != Phase.PLAY_TURN) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        val p = 1
        for (r in 0 until 5) s.hands[p * wgbg.engine.CARD_TYPES + r] = if (r < 4) 2 else 1      // 9 cards
        Rules.apply(s, Action.roll(3, 4))
        while (s.phase == Phase.DISCARD && s.actingPlayer != p) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        assertEquals(Phase.DISCARD, s.phase); assertEquals(p, s.actingPlayer)
        val owed = s.discardOwed[p]
        assertEquals(4, owed)
        var pile = IntArray(5)
        for (r in listOf(0, 0, 4, 2)) pile = discardPick(pile, r, true, holding(s, p, r), owed)
        val moves = discardMoves(pile)
        assertEquals(4, moves.size)
        for (m in moves) { assertTrue(Rules.isLegal(s, m), "$m"); Rules.apply(s, m) }
        assertEquals(0, s.discardOwed[p])
        assertEquals(listOf(0, 2, 1, 2, 0), (0 until 5).map { s.hand(p, it) })
        assertTrue(moves.all { it.type == ActionType.DISCARD })
    }
}

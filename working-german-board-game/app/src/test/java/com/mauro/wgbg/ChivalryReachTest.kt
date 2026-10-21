package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rng
import wgbg.engine.Rules

/** In Age of Chivalry every legal move for the human seat can be made from the screen. */
class ChivalryReachTest {
    /** Base-game moves the thumb column or the trade chest already offers. */
    private val buttons = setOf(ActionType.ROLL, ActionType.END_TURN, ActionType.DISCARD, ActionType.BUY_DEVELOPMENT_CARD,
        ActionType.PLAY_KNIGHT_CARD, ActionType.PLAY_ROAD_BUILDING, ActionType.PLAY_YEAR_OF_PLENTY, ActionType.PLAY_MONOPOLY,
        ActionType.ACCEPT_TRADE, ActionType.REJECT_TRADE, ActionType.CONFIRM_TRADE, ActionType.CANCEL_TRADE,
        // The trade chest: bank trades on its Harbour tab, offers on its Players tab.
        ActionType.MARITIME_TRADE, wgbg.engine.ChivalryActionType.OFFER_TRADE_CK, wgbg.engine.ChivalryActionType.COUNTER_TRADE_CK)

    @Test
    fun `every legal Age of Chivalry move for seat 0 is a board tap or a button`() {
        val names = listOf("You", "Blue", "Orange", "White")
        var checked = 0
        repeat(12) { g ->
            val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 4, maxTurns = 250), g * 101L)
            val rng = Rng(g.toLong())
            val buf = ActionBuffer()
            var steps = 0
            while (!s.isOver && steps < 20_000) {
                Rules.legalActions(s, buf)
                val legal = buf.toList()
                if (s.actingPlayer == 0) {
                    val grouped = chivalryButtonGroups(s, legal, names).flatMap { it.second }.toSet()
                    for (a in legal.filter { offered(s, it) }) {   // plays that would do nothing are left out on purpose
                        val tap = when {
                            a.type == ActionType.BUILD_SETTLEMENT || a.type == ActionType.BUILD_CITY -> movesAt(s.board, legal, vertexLattice(s.board, a.payload.toInt()))
                            a.type == ActionType.BUILD_ROAD -> movesAt(s.board, legal, edgeLattice(s.board, a.payload.toInt()))
                            a.type == ActionType.MOVE_ROBBER -> movesAt(s.board, legal, hexLattice(s.board, a.robberHex))
                            a.type in CHIVALRY_VERTEX_KINDS -> movesAt(s.board, legal, vertexLattice(s.board, chivalryVertex(a)))
                            else -> emptyList()
                        }
                        val reachable = a in tap || a in grouped || a.type in buttons
                        assertTrue(reachable, "unreachable for the human: $a in ${s.phase}")
                        checked++
                    }
                }
                Rules.apply(s, legal[rng.nextInt(legal.size)])
                steps++
            }
        }
        assertTrue(checked > 1000, "only $checked moves checked")
    }
}

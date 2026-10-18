package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.ActionBuffer
import wgbg.engine.CkActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Piece
import wgbg.engine.Rules

/** Tapping one of your towns offers its improvements (Mauro: "put the city upgrade on the city"). */
class TownTapTest {
    @Test
    fun `a tap on your town offers exactly the improvements you can pay for, and nowhere else`() {
        var checked = 0; var capitalOnly = 0
        for (seed in 1L..12L) {
            val s = Rules.newGame(GameConfig(Expansion.CITIES_AND_KNIGHTS, numPlayers = 3), seed)
            val bots = List(3) { GreedyBot(seed + it) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 400) {
                val legal = Rules.legalActions(s)
                val me = s.actingPlayer
                val improves = legal.filter { it.type == CkActionType.IMPROVE_CITY }
                if (improves.isNotEmpty()) {
                    val towns = (0 until s.board.topology.vertexCount).filter { s.vertexOwner[it] == me && s.vertexBuilding[it] == Piece.CITY }
                    for (t in towns) {
                        val at = vertexLattice(s.board, t)
                        val offered = townMovesAt(s, legal, at, me)
                        // When the only raise left makes a Capital and that Capital can't go here, this town offers nothing.
                        val forHere = improves.filter { it.b == 0 || it.b - 1 == t }
                        if (forHere.isEmpty()) { capitalOnly++; assertEquals(emptyList<Any>(), offered); continue }
                        assertTrue(offered.isNotEmpty() && offered.all { it in improves })
                        assertEquals(offered.sortedBy { it.a }, offered)
                        // Every deck you can raise here is there; a Capital only goes to the tapped town.
                        assertEquals(forHere.map { it.a }.toSet(), offered.map { it.a }.toSet())
                        assertTrue(offered.all { it.b == 0 || it.b - 1 == t })
                        // Someone else, or a tap off in the sea, gets nothing.
                        assertEquals(emptyList<Any>(), townMovesAt(s, legal, at, (me + 1) % 3))
                        assertEquals(emptyList<Any>(), townMovesAt(s, legal, Offset(at.x + 5f, at.y + 5f), me))
                        // A picked build-menu entry keeps the tap to that entry.
                        assertTrue(tapMoves(s, legal, legal, at, me, menuPicked = true).none { it.type == CkActionType.IMPROVE_CITY })
                        assertTrue(tapMoves(s, legal, legal, at, me, menuPicked = false).containsAll(offered))
                        checked++
                    }
                }
                Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
        assertTrue(checked > 20, "town taps checked: $checked, capital-only towns $capitalOnly")
        val s = Rules.newGame(GameConfig(Expansion.CITIES_AND_KNIGHTS, numPlayers = 3), 1)
        assertEquals("Improve your towns?", confirmTitle(s, wgbg.engine.Action.of(CkActionType.IMPROVE_CITY, 0L)))
    }
}

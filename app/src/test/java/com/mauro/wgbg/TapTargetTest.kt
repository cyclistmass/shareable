package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.RandomBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.ChivalryActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Piece
import wgbg.engine.Rules

/**
 * Tap menus on board objects (0.3.5): a knight or a town answers a tap with its own menu, built from [TAP_TARGETS].
 * Every knight or town move the rules offer sits on exactly one line of exactly one menu, so nothing is lost or doubled.
 */
class TapTargetTest {
    private val knightKinds = setOf(ChivalryActionType.ACTIVATE_KNIGHT, ChivalryActionType.PROMOTE_KNIGHT, ChivalryActionType.CHASE_ROBBER) + KNIGHT_MOVES
    private fun knightAt(a: Action) = if (a.type in KNIGHT_MOVES) a.a else chivalryVertex(a)

    private fun positions(seeds: LongRange, players: Int, check: (GameState, List<Action>) -> Unit): Int {
        var n = 0
        for (seed in seeds) {
            val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = players), seed)
            val bots = List(players) { RandomBot(it + seed) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 400) {
                val legal = Rules.legalActions(s)
                if (legal.any { it.type in knightKinds || it.type == ChivalryActionType.IMPROVE_CITY || it.type == ChivalryActionType.BUILD_CITY_WALL }) { check(s, legal); n++ }
                Rules.legalActions(s, buf)
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
        return n
    }

    private val knight = TAP_TARGETS.single { it.kind == TapKind.KNIGHT }
    private val town = TAP_TARGETS.single { it.kind == TapKind.TOWN }

    @Test
    fun `each knight move sits on exactly one line of its knight's menu, and that knight answers a tap`() {
        val found = positions(1L..10L, 3) { s, legal ->
            for (a in legal.filter { it.type in knightKinds }) {
                val k = knightAt(a)
                assertEquals(1, knight.lines.count { a in lineMoves(it, legal, k) }, "$a at knight $k")
                assertEquals(TapHit(knight, k), tapTargetAt(s, legal, vertexLattice(s.board, k), s.actingPlayer))
            }
        }
        assertTrue(found > 50, "only $found positions")
    }

    @Test
    fun `each town move sits on exactly one line of a town's menu`() {
        var seen = 0
        positions(11L..20L, 4) { s, legal ->
            val me = s.actingPlayer
            val towns = (0 until s.board.topology.vertexCount).filter { town.owns(s, me, it) }
            for (a in legal.filter { it.type == ChivalryActionType.IMPROVE_CITY || it.type == ChivalryActionType.BUILD_CITY_WALL }) {
                val at = towns.filter { v -> town.lines.any { a in lineMoves(it, legal, v) } }
                assertTrue(at.isNotEmpty(), "$a is on no town's menu")
                for (v in at) assertEquals(1, town.lines.count { a in lineMoves(it, legal, v) }, "$a at town $v")
                seen++
            }
        }
        assertTrue(seen > 20, "only $seen town moves")
    }

    @Test
    fun `the knights that glow are exactly the ones with something to do, and towns never glow`() {
        positions(21L..26L, 3) { s, legal ->
            assertEquals(legal.filter { it.type in knightKinds }.map { knightAt(it) }.toSet(), tapHighlights(s, legal, s.actingPlayer))
        }
    }

    @Test
    fun `a new kind of object is a table row - homesteads that upgrade on a tap`() {
        val homestead = TapTarget(TapKind.TOWN, { s, me, v -> s.vertexOwner[v] == me && s.vertexBuilding[v] == Piece.SETTLEMENT }, listOf(
            TapLine("upgrade", MoveIcon.TOWN, "Town") { a, v -> a.type == ActionType.BUILD_CITY && a.payload.toInt() == v },
        ), highlight = true)
        var hits = 0
        val s = Rules.newGame(GameConfig(numPlayers = 3), 4)
        val bots = List(3) { RandomBot(it.toLong()) }
        val buf = ActionBuffer()
        while (!s.isOver && s.turn < 300) {
            val legal = Rules.legalActions(s)
            for (a in legal.filter { it.type == ActionType.BUILD_CITY }) {
                val v = a.payload.toInt()
                val hit = tapTargetAt(s, legal, vertexLattice(s.board, v), s.actingPlayer, listOf(homestead))
                if (s.vertexBuilding[v] == Piece.SETTLEMENT) { assertEquals(v, hit?.vertex); hits++ }
                assertTrue(v in tapHighlights(s, legal, s.actingPlayer, listOf(homestead)) || s.vertexBuilding[v] != Piece.SETTLEMENT)
            }
            Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
        }
        assertTrue(hits > 3, "only $hits homestead taps")
    }

    @Test
    fun `break it - open sea, no player, the base game and an empty move list open nothing`() {
        positions(27L..29L, 3) { s, legal ->
            assertNull(tapTargetAt(s, legal, Offset(1000f, -1000f), s.actingPlayer))
            assertNull(tapTargetAt(s, legal, Offset.Zero, -1))
            assertTrue(tapHighlights(s, legal, -1).isEmpty())
            for (v in tapHighlights(s, legal, s.actingPlayer)) assertNull(tapTargetAt(s, emptyList(), vertexLattice(s.board, v), s.actingPlayer))
        }
        val base = Rules.newGame(GameConfig(numPlayers = 3), 1)
        assertTrue(tapHighlights(base, Rules.legalActions(base), base.actingPlayer).isEmpty())
    }
}

package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.ai.RandomBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.CkActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Rules

/**
 * Knight moves glow only for the knight the player picked (0.3.4, Mauro: with a knight able to move, every corner lit up
 * as a move target). Before a pick, each knight that can move glows; tapping it picks it, tapping it again lets go.
 */
class KnightPickTest {
    /** Plays Age of Chivalry games and calls [check] on every position where the player to act can move a knight. */
    private fun knightPositions(seeds: LongRange, players: Int, random: Boolean, check: (GameState, List<Action>) -> Unit): Int {
        var found = 0
        for (seed in seeds) {
            val s = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS, numPlayers = players), seed)
            val bots = List(players) { if (random) RandomBot(it + seed) else GreedyBot(it + seed) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 400) {
                val legal = Rules.legalActions(s)
                if (legal.any { it.type in KNIGHT_MOVES }) { check(s, legal); found++ }
                Rules.legalActions(s, buf)
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
        return found
    }

    @Test
    fun `before a pick, the knights that can move glow and none of their destinations do`() {
        val found = knightPositions(1L..12L, 3, random = true) { _, legal ->
            val targets = knightTargets(legal, null)
            assertTrue(targets.vertices.containsAll(movableKnights(legal)), "each movable knight glows")
            // A corner glows only for a move other than a knight's: a destination alone stays dark.
            val other = Targets.of(legal.filter { it.type !in KNIGHT_MOVES }).vertices
            for (a in legal.filter { it.type in KNIGHT_MOVES }) {
                val to = a.b
                if (to !in other && to !in movableKnights(legal)) assertTrue(to !in targets.vertices, "destination $to glows before a pick")
            }
        }
        assertTrue(found > 50, "only $found positions with a knight move")
    }

    @Test
    fun `a picked knight shows exactly its own moves`() {
        val found = knightPositions(13L..24L, 4, random = false) { _, legal ->
            for (k in movableKnights(legal)) {
                val shown = withKnightPick(legal, k)
                assertTrue(legal.containsAll(shown), "a pick never invents a move")
                val moves = shown.filter { it.type in KNIGHT_MOVES }
                assertEquals(legal.filter { it.type in KNIGHT_MOVES && it.a == k }.toSet(), moves.toSet())
                assertTrue(moves.isNotEmpty())
                // Everything else (builds, Supply, Promote...) is still offered.
                assertEquals(legal.filter { it.type !in KNIGHT_MOVES }, shown.filter { it.type !in KNIGHT_MOVES })
                val targets = knightTargets(legal, k)
                for (a in moves) assertTrue(a.b in targets.vertices, "the picked knight's destination ${a.b} glows")
            }
        }
        assertTrue(found > 50, "only $found positions with a knight move")
    }

    @Test
    fun `tapping a movable knight picks it, again lets it go, and a tap at a destination means only its move`() {
        var picked = 0
        knightPositions(25L..36L, 3, random = true) { s, legal ->
            for (k in movableKnights(legal)) {
                val at = vertexLattice(s.board, k)
                val busy = legal.any { it.type !in KNIGHT_MOVES && it.type in CK_VERTEX_KINDS && ckVertex(it) == k }
                if (busy) {
                    // Supply or Promote at that knight comes first; Move knight in the build menu is the way to move it.
                    assertEquals(null, knightPickAt(s, legal, at, null))
                    val menu = boardMoves(legal, BuildKind.MOVE)
                    assertEquals(k, knightPickAt(s, menu, at, null))
                    continue
                }
                assertEquals(k, knightPickAt(s, legal, at, null)); picked++
                assertNull(knightPickAt(s, legal, at, k), "a second tap lets go")
                for (a in legal.filter { it.type in KNIGHT_MOVES && it.a == k }) {
                    val tapped = tapMoves(s, withKnightPick(legal, k), legal, vertexLattice(s.board, a.b), s.actingPlayer, false)
                    assertTrue(tapped.none { it.type in KNIGHT_MOVES && it.a != k }, "a tap at ${a.b} offered another knight's move")
                    assertTrue(a in tapped || tapped.any { it.type !in KNIGHT_MOVES }, "the tap at ${a.b} lost its move")
                }
            }
        }
        assertTrue(picked > 20, "only $picked knights picked by a tap")
    }

    @Test
    fun `Move knight in the build menu offers moves and charges, nothing else`() {
        val found = knightPositions(37L..44L, 4, random = true) { _, legal ->
            val menu = boardMoves(legal, BuildKind.MOVE)
            assertEquals(legal.filter { it.type == CkActionType.MOVE_KNIGHT || it.type == CkActionType.DISPLACE_KNIGHT }, menu)
            assertTrue(BuildKind.MOVE in shownKinds(true, legal, null))
        }
        assertTrue(found > 0)
    }

    @Test
    fun `break it - taps away from knights, bogus picks and empty move lists change nothing`() {
        knightPositions(45L..48L, 3, random = true) { s, legal ->
            val far = Offset(1000f, -1000f)
            assertEquals(null, knightPickAt(s, legal, far, null))
            assertEquals(7, knightPickAt(s, legal, far, 7), "a tap on open sea keeps the pick as it was")
            for (bogus in listOf(-1, 999, Int.MAX_VALUE)) {
                assertTrue(withKnightPick(legal, bogus).none { it.type in KNIGHT_MOVES }, "pick $bogus offers no knight moves")
                assertEquals(legal.filter { it.type !in KNIGHT_MOVES }, withKnightPick(legal, bogus))
            }
        }
        assertTrue(movableKnights(emptyList()).isEmpty())
        assertTrue(knightTargets(emptyList(), null).isEmpty && knightTargets(emptyList(), 3).isEmpty)
        val base = Rules.newGame(GameConfig(numPlayers = 3), 1)
        assertEquals(null, knightPickAt(base, Rules.legalActions(base), Offset.Zero, null), "the base game has no knights to pick")
    }
}

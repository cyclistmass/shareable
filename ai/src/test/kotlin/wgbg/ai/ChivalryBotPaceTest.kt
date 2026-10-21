package wgbg.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Piece
import wgbg.engine.Rules

/**
 * Regression for the Age of Chivalry bot stall: Greedy bots used to spend every grain on knight
 * activations and knight moves, sat city-less while the Raiders took their towns, and games ran
 * 145 turns median (some 700). All-Greedy games must now finish at a normal pace.
 */
class ChivalryBotPaceTest {
    class Stats(val turns: List<Int>, val capped: Int, val botTurns: Int, val cityLessTurns: Int) {
        val median get() = turns[turns.size / 2]
        val p90 get() = turns[turns.size * 9 / 10]
        val over300 get() = turns.count { it > 300 }
        val cityLessPct get() = 100.0 * cityLessTurns / botTurns
        override fun toString() =
            "median=$median p90=$p90 >300=$over300 max=${turns.last()} capped=$capped cityLess=${"%.1f".format(cityLessPct)}%"
    }

    companion object {
        /** Plays [games] headless all-Greedy Chivalry games from [firstSeed]; a game ending at [cap] counts as capped. */
        fun play(players: Int, games: Int, firstSeed: Long, cap: Int): Stats {
            val turns = ArrayList<Int>()
            var capped = 0
            var botTurns = 0
            var cityLess = 0
            val buf = ActionBuffer()
            for (g in 0 until games) {
                val seed = firstSeed + g
                val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = players, maxTurns = cap), seed)
                val bots = List(players) { GreedyBot(seed * 31 + it) }
                var steps = 0
                while (!s.isOver && steps < 500_000) {
                    Rules.legalActions(s, buf)
                    val p = s.actingPlayer
                    val a = bots[p].choose(s, buf)
                    assertTrue(Rules.isLegal(s, a), "illegal $a")
                    if (a.type == ActionType.END_TURN) {
                        botTurns++
                        if (s.piecesOnBoard(p, Piece.CITY) == 0) cityLess++
                    }
                    Rules.apply(s, a)
                    steps++
                }
                assertTrue(s.isOver, "game $seed stuck after $steps steps")
                if (s.winner < 0) capped++
                turns += s.turn
            }
            turns.sort()
            return Stats(turns, capped, botTurns, cityLess)
        }
    }

    private fun check(players: Int) {
        val st = play(players, games = 40, firstSeed = 1000L, cap = 400)
        println("Chivalry ${players}p all-Greedy: $st")
        assertEquals(0, st.capped, "games hit the turn cap: $st")
        assertTrue(st.median < 130, "median game too long: $st")
        assertTrue(st.cityLessPct < 35.0, "bots too often without a town: $st")
    }

    @Test
    fun `three Greedy bots finish Chivalry games at a normal pace`() = check(3)

    @Test
    fun `four Greedy bots finish Chivalry games at a normal pace`() = check(4)
}

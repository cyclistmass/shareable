package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Random legal play across many seeds and configs, checking invariants after every action. */
class FuzzTest {
    @Test
    fun `2000 random games keep every invariant`() {
        var wins = 0
        var draws = 0
        var actions = 0L
        val types = IntArray(ActionType.entries.size)
        val coverage = Coverage("fuzz")
        val buf = ActionBuffer()
        for (game in 0 until 2000 * testScale) {
            val config = GameConfig(
                numPlayers = if (game % 10 == 3) 2 else 3 + game % 2,
                layout = if (game % 5 == 0) BoardLayout.BEGINNER else BoardLayout.RANDOM,
                avoidAdjacentRedNumbers = game % 3 != 0,
                randomDiscard = game % 4 == 1,
                generateTradeOffers = game % 4 == 2,
                maxTurns = 600,
            )
            val s = Rules.newGame(config, game * 7919L + 13)
            val bot = Rng(game.toLong())
            var steps = 0
            while (!s.isOver) {
                Rules.legalActions(s, buf)
                assertTrue(buf.size > 0, "no legal action in ${s.phase}")
                val a = buf[bot.nextInt(buf.size)]
                // Occasionally exercise the full legality check and copy.
                if (steps % 97 == 0) {
                    assertTrue(Rules.isLegal(s, a))
                    assertEquals(s.stateHash(), s.copy().stateHash())
                }
                Rules.apply(s, a)
                types[a.typeId]++
                T.checkInvariants(s)
                Coverage.base(coverage, game, s, a)
                coverage.note("the Fortune deck ran out", game, s.devDeck.sum() == 0)
                coverage.note("a 2-player game won", game, s.isOver && s.winner >= 0 && s.numPlayers == 2)
                steps++
                assertTrue(steps < 200_000, "game $game does not terminate")
            }
            actions += steps
            if (s.winner >= 0) wins++ else draws++
        }
        println("fuzz: $wins wins, $draws draws, $actions actions")
        println("action mix: " + ActionType.entries.joinToString { "${it.name}=${types[it.ordinal]}" })
        coverage.print()
        assertEquals(2000, wins + draws)
        assertTrue(wins > 1000, "random games should mostly finish")
        // Every action type must have been exercised.
        for (t in ActionType.entries) assertTrue(types[t.ordinal] > 0, "$t never played")
        // And every rare state the rules have, not just every kind of move.
        coverage.assertAll()
    }

    @Test
    fun `benchmark random playouts`() {
        val config = GameConfig(maxTurns = 1000)
        val buf = ActionBuffer()
        fun playout(seed: Long): Int {
            val s = Rules.newGame(config, seed)
            val bot = Rng(seed)
            var n = 0
            while (!s.isOver) {
                Rules.legalActions(s, buf)
                Rules.apply(s, buf[bot.nextInt(buf.size)])
                n++
            }
            return n
        }
        repeat(300) { playout(it.toLong()) } // warm-up
        val games = 2000
        var actions = 0L
        val t0 = System.nanoTime()
        for (g in 0 until games) actions += playout(10_000L + g)
        val secs = (System.nanoTime() - t0) / 1e9
        println("benchmark: %.0f games/s, %.0f actions/s (%d games, avg %d actions)".format(
            games / secs, actions / secs, games, actions / games,
        ))
    }
}

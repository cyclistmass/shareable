package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #126: the first player comes from the seed, the setup snake starts there, and so does the first turn. */
class FirstPlayerTest {
    private fun check(s: GameState) = if (s.chivalry != null) CK.checkInvariants(s) else T.checkInvariants(s)

    private fun setupOrder(config: GameConfig, seed: Long): Pair<List<Int>, GameState> {
        val s = Rules.newGame(config, seed)
        val order = mutableListOf<Int>()
        val rng = Rng(seed)
        while (s.phase != Phase.PLAY_TURN) {
            val l = T.legal(s)
            if (s.phase == Phase.BUILD_INITIAL_SETTLEMENT) order += s.actingPlayer
            Rules.applyChecked(s, l[rng.nextInt(l.size)])
        }
        return order to s
    }

    @Test
    fun `every seat can start, the snake goes round from it and back, and it takes the first turn`() {
        for (exp in Expansion.entries) for (n in (if (exp == Expansion.BASE) 2 else 3)..4) {
            val config = GameConfig(expansion = exp, numPlayers = n, randomFirstPlayer = true)
            val starts = mutableSetOf<Int>()
            for (seed in 1L..60L) {
                val f = Rules.firstPlayer(seed, n)
                starts += f
                val (order, s) = setupOrder(config, seed)
                val round = List(n) { (f + it) % n }
                assertEquals(round + round.reversed(), order, "$exp n=$n seed=$seed")
                assertEquals(f, s.currentPlayer); assertEquals(f, s.actingPlayer)
                for (p in 0 until n) assertEquals(if (exp == Expansion.BASE) 2 else 3, s.publicVictoryPoints(p))   // Age of Chivalry: homestead + town
                check(s)
            }
            assertEquals((0 until n).toSet(), starts, "$exp n=$n: every seat starts for some seed")
        }
    }

    @Test
    fun `the same seed gives the same first seat, board and dice whoever starts`() {
        for (seed in listOf(1L, 42L, -7L, Long.MAX_VALUE)) {
            assertEquals(Rules.firstPlayer(seed, 4), Rules.firstPlayer(seed, 4))
            val on = Rules.newGame(GameConfig(randomFirstPlayer = true), seed)
            val off = Rules.newGame(GameConfig(), seed)
            assertTrue(on.board.hexResource.contentEquals(off.board.hexResource))
            assertTrue(on.board.hexNumber.contentEquals(off.board.hexNumber))
            assertEquals(on.rng.seed, off.rng.seed)
            assertEquals(on.firstPlayer, on.copy().firstPlayer)
        }
    }

    @Test
    fun `off by default, seat 0 starts and the state hash is unchanged`() {
        val s = Rules.newGame(GameConfig(), 3)
        assertEquals(0, s.firstPlayer); assertEquals(0, s.currentPlayer)
        // A random-first game whose seed happens to pick seat 0 is the same game as one with it off.
        val seed = (1L..100L).first { Rules.firstPlayer(it, 4) == 0 }
        assertEquals(Rules.newGame(GameConfig(), seed).stateHash(), Rules.newGame(GameConfig(randomFirstPlayer = true), seed).stateHash())
    }

    @Test
    fun `whole games with a random first player stay legal to the end`() {
        for (exp in Expansion.entries) for (seed in 1L..20L) {
            val s = Rules.newGame(GameConfig(expansion = exp, numPlayers = if (exp == Expansion.BASE) 4 else 3, randomFirstPlayer = true), seed)
            val rng = Rng(seed * 7)
            var guard = 0
            while (!s.isOver && guard++ < 20000) {
                val l = T.legal(s)
                Rules.applyChecked(s, l[rng.nextInt(l.size)])
            }
            check(s)
        }
    }
}

package wgbg.engine

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HiddenInfoTest {
    private fun midGame(): GameState {
        val s = Rules.newGame(GameConfig(), 11)
        val bot = Rng(4)
        val buf = ActionBuffer()
        while (s.turn < 120 && !s.isOver) {
            Rules.legalActions(s, buf)
            Rules.apply(s, buf[bot.nextInt(buf.size)])
        }
        return s
    }

    @Test
    fun `view exposes only own cards and public counts`() {
        val s = T.playState()
        T.give(s, 1, Resource.ORE, 3)
        T.giveDev(s, 1, DevCard.VICTORY_POINT)
        T.giveDev(s, 0, DevCard.KNIGHT)
        val v = HiddenInfo.view(s, 0)
        assertEquals(3, v.handSize[1])
        assertEquals(1, v.devCount[1])
        assertEquals(0, v.publicVictoryPoints[1])
        assertEquals(listOf(0, 0, 0, 0, 0), v.hand.toList())
        assertEquals(1, v.devHand[DevCard.KNIGHT])
        assertEquals(23, v.devDeckSize)
    }

    @Test
    fun `determinize keeps public info and the viewer's own cards`() {
        val s = midGame()
        for (seed in 0L until 50) {
            val d = HiddenInfo.determinize(s, 0, seed)
            T.checkInvariants(d)
            for (p in 0 until s.numPlayers) {
                assertEquals(s.devCount(p), d.devCount(p))
                assertEquals(s.publicVictoryPoints(p), d.publicVictoryPoints(p))
                assertEquals(s.handSize(p), d.handSize(p))
                assertEquals(
                    (0 until DevCard.COUNT).sumOf { s.devNew[p * DevCard.COUNT + it] },
                    (0 until DevCard.COUNT).sumOf { d.devNew[p * DevCard.COUNT + it] },
                )
            }
            assertArrayEquals(s.devHand.copyOfRange(0, DevCard.COUNT), d.devHand.copyOfRange(0, DevCard.COUNT))
            assertArrayEquals(s.devPlayed, d.devPlayed)
            assertEquals(s.devDeck.sum(), d.devDeck.sum())
            assertEquals(seed, d.rng.seed)
        }
    }

    /** Plays random moves until some steal happened that [viewer] wasn't part of. */
    private fun withUnseenSteal(expansion: Expansion, viewer: Int, seed: Long): GameState? {
        val s = Rules.newGame(GameConfig(expansion = expansion), seed)
        val bot = Rng(seed + 99)
        val buf = ActionBuffer()
        var steps = 0
        while (!s.isOver && steps++ < 30_000) {
            Rules.legalActions(s, buf)
            Rules.apply(s, buf[bot.nextInt(buf.size)])
            val n = s.numPlayers
            if ((0 until n).any { t -> t != viewer && (0 until n).any { v -> v != viewer && v != t && s.unseenSteals[t * n + v] > 0 } }) return s
        }
        return null
    }

    @Test
    fun `steals the viewer didn't see are resampled - hand sizes and card totals stay exact`() {
        for (exp in listOf(Expansion.BASE, Expansion.CHIVALRY)) {
            var changed = 0; var tried = 0
            for (seed in 0L until 40L) {
                val s = withUnseenSteal(exp, 0, seed) ?: continue
                tried++
                val types = if (s.chivalry != null) CHIVALRY_CARD_TYPES else Resource.COUNT
                for (k in 0 until 5) {
                    val d = HiddenInfo.determinize(s, 0, seed * 7 + k)
                    for (p in 0 until s.numPlayers) assertEquals(s.cardCount(p), d.cardCount(p), "$exp hand size of $p")
                    for (c in 0 until types) assertEquals((0 until s.numPlayers).sumOf { s.card(it, c) }, (0 until d.numPlayers).sumOf { d.card(it, c) }, "$exp total of card $c")
                    for (c in 0 until types) assertEquals(s.card(0, c), d.card(0, c), "$exp viewer's own card $c")
                    if ((1 until s.numPlayers).any { p -> (0 until types).any { c -> s.card(p, c) != d.card(p, c) } }) changed++
                }
            }
            assertTrue(tried >= 10, "$exp: only $tried games had an unseen steal")
            assertTrue(changed > 0, "$exp: unseen steals were never resampled")
        }
    }

    @Test
    fun `steals the viewer took part in are not resampled`() {
        val s = Rules.newGame(GameConfig(), 3)
        T.give(s, 1, Resource.ORE, 2); T.give(s, 2, Resource.WOOD, 2)
        s.unseenSteals[0 * s.numPlayers + 1] = 1   // viewer 0 stole from 1
        s.unseenSteals[2 * s.numPlayers + 0] = 1   // 2 stole from viewer 0
        val d = HiddenInfo.determinize(s, 0, 5)
        for (p in 0 until s.numPlayers) for (r in 0 until Resource.COUNT) assertEquals(s.hand(p, r), d.hand(p, r))
    }
}

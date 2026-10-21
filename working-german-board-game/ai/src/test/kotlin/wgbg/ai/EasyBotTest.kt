package wgbg.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionKind
import wgbg.engine.ActionType
import wgbg.engine.BANK_PER_RESOURCE
import wgbg.engine.CARD_TYPES
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Rules

/** The Easy bot (#19's bot picker): legal, finishes games, always builds, accepts only clearly good trades. */
class EasyBotTest {
    private val builds: Set<ActionKind> = setOf(ActionType.BUILD_CITY, ActionType.BUILD_SETTLEMENT, ActionType.BUILD_ROAD)

    @Test
    fun `easy bots play legal games to the end, always build when they can, and never offer trades`() {
        var finished = 0
        var buildChances = 0
        for (g in 0 until 200) {
            val s = Rules.newGame(GameConfig(numPlayers = 2 + g % 3), g.toLong())
            val bots = List(s.numPlayers) { EasyBot(g * 17L + it) }
            val buf = ActionBuffer()
            var steps = 0
            while (!s.isOver && steps++ < 50_000) {
                Rules.legalActions(s, buf)
                val a = bots[s.actingPlayer].choose(s, buf)
                val couldBuild = (0 until buf.size).any { buf[it].type in builds }
                if (couldBuild) {
                    buildChances++
                    assertTrue(a.type in builds, "game $g: could build but chose ${a.type}")
                }
                assertTrue(a.type != ActionType.OFFER_TRADE && a.type != ActionType.COUNTER_TRADE, "Easy never starts or counters a trade")
                Rules.applyChecked(s, a)
                for (r in 0 until CARD_TYPES) assertEquals(BANK_PER_RESOURCE, s.bank[r] + (0 until s.numPlayers).sumOf { s.hand(it, r) })
            }
            assertTrue(s.isOver, "game $g stalled after $steps steps")
            if (s.winner >= 0) finished++
        }
        println("easy vs easy: $finished/200 won outright, $buildChances build chances all taken")
        assertTrue(finished >= 190, "almost every game should end with a winner, got $finished")
    }

    @Test
    fun `easy bots finish Age of Chivalry games too`() {
        for (g in 0 until 20) {
            val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3 + g % 2), g.toLong())
            playOut(s, List(s.numPlayers) { EasyBot(g * 5L + it) })
            assertTrue(s.isOver, "chivalry game $g did not finish")
        }
    }

    @Test
    fun `the same seed plays the same game`() {
        fun play(): Pair<Int, Int> {
            val s = Rules.newGame(GameConfig(numPlayers = 3), 42)
            val w = playOut(s, List(3) { EasyBot(9L + it) })
            return w to s.turn
        }
        assertEquals(play(), play())
    }

    @Test
    fun `easy sits between random and greedy`() {
        val games = 200
        var easyVsRandom = 0
        var easyVsGreedy = 0
        for (g in 0 until games) {
            val seat = g % 4
            val a = Rules.newGame(GameConfig(numPlayers = 4), g.toLong())
            if (playOut(a, List(4) { if (it == seat) EasyBot(g * 3L) else RandomBot(g * 11L + it) }) == seat) easyVsRandom++
            val b = Rules.newGame(GameConfig(numPlayers = 4), g.toLong())
            if (playOut(b, List(4) { if (it == seat) EasyBot(g * 3L) else GreedyBot(g * 11L + it) }) == seat) easyVsGreedy++
        }
        println("easy wins: ${100 * easyVsRandom / games}% vs 3 random, ${100 * easyVsGreedy / games}% vs 3 greedy (fair share 25%)")
        assertTrue(easyVsRandom > games / 4, "easy should beat random bots more than its fair share, won $easyVsRandom/$games")
        assertTrue(easyVsGreedy < games / 4, "easy should lose to greedy bots more than its fair share, won $easyVsGreedy/$games")
    }

    // ---------------------------------------------------------------- trades

    /** Player 0 on their own turn after rolling (seed 7 rolls no 7 there, so no discard detour). */
    private fun atOffer(vpToWin: Int = 10): GameState {
        val s = Rules.newGame(GameConfig(numPlayers = 4, vpToWin = vpToWin), 7)
        val buf = ActionBuffer()
        val rnd = RandomBot(1)
        while (s.phase != Phase.PLAY_TURN || s.currentPlayer != 0) { Rules.legalActions(s, buf); Rules.apply(s, rnd.choose(s, buf)) }
        if (!s.hasRolled) Rules.apply(s, Action.ROLL)
        assertEquals(Phase.PLAY_TURN, s.phase, "seed 7 should leave player 0 free to trade")
        return s
    }

    private fun GameState.set(p: Int, vararg counts: Int) {
        for (r in 0 until CARD_TYPES) { bank[r] += hand(p, r); hands[p * CARD_TYPES + r] = 0 }
        for (r in 0 until CARD_TYPES) { hands[p * CARD_TYPES + r] = counts[r]; bank[r] -= counts[r] }
    }

    /** Offers [give] for [want] from player 0 and returns Easy's answer as player 1. */
    private fun answer(s: GameState, give: IntArray, want: IntArray): ActionKind {
        Rules.applyChecked(s, Action.offerTrade(give, want))
        while (s.phase == Phase.DECIDE_TRADE && s.actingPlayer != 1) Rules.apply(s, Action.REJECT_TRADE)
        val buf = ActionBuffer()
        Rules.legalActions(s, buf)
        return EasyBot(3).choose(s, buf).type
    }

    private fun one(r: Int) = IntArray(CARD_TYPES).also { it[r] = 1 }

    @Test
    fun `easy accepts a card it lacks for one it hoards`() {
        val s = atOffer()
        s.set(0, 3, 0, 0, 0, 0)
        s.set(1, 0, 0, 4, 1, 0) // no timber, four fleece
        assertEquals(ActionType.ACCEPT_TRADE, answer(s, give = one(0), want = one(2)))
    }

    @Test
    fun `easy accepts two for one`() {
        val s = atOffer()
        s.set(0, 3, 0, 0, 0, 0)
        s.set(1, 1, 0, 1, 1, 0)
        assertEquals(ActionType.ACCEPT_TRADE, answer(s, give = IntArray(CARD_TYPES).also { it[0] = 2 }, want = one(2)))
    }

    @Test
    fun `easy refuses an even swap that isn't clearly good`() {
        val s = atOffer()
        s.set(0, 3, 0, 0, 0, 0)
        s.set(1, 1, 0, 2, 1, 0) // already has timber, only two fleece
        assertEquals(ActionType.REJECT_TRADE, answer(s, give = one(0), want = one(2)))
    }

    @Test
    fun `easy refuses even a great trade from someone about to win`() {
        // Setup leaves player 0 on 2 points; with a 4-point game they are within 2 of winning.
        val s = atOffer(vpToWin = 4)
        assertTrue(s.publicVictoryPoints(0) >= 2)
        s.set(0, 3, 0, 0, 0, 0)
        s.set(1, 0, 0, 4, 1, 0)
        assertEquals(ActionType.REJECT_TRADE, answer(s, give = one(0), want = one(2)))
    }
}

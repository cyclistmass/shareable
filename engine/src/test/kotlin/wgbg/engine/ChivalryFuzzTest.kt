package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Random legal Age of Chivalry play, checking every invariant after every action. */
class ChivalryFuzzTest {
    private val allKinds: List<ActionKind> = ActionType.entries + ChivalryActionType.entries

    @Test
    fun `1000 random Age of Chivalry games keep every invariant`() {
        var wins = 0
        var draws = 0
        var actions = 0L
        val counts = HashMap<ActionKind, Int>()
        val phases = HashMap<Phase, Int>()
        val cards = IntArray(Progress.COUNT)
        var attacks = 0
        var metropolises = 0
        val buf = ActionBuffer()
        for (game in 0 until 1000 * testScale) {
            val config = GameConfig(
                expansion = Expansion.CHIVALRY,
                numPlayers = 3 + game % 2,
                layout = if (game % 5 == 0) BoardLayout.BEGINNER else BoardLayout.RANDOM,
                randomDiscard = game % 4 == 1,
                generateTradeOffers = game % 4 == 2,
                maxTurns = 600,
            )
            val s = Rules.newGame(config, game * 7919L + 17)
            val bot = Rng(game.toLong() + 99)
            var steps = 0
            while (!s.isOver) {
                Rules.legalActions(s, buf)
                assertTrue(buf.size > 0, "no legal action in ${s.phase}")
                val a = pick(s, buf, bot)
                if (steps % 97 == 0) {
                    assertTrue(Rules.isLegal(s, a), "generated action $a not legal")
                    assertEquals(s.stateHash(), s.copy().stateHash())
                }
                val before = s.phase
                try {
                    Rules.apply(s, a)
                    CK.checkInvariants(s)
                } catch (e: Throwable) {
                    throw AssertionError("game $game step $steps: $a in $before -> ${s.phase}: ${e.message}", e)
                }
                counts.merge(a.type, 1, Int::plus)
                phases.merge(s.phase, 1, Int::plus)
                if (a.type == ChivalryActionType.PLAY_PROGRESS) cards[a.a]++
                steps++
                assertTrue(steps < 300_000, "game $game does not terminate")
            }
            actions += steps
            attacks += s.chivalry!!.barbarianAttacks
            metropolises += s.chivalry!!.metroOwner.count { it >= 0 }
            if (s.winner >= 0) wins++ else draws++
        }
        println("chivalry fuzz: $wins wins, $draws draws, $actions actions, $attacks barbarian attacks, $metropolises metropolises")
        println("chivalry action mix: " + allKinds.joinToString { "$it=${counts[it] ?: 0}" })
        println("chivalry phases: " + Phase.entries.joinToString { "$it=${phases[it] ?: 0}" })
        println("chivalry progress plays: " + Progress.NAMES.indices.joinToString { "${Progress.NAMES[it]}=${cards[it]}" })
        assertEquals(1000, wins + draws)
        assertTrue(wins > 400, "random games should often finish")
        val unused = setOf<ActionKind>(
            ActionType.BUY_DEVELOPMENT_CARD, ActionType.PLAY_KNIGHT_CARD, ActionType.PLAY_YEAR_OF_PLENTY,
            ActionType.PLAY_MONOPOLY, ActionType.PLAY_ROAD_BUILDING, ActionType.OFFER_TRADE, ActionType.COUNTER_TRADE,
        )
        for (k in allKinds) {
            if (k in unused) assertEquals(0, counts[k] ?: 0, "$k played in Age of Chivalry")
            else assertTrue((counts[k] ?: 0) > 0, "$k never played")
        }
        for (p in Phase.entries) if (p != Phase.DECIDE_COUNTER) assertTrue((phases[p] ?: 0) > 0, "phase $p never reached") // counters are base-game only
        for (c in 0 until Progress.COUNT) {
            if (!Progress.isVictoryPoint(c)) assertTrue(cards[c] > 0, "${Progress.NAMES[c]} never played")
        }
    }

    private val building = setOf<ActionKind>(
        ActionType.BUILD_SETTLEMENT, ActionType.BUILD_CITY, ChivalryActionType.IMPROVE_CITY, ChivalryActionType.BUILD_CITY_WALL,
        ChivalryActionType.BUILD_KNIGHT, ChivalryActionType.ACTIVATE_KNIGHT, ChivalryActionType.PROMOTE_KNIGHT,
    )

    /**
     * Random choice biased so random games build enough to reach the interesting rules and
     * finish: half the time a building action is preferred when one exists, and END_TURN,
     * trades and offers are down-weighted.
     */
    private fun pick(s: GameState, buf: ActionBuffer, bot: Rng): Action {
        if (s.phase == Phase.PLAY_TURN && bot.nextInt(2) == 0) {
            var k = 0
            for (i in 0 until buf.size) if (buf[i].type in building) k++
            if (k > 0) {
                var j = bot.nextInt(k)
                for (i in 0 until buf.size) if (buf[i].type in building && j-- == 0) return buf[i]
            }
        }
        while (true) {
            val a = buf[bot.nextInt(buf.size)]
            val t = a.type
            val slow = t == ActionType.END_TURN || t == ChivalryActionType.OFFER_TRADE_CK || t == ActionType.MARITIME_TRADE
            if (buf.size > 1 && slow && bot.nextInt(4) != 0) continue
            return a
        }
    }

    @Test
    fun `benchmark random Age of Chivalry playouts`() {
        val buf = ActionBuffer()
        fun playout(seed: Long, players: Int): Int {
            val s = Rules.newGame(CK.config(players, BoardLayout.RANDOM, maxTurns = 1000), seed)
            val bot = Rng(seed)
            var n = 0
            while (!s.isOver) {
                Rules.legalActions(s, buf)
                Rules.apply(s, pick(s, buf, bot))
                n++
            }
            return n
        }
        repeat(200) { playout(it.toLong(), 4) } // warm-up
        val games = 1000
        var actions = 0L
        val t0 = System.nanoTime()
        for (g in 0 until games) actions += playout(50_000L + g, 3 + g % 2)
        val secs = (System.nanoTime() - t0) / 1e9
        println("chivalry benchmark: %.0f games/s, %.0f actions/s (%d games, avg %d actions)".format(
            games / secs, actions / secs, games, actions / games,
        ))
    }
}

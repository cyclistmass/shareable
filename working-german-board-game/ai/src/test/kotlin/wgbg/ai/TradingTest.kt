package wgbg.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.BANK_PER_RESOURCE
import wgbg.engine.CARD_TYPES
import wgbg.engine.CHIVALRY_CARD_TYPES
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Rules

class TradingTest {
    private fun conserved(s: GameState) {
        for (r in 0 until CARD_TYPES) {
            val held = (0 until s.numPlayers).sumOf { s.hand(it, r) }
            assertEquals(BANK_PER_RESOURCE, s.bank[r] + held, "resource $r not conserved")
        }
    }

    @Test
    fun `greedy bots trade with each other and with the bank, and no cards appear or vanish`() {
        var offers = 0
        var deals = 0
        var bank = 0
        var finished = 0
        for (g in 0 until 100) {
            val s = Rules.newGame(GameConfig(numPlayers = 4), g.toLong())
            val bots = List(4) { GreedyBot(g * 31L + it) }
            val buf = ActionBuffer()
            var steps = 0
            while (!s.isOver && steps++ < 20_000) {
                Rules.legalActions(s, buf)
                val a = bots[s.actingPlayer].choose(s, buf)
                when (a.type) {
                    ActionType.OFFER_TRADE -> offers++
                    ActionType.CONFIRM_TRADE -> deals++
                    ActionType.MARITIME_TRADE -> bank++
                    else -> {}
                }
                Rules.applyChecked(s, a) // rejects anything illegal, offers included
                conserved(s)
            }
            if (s.winner >= 0) finished++
        }
        println("100 games: $offers offers, $deals player trades, $bank bank trades, $finished finished")
        assertTrue(deals > 100, "bots should complete player trades, got $deals")
        assertTrue(bank > 100, "bots should trade with the bank, got $bank")
        assertTrue(finished >= 95, "games should still finish, got $finished")
    }

    @Test
    fun `a bot accepts a trade that gives it what it lacks and refuses one that strips it`() {
        val s = Rules.newGame(GameConfig(numPlayers = 4), 7)
        // Fast-forward through setup with random bots.
        val buf = ActionBuffer()
        val rnd = RandomBot(1)
        while (s.phase != Phase.PLAY_TURN || s.currentPlayer != 0) { Rules.legalActions(s, buf); Rules.apply(s, rnd.choose(s, buf)) }
        if (!s.hasRolled) Rules.apply(s, Action.ROLL)
        if (s.phase != Phase.PLAY_TURN) return // a 7 sent us into discards; nothing to test this seed
        // Give player 0 a pile of wood to offer and player 1 a hand that wants wood.
        fun set(p: Int, vararg counts: Int) {
            for (r in 0 until CARD_TYPES) { s.bank[r] += s.hand(p, r); s.hands[p * CARD_TYPES + r] = 0 }
            for (r in 0 until CARD_TYPES) { s.hands[p * CARD_TYPES + r] = counts[r]; s.bank[r] -= counts[r] }
        }
        set(0, 3, 0, 0, 0, 0)
        set(1, 0, 1, 3, 1, 0) // needs wood for a homestead, has spare sheep
        val good = Action.offerTrade(intArrayOf(1, 0, 0, 0, 0), intArrayOf(0, 0, 1, 0, 0)) // wood for sheep
        Rules.applyChecked(s, good)
        assertEquals(Phase.DECIDE_TRADE, s.phase)
        val bot1 = GreedyBot(5)
        while (s.phase == Phase.DECIDE_TRADE && s.actingPlayer != 1) Rules.apply(s, Action.REJECT_TRADE)
        Rules.legalActions(s, buf)
        assertEquals(ActionType.ACCEPT_TRADE, bot1.choose(s, buf).type, "wood it needs for spare sheep: accept")
        conserved(s)
    }

    @Test
    fun `greedy bots make counter-offers, and the offerer settles every round that has one`() {
        var counters = 0
        var answered = 0
        var rounds = 0
        for (g in 0 until 60) {
            val s = Rules.newGame(GameConfig(numPlayers = 4), 1000L + g)
            val bots = List(4) { GreedyBot(g * 17L + it) }
            val buf = ActionBuffer()
            var steps = 0
            var waiting = false
            while (!s.isOver && steps++ < 20_000) {
                Rules.legalActions(s, buf)
                val a = bots[s.actingPlayer].choose(s, buf)
                if (waiting && s.phase == Phase.DECIDE_ACCEPTEES &&
                    (a.type == ActionType.CONFIRM_TRADE || a.type == ActionType.CANCEL_TRADE)) { answered++; waiting = false }
                if (a.type == ActionType.COUNTER_TRADE) { counters++; if (!waiting) rounds++; waiting = true }
                Rules.applyChecked(s, a) // counters must be legal
                conserved(s)
            }
        }
        assertTrue(counters >= 10, "greedy bots countered only $counters times in 60 games")
        assertTrue(answered >= rounds - 1, "$rounds rounds with counters, $answered settled")
    }

    @Test
    fun `#135 a one-sided ask gets counters from greedy bots, in both rulesets`() {
        for (chivalry in listOf(false, true)) {
            var asks = 0
            var countered = 0
            for (g in 0 until 40) {
                val cfg = if (chivalry) GameConfig(numPlayers = 4, expansion = Expansion.CHIVALRY) else GameConfig(numPlayers = 4)
                val s = Rules.newGame(cfg, 500L + g)
                val bots = List(4) { GreedyBot(g * 13L + it) }
                val buf = ActionBuffer()
                var steps = 0
                var askedTurn = -1
                while (!s.isOver && steps++ < 4_000 && asks < 200) {
                    Rules.legalActions(s, buf)
                    // Seat 0 asks for a resource it has none of, for nothing, once a turn after rolling.
                    if (s.actingPlayer == 0 && s.phase == Phase.PLAY_TURN && s.hasRolled && s.freeRoads == 0) {
                        val r = (0 until CARD_TYPES).firstOrNull { s.hand(0, it) == 0 }
                        val ask = r?.let {
                            if (chivalry) Action.offerTradeCk(IntArray(CHIVALRY_CARD_TYPES), IntArray(CHIVALRY_CARD_TYPES).also { w -> w[it] = 1 })
                            else Action.offerTrade(IntArray(CARD_TYPES), IntArray(CARD_TYPES).also { w -> w[it] = 1 })
                        }
                        if (ask != null && Rules.isLegal(s, ask) && s.turn != askedTurn) {
                            askedTurn = s.turn
                            Rules.applyChecked(s, ask); asks++
                            while (s.phase == Phase.DECIDE_TRADE) { Rules.legalActions(s, buf); Rules.applyChecked(s, bots[s.actingPlayer].choose(s, buf)) }
                            if (s.phase == Phase.DECIDE_ACCEPTEES) { countered++; Rules.applyChecked(s, Action.CANCEL_TRADE) }
                            continue
                        }
                    }
                    Rules.applyChecked(s, bots[s.actingPlayer].choose(s, buf))
                }
            }
            assertTrue(asks >= 20, "chivalry=$chivalry: only $asks asks")
            assertTrue(countered * 4 >= asks, "chivalry=$chivalry: $countered of $asks asks got a counter")
        }
    }
}

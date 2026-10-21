package com.mauro.wgbg

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.CARD_TYPES
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Resource
import wgbg.engine.Rules

/** The trade panel's logic: which mode it is in, how terms are mirrored, and counters. */
class TradePanelTest {
    private fun cards(vararg rc: Pair<Int, Int>) = IntArray(CARD_TYPES).also { for ((r, n) in rc) it[r] = n }

    /** Plays bots until [me] has rolled on their own turn (and holds cards), or [stop] says so. */
    private fun until(seed: Long, stop: (GameState) -> Boolean): GameState {
        val s = Rules.newGame(GameConfig(numPlayers = 4), seed)
        val bots = List(4) { GreedyBot(it.toLong()) }
        val buf = ActionBuffer()
        while (!s.isOver && !stop(s)) {
            Rules.legalActions(s, buf)
            Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
        }
        return s
    }

    private fun myRolledTurn(s: GameState) =
        s.phase == Phase.PLAY_TURN && s.currentPlayer == 0 && s.hasRolled && s.handSize(0) >= 2 && s.handSize(1) >= 1

    @Test
    fun `modes follow the trade from compose to respond to counter`() {
        val s = until(3, ::myRolledTurn)
        assertEquals(TradeMode.COMPOSE, tradeMode(s, 0))
        assertEquals(TradeMode.CLOSED, tradeMode(s, 1))
        assertFalse(tradeNeedsMe(s, 0))

        // Offer one of our cards for something player 1 holds.
        val give = (0 until CARD_TYPES).first { s.hand(0, it) > 0 }
        val want = (0 until CARD_TYPES).first { it != give && s.hand(1, it) > 0 }
        Rules.applyChecked(s, Action.offerTrade(cards(give to 1), cards(want to 1)))
        assertEquals(TradeMode.RESPOND, tradeMode(s, 1))
        assertTrue(tradeNeedsMe(s, 1))
        assertEquals(TradeMode.WAITING, tradeMode(s, 0))

        // Seen by the responder, the terms are mirrored: they get what we give.
        val (get1, give1) = termsFor(s, 1)
        assertEquals(1, get1[give]); assertEquals(1, give1[want])
        val (get0, give0) = termsFor(s, 0)
        assertEquals(1, get0[want]); assertEquals(1, give0[give])

        // Player 1 counters: "I give the same, but I want 2". Only if we have 2. #135: the offer goes on round,
        // and at the end the offerer sees player 1 as a taker on the countered terms.
        if (s.hand(0, give) >= 2) {
            val c = counterFor(s, get = cards(give to 2), give = cards(want to 1))!!
            assertTrue(Rules.isLegal(s, c), "$c")
            Rules.applyChecked(s, c)
            assertEquals(Phase.DECIDE_TRADE, s.phase)
            assertEquals(2, s.actingPlayer)
            while (s.phase == Phase.DECIDE_TRADE) Rules.applyChecked(s, Action.REJECT_TRADE)
            assertEquals(TradeMode.PICK_PARTNER, tradeMode(s, 0))
            val (g, gv) = takerTerms(s, 1)
            assertEquals(1, g[want]); assertEquals(2, gv[give])
        }
    }

    @Test
    fun `#135 an open ask - Yes is off, Counter and No are on, and it is never auto-declined`() {
        val s = Rules.newGame(GameConfig(numPlayers = 3), 4)
        val buf = wgbg.engine.ActionBuffer()
        val bots = List(3) { wgbg.ai.GreedyBot(it.toLong()) }
        while (s.phase != Phase.PLAY_TURN) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        Rules.apply(s, Action.roll(2, 2))
        val from = s.currentPlayer; val to = (from + 1) % 3
        for (r in 0 until 5) { s.hands[from * CARD_TYPES + r] = 1; s.hands[to * CARD_TYPES + r] = 0 }
        Rules.applyChecked(s, offerFor(s, IntArray(5), cards(Resource.WHEAT to 1))!!) // "who has grain?"
        assertTrue(isOpenAsk(s))
        assertFalse(canPayOffer(s, to), "no grain to give...")
        assertFalse(Action.ACCEPT_TRADE in Rules.legalActions(s), "...and nothing to say yes to")
        s.hands[to * CARD_TYPES + Resource.WHEAT] = 2
        val c = counterFor(s, get = cards(Resource.SHEEP to 1), give = cards(Resource.WHEAT to 1))!!
        assertTrue(Rules.isLegal(s, c))
        Rules.applyChecked(s, c)
        while (s.phase == Phase.DECIDE_TRADE) Rules.applyChecked(s, Action.REJECT_TRADE)
        assertEquals(TradeMode.PICK_PARTNER, tradeMode(s, from))
        val (g, gv) = takerTerms(s, to)
        assertEquals(1, g[Resource.WHEAT]); assertEquals(1, gv[Resource.SHEEP])
        Rules.applyChecked(s, Action.confirmTrade(to))
        assertEquals(1, s.hand(to, Resource.SHEEP))
    }

    @Test
    fun `counterFor writes the counter from the offerer's side, in either ruleset`() {
        val base = Rules.newGame(GameConfig(numPlayers = 3), 1)
        val c = counterFor(base, get = cards(Resource.WOOD to 2), give = cards(Resource.ORE to 1))!!
        assertEquals(ActionType.COUNTER_TRADE, c.type)
        val chivalry = Rules.newGame(GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY), 1)
        val k = counterFor(chivalry, get = IntArray(8).also { it[5] = 1 }, give = IntArray(8).also { it[0] = 1 })!!
        assertEquals(wgbg.engine.ChivalryActionType.COUNTER_TRADE_CK, k.type)
        assertEquals(1, k.chivalryOfferGive(5)); assertEquals(1, k.chivalryOfferWant(0))
        assertEquals(2, c.offerGive(Resource.WOOD)) // the offerer gives us 2 timber
        assertEquals(1, c.offerWant(Resource.ORE)) // and gets our stone
    }

    @Test
    fun `a free road to place closes trading with the real reason, not that it is not your turn`() {
        // Mauro's playtest: Envoy on his own road gave a road to place again; the chest said it wasn't his turn.
        val s = until(3, ::myRolledTurn)
        assertEquals(TradeMode.COMPOSE, tradeMode(s, 0))
        s.freeRoads = 1
        assertEquals(TradeMode.CLOSED, tradeMode(s, 0))
        assertEquals("Place your free road first, then trade", tradeClosedReason(s, 0))
        assertEquals("Trade on your turn", tradeClosedReason(s, 1))
        assertTrue(prompt(s, Rules.legalActions(s), listOf("You", "Blue", "Orange", "White")).startsWith("Place your free road first"))
        s.freeRoads = 0
        assertEquals(TradeMode.COMPOSE, tradeMode(s, 0))
    }

    @Test
    fun `in Age of Chivalry, after any progress card your own turn never says it is not your turn, and trading comes back`() {
        // Mauro wasn't sure which action card it was, so every card bots play in 40 games is covered.
        var checked = 0; var cardsPlayed = 0
        for (seed in 1L..40L) {
            val s = Rules.newGame(GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY, numPlayers = 4), seed)
            val bots = List(4) { GreedyBot(it.toLong() + seed) }
            val buf = ActionBuffer()
            var afterCard = false
            while (!s.isOver && s.turn < 400) {
                val p = s.currentPlayer
                if (s.hasRolled && s.actingPlayer == p) {
                    val mode = tradeMode(s, p)
                    if (mode == TradeMode.CLOSED) assertTrue(tradeClosedReason(s, p) != "Trade on your turn", "seed $seed turn ${s.turn} phase ${s.phase}")
                    if (s.phase == Phase.PLAY_TURN && s.freeRoads == 0) {
                        assertEquals(TradeMode.COMPOSE, mode, "seed $seed turn ${s.turn}")
                        if (afterCard) { checked++; afterCard = false }
                    }
                }
                Rules.legalActions(s, buf)
                val a = bots[s.actingPlayer].choose(s, buf)
                if (a.type == wgbg.engine.ChivalryActionType.PLAY_PROGRESS && s.actingPlayer == s.currentPlayer && s.hasRolled) { afterCard = true; cardsPlayed++ }
                Rules.apply(s, a)
            }
        }
        assertTrue(cardsPlayed > 50 && checked > 50, "cards $cardsPlayed, back to trading $checked")
    }

    @Test
    fun `every pick-a-card moment says what it is for`() {
        val seen = mutableSetOf<Phase>()
        for (seed in 1L..40L) {
            val s = Rules.newGame(GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY, numPlayers = 4), seed)
            val bots = List(4) { GreedyBot(it.toLong() + seed) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 400) {
                Rules.legalActions(s, buf)
                if (Rules.legalActions(s).any { it.type == wgbg.engine.ChivalryActionType.CHOOSE_CARD }) {
                    assertTrue(cardChoiceTitle(s, listOf("You", "Blue", "Orange", "White")) != "Choose a card", "${s.phase}")
                    seen += s.phase
                }
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
        assertTrue(Phase.AQUEDUCT in seen, "science bonus seen: $seen")
    }

    @Test
    fun `before rolling the panel is closed`() {
        val s = until(5) { it.phase == Phase.PLAY_TURN && it.currentPlayer == 0 && !it.hasRolled }
        assertEquals(TradeMode.CLOSED, tradeMode(s, 0))
    }

    @Test
    fun `no Trade button while placing the first homesteads and roads`() {
        val s = Rules.newGame(GameConfig(numPlayers = 4), 1)
        assertFalse(tradeButtonVisible(s), "setup: first homestead")
        val before = until(1) { it.phase == Phase.PLAY_TURN && !it.hasRolled }
        assertFalse(tradeButtonVisible(before), "no trading before the roll (Mauro: trading before rolling broke the game)")
        val after = until(1) { it.phase == Phase.PLAY_TURN && it.hasRolled }
        assertTrue(tradeButtonVisible(after), "visible once the dice are rolled")
    }

    @Test
    fun `bank rates follow ports`() {
        val s = Rules.newGame(GameConfig(numPlayers = 4), 1)
        assertEquals(4, bankRate(s, 0, Resource.WOOD))
    }

    @Test
    fun `Age of Chivalry trades cover the commodities, mirror terms, and send the Age of Chivalry offer`() {
        val s = Rules.newGame(GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY, numPlayers = 4), 3)
        assertEquals(8, tradeCards(s))
        // Give seat 0 a Parchment (card 5) and seat 1 a Timber, then offer one for the other.
        s.chivalry!!.commodities[0 * wgbg.engine.Commodity.COUNT + 0] += 1   // seat 0: a Parchment (card 5)
        s.hands[1 * wgbg.engine.CARD_TYPES + 0] += 1                    // seat 1: a Timber
        val give = IntArray(8).also { it[5] = 1 }
        val get = IntArray(8).also { it[0] = 1 }
        val offer = offerFor(s, give, get)!!
        assertEquals(wgbg.engine.ChivalryActionType.OFFER_TRADE_CK, offer.type)
        assertEquals(1, holding(s, 0, 5))
        // Pretend it's our turn after rolling, so the offer applies.
        s.phase = Phase.PLAY_TURN; s.hasRolled = true; s.currentPlayer = 0; s.actingPlayer = 0
        Rules.apply(s, offer)
        val (get1, give1) = termsFor(s, 1)
        assertEquals(1, get1[5], "the responder gets the Parchment")
        assertEquals(1, give1[0], "and gives the Timber")
    }

    @Test
    fun `trade buttons say what they do - Offer and Clear for your own offer, Yes and No only when answering`() {
        assertEquals("Offer" to "Close", tradeButtonLabels(TradeMode.COMPOSE, countering = false, bank = false, entered = false))
        assertEquals("Offer" to "Clear", tradeButtonLabels(TradeMode.COMPOSE, countering = false, bank = false, entered = true))
        assertEquals("Trade" to "Clear", tradeButtonLabels(TradeMode.COMPOSE, countering = false, bank = true, entered = true))
        assertEquals("Trade" to "Withdraw", tradeButtonLabels(TradeMode.WAITING, countering = false, bank = false, entered = false, withdraw = true)) // #114
        assertEquals("Yes" to "No", tradeButtonLabels(TradeMode.RESPOND, countering = false, bank = false, entered = false))
        assertEquals("Send" to "Back", tradeButtonLabels(TradeMode.RESPOND, countering = true, bank = false, entered = true))
        assertEquals("Trade" to "Cancel", tradeButtonLabels(TradeMode.PICK_PARTNER, countering = false, bank = false, entered = false))
    }

    @Test
    fun `one row of cards - top asks, bottom offers, the other half switches, hold takes one back`() {
        val z = IntArray(5)
        var (g, v) = askOffer(z, z, 0, CardTap.ASK, have = 0)
        assertEquals(1, g[0]); assertEquals(0, v[0])
        askOffer(g, v, 0, CardTap.ASK, have = 0).let { g = it.first; v = it.second }
        assertEquals(2, g[0])
        askOffer(g, v, 0, CardTap.OFFER, have = 3).let { g = it.first; v = it.second }   // switch over
        assertEquals(0, g[0]); assertEquals(1, v[0])
        askOffer(g, v, 0, CardTap.OFFER, have = 1).let { g = it.first; v = it.second }   // can't offer more than held
        assertEquals(1, v[0])
        assertEquals(0, askOffer(z, z, 2, CardTap.OFFER, have = 0).second[2])              // nothing to offer
        askOffer(g, v, 0, CardTap.TAKE_BACK, have = 1).let { g = it.first; v = it.second }
        assertEquals(0, v[0]); assertEquals(0, g[0])
        for (i in 0..20) g = askOffer(g, v, 1, CardTap.ASK, have = 0).first
        assertEquals(9, g[1])                                                             // asks stop at 9
    }

    @Test
    fun `with the bank each tap on the bottom pays one more lot at your rate (#122)`() {
        val z = IntArray(5)
        var (g, v) = askOffer(z, z, 3, CardTap.ASK, have = 0, bankRate = 4)
        askOffer(g, v, 3, CardTap.ASK, have = 0, bankRate = 4).let { g = it.first; v = it.second }        // a second Grain
        askOffer(g, v, 1, CardTap.OFFER, have = 8, bankRate = 4).let { g = it.first; v = it.second }
        askOffer(g, v, 1, CardTap.OFFER, have = 8, bankRate = 4).let { g = it.first; v = it.second }      // a second lot
        assertEquals(2, g[3]); assertEquals(8, v[1])
        assertEquals(8, askOffer(g, v, 1, CardTap.OFFER, have = 8, bankRate = 4).second[1])                 // no third lot from 8
        assertEquals(0, askOffer(g, z, 0, CardTap.OFFER, have = 3, bankRate = 4).second[0])                 // can't pay 4 with 3
        askOffer(g, v, 1, CardTap.TAKE_BACK, have = 8, bankRate = 4).let { assertEquals(4, it.second[1]) }  // one lot back
        askOffer(g, v, 3, CardTap.TAKE_BACK, have = 0, bankRate = 4).let { assertEquals(1, it.first[3]) }   // one card back
        askOffer(g, v, 1, CardTap.ASK, have = 8, bankRate = 4).let { assertEquals(0, it.second[1]); assertEquals(1, it.first[1]) } // switch over
    }

    /** A 3-player game at [seed] on a main-phase turn (rolled, END_TURN legal), driven there by bots. */
    private fun midTurn(expansion: wgbg.engine.Expansion, seed: Long): GameState {
        val s = Rules.newGame(GameConfig(expansion = expansion, numPlayers = 3), seed)
        val buf = ActionBuffer()
        val bots = List(3) { GreedyBot(it.toLong()) }
        while (true) {
            Rules.legalActions(s, buf)
            if (s.phase == Phase.PLAY_TURN && Action.END_TURN in buf) return s
            Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
        }
    }

    private fun setHand(s: GameState, p: Int, vararg counts: Pair<Int, Int>) {
        val nc = wgbg.engine.Commodity.COUNT
        for (r in 0 until CARD_TYPES) s.hands[p * CARD_TYPES + r] = 0
        s.chivalry?.let { chivalry -> for (i in 0 until nc) chivalry.commodities[p * nc + i] = 0 }
        for ((r, n) in counts) if (r < CARD_TYPES) s.hands[p * CARD_TYPES + r] = n else s.chivalry!!.commodities[p * nc + r - CARD_TYPES] = n
    }

    @Test
    fun `#122 two bank trades in a row, several lots and mixed rates go through, in both rulesets`() {
        for (exp in listOf(wgbg.engine.Expansion.BASE, wgbg.engine.Expansion.CHIVALRY)) for (seed in 1L..6L) {
            val s = midTurn(exp, seed)
            val me = s.actingPlayer
            val n = tradeCards(s)
            // Two separate one-lot trades, one after the other.
            setHand(s, me, 0 to bankRate(s, me, 0), 1 to bankRate(s, me, 1))
            for ((pay, want) in listOf(0 to 2, 1 to 3)) {
                val moves = bankTrades(s, me, IntArray(n).also { it[want] = 1 }, IntArray(n).also { it[pay] = bankRate(s, me, pay) })
                assertEquals(1, moves?.size, "$exp seed $seed: one lot of $pay for $want")
                moves!!.forEach { assertTrue(Rules.isLegal(s, it)); Rules.apply(s, it) }
            }
            assertEquals(1, holding(s, me, 2)); assertEquals(1, holding(s, me, 3)); assertEquals(0, holding(s, me, 0))
            // Several lots of mixed cards (each at its own rate) for several cards in one go.
            val ra = bankRate(s, me, 0); val rb = bankRate(s, me, 4)
            setHand(s, me, 0 to 2 * ra, 4 to rb)
            val moves = bankTrades(s, me, IntArray(n).also { it[1] = 2; it[2] = 1 }, IntArray(n).also { it[0] = 2 * ra; it[4] = rb })
            assertEquals(3, moves?.size, "$exp seed $seed: three lots")
            moves!!.forEach { assertTrue(Rules.isLegal(s, it)); Rules.apply(s, it) }
            assertEquals(2, holding(s, me, 1)); assertEquals(1, holding(s, me, 2)); assertEquals(0, holding(s, me, 0)); assertEquals(0, holding(s, me, 4))
            // Still your turn, so more trades (bank or players) can follow.
            assertEquals(me, s.actingPlayer)
            assertTrue(Action.END_TURN in Rules.legalActions(s))
        }
    }

    @Test
    fun `#122 a bank trade that doesn't add up is no trade, and checking never changes the game`() {
        val s = midTurn(wgbg.engine.Expansion.BASE, 3)
        val me = s.actingPlayer
        val rate = bankRate(s, me, 0)
        setHand(s, me, 0 to 2 * rate)
        val one = IntArray(5).also { it[1] = 1 }
        val before = s.stateHash()
        assertEquals(null, bankTrades(s, me, IntArray(5), IntArray(5).also { it[0] = rate }))                     // nothing asked
        assertEquals(null, bankTrades(s, me, one, IntArray(5)))                                                    // nothing paid
        assertEquals(null, bankTrades(s, me, one, IntArray(5).also { it[0] = 2 * rate }))                          // two lots for one card
        assertEquals(null, bankTrades(s, me, IntArray(5).also { it[1] = 3 }, IntArray(5).also { it[0] = 2 * rate })) // three cards for two lots
        if (rate > 2) assertEquals(null, bankTrades(s, me, one, IntArray(5).also { it[0] = rate - 1 }))            // a short lot
        assertEquals(1, bankTrades(s, me, one, IntArray(5).also { it[0] = rate })?.size)
        assertEquals(before, s.stateHash())
        s.bank[1] = 1
        assertEquals(null, bankTrades(s, me, IntArray(5).also { it[1] = 2 }, IntArray(5).also { it[0] = 2 * rate })) // the bank has only one
    }

    @Test
    fun `an offer chimes when you can pay it and counts down to No when you can't`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(numPlayers = 3), 4)
        val buf = wgbg.engine.ActionBuffer()
        val bots = List(3) { wgbg.ai.GreedyBot(it.toLong()) }
        while (s.phase != wgbg.engine.Phase.PLAY_TURN) { wgbg.engine.Rules.legalActions(s, buf); wgbg.engine.Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        wgbg.engine.Rules.apply(s, wgbg.engine.Action.roll(2, 2))
        val from = s.currentPlayer; val to = (from + 1) % 3
        for (r in 0 until 5) { s.hands[from * wgbg.engine.CARD_TYPES + r] = 2; s.hands[to * wgbg.engine.CARD_TYPES + r] = 0 }
        s.hands[to * wgbg.engine.CARD_TYPES + 4] = 1                      // the other player holds 1 Stone only
        val offer = offerFor(s, IntArray(5).also { it[0] = 1 }, IntArray(5).also { it[4] = 1 })!!   // 1 Timber for their 1 Stone
        wgbg.engine.Rules.apply(s, offer)
        while (s.actingPlayer != to) { wgbg.engine.Rules.apply(s, wgbg.engine.Action.REJECT_TRADE) }
        assertEquals(TradeMode.RESPOND, tradeMode(s, to))
        assertTrue(canPayOffer(s, to))
        s.hands[to * wgbg.engine.CARD_TYPES + 4] = 0
        assertFalse(canPayOffer(s, to))
    }

    @Test
    fun `the take-back control always brings a card back to none, asked or offered, player or bank trade`() {
        var g = IntArray(5); var v = IntArray(5)
        repeat(4) { askOffer(g, v, 1, CardTap.ASK, have = 0).let { g = it.first; v = it.second } }
        repeat(3) { askOffer(g, v, 2, CardTap.OFFER, have = 3).let { g = it.first; v = it.second } }
        assertEquals(4, g[1]); assertEquals(3, v[2])
        repeat(4) { askOffer(g, v, 1, CardTap.TAKE_BACK, have = 0).let { g = it.first; v = it.second } }
        repeat(3) { askOffer(g, v, 2, CardTap.TAKE_BACK, have = 3).let { g = it.first; v = it.second } }
        assertTrue(g.all { it == 0 } && v.all { it == 0 })
        // One more never goes below none.
        askOffer(g, v, 1, CardTap.TAKE_BACK, have = 0).let { assertTrue(it.first.all { n -> n == 0 } && it.second.all { n -> n == 0 }) }
        // The bank: one tap clears the card either way.
        askOffer(IntArray(5).also { it[0] = 1 }, IntArray(5).also { it[3] = 4 }, 3, CardTap.TAKE_BACK, have = 4, bankRate = 4)
            .let { assertEquals(0, it.second[3]); assertEquals(1, it.first[0]) }
    }

    @Test
    fun `#109 for commodities - Parchment, Fabric and Silver step back to none too (Age of Chivalry rows)`() {
        val n = wgbg.engine.CHIVALRY_CARD_TYPES
        for (r in wgbg.engine.CARD_TYPES until n) {
            var g = IntArray(n); var v = IntArray(n)
            repeat(2) { askOffer(g, v, r, CardTap.ASK, have = 0).let { g = it.first; v = it.second } }
            repeat(3) { askOffer(g, v, r, CardTap.TAKE_BACK, have = 0).let { g = it.first; v = it.second } }
            assertTrue(g.all { it == 0 } && v.all { it == 0 }, "asked commodity $r back to none")
            repeat(2) { askOffer(g, v, r, CardTap.OFFER, have = 2).let { g = it.first; v = it.second } }
            assertEquals(2, v[r])
            repeat(3) { askOffer(g, v, r, CardTap.TAKE_BACK, have = 2).let { g = it.first; v = it.second } }
            assertTrue(g.all { it == 0 } && v.all { it == 0 }, "offered commodity $r back to none")
            // The bank (a Trading House 2:1 on commodities): one take-back clears it.
            askOffer(IntArray(n).also { it[0] = 1 }, IntArray(n).also { it[r] = 2 }, r, CardTap.TAKE_BACK, have = 2, bankRate = 2)
                .let { assertEquals(0, it.second[r]) }
        }
    }

    @Test
    fun `#125 after a trade the panel says what you hold now and what moved`() {
        assertEquals("Now: 4 Grain (+1) · 0 Stone (−3)", tradeSummary(listOf(2, 1, 0, 3, 3), listOf(2, 1, 0, 4, 0)))
        assertEquals(null, tradeSummary(listOf(1, 1), listOf(1, 1)))
        assertEquals("Now: 1 Silver (+1)", tradeSummary(listOf(0, 0, 0, 0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0, 0, 0, 1)))
    }

    // CI 0.3.0: on a 640 dp screen the Age of Chivalry chest (8 cards) ran its Trade button off the edge right of the panel.
    @Test
    fun chestNeedsTheFullWidthOnANarrowScreen() {
        val beside = 640.dp - 16.dp - 250.dp // right of a ~250 dp side panel
        assertTrue(chestFits(beside, 5), "base: 5 cards fit beside the panel")
        assertFalse(chestFits(beside, 8), "Chivalry: 8 cards do not fit beside the panel")
        assertTrue(chestFits(640.dp - 16.dp, 8), "Chivalry: 8 cards fit across the whole screen")
        assertTrue(chestFits(915.dp - 16.dp - 250.dp, 8), "a usual phone fits 8 beside the panel")
    }

    @Test
    fun chestCardsGapThenOverlapThenShrinkThenScroll() {
        // 0.3.4 blocker: 8 Age of Chivalry cards at font 1.3 on a 640 dp phone, beside the side panel, covered Discard.
        // The cards get what the buttons leave and, like the hand (Mauro: "don't cards overlap a little"), fit it by
        // a small gap, else a little overlap at full size, else smaller cards, and past the smallest they scroll.
        fun span(f: ChestFit, n: Int) = f.card * n + f.spacing * (n - 1)
        assertEquals(ChestFit(42.dp, CHEST_GAP.dp), chestFit(400.dp, 5, 42.dp), "room to spare: the usual size and gap")
        val tight = chestFit(200.dp, 5, 42.dp)   // 5 cards are 210 dp edge to edge, 193 at 10% overlapping
        assertEquals(42.dp, tight.card, "a little short: full size, overlapping")
        assertTrue(tight.spacing < 0.dp && tight.spacing >= 42.dp * -CHEST_OVERLAP, "overlap at most 10%: ${tight.spacing}")
        assertEquals(200f, span(tight, 5).value, 0.01f, "and exactly as close as needed")
        val small = chestFit(300.dp, 8, 42.dp)
        assertTrue(small.card < 42.dp && small.spacing == small.card * -CHEST_OVERLAP, "8 in 300 dp: smaller, still 10% over: $small")
        assertEquals(300f, span(small, 8).value, 0.01f, "8 cards fill 300 dp")
        assertEquals(MIN_CHEST_CARD, chestFit(200.dp, 8, 42.dp).card, "too narrow: the smallest card, and the row scrolls")
        for (n in 0..8) for (room in 0..800 step 7) {
            val f = chestFit(room.dp, n, 58.dp)
            assertTrue(f.card in MIN_CHEST_CARD..58.dp, "$n cards in $room dp: $f")
            assertTrue(f.spacing <= CHEST_GAP.dp && f.spacing >= f.card * -CHEST_OVERLAP - 0.01.dp, "never more than a 10% overlap: $f")
            if (f.card < 58.dp) assertTrue(f.spacing < 0.dp, "cards shrink only once they overlap: $n in $room dp, $f")
            if (f.card > MIN_CHEST_CARD && n > 0) assertTrue(span(f, n) <= room.dp + 0.01.dp, "$n cards in $room dp fit without scrolling: $f")
        }
        assertEquals(MIN_CHEST_CARD, chestFit(100.dp, 3, 20.dp).card, "a max below the floor still draws the smallest card")
        for (room in listOf(-50.dp, 0.dp)) assertEquals(MIN_CHEST_CARD, chestFit(room, 4, 42.dp).card, "room $room")
    }
}

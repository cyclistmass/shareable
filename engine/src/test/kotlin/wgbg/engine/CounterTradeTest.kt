package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Commodity.CLOTH
import wgbg.engine.Commodity.PAPER
import wgbg.engine.Resource.BRICK
import wgbg.engine.Resource.ORE
import wgbg.engine.Resource.SHEEP
import wgbg.engine.Resource.WHEAT
import wgbg.engine.Resource.WOOD

/**
 * #135 open offers: an offer may give only or ask only, it goes all round the table, responders accept,
 * decline or counter, and the offerer then picks one acceptance or counter (or withdraws).
 */
class CounterTradeTest {
    private fun cards(vararg rc: Pair<Int, Int>) = IntArray(CARD_TYPES).also { for ((r, n) in rc) it[r] = n }
    private fun chivalryCards(vararg rc: Pair<Int, Int>) = IntArray(CHIVALRY_CARD_TYPES).also { for ((r, n) in rc) it[r] = n }

    /** Player 0 offers 1 wood for 1 brick; players 1 and 2 hold brick, sheep and ore. */
    private fun offered(): GameState {
        val s = T.playState(4).also { T.roll(it, 8) }
        T.give(s, 0, WOOD, 2)
        T.give(s, 0, WHEAT, 1)
        T.give(s, 1, BRICK, 1)
        T.give(s, 1, SHEEP, 1)
        T.give(s, 2, ORE, 1)
        Rules.applyChecked(s, Action.offerTrade(cards(WOOD to 1), cards(BRICK to 1)))
        assertEquals(1, s.actingPlayer)
        return s
    }

    private fun hand(s: GameState, p: Int) = (0 until CARD_TYPES).map { s.hand(p, it) }

    @Test
    fun `a counter is collected, the offer goes on round, and the offerer takes it on the countered terms`() {
        val s = offered()
        // Player 1: "give me wheat instead of wood".
        val counter = Action.counterTrade(cards(WHEAT to 1), cards(BRICK to 1))
        assertTrue(Rules.isLegal(s, counter))
        Rules.applyChecked(s, counter)
        assertEquals(Phase.DECIDE_TRADE, s.phase, "the offer stays open for the others")
        assertEquals(2, s.actingPlayer)
        assertEquals(counter, s.counterOf(1))
        Rules.applyChecked(s, Action.REJECT_TRADE)
        Rules.applyChecked(s, Action.REJECT_TRADE)
        assertEquals(Phase.DECIDE_ACCEPTEES, s.phase)
        assertEquals(0, s.actingPlayer)
        assertEquals(setOf(Action.CANCEL_TRADE, Action.confirmTrade(1)), T.legal(s).toSet())
        Rules.applyChecked(s, Action.confirmTrade(1))
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertEquals(listOf(2, 1, 0, 0, 0), hand(s, 0)) // wood 2, brick 1, no wheat
        assertEquals(listOf(0, 0, 1, 1, 0), hand(s, 1)) // sheep 1, wheat 1
        assertNull(s.counterOf(1))
        assertFalse(s.anyCounter)
        T.checkInvariants(s)
    }

    @Test
    fun `the offerer chooses between an acceptance and counters, or withdraws`() {
        val s = T.playState(4).also { T.roll(it, 8) }
        T.give(s, 0, WOOD, 2)
        T.give(s, 0, ORE, 1)
        T.give(s, 1, BRICK, 1)
        T.give(s, 2, BRICK, 1)
        T.give(s, 3, SHEEP, 2)
        Rules.applyChecked(s, Action.offerTrade(cards(WOOD to 1), cards(BRICK to 1)))
        Rules.applyChecked(s, Action.ACCEPT_TRADE) // player 1
        Rules.applyChecked(s, Action.counterTrade(cards(ORE to 1), cards(BRICK to 1))) // player 2
        Rules.applyChecked(s, Action.counterTrade(cards(WOOD to 1), cards(SHEEP to 2))) // player 3
        assertEquals(Phase.DECIDE_ACCEPTEES, s.phase)
        assertEquals(setOf(Action.CANCEL_TRADE, Action.confirmTrade(1), Action.confirmTrade(2), Action.confirmTrade(3)), T.legal(s).toSet())
        val pick3 = s.copy().also { Rules.applyChecked(it, Action.confirmTrade(3)) }
        assertEquals(listOf(1, 0, 2, 0, 1), hand(pick3, 0), "player 3's terms: 1 wood for 2 sheep")
        val pick1 = s.copy().also { Rules.applyChecked(it, Action.confirmTrade(1)) }
        assertEquals(listOf(1, 1, 0, 0, 1), hand(pick1, 0), "player 1 took the offer as it stood")
        Rules.applyChecked(s, Action.CANCEL_TRADE)
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertEquals(listOf(2, 0, 0, 0, 1), hand(s, 0), "withdrawn: nothing moves")
        assertFalse(s.anyCounter)
    }

    @Test
    fun `nobody accepts or counters - the offer just ends`() {
        val s = offered()
        repeat(3) { Rules.applyChecked(s, Action.REJECT_TRADE) }
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertEquals(listOf(2, 0, 0, 1, 0), hand(s, 0))
    }

    @Test
    fun `ask only - who has grain - is answered with counters, never accepted as a gift`() {
        val s = T.playState(4).also { T.roll(it, 8) }
        T.give(s, 0, WOOD, 1)
        T.give(s, 0, SHEEP, 1)
        T.give(s, 1, WHEAT, 2)
        val ask = Action.offerTrade(cards(), cards(WHEAT to 1))
        assertTrue(Rules.isLegal(s, ask))
        Rules.applyChecked(s, ask)
        assertFalse(Action.ACCEPT_TRADE in T.legal(s), "accepting would hand over grain for nothing")
        val counter = Action.counterTrade(cards(SHEEP to 1), cards(WHEAT to 1))
        assertTrue(Rules.isLegal(s, counter))
        Rules.applyChecked(s, counter)
        Rules.applyChecked(s, Action.REJECT_TRADE)
        Rules.applyChecked(s, Action.REJECT_TRADE)
        Rules.applyChecked(s, Action.confirmTrade(1))
        assertEquals(listOf(1, 0, 0, 1, 0), hand(s, 0)) // wood and the grain
        assertEquals(listOf(0, 0, 1, 1, 0), hand(s, 1))
        T.checkInvariants(s)
    }

    @Test
    fun `give only - anyone want wool - works the same way`() {
        val s = T.playState(4).also { T.roll(it, 8) }
        T.give(s, 0, SHEEP, 2)
        T.give(s, 2, ORE, 1)
        val offer = Action.offerTrade(cards(SHEEP to 2), cards())
        assertTrue(Rules.isLegal(s, offer))
        Rules.applyChecked(s, offer)
        assertFalse(Action.ACCEPT_TRADE in T.legal(s))
        Rules.applyChecked(s, Action.REJECT_TRADE)
        Rules.applyChecked(s, Action.counterTrade(cards(SHEEP to 2), cards(ORE to 1)))
        Rules.applyChecked(s, Action.REJECT_TRADE)
        Rules.applyChecked(s, Action.confirmTrade(2))
        assertEquals(listOf(0, 0, 0, 0, 1), hand(s, 0))
        assertEquals(listOf(0, 0, 2, 0, 0), hand(s, 2))
    }

    @Test
    fun `an empty offer or a resource for itself is still refused`() {
        val s = T.playState(4).also { T.roll(it, 8) }
        T.give(s, 0, SHEEP, 2)
        assertFalse(Rules.isLegal(s, Action.offerTrade(cards(), cards())))
        assertFalse(Rules.isLegal(s, Action.offerTrade(cards(SHEEP to 1), cards(SHEEP to 1))))
        assertFalse(Rules.isLegal(s, Action.offerTrade(cards(WOOD to 1), cards())), "can't give what you don't hold")
    }

    @Test
    fun `counter state is part of the hash and of copies`() {
        val s = offered()
        val before = s.stateHash()
        Rules.applyChecked(s, Action.counterTrade(cards(WHEAT to 1), cards(BRICK to 1)))
        val a = s.copy()
        val b = s.copy().also { it.counters[1] = Action.counterTrade(cards(WOOD to 2), cards(BRICK to 1)).payload }
        assertEquals(s.stateHash(), a.stateHash())
        assertEquals(s.counterOf(1), a.counterOf(1))
        assertFalse(a.stateHash() == b.stateHash(), "different counters hash differently")
        assertFalse(before == s.stateHash())
    }

    @Test
    fun `invalid counters are refused`() {
        val s = offered()
        fun bad(give: IntArray, want: IntArray, why: String) =
            assertFalse(Rules.isLegal(s, Action.counterTrade(give, want)), why)
        bad(cards(WOOD to 1), cards(BRICK to 1), "same as the offer")
        bad(cards(ORE to 1), cards(BRICK to 1), "offerer has no ore")
        bad(cards(WOOD to 1), cards(ORE to 1), "responder has no ore")
        bad(cards(WOOD to 1), cards(), "gets nothing")
        bad(cards(), cards(BRICK to 1), "gives nothing")
        bad(cards(WOOD to 1, BRICK to 1), cards(BRICK to 1), "brick on both sides")
        assertFalse(Rules.isLegal(s, Action.of(ActionType.COUNTER_TRADE, 1L shl 44)), "junk high bits")
        // Not while it's the offerer's own turn, nor a base counter in an Age of Chivalry game.
        val turn = T.playState(4).also { T.roll(it, 8) }
        T.give(turn, 0, WOOD, 1)
        assertFalse(Rules.isLegal(turn, Action.counterTrade(cards(WOOD to 1), cards(BRICK to 1))))
        val chivalry = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY), 1)
        assertFalse(Rules.isLegal(chivalry, Action.counterTrade(cards(WOOD to 1), cards(BRICK to 1))))
        assertFalse(Rules.isLegal(s, Action.counterTradeCk(chivalryCards(WHEAT to 1), chivalryCards(BRICK to 1))), "no Chivalry counter in a base game")
    }

    @Test
    fun `counters are enumerated for bots when trade offers are generated`() {
        val s = offered()
        assertTrue(T.legal(s).none { it.type == ActionType.COUNTER_TRADE }, "not by default")
        val g = T.playState(4, GameConfig(generateTradeOffers = true)).also { T.roll(it, 8) }
        T.give(g, 0, WOOD, 1)
        T.give(g, 1, BRICK, 1)
        T.give(g, 1, SHEEP, 1)
        Rules.applyChecked(g, Action.offerTrade(cards(WOOD to 1), cards(BRICK to 1)))
        val counters = T.legal(g).filter { it.type == ActionType.COUNTER_TRADE }.toSet()
        assertEquals(setOf(Action.counterTrade(cards(WOOD to 1), cards(SHEEP to 1))), counters)
        for (c in counters) assertTrue(Rules.isLegal(g, c))
    }

    // ------------------------------------------------------------ Age of Chivalry

    @Test
    fun `Chivalry - ask only for grain, collect a commodity counter, take it`() {
        val s = CK.playState()
        CK.roll(s, 6, 6)
        CK.give(s, 0, PAPER, 1)
        CK.give(s, 1, WHEAT, 1)
        CK.give(s, 2, WHEAT, 1)
        val ask = Action.offerTradeCk(chivalryCards(), chivalryCards(WHEAT to 1))
        assertTrue(Rules.isLegal(s, ask))
        Rules.applyChecked(s, ask)
        assertFalse(Action.ACCEPT_TRADE in CK.legal(s))
        assertFalse(Rules.isLegal(s, Action.counterTrade(cards(), cards(WHEAT to 1))), "base counters are off")
        val c1 = Action.counterTradeCk(chivalryCards(PAPER to 1), chivalryCards(WHEAT to 1))
        assertTrue(Rules.isLegal(s, c1))
        assertFalse(Rules.isLegal(s, Action.counterTradeCk(chivalryCards(CLOTH to 1), chivalryCards(WHEAT to 1))), "offerer has no cloth")
        Rules.applyChecked(s, c1)
        Rules.applyChecked(s, Action.REJECT_TRADE)
        Rules.applyChecked(s, Action.REJECT_TRADE)
        assertEquals(Phase.DECIDE_ACCEPTEES, s.phase)
        assertEquals(setOf(Action.CANCEL_TRADE, Action.confirmTrade(1)), CK.legal(s).toSet())
        Rules.applyChecked(s, Action.confirmTrade(1))
        assertEquals(1, s.card(0, WHEAT)); assertEquals(0, s.card(0, PAPER))
        assertEquals(1, s.card(1, PAPER)); assertEquals(0, s.card(1, WHEAT))
        assertEquals(0, s.chivalry!!.offer.sum())
        assertFalse(s.anyCounter)
        CK.checkInvariants(s)
    }

    @Test
    fun `Chivalry - nobody answers, the round ends and the offer is cleared`() {
        val s = CK.playState()
        CK.roll(s, 6, 6)
        CK.give(s, 0, CLOTH, 1)
        Rules.applyChecked(s, Action.offerTradeCk(chivalryCards(CLOTH to 1), chivalryCards()))
        repeat(3) { Rules.applyChecked(s, Action.REJECT_TRADE) }
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertEquals(0, s.chivalry!!.offer.sum())
    }

    @Test
    fun `#114 the offerer may withdraw while others are still answering, in both rulesets`() {
        val s = offered()
        Rules.applyChecked(s, Action.counterTrade(cards(WHEAT to 1), cards(BRICK to 1))) // player 1 counters
        assertEquals(2, s.actingPlayer)
        assertEquals(0, Rules.mover(s, Action.CANCEL_TRADE), "the offerer withdraws, not the player who is up")
        assertEquals(2, Rules.mover(s, Action.REJECT_TRADE))
        assertFalse(Action.CANCEL_TRADE in T.legal(s), "not a move for the responder")
        assertTrue(Rules.isLegal(s, Action.CANCEL_TRADE))
        assertFalse(Rules.isLegal(s, Action.of(ActionType.CANCEL_TRADE, 5)), "junk payload")
        val hand0 = hand(s, 0)
        Rules.applyChecked(s, Action.CANCEL_TRADE)
        assertEquals(Phase.PLAY_TURN, s.phase); assertEquals(0, s.actingPlayer)
        assertEquals(hand0, hand(s, 0)); assertFalse(s.anyCounter); assertEquals(0, s.tradeOffer.sum())
        assertFalse(Rules.isLegal(s, Action.CANCEL_TRADE), "nothing left to withdraw")

        val chivalry = CK.playState()
        CK.roll(chivalry, 6, 6)
        CK.give(chivalry, 0, PAPER, 1)
        Rules.applyChecked(chivalry, Action.offerTradeCk(chivalryCards(PAPER to 1), chivalryCards()))
        assertEquals(0, Rules.mover(chivalry, Action.CANCEL_TRADE))
        Rules.applyChecked(chivalry, Action.CANCEL_TRADE)
        assertEquals(Phase.PLAY_TURN, chivalry.phase); assertEquals(0, chivalry.chivalry!!.offer.sum())
        CK.checkInvariants(chivalry)
    }
}

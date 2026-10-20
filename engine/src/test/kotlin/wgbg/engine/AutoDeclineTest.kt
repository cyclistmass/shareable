package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Commodity.CLOTH
import wgbg.engine.Commodity.COIN
import wgbg.engine.Resource.BRICK
import wgbg.engine.Resource.SHEEP
import wgbg.engine.Resource.WHEAT
import wgbg.engine.Resource.WOOD

/**
 * #26: an offer the player answering can't pay is declined for them (the app after its 5-second No, the CLI at once),
 * and both say what they lacked. [Rules.cantPayOffer] decides it and [Rules.offerShortfall] says what was missing.
 */
class AutoDeclineTest {
    private fun cards(vararg rc: Pair<Int, Int>) = IntArray(CARD_TYPES).also { for ((r, n) in rc) it[r] = n }
    private fun ckCards(vararg rc: Pair<Int, Int>) = IntArray(CK_CARD_TYPES).also { for ((r, n) in rc) it[r] = n }

    @Test
    fun `an offer asking for more than you hold can't be paid, and the shortfall names what's missing`() {
        val s = T.playState(3).also { T.roll(it, 8) }
        T.give(s, 0, WOOD, 1)
        T.give(s, 1, BRICK, 1)
        T.give(s, 2, BRICK, 2); T.give(s, 2, SHEEP, 1)
        Rules.applyChecked(s, Action.offerTrade(cards(WOOD to 1), cards(BRICK to 2, SHEEP to 1)))
        assertEquals(1, s.actingPlayer)
        assertTrue(Rules.cantPayOffer(s, 1))
        assertEquals(listOf(0, 1, 1, 0, 0), Rules.offerShortfall(s, 1).toList())
        assertFalse(Action.ACCEPT_TRADE in T.legal(s))
        assertFalse(Rules.cantPayOffer(s, 2), "not player 2's turn to answer yet")
        assertFalse(Rules.cantPayOffer(s, 0), "the offerer never answers their own offer")
        Rules.applyChecked(s, Action.REJECT_TRADE)
        assertEquals(2, s.actingPlayer)
        assertFalse(Rules.cantPayOffer(s, 2), "player 2 holds 2 Clay and a Fleece")
        assertEquals(listOf(0, 0, 0, 0, 0), Rules.offerShortfall(s, 2).toList())
        assertTrue(Action.ACCEPT_TRADE in T.legal(s))
    }

    @Test
    fun `an open ask waits for an answer even from someone who holds none of it`() {
        val s = T.playState(3).also { T.roll(it, 8) }
        T.give(s, 1, WOOD, 1)
        Rules.applyChecked(s, Action.offerTrade(cards(), cards(WHEAT to 1)))
        assertEquals(1, s.actingPlayer)
        assertEquals(1, Rules.offerShortfall(s, 1)[WHEAT])
        assertFalse(Rules.cantPayOffer(s, 1), "a counter is how an open ask is answered")
    }

    @Test
    fun `Age of Chivalry - commodities count, both the ones asked for and the ones held`() {
        val s = CK.playState(3)
        CK.roll(s, 6, 2)
        CK.give(s, 0, WOOD, 1)
        CK.give(s, 1, CLOTH, 1)
        Rules.applyChecked(s, Action.offerTradeCk(ckCards(WOOD to 1), ckCards(CLOTH to 1, COIN to 1)))
        assertEquals(1, s.actingPlayer)
        assertTrue(Rules.cantPayOffer(s, 1))
        assertEquals(ckCards(COIN to 1).toList(), Rules.offerShortfall(s, 1).toList())
        CK.give(s, 1, COIN, 1)
        assertFalse(Rules.cantPayOffer(s, 1))
        assertTrue(Action.ACCEPT_TRADE in CK.legal(s))
        CK.checkInvariants(s)
    }

    @Test
    fun `fuzz - in thousands of trade answers, can't-pay means exactly that Yes isn't legal, and No always is`() {
        var checked = 0; var cantPay = 0
        for (ck in listOf(false, true)) for (seed in 1L..40L) {
            val config = GameConfig(expansion = if (ck) Expansion.CITIES_AND_KNIGHTS else Expansion.BASE, numPlayers = 3 + (seed % 2).toInt(),
                generateTradeOffers = true, maxTurns = 300)
            val s = Rules.newGame(config, seed)
            val rnd = Rng(seed * 7)
            val buf = ActionBuffer()
            while (!s.isOver) {
                Rules.legalActions(s, buf)
                val legal = (0 until buf.size).map { buf[it] }
                val p = s.actingPlayer
                for (q in 0 until s.numPlayers) {
                    val short = Rules.offerShortfall(s, q)
                    assertTrue(short.all { it >= 0 })
                    if (s.phase != Phase.DECIDE_TRADE) assertTrue(short.all { it == 0 })
                    if (q != p) assertFalse(Rules.cantPayOffer(s, q))
                }
                if (s.phase == Phase.DECIDE_TRADE && p != s.currentPlayer) {
                    checked++
                    val cant = Rules.cantPayOffer(s, p)
                    assertEquals(Rules.isTwoSided(s) && Action.ACCEPT_TRADE !in legal, cant)
                    if (cant) { cantPay++; assertTrue(Action.REJECT_TRADE in legal) }
                }
                // Mostly the way a player would: take the No for them whenever it's due, as the app and CLI do.
                val a = if (Rules.cantPayOffer(s, p)) Action.REJECT_TRADE else legal[rnd.nextInt(legal.size)]
                Rules.applyChecked(s, a)
                if (ck) CK.checkInvariants(s) else T.checkInvariants(s)
            }
        }
        assertTrue(checked > 1000, "only $checked trade answers")
        assertTrue(cantPay > 100, "only $cantPay offers that couldn't be paid")
    }

    @Test
    fun `break it - Yes to an offer you can't pay is refused and changes nothing`() {
        val s = T.playState(3).also { T.roll(it, 8) }
        T.give(s, 0, WOOD, 1)
        Rules.applyChecked(s, Action.offerTrade(cards(WOOD to 1), cards(WHEAT to 1)))
        assertTrue(Rules.cantPayOffer(s, 1))
        val hash = s.stateHash()
        assertFalse(Rules.isLegal(s, Action.ACCEPT_TRADE))
        assertThrows(IllegalArgumentException::class.java) { Rules.applyChecked(s, Action.ACCEPT_TRADE) }
        assertEquals(hash, s.stateHash())
        // Out of a trade: no shortfall, nothing to decline.
        val idle = T.playState(3)
        for (p in 0 until 3) { assertFalse(Rules.cantPayOffer(idle, p)); assertTrue(Rules.offerShortfall(idle, p).all { it == 0 }) }
    }
}

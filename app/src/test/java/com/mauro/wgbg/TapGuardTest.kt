package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.CARD_TYPES
import wgbg.engine.Commodity
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Progress
import wgbg.engine.Rules

/**
 * 0.3.4 player report: a double tap on Roll also ended the turn (End turn takes Roll's place under the finger).
 * Every move button now shares one [TapGuard]: a second tap within half a second does nothing.
 */
class TapGuardTest {
    private var now = 1_000L
    private val guard = TapGuard(500) { now }

    @Test
    fun `a second tap within the window does nothing, on any guarded button`() {
        val sent = mutableListOf<String>()
        val roll = guard.wrap { sent += "roll" }
        val endTurn = guard.wrap { sent += "end" }
        roll()
        now += 120; endTurn()          // the double tap lands on End turn, now where Roll was
        now += 200; roll()             // and a third, still inside the window
        assertEquals(listOf("roll"), sent)
        now += 500; endTurn()          // a deliberate tap later goes through
        assertEquals(listOf("roll", "end"), sent)
    }

    @Test
    fun `the window starts again only from a tap that went through`() {
        assertTrue(guard.allow())
        now += 499; assertFalse(guard.allow())
        now += 1; assertTrue(guard.allow(), "500 ms after the first tap, not the ignored one")
        now += 499; assertFalse(guard.allow())
    }

    @Test
    fun `a clock that jumps back never locks the buttons`() {
        assertTrue(guard.allow())
        now -= 10_000; assertTrue(guard.allow(), "an earlier time than the last tap lets it through")
        now += 100; assertFalse(guard.allow())
    }

    @Test
    fun `taps far apart all go through`() {
        var n = 0
        val tap = guard.wrap { n++ }
        repeat(10) { tap(); now += 600 }
        assertEquals(10, n)
    }

    @Test
    fun `Grand Factor's picker shows their count and yours, other pickers just yours`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 5)
        s.hands[0 * CARD_TYPES + 2] = 1; s.hands[1 * CARD_TYPES + 2] = 3
        assertEquals("has 3\nyou 1", cardChoiceUnder(s, me = 0, owner = 1, card = 2))
        assertEquals("have 1", cardChoiceUnder(s, me = 0, owner = 0, card = 2))
        // Narrow cards keep both lines, short.
        assertEquals("×3\nyou 1", shortUnder(cardChoiceUnder(s, me = 0, owner = 1, card = 2)))
    }

    // Player reports (0.3.5): Swift Hulls, Corner the Field, Corner the Wares, Windfall and Corner the Market showed the
    // cards but not how many you hold, and their open picker hides the hand row. Each card in a choice says "have N".
    @Test
    fun `every card picker says how many of each card you hold, under both of Windfall's`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 5)
        s.hands.fill(0); s.chivalry!!.commodities.fill(0)
        s.hands[0 * CARD_TYPES + 2] = 2; s.hands[0 * CARD_TYPES + 4] = 1
        s.hands[1 * CARD_TYPES + 2] = 5                                   // someone else's Grain is not yours
        s.chivalry!!.commodities[0 * Commodity.COUNT + 1] = 3             // card 6, a commodity
        fun under(a: Action) = optionCards(a)!!.map { cardChoiceUnder(s, 0, 0, it) }
        assertEquals(listOf("have 2", "have 1"), under(Action.yearOfPlenty(2, 4)))           // Windfall, two kinds
        assertEquals(listOf("have 2", "have 2"), under(Action.yearOfPlenty(2, 2)))           // and two of one
        assertEquals(listOf("have 1"), under(Action.monopoly(4)))                            // Corner the Market
        assertEquals(listOf("have 2"), under(Action.playProgress(Progress.RESOURCE_MONOPOLY, 2)))   // Corner the Field
        assertEquals(listOf("have 3"), under(Action.playProgress(Progress.TRADE_MONOPOLY, 6)))      // Corner the Wares
        assertEquals(listOf("have 3"), under(Action.playProgress(Progress.MERCHANT_FLEET, 6)))      // Swift Hulls
        assertEquals(listOf("have 0"), under(Action.playProgress(Progress.MERCHANT_FLEET, 0)))
    }
}

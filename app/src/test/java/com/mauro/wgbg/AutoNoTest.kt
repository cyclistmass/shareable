package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.CARD_TYPES
import wgbg.engine.CHIVALRY_CARD_TYPES
import wgbg.engine.Commodity
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Resource
import wgbg.engine.Rules
import java.io.File

/** #26: the app's No to an offer you can't pay (after its 5-second countdown) says in the turn history what you lacked. */
class AutoNoTest {
    private val names = listOf("Ann", "Bea", "Cy")

    private fun playing(expansion: Expansion): GameState {
        val s = Rules.newGame(GameConfig(expansion = expansion, numPlayers = 3), 4)
        val buf = ActionBuffer()
        val bots = List(3) { GreedyBot(it.toLong()) }
        while (s.phase != Phase.PLAY_TURN) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        Rules.apply(s, Action.roll(2, 2, if (s.chivalry != null) 0 else -1))
        while (s.phase != Phase.PLAY_TURN) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        for (p in 0 until 3) for (c in 0 until tradeCards(s)) {
            if (c < CARD_TYPES) s.hands[p * CARD_TYPES + c] = 0 else s.chivalry!!.commodities[p * Commodity.COUNT + c - CARD_TYPES] = 0
        }
        return s
    }

    @Test
    fun `the reason names every card you were short of`() {
        val s = playing(Expansion.BASE)
        val from = s.currentPlayer; val to = (from + 1) % 3
        s.hands[from * CARD_TYPES + Resource.WOOD] = 1
        s.hands[to * CARD_TYPES + Resource.WHEAT] = 1
        Rules.applyChecked(s, offerFor(s, IntArray(5).also { it[Resource.WOOD] = 1 }, IntArray(5).also { it[Resource.WHEAT] = 2; it[Resource.ORE] = 1 })!!)
        assertEquals(to, s.actingPlayer)
        assertTrue(Rules.cantPayOffer(s, to)); assertFalse(canPayOffer(s, to))
        assertEquals("1 Grain, 1 Stone", shortfallText(s, to))
    }

    @Test
    fun `Age of Chivalry - a missing commodity is named too`() {
        val s = playing(Expansion.CHIVALRY)
        val from = s.currentPlayer; val to = (from + 1) % 3
        s.hands[from * CARD_TYPES + Resource.WOOD] = 1
        val get = IntArray(CHIVALRY_CARD_TYPES).also { it[Commodity.COIN] = 1 }
        Rules.applyChecked(s, offerFor(s, IntArray(CHIVALRY_CARD_TYPES).also { it[Resource.WOOD] = 1 }, get)!!)
        assertTrue(Rules.cantPayOffer(s, to))
        assertEquals("1 Silver", shortfallText(s, to))
    }

    @Test
    fun `the countdown uses the engine's rule, then sends No as a tap would`() {
        val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText().replace("\r\n", "\n")
        val effect = main.substringAfter("LaunchedEffect(offerKey) {").substringBefore("\n    }\n")
        assertTrue("if (!Rules.cantPayOffer(state, me)) { sounds.tradeChime" in effect)
        assertTrue(effect.indexOf("delay(1000)") < effect.indexOf("humanMoves.trySend(Action.REJECT_TRADE)"), "No only after the countdown")
        // The Menu's move log is gone (#91): the reason is the turn history's, from TurnTracker.
        assertFalse("log.add" in effect, effect)
    }

    @Test
    fun `the turn history shows why a No came from a player who couldn't pay, and nothing for a No by choice`() {
        val s = playing(Expansion.BASE)
        val from = s.currentPlayer; val to = (from + 1) % 3; val third = (from + 2) % 3
        s.hands[from * CARD_TYPES + Resource.WOOD] = 1
        s.hands[third * CARD_TYPES + Resource.WHEAT] = 1
        val tracker = TurnTracker(names)
        val offer = offerFor(s, IntArray(5).also { it[Resource.WOOD] = 1 }, IntArray(5).also { it[Resource.WHEAT] = 1 })!!
        tracker.apply(s, from, offer) { Rules.applyChecked(s, offer) }
        while (s.phase == Phase.DECIDE_TRADE) {
            val who = s.actingPlayer
            tracker.apply(s, who, Action.REJECT_TRADE) { Rules.applyChecked(s, Action.REJECT_TRADE) }
        }
        val events = tracker.current!!.events
        assertEquals("Said No, couldn't pay: ${names[to]} (1 Grain)", events.last(), events.toString())
        assertTrue(events.none { names[third] in it && "couldn't pay" in it }, "${names[third]} could pay, so their No needs no reason")
    }

    @Test
    fun `fuzz - in bot games every reason line belongs to a No that really couldn't be paid`() {
        val buf = ActionBuffer()
        var lines = 0
        for (seed in 1..40L) {
            val s = Rules.newGame(GameConfig(expansion = if (seed % 2 == 0L) Expansion.CHIVALRY else Expansion.BASE, numPlayers = 3), seed)
            val bots = List(3) { GreedyBot(seed * 3 + it) }
            val tracker = TurnTracker(names)
            var moves = 0
            while (!s.isOver && moves++ < 3000) {
                Rules.legalActions(s, buf); val who = s.actingPlayer; val a = bots[who].choose(s, buf)
                val couldnt = a.type == wgbg.engine.ActionType.REJECT_TRADE && Rules.cantPayOffer(s, who)
                val why = if (couldnt) shortfallText(s, who) else null
                val before = tracker.current?.events
                tracker.apply(s, who, a) { Rules.apply(s, a) }
                val events = tracker.current!!.events
                if (why != null) {
                    assertTrue(events.last().startsWith("Said No, couldn't pay: ") && events.last().endsWith("${names[who]} ($why)"), "seed $seed move $moves: $events")
                    assertTrue(events.size <= 6)
                } else if (a.type == wgbg.engine.ActionType.REJECT_TRADE && before != null) assertEquals(before, events, "a No by choice adds nothing")
                if (couldnt) lines++
            }
        }
        println("reason lines: $lines")
    }
}

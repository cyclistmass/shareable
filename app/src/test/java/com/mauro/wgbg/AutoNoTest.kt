package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.CARD_TYPES
import wgbg.engine.CK_CARD_TYPES
import wgbg.engine.Commodity
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Resource
import wgbg.engine.Rules
import java.io.File

/** #26: the app's No to an offer you can't pay (after its 5-second countdown) says in Recent moves what you lacked. */
class AutoNoTest {
    private val names = listOf("Ann", "Bea", "Cy")

    private fun playing(expansion: Expansion): GameState {
        val s = Rules.newGame(GameConfig(expansion = expansion, numPlayers = 3), 4)
        val buf = ActionBuffer()
        val bots = List(3) { GreedyBot(it.toLong()) }
        while (s.phase != Phase.PLAY_TURN) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        Rules.apply(s, Action.roll(2, 2, if (s.ck != null) 0 else -1))
        while (s.phase != Phase.PLAY_TURN) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
        for (p in 0 until 3) for (c in 0 until tradeCards(s)) {
            if (c < CARD_TYPES) s.hands[p * CARD_TYPES + c] = 0 else s.ck!!.commodities[p * Commodity.COUNT + c - CARD_TYPES] = 0
        }
        return s
    }

    @Test
    fun `the line names the offerer and every card you were short of`() {
        val s = playing(Expansion.BASE)
        val from = s.currentPlayer; val to = (from + 1) % 3
        s.hands[from * CARD_TYPES + Resource.WOOD] = 1
        s.hands[to * CARD_TYPES + Resource.WHEAT] = 1
        Rules.applyChecked(s, offerFor(s, IntArray(5).also { it[Resource.WOOD] = 1 }, IntArray(5).also { it[Resource.WHEAT] = 2; it[Resource.ORE] = 1 })!!)
        assertEquals(to, s.actingPlayer)
        assertTrue(Rules.cantPayOffer(s, to)); assertFalse(canPayOffer(s, to))
        assertEquals("No to ${names[from]}'s offer, given for you: you don't hold 1 Grain, 1 Stone", autoNoText(s, to, names))
    }

    @Test
    fun `Age of Chivalry - a missing commodity is named too`() {
        val s = playing(Expansion.CITIES_AND_KNIGHTS)
        val from = s.currentPlayer; val to = (from + 1) % 3
        s.hands[from * CARD_TYPES + Resource.WOOD] = 1
        val get = IntArray(CK_CARD_TYPES).also { it[Commodity.COIN] = 1 }
        Rules.applyChecked(s, offerFor(s, IntArray(CK_CARD_TYPES).also { it[Resource.WOOD] = 1 }, get)!!)
        assertTrue(Rules.cantPayOffer(s, to))
        assertEquals("No to ${names[from]}'s offer, given for you: you don't hold 1 Silver", autoNoText(s, to, names))
    }

    @Test
    fun `the countdown uses the engine's rule, logs the line, then sends No as a tap would`() {
        val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        val effect = main.substringAfter("LaunchedEffect(offerKey) {").substringBefore("\n    }\n")
        assertTrue("if (!Rules.cantPayOffer(state, me)) { sounds.tradeChime" in effect)
        val logged = effect.indexOf("log.add(0, autoNoText(state, me, names))")
        assertTrue(logged > 0 && logged < effect.indexOf("humanMoves.trySend(Action.REJECT_TRADE)"), "the line goes in before the No")
    }
}

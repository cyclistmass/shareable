package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.engine.Progress
import wgbg.engine.Rules

/** #123: every pick-a-card moment is a sheet of cards to tap, and Trade never covers one. */
class CardPickTest {
    private val names = listOf("You", "Blue", "Orange")
    private fun chivalry() = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 5)

    @Test
    fun `the Informer's take is a card pick naming whose cards they are`() {
        val s = chivalry().also { it.chivalry!!.pendingTarget = 1 }
        val legal = listOf(Action.takeProgress(Progress.SPY), Action.takeProgress(Progress.WEDDING))
        val pick = cardPick(s, legal, names)!!
        assertEquals(legal, pick.options)
        assertTrue("Blue" in pick.title, pick.title)
        assertNull(pick.done)                                 // forced: no way out
    }

    @Test
    fun `discarding a progress card, the Raiders deck draw and Harbour Charter offers are card picks too`() {
        val s = chivalry()
        assertEquals(2, cardPick(s, listOf(Action.discardProgress(1), Action.discardProgress(4)), names)!!.options.size)
        assertEquals(3, cardPick(s, (0 until 3).map { Action.chooseProgressDeck(it) }, names)!!.options.size)
        val harbour = listOf(Action.CHIVALRY_DONE, Action.commercialHarborOffer(0, 1), Action.commercialHarborOffer(2, 2))
        val pick = cardPick(s, harbour, names)!!
        assertEquals(2, pick.options.size)
        assertEquals(Action.CHIVALRY_DONE, pick.done)              // Harbour Charter can stop early
    }

    @Test
    fun `the prompt says what the pick is, not "Trade, improve your towns, or end your turn"`() {
        val s = chivalry().also { it.chivalry!!.pendingTarget = 2; it.phase = Phase.SPY }
        assertEquals("Informer: take one of Orange's progress cards", chivalryPrompt(s, listOf(Action.takeProgress(Progress.SPY)), names))
        s.phase = Phase.COMMERCIAL_HARBOR
        assertTrue(chivalryPrompt(s, listOf(Action.CHIVALRY_DONE), names)!!.startsWith("Harbour Charter"))
    }

    @Test
    fun `ordinary turns and the base game have no card pick`() {
        assertNull(cardPick(chivalry(), listOf(Action.END_TURN), names))
        assertNull(cardPick(Rules.newGame(GameConfig(numPlayers = 3), 5), listOf(Action.END_TURN), names))
    }

    @Test
    fun `the Trade button only shows in the main phase of your turn, never over a pending pick`() {
        val s = chivalry()
        while (s.phase != Phase.PLAY_TURN) { val l = Rules.legalActions(s); Rules.apply(s, l.first()) }
        s.hasRolled = true
        assertTrue(tradeButtonVisible(s))
        for (p in listOf(Phase.SPY, Phase.PROGRESS_DISCARD, Phase.DEFENDER_DRAW, Phase.COMMERCIAL_HARBOR, Phase.MOVE_ROBBER)) {
            s.phase = p
            assertFalse(tradeButtonVisible(s), "$p")
        }
    }

    @Test
    fun `after a 7 the prompt says the bandit comes first, other bandit moves keep the plain line`() {
        var sevens = 0; var others = 0
        for (seed in 1L..40L) {
            val s = Rules.newGame(GameConfig(numPlayers = 3), seed)
            val bots = List(3) { wgbg.ai.GreedyBot(it.toLong() + seed) }
            val buf = wgbg.engine.ActionBuffer()
            while (!s.isOver && s.turn < 120) {
                Rules.legalActions(s, buf)
                val legal = (0 until buf.size).map { buf[it] }
                if (s.phase == Phase.MOVE_ROBBER) {
                    val line = prompt(s, legal, names)
                    if (s.hasRolled && s.lastRoll == 7) { sevens++; assertEquals("You rolled 7: move the bandit first, then you can play cards", line) }
                    else { others++; assertEquals("Tap a tile to move the bandit", line) }
                }
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
        assertTrue(sevens > 20, "sevens $sevens, other bandit moves $others")
    }
}

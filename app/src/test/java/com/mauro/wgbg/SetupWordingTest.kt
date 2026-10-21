package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.engine.Rules

/** Placing the first pieces says what is placed, and shows no dice or trading yet (0.2.8). */
class SetupWordingTest {
    @Test
    fun `setup asks for a homestead, then in Age of Chivalry for a town on an empty corner, never an upgrade`() {
        for (expansion in listOf(Expansion.BASE, Expansion.CHIVALRY)) {
            val s = Rules.newGame(GameConfig(expansion, numPlayers = 3), 5)
            val bots = List(3) { GreedyBot(it.toLong()) }
            val buf = ActionBuffer()
            var towns = 0; var homesteads = 0
            while (inSetup(s)) {
                val legal = Rules.legalActions(s)
                if (s.phase == Phase.BUILD_INITIAL_SETTLEMENT) {
                    val a = legal.first()
                    if (a.type == ActionType.BUILD_CITY) {
                        towns++
                        assertEquals("Tap a glowing corner to place a town", prompt(s, legal, PLAYER_NAMES))
                        assertEquals("Place your town here?", confirmTitle(s, a))
                    } else {
                        homesteads++
                        assertEquals("Tap a glowing corner to place a homestead", prompt(s, legal, PLAYER_NAMES))
                        assertEquals("Place your homestead here?", confirmTitle(s, a))
                    }
                }
                Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
            if (expansion == Expansion.BASE) assertEquals(0 to 6, towns to homesteads)
            else assertEquals(3 to 3, towns to homesteads)
            assertFalse(inSetup(s))
            assertFalse(tradeButtonVisible(s)) // setup over, but no trade before the first roll
        }
    }

    @Test
    fun `a town upgrade during play still reads as an upgrade`() {
        val s = Rules.newGame(GameConfig(numPlayers = 3), 2)
        s.phase = Phase.PLAY_TURN
        assertEquals("Upgrade this homestead to a town?", confirmTitle(s, wgbg.engine.Action.of(ActionType.BUILD_CITY, 4L)))
    }
}

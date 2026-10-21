package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.engine.Rules

class TurnHeaderTest {
    @Test
    fun `the turn panel names whoever's turn it is, even before they roll`() {
        val s = Rules.newGame(GameConfig(numPlayers = 4), 9)
        val tracker = TurnTracker(listOf("You", "Blue", "Orange", "White"))
        val bots = List(4) { GreedyBot(it.toLong()) }
        val buf = ActionBuffer()
        var checked = 0
        while (!s.isOver && s.turn < 30) {
            Rules.legalActions(s, buf)
            val who = s.actingPlayer
            val a = bots[who].choose(s, buf)
            tracker.apply(s, who, a) { Rules.apply(s, a) }
            if (s.phase == Phase.PLAY_TURN && !s.hasRolled) {
                assertEquals(s.currentPlayer, turnHeader(tracker, s)!!.player, "turn ${s.turn}")
                checked++
            }
        }
        assert(checked > 10)
    }

    @Test
    fun `during setup the panel names whoever places now, before their first piece`() {
        val s = Rules.newGame(GameConfig(numPlayers = 4), 3)
        val tracker = TurnTracker(listOf("Mauro", "Crest", "Orange", "White"))
        val bots = List(4) { GreedyBot(it.toLong()) }
        val buf = ActionBuffer()
        var checked = 0
        while (s.phase == Phase.BUILD_INITIAL_SETTLEMENT || s.phase == Phase.BUILD_INITIAL_ROAD) {
            assertEquals(s.actingPlayer, turnHeader(tracker, s)!!.player, "placement ${checked}")
            checked++
            Rules.legalActions(s, buf)
            val who = s.actingPlayer
            val a = bots[who].choose(s, buf)
            tracker.apply(s, who, a) { Rules.apply(s, a) }
        }
        assertEquals(16, checked)
    }

    @Test
    fun `an Age of Chivalry roll records the event die, a base roll has none`() {
        val chivalry = Rules.newGame(GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY, numPlayers = 3), 5)
        val tracker = TurnTracker(listOf("Mauro", "Crest", "Orange"))
        val bots = List(3) { GreedyBot(it.toLong()) }
        val buf = ActionBuffer()
        var seen = 0
        while (seen < 20 && !chivalry.isOver) {
            Rules.legalActions(chivalry, buf)
            val who = chivalry.actingPlayer
            var a = bots[who].choose(chivalry, buf)
            if (a.type == ActionType.ROLL) a = wgbg.engine.Action.roll(3, 4, seen % 6)
            tracker.apply(chivalry, who, a) { Rules.apply(chivalry, a) }
            if (a.type == ActionType.ROLL) { assertEquals(seen % 6, tracker.current!!.event); seen++ }
        }
        assert(seen == 20)
        val base = Rules.newGame(GameConfig(numPlayers = 3), 5)
        val t2 = TurnTracker(listOf("A", "B", "C"))
        while (base.phase != Phase.PLAY_TURN) { Rules.legalActions(base, buf); val w = base.actingPlayer; val a = bots[w].choose(base, buf); t2.apply(base, w, a) { Rules.apply(base, a) } }
        val roll = wgbg.engine.Action.roll(2, 5)
        t2.apply(base, base.actingPlayer, roll) { Rules.apply(base, roll) }
        assertEquals(-1, t2.current!!.event)
    }
}

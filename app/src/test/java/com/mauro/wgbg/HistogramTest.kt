package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rules

class HistogramTest {
    @Test
    fun `every roll is counted once, totals and the event die, with fair-dice expectations`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 4)
        val tracker = TurnTracker(listOf("You", "Blue", "Orange"))
        val bots = List(3) { GreedyBot(it.toLong()) }
        val buf = ActionBuffer()
        var rolls = 0
        while (!s.isOver && s.turn < 300) {
            Rules.legalActions(s, buf)
            val who = s.actingPlayer
            val a = bots[who].choose(s, buf)
            if (a.type == ActionType.ROLL) rolls++
            tracker.apply(s, who, a) { Rules.apply(s, a) }
        }
        assertEquals(rolls, tracker.rollCounts.sum())
        assertEquals(rolls, tracker.eventCounts.sum())
        assertEquals(0, tracker.rollCounts[0] + tracker.rollCounts[1])
        val bars = totalBars(tracker.rollCounts)
        assertEquals((2..12).map { "$it" }, bars.map { it.label })
        assertEquals(rolls.toFloat(), bars.sumOf { it.expected.toDouble() }.toFloat(), 0.01f)
        assertEquals(rolls.toFloat(), eventBars(tracker.eventCounts).sumOf { it.expected.toDouble() }.toFloat(), 0.01f)
        assertTrue(DICE_WAYS.sum() == 36)
    }

    @Test
    fun `every Age of Chivalry move reads as words in the history, never an engine name`() {
        for (t in wgbg.engine.ChivalryActionType.entries) assertTrue(describe(t).none { it == '_' } && describe(t) != t.toString().lowercase().replace('_', ' '), "$t -> ${describe(t)}")
    }
}

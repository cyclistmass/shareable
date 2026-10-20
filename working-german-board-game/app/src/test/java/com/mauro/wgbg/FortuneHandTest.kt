package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.DevCard
import wgbg.engine.GameConfig
import wgbg.engine.Rules
import wgbg.ai.GreedyBot

/** Fortune cards in the hand row (Fable review 2.5): a card is playable exactly when the engine offers its move. */
class FortuneHandTest {
    private val plays = mapOf(
        ActionType.PLAY_KNIGHT_CARD to DevCard.KNIGHT, ActionType.PLAY_ROAD_BUILDING to DevCard.ROAD_BUILDING,
        ActionType.PLAY_YEAR_OF_PLENTY to DevCard.YEAR_OF_PLENTY, ActionType.PLAY_MONOPOLY to DevCard.MONOPOLY,
    )

    @Test
    fun `the playable set matches the engine's Fortune moves, and never a Landmark`() {
        var seen = 0
        for (seed in 1L..20L) {
            val s = Rules.newGame(GameConfig(numPlayers = 4), seed)
            val bots = List(4) { GreedyBot(seed * 7 + it) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 250) {
                Rules.legalActions(s, buf)
                val legal = buf.toList()
                val set = fortunePlayable(legal)
                assertEquals(legal.mapNotNull { plays[it.type] }.toSet(), set)
                assertTrue(DevCard.VICTORY_POINT !in set)
                // Every card offered is one the player holds.
                for (c in set) assertTrue(s.devHand[s.actingPlayer * DevCard.COUNT + c] > 0, "card $c not in hand")
                seen += set.size
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
        assertTrue(seen > 20, "Fortune plays seen: $seen")
    }
}

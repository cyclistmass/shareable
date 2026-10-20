package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ActionType
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.ai.GreedyBot
import wgbg.engine.ActionBuffer
import wgbg.engine.Rules

class BuildMenuTest {
    @Test
    fun `each ruleset gets its own entries, Fortune cards only in the base game`() {
        assertEquals(listOf("Road", "Homestead", "Town", "Fortune card"), BuildKind.forRuleset(false).map { it.label })
        assertEquals(listOf("Road", "Homestead", "Town", "New knight", "Supply knight", "Promote knight", "Move knight", "Ramparts"), BuildKind.forRuleset(true).map { it.label })
    }

    @Test
    fun `a picked entry narrows the board to its moves only, and nothing picked offers everything`() {
        val s = Rules.newGame(GameConfig(numPlayers = 4), 9)
        val bots = List(4) { GreedyBot(it.toLong()) }
        val buf = ActionBuffer()
        var checked = 0
        while (!s.isOver && s.turn < 200) {
            val legal = Rules.legalActions(s)
            if (s.phase == Phase.PLAY_TURN && legal.any { it.type == ActionType.BUILD_ROAD }) {
                assertEquals(legal, boardMoves(legal, null))
                assertTrue(boardMoves(legal, BuildKind.ROAD).all { it.type == ActionType.BUILD_ROAD } && boardMoves(legal, BuildKind.ROAD).isNotEmpty())
                checked++
            }
            Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
        }
        assertTrue(checked > 5, "road moments seen: $checked")
    }

    @Test
    fun `the menu shows only what can be done now, plus the picked entry`() {
        val road = Action.of(ActionType.BUILD_ROAD, 3L)
        assertEquals(listOf(BuildKind.ROAD), shownKinds(false, listOf(road), null))
        assertEquals(emptyList<BuildKind>(), shownKinds(true, emptyList(), null))
        assertEquals(listOf(BuildKind.ROAD, BuildKind.TOWN), shownKinds(false, listOf(road), BuildKind.TOWN))
    }
}

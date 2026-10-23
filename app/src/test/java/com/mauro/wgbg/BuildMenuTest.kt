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
    private fun improve(track: Int, b: Int) = Action.of(wgbg.engine.ChivalryActionType.IMPROVE_CITY, (b.toLong() shl 8) or track.toLong())

    @Test
    fun `each ruleset gets its own entries, Fortune cards only in the base game`() {
        assertEquals(listOf("Road", "Homestead", "Town", "Fortune card"), BuildKind.forRuleset(false).map { it.label })
        assertEquals(listOf("Road", "Homestead", "Town", "New knight", "Ramparts", "Guilds", "Academy", "Crown"), BuildKind.forRuleset(true).map { it.label })
    }

    @Test
    fun `every board plate says what to tap while it's picked, and only those plates do`() {
        for (k in BuildKind.entries) assertEquals(k.onBoard, k.boardHint != null, "${k.label}: ${k.boardHint}")
        assertTrue(BuildKind.RAMPARTS.boardHint!!.contains("Town"))
        assertTrue(BuildKind.entries.all { it.boardHint?.startsWith("Tap a glowing") ?: true })
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
    fun `0_3_5 every plate shows always, engraved when it can't be done (one row, nothing jumps)`() {
        assertEquals(BuildKind.forRuleset(false), shownKinds(false))
        assertEquals(BuildKind.forRuleset(true), shownKinds(true))
    }

    @Test
    fun `a plate tap narrows the board, buys at once, asks, or says which town to tap`() {
        val road = Action.of(ActionType.BUILD_ROAD, 3L)
        assertEquals(null, plateTap(BuildKind.ROAD, emptyList(), null), "an engraved plate does nothing")
        assertEquals(Pick.Mode(BuildKind.ROAD), plateTap(BuildKind.ROAD, listOf(road), null))
        assertEquals(Pick.Mode(null), plateTap(BuildKind.ROAD, listOf(road), BuildKind.ROAD), "a second tap lets go")
        val card = Action.of(ActionType.BUY_DEVELOPMENT_CARD, 0L)
        assertEquals(Pick.Now(card), plateTap(BuildKind.FORTUNE, listOf(card), null))
        val guild = improve(wgbg.engine.Track.TRADE, 0)
        assertEquals(Pick.Ask(listOf(guild)), plateTap(BuildKind.GUILDS, listOf(guild), null))
        val capitals = listOf(improve(wgbg.engine.Track.TRADE, 5), improve(wgbg.engine.Track.TRADE, 9))
        assertTrue(plateTap(BuildKind.GUILDS, capitals, null) is Pick.Hint)
        assertTrue(BuildKind.GUILDS.offers(guild) && !BuildKind.ACADEMY.offers(guild))
    }
}

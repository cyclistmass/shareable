package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rules
import java.io.File

/**
 * A finished game's Menu (0.3.3 user test): no "Leave for now" or Resume for a game that is over, only a new game, the
 * start screen or a look at the board; and the roll chart closes when the Menu opens.
 */
class EndGameMenuTest {
    private val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText().replace("\r\n", "\n")
    private val exitDialog = main.substringAfter("if (confirmExit) Column(").substringBefore("// The Trade button is always there")

    @Test
    fun `a finished game's Leave dialog offers New game, Back to start and See the board, and nothing to keep or quit`() {
        val over = exitDialog.substringAfter("if (tick.let { state.isOver }) {").substringBefore("return@Column")
        assertTrue("This game is over" in over)
        for (tag in listOf("\"exit-new-game\"", "\"exit-yes\"", "\"exit-no\"")) assertTrue(tag in over, tag)
        for (gone in listOf("Leave for now", "Resume", "Keep playing", "Quit", "End game for everyone")) assertFalse(gone in over, gone)
        assertTrue("if (online == null) GoButton(\"New game\"" in over, "a new game only where this phone runs the game")
        // The finished branch returns before the running game's choices are built.
        assertTrue(exitDialog.indexOf("return@Column") < exitDialog.indexOf("Text(if (keeps) \"Leave for now"))
    }

    @Test
    fun `opening the Menu closes the roll chart, and the chart closes the Menu`() {
        val toggle = main.substringAfter("\"menu-toggle\"").substringBefore("\n        }\n")
        assertTrue("panelOpen = !panelOpen; histogramOpen = false" in toggle, toggle)
        // Look & art (#91): no game Menu there, but its Settings still show and hide (the first cut left them stuck open).
        assertTrue("if (lookAndArt) \"Settings\" else \"Menu\"" in main.substringBefore("\"menu-toggle\"").takeLast(200))
        assertTrue("if (settingsOpen || lookAndArt) settingsOpen = !settingsOpen" in toggle, toggle)
        assertTrue("onDiceDoubleTap = { histogramOpen = true; settingsOpen = false; panelOpen = false }" in main)
        // The Leave dialog and a new game come up without the Menu left open over them (device check, 0.3.4).
        assertTrue("StopButton(\"Leave game\", \"menu-exit\") { confirmExit = true; panelOpen = false }" in main)
        assertTrue("({ seed++; playing = true; panelOpen = false })" in main)
    }

    @Test
    fun `Settings hides Pause and speed once a game you played is over`() {
        val controls = main.substringAfter("gameControls = if (!lookAndArt) {").substringBefore("} else null,")
        val guarded = controls.substringAfter("if (!(human && tick.let { state.isOver })) {").substringBefore("\n                        }\n")
        assertTrue("\"menu-pause\"" in guarded && "\"menu-speed\"" in guarded)
    }

    @Test
    fun `fuzz and break it - every finished game, both rulesets and 3 or 4 players, takes no further move`() {
        val buf = ActionBuffer()
        val tries = listOf(Action.of(ActionType.ROLL), Action.of(ActionType.END_TURN), Action.REJECT_TRADE, Action.BUY_DEVELOPMENT_CARD)
        var finished = 0
        for (seed in 1..24L) {
            val ck = seed % 2 == 0L; val n = if (seed % 3 == 0L) 3 else 4
            val s = Rules.newGame(GameConfig(expansion = if (ck) Expansion.CITIES_AND_KNIGHTS else Expansion.BASE, numPlayers = n), seed)
            val bots = List(n) { GreedyBot(seed * 7 + it) }
            var moves = 0
            while (!s.isOver && moves++ < 20_000) { Rules.legalActions(s, buf); Rules.apply(s, bots[s.actingPlayer].choose(s, buf)) }
            if (!s.isOver) continue
            finished++
            Rules.legalActions(s, buf)
            assertEquals(0, buf.size, "seed $seed: moves offered after the end")
            val hash = s.stateHash()
            for (a in tries) {
                assertTrue(runCatching { Rules.applyChecked(s, a) }.isFailure, "seed $seed: $a after the end")
                assertEquals(hash, s.stateHash())
            }
        }
        assertTrue(finished >= 20, "only $finished of 24 games finished")
    }
}

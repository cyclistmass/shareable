package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.ActionBuffer
import wgbg.engine.ChivalryActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Progress
import wgbg.engine.Rules
import kotlin.random.Random

/** Mauro's two card reports, 2026-10-13: Hoist went off before he saw what it did; Grand Factor sat there with no reason. */
class ProgressPlayTest {
    private fun report(name: String, drop: Int = 0): GameState {
        val save = LocalSave.decode(javaClass.getResource("/reports/$name.save")!!.readText())!!
        return save.copy(moves = save.moves.dropLast(drop)).replay()!!
    }

    @Test
    fun `Hoist with one choice still shows the picker, saying what it improves and what it costs`() {
        val s = report("hoist", drop = 1)                          // just before he played it
        val (label, acts) = chivalryButtonGroups(s, Rules.legalActions(s), PLAYER_NAMES).first { it.first == "Play Hoist" }
        assertEquals(1, acts.size)
        assertTrue(asksFirst(acts[0]), "one choice still asks")
        assertEquals("Improve Guilds to level 2: 1 Fabric instead of 2", progressOption(s, acts[0], PLAYER_NAMES), label)
    }

    @Test
    fun `Grand Factor says why it can't be played when you lead`() {
        val s = report("grandfactor")
        val me = s.currentPlayer
        assertTrue(Progress.MASTER_MERCHANT !in playableProgress(s, me))
        assertEquals("it only takes from a player with more points than you, and nobody has more", notPlayableWhy(s, me, Progress.MASTER_MERCHANT))
    }

    @Test
    fun `the Wrecker that hit nobody in that game isn't offered, and says why`() {
        val save = LocalSave.decode(javaClass.getResource("/reports/grandfactor.save")!!.readText())!!
        val s = save.copy(moves = save.moves.take(239)).replay()!!     // move 239: his Saboteur, nobody at his points
        val me = s.currentPlayer
        assertTrue(Rules.legalActions(s).any { it.type == ChivalryActionType.PLAY_PROGRESS && it.a == Progress.SABOTEUR }, "still legal (old games replay)")
        assertTrue(Progress.SABOTEUR !in playableProgress(s, me))
        assertTrue(chivalryButtonGroups(s, Rules.legalActions(s), PLAYER_NAMES).none { it.first == "Play Wrecker" })
        assertEquals("the players it would hit hold fewer than 2 cards", notPlayableWhy(s, me, Progress.SABOTEUR))
    }

    @Test
    fun `every progress card you can't play after the roll says what it's missing`() {
        val generic = "nothing it can do right now"
        var checked = 0
        for (seed in 1L..30L) {
            val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), seed)
            val rnd = Random(seed); val buf = ActionBuffer(); var guard = 0
            while (!s.isOver && guard++ < 20_000 && !(s.turn >= 20 + seed % 30 && s.phase == Phase.PLAY_TURN && s.hasRolled && s.actingPlayer == s.currentPlayer)) {
                Rules.legalActions(s, buf)
                val all = (0 until buf.size).map { buf[it] }
                val pick = all.filter { it.type != ChivalryActionType.PLAY_PROGRESS }.ifEmpty { all }
                Rules.apply(s, pick[rnd.nextInt(pick.size)])
            }
            if (s.isOver) continue
            val me = s.currentPlayer
            val now = playableProgress(s, me)
            for (c in 0 until Progress.COUNT) {
                if (c == Progress.ALCHEMIST || c == Progress.PRINTER || c == Progress.CONSTITUTION || c in now) continue
                // Any card that isn't legal here, as if held: its reason is the card's own.
                val held = s.copy().also { it.chivalry!!.progressHand[me * Progress.COUNT + c]++ }
                if (c in playableProgress(held, me)) continue
                assertNotEquals(generic, notPlayableWhy(held, me, c), "${PROGRESS_NAMES[c]} seed $seed")
                checked++
            }
        }
        assertTrue(checked > 50, "checked $checked")
    }
}

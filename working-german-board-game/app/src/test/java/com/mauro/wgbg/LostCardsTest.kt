package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.ai.RandomBot
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.CARD_TYPES
import wgbg.engine.ChivalryActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Progress
import wgbg.engine.Rules

/**
 * Cards a player loses after the roll (a 7's discard, the bandit's robbery, the Abbot robbing everyone at the bandit's
 * new tile) show as red minis on that player's panel, commodities too (a player report: lost commodities didn't show).
 */
class LostCardsTest {
    /** Red minis per card kind for player [p], as [p] sees them. */
    private fun lostShown(r: TurnReport?, p: Int): IntArray {
        val out = IntArray(wgbg.engine.CHIVALRY_CARD_TYPES)
        for (m in rollMinis(r, p, p)) if (m.n < 0 && m.card >= 0) out[m.card] -= m.n
        return out
    }

    @Test
    fun `every card lost to a discard, the bandit or the Abbot shows as a lost card, commodities too`() {
        var discards = 0; var robberies = 0; var abbots = 0; var commodities = 0
        for (seed in 1L..60L) {
            val players = 3 + (seed % 2).toInt()
            val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = players), seed)
            val names = List(players) { "P$it" }
            val tracker = TurnTracker(names)
            // Random bots play the Abbot whenever they hold one; greedy ones keep the game moving.
            val bots = List(players) { if (it % 2 == 0) GreedyBot(seed + it) else RandomBot(seed * 7 + it) }
            val buf = ActionBuffer()
            // Cards each player lost this turn to the moves checked here, by kind.
            var lostTurn = Array(players) { IntArray(wgbg.engine.CHIVALRY_CARD_TYPES) }
            var turnKey = -1 to -1
            while (!s.isOver && s.turn < 250) {
                Rules.legalActions(s, buf)
                val who = s.actingPlayer
                val a = bots[who].choose(s, buf)
                if (turnKey != s.turn to s.currentPlayer) { turnKey = s.turn to s.currentPlayer; lostTurn = Array(players) { IntArray(wgbg.engine.CHIVALRY_CARD_TYPES) } }
                val before = Array(players) { p -> IntArray(wgbg.engine.CHIVALRY_CARD_TYPES) { s.card(p, it) } }
                val abbot = a.type == ChivalryActionType.PLAY_PROGRESS && (a.payload and 0xff).toInt() == Progress.BISHOP
                tracker.apply(s, who, a) { Rules.apply(s, a) }
                val checked = a.type == ActionType.DISCARD || a.type == ActionType.MOVE_ROBBER || abbot
                if (!checked || !(tracker.current?.rolled ?: false)) continue
                when {
                    a.type == ActionType.DISCARD -> discards++
                    a.type == ActionType.MOVE_ROBBER -> robberies++
                    else -> abbots++
                }
                for (p in 0 until players) for (c in 0 until wgbg.engine.CHIVALRY_CARD_TYPES) {
                    val lost = before[p][c] - s.card(p, c)
                    if (lost > 0 && p != s.currentPlayer || lost > 0 && a.type == ActionType.DISCARD) {
                        lostTurn[p][c] += lost
                        if (c >= CARD_TYPES) commodities++
                    }
                }
                for (p in 0 until players) {
                    val shown = lostShown(tracker.current, p)
                    for (c in 0 until wgbg.engine.CHIVALRY_CARD_TYPES) assertTrue(shown[c] >= lostTurn[p][c],
                        "seed $seed turn ${s.turn} after $a: ${names[p]} lost ${lostTurn[p].toList()}, shown ${shown.toList()}")
                }
            }
        }
        assertTrue(discards > 50 && robberies > 10 && abbots > 0 && commodities > 10,
            "discards $discards, robberies $robberies, Abbots $abbots, commodity losses $commodities")
    }

    @Test
    fun `a robbed card stays secret to everyone but the two players`() {
        val r = TurnReport(0, 5, 3, 4, steals = listOf(Steal(0, 1, 6), Steal(0, 2, 1)))
        assertEquals(listOf(RollMini(6, -1)), rollMinis(r, 1, 1))
        assertEquals(listOf(RollMini(-1, -1)), rollMinis(r, 1, 2), "player 2 sees player 1's loss face down")
        assertEquals(listOf(RollMini(6, 1), RollMini(1, 1)), rollMinis(r, 0, 0))
        assertEquals(listOf(RollMini(-1, 1), RollMini(1, 1)), rollMinis(r, 0, 2))
    }
}

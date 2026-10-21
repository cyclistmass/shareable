package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.ai.RandomBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.ChivalryActionType
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Rules

/**
 * Every card mode, every move: whatever the rules allow, the player has a way to do it on screen, either a board tap,
 * a button, the trade chest or the bottom-centre card sheet (Mauro: "fully test every card mode"). Also, while a
 * card sheet takes over the controls, nothing else may be needed, since the thumb buttons are hidden then.
 */
class EveryMoveReachableTest {
    private val names = listOf("You", "Blue", "Orange", "White")
    private val buttons = setOf(ActionType.ROLL, ActionType.END_TURN, ActionType.BUY_DEVELOPMENT_CARD, ActionType.PLAY_KNIGHT_CARD,
        ActionType.PLAY_ROAD_BUILDING, ActionType.PLAY_YEAR_OF_PLENTY, ActionType.PLAY_MONOPOLY)
    private val tradeChest = setOf(ActionType.OFFER_TRADE, ActionType.ACCEPT_TRADE, ActionType.REJECT_TRADE, ActionType.COUNTER_TRADE,
        ActionType.CONFIRM_TRADE, ActionType.CANCEL_TRADE, ActionType.MARITIME_TRADE, ChivalryActionType.OFFER_TRADE_CK, ChivalryActionType.COUNTER_TRADE_CK)
    private val sheets = setOf(ActionType.DISCARD, ChivalryActionType.CHOOSE_CARD)

    private fun reachable(s: GameState, a: Action, legal: List<Action>): Boolean =
        spotOf(s.board, a) != null || (a.type == ChivalryActionType.PLAY_PROGRESS && spotOf(s.board, a, second = true) != null) ||
            a.type in buttons || a.type in tradeChest || a.type in sheets ||
            chivalryButtonGroups(s, legal, names).any { (_, acts) -> a in acts }

    private fun play(expansion: Expansion, players: Int, seeds: LongRange, random: Boolean): Pair<Set<String>, Set<Int>> {
        val kinds = mutableSetOf<String>(); val cards = mutableSetOf<Int>()
        for (seed in seeds) {
            val s = Rules.newGame(GameConfig(expansion = expansion, numPlayers = players), seed)
            val bots = List(players) { if (random) RandomBot(it.toLong() + seed) else GreedyBot(it.toLong() + seed) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 500) {
                val legal = Rules.legalActions(s)
                for (a in legal.filter { offered(s, it) }) {   // plays that would do nothing are left out on purpose
                    assertTrue(reachable(s, a, legal), "no way to do $a in ${s.phase} (seed $seed)")
                    kinds += a.type.toString()
                    if (a.type == ChivalryActionType.PLAY_PROGRESS) cards += a.a
                }
                val types = legal.map { it.type }.toSet()
                if (types.any { it in sheets }) assertTrue(types.all { it in sheets }, "a card sheet hides needed buttons: $types in ${s.phase}")
                Rules.legalActions(s, buf)
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
            }
        }
        return kinds to cards
    }

    @Test
    fun `base game, 3 and 4 players`() {
        play(Expansion.BASE, 3, 1L..15L, random = true); play(Expansion.BASE, 4, 1L..15L, random = false)
    }

    @Test
    fun `Age of Chivalry, every progress card reached`() {
        val (_, a) = play(Expansion.CHIVALRY, 3, 1L..30L, random = true)
        val (_, b) = play(Expansion.CHIVALRY, 4, 1L..30L, random = false)
        val missing = PROGRESS_NAMES.indices.filter { it !in a + b }.map { PROGRESS_NAMES[it] }
        // Printing Press and Charter of Rights are played at once when drawn, so they never show as moves.
        assertTrue(missing.all { it in setOf("Printing Press", "Charter of Rights") }, "progress cards never offered: $missing")
    }
}

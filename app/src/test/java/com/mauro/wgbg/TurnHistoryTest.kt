package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.GreedyBot
import wgbg.engine.ActionBuffer
import wgbg.engine.GameConfig
import wgbg.engine.Rules

class TurnHistoryTest {
    @Test
    fun `the info box keeps finished turns newest first, every player's, at most HISTORY of them`() {
        val s = Rules.newGame(GameConfig(numPlayers = 3), 11)
        val tracker = TurnTracker(listOf("You", "Blue", "Orange"))
        val bots = List(3) { GreedyBot(it.toLong()) }
        val buf = ActionBuffer()
        while (!s.isOver && s.turn < 80) {
            Rules.legalActions(s, buf)
            val who = s.actingPlayer
            val a = bots[who].choose(s, buf)
            tracker.apply(s, who, a) { Rules.apply(s, a) }
        }
        val h = tracker.history
        assertEquals(TurnTracker.HISTORY, h.size, "capped")
        assertEquals(h.first(), tracker.last, "newest first")
        assertTrue(h.zipWithNext().all { (newer, older) -> newer.turn >= older.turn }, "in order")
        assertEquals(setOf(0, 1, 2), h.map { it.player }.toSet(), "everyone's turns, not just yours")
        assertTrue(h.all { it.rolled || it.events.isNotEmpty() }, "only turns that did something")
    }

    @Test
    fun `players hit by someone else's card or bandit are told who, with what, and what they lost`() {
        var hits = 0; var byCard = 0; var byBandit = 0
        for (seed in 1L..20L) {
            val s = Rules.newGame(GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY, numPlayers = 3), seed)
            val tracker = TurnTracker(listOf("You", "Blue", "Orange"))
            val bots = List(3) { GreedyBot(it.toLong() + seed) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 300) {
                Rules.legalActions(s, buf)
                val who = s.actingPlayer
                val a = bots[who].choose(s, buf)
                tracker.apply(s, who, a) { Rules.apply(s, a) }
            }
            for (h in tracker.hits) {
                assertTrue(h.victim != h.by, "never your own turn: $h")
                assertTrue(h.lost.isNotBlank(), "$h")
                if (h.cause.startsWith("played ")) { byCard++; assertTrue(h.why != null && h.cause.removePrefix("played ") in PROGRESS_NAMES, "$h") }
                if (h.cause.contains("bandit")) byBandit++
            }
            assertEquals((1..tracker.hits.size).toList(), tracker.hits.map { it.seq - tracker.hits.first().seq + 1 }, "seq counts up")
            hits += tracker.hits.size
        }
        assertTrue(byCard > 10 && byBandit > 10, "cards $byCard, bandit $byBandit of $hits")
    }

    @Test
    fun `discards, robberies and card counts include commodities`() {
        // Reports Mnmfs73 / jpRLBzr / ALdrduT (0.3.0): discarded commodities were missing from the list, and a
        // hand of 4 resources + 2 commodities showed 4 cards.
        var discards = 0; var commodityDiscards = 0; var commoditySteals = 0
        for (seed in 1L..30L) {
            val s = Rules.newGame(GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY, numPlayers = 3), seed)
            val tracker = TurnTracker(listOf("You", "Blue", "Orange"))
            val bots = List(3) { GreedyBot(it.toLong() + seed) }
            val buf = ActionBuffer()
            while (!s.isOver && s.turn < 300) {
                Rules.legalActions(s, buf)
                val who = s.actingPlayer
                val a = bots[who].choose(s, buf)
                val had = s.cardCount(who)
                val before = tracker.current?.rollOut?.getOrNull(who)?.sum()
                tracker.apply(s, who, a) { Rules.apply(s, a) }
                if (a.type == wgbg.engine.ActionType.DISCARD) {
                    discards++
                    val names = listOf("You", "Blue", "Orange")
                    // One line per player for the whole discard, listing every card put back so far (Fable review T8).
                    val out = tracker.current!!.rollOut.getOrNull(who)
                    if (before != null && out != null) assertEquals(had - s.cardCount(who), out.sum() - before, "the running total grows by what left")
                    val mine = tracker.current!!.events.filter { it.startsWith("${names[who]} discarded ") && it.substringAfter("discarded ").first().isDigit() }
                    if (out != null) assertEquals(1, mine.size, "one line each: $mine")
                    val line = mine.last()
                    val listed = Regex("""(\d+) (\w+)""").findAll(line.substringAfter("discarded ")).sumOf { it.groupValues[1].toInt() }
                    assertEquals(out?.sum() ?: (had - s.cardCount(who)), listed, "every card that left the hand is listed: $line")
                    if (listOf("Parchment", "Fabric", "Silver").any { it in line }) commodityDiscards++
                }
                tracker.current?.steals?.forEach { st ->
                    assertTrue(st.card in ALL_CARD_NAMES.indices, "a real card: $st")
                    if (st.card >= wgbg.engine.CARD_TYPES) commoditySteals++
                }
            }
            for (p in 0 until 3) assertEquals(s.cardCount(p), PlayerInfo.of(s, p).cards, "the panel counts commodities")
        }
        assertTrue(discards > 20 && commodityDiscards > 0, "discards $discards, with commodities $commodityDiscards, commodity steals $commoditySteals")
    }
}

package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import wgbg.engine.CARD_TYPES
import wgbg.engine.Commodity
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Piece
import wgbg.engine.Resource
import wgbg.engine.Rules

/**
 * #133: every card-tester scenario is a fair position, the card is (or isn't) offered as it says, and playing it does
 * what the panel promises.
 */
class CardTesterTest {
    private val all = CardTester.scenarios

    /** Cards, pieces and progress cards add up as in a fresh game: nothing made out of thin air. */
    private fun assertConserved(id: String, s: GameState) {
        val fresh = Rules.newGame(s.config, CardTester.SEED)
        for (r in 0 until Resource.COUNT) {
            val held = (0 until s.numPlayers).sumOf { s.hands[it * CARD_TYPES + r] }
            assertEquals(fresh.bank[r], s.bank[r] + held, "$id: resource $r")
        }
        assertEquals(fresh.devDeck.sum(), s.devDeck.sum() + s.devHand.sum() + s.devPlayed.sum(), "$id: Fortune cards")
        for (p in 0 until s.numPlayers) for (piece in 0 until Piece.COUNT)
            assertEquals(fresh.piecesLeft[p * Piece.COUNT + piece], s.piecesOnBoard(p, piece) + Rules.piecesLeft(s, p, piece), "$id: player $p piece $piece")
        val chivalry = s.chivalry ?: return
        val fck = fresh.chivalry!!
        for (l in 0 until Commodity.COUNT)
            assertEquals(fck.commodityBank[l], chivalry.commodityBank[l] + (0 until s.numPlayers).sumOf { chivalry.commodities[it * Commodity.COUNT + l] }, "$id: commodity $l")
        assertEquals(fck.deckSize.sum(), chivalry.deckSize.sum() + chivalry.progressHand.sum() + chivalry.progressVp.sum(), "$id: progress cards")
    }

    @Test
    fun `scenario ids are unique and every card is covered`() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val cards = all.map { it.card }.toSet()
        for (c in FORTUNE) assertTrue(c in cards, c)
        for (c in PROGRESS_NAMES) assertTrue(c in cards, c)
        assertTrue(all.size >= 80, "${all.size} scenarios")
    }

    @TestFactory
    fun `every scenario builds a fair position with you to act`() = all.map { sc ->
        DynamicTest.dynamicTest(sc.id) {
            val p = sc.build()
            val s = p.state
            assertEquals(Phase.PLAY_TURN, s.phase, sc.id)
            assertEquals(0, s.currentPlayer); assertEquals(0, s.actingPlayer)
            assertFalse(s.isOver)
            assertEquals(sc.chivalry, s.chivalry != null)
            assertConserved(sc.id, s)
            assertTrue(p.howTo.isNotBlank() && p.expect.isNotBlank(), sc.id)
            assertTrue(p.facts.isNotEmpty(), sc.id)
            assertEquals(s.stateHash(), sc.build().state.stateHash(), "${sc.id} is built the same every time")
        }
    }

    @TestFactory
    fun `the card is offered exactly when the scenario says`() = all.map { sc ->
        DynamicTest.dynamicTest(sc.id) {
            val s = sc.build().state
            val plays = CardTester.offeredPlays(sc, s)
            assertEquals(sc.playable, plays.isNotEmpty(), "${sc.id}: ${plays.size} plays offered")
            sc.rule?.let { assertTrue(it(s, Rules.legalActions(s)), "${sc.id}: rule") }
        }
    }

    @TestFactory
    fun `a refused progress card says why`() = all.filter { it.chivalry && !it.playable && it.card in PROGRESS_NAMES }.map { sc ->
        DynamicTest.dynamicTest(sc.id) {
            val s = sc.build().state
            val card = PROGRESS_NAMES.indexOf(sc.card)
            assertTrue(card !in playableProgress(s, 0), sc.id)
            assertTrue(notPlayableWhy(s, 0, card).isNotBlank(), sc.id)
        }
    }

    @TestFactory
    fun `playing the script gives what the panel expects`() = all.filter { it.playable }.map { sc ->
        DynamicTest.dynamicTest(sc.id) {
            val p = sc.build()
            assertTrue(p.script.isNotEmpty(), sc.id)
            assertTrue(sc.isPlay(p.script.first()(p.state)), "${sc.id}: the script starts with the card")
            val after = CardTester.runScript(p)
            for (row in p.rows(after)) assertEquals(row.expected, row.actual, "${sc.id}: ${row.label} (start ${row.before})")
            assertConserved("${sc.id} after", after)
        }
    }

    @Test
    fun `the expectations aren't already met before the card`() {
        for (sc in all.filter { it.playable }) {
            val p = sc.build()
            assertTrue(p.rows(p.state).any { !it.ok }, "${sc.id}: nothing for the card to change")
        }
    }

    @Test
    fun `checklist entries survive a round trip`() {
        assertEquals(CheckState.PASSED to "fine", CardTester.decodeEntry(CardTester.encodeEntry(true, "fine")))
        assertEquals(CheckState.FAILED to "two lines", CardTester.decodeEntry(CardTester.encodeEntry(false, "two\nlines")))
        assertEquals(CheckState.LEFT to "", CardTester.decodeEntry(null))
        assertEquals(CheckState.LEFT to "", CardTester.decodeEntry("junk"))
        assertEquals(302, CardTester.encodeEntry(true, "x".repeat(1000)).length)
        val states = mapOf(all[0].id to CheckState.PASSED, all[1].id to CheckState.FAILED)
        assertEquals("2 of ${all.size} done · 1 passed · 1 failed", CardTester.progressLine(states))
    }

    @Test
    fun `fail screenshots fit in two pictures`() {
        assertEquals(emptyList<List<Int>>(), CardTester.shotPlan(0))
        assertEquals(listOf(listOf(0)), CardTester.shotPlan(1))
        assertEquals(listOf(listOf(0), listOf(1)), CardTester.shotPlan(2))
        assertEquals(listOf(listOf(0, 1), listOf(2)), CardTester.shotPlan(3))
        for (n in 1..90) {
            val plan = CardTester.shotPlan(n)
            assertTrue(plan.size <= 2)
            assertEquals((0 until n).toList(), plan.flatten())
        }
        assertEquals(1 to 1, CardTester.grid(1))
        assertEquals(2 to 1, CardTester.grid(2))
        assertEquals(2 to 2, CardTester.grid(4))
        assertEquals(3 to 2, CardTester.grid(5))
        for (n in 1..50) CardTester.grid(n).let { (c, r) -> assertTrue(c * r >= n && c >= r) }
    }

    @Test
    fun `the report names every result, its pictures and what's left, within the upload cap`() {
        val rows = listOf(FactRow("Your cards", 2, 3, 2))
        val results = listOf(
            CardResult(all[0].id, true, "", emptyList(), 1L),
            CardResult(all[1].id, false, "bandit didn't move", rows, 2L),
            CardResult(all[2].id, false, "", rows, 3L),
            CardResult(all[3].id, false, "", rows, 4L),
        )
        val states = results.associate { it.id to if (it.pass) CheckState.PASSED else CheckState.FAILED }
        val text = CardTester.report(results, states, "0.2.10", 13, "Pixel", "1080x2400", 0L)
        assertTrue(text.startsWith("WGBG card test report"))
        assertTrue("PASS  ${all[0].id}" in text)
        assertTrue("FAIL  ${all[1].id}" in text && "[screenshot 1, tile 1]" in text)
        assertTrue("[screenshot 1, tile 2]" in text && "[screenshot 2]" in text)
        assertTrue("note: bandit didn't move" in text)
        assertTrue("Your cards: start 2, should be 3, was 2  <-- differs" in text)
        assertTrue("left to test (${all.size - 4}):" in text)
        assertTrue(all.last().id in text)
        val many = List(8) { all.map { CardResult(it.id, false, "n".repeat(300), rows, 0L) } }.flatten()
        val big = CardTester.report(many, emptyMap(), "v", 1, "d", "s", 0L, max = 5000)
        assertTrue(big.length <= 5000 && big.trimEnd().endsWith("[truncated]"))
    }

    @Test
    fun `the tester's game runs on the scenario's ruleset with three bots`() {
        for (sc in listOf(all.first { !it.chivalry }, all.first { it.chivalry })) {
            val s = sc.build().state
            val setup = CardTester.setupFor(s, "Mauro")
            assertEquals(s.config.expansion, setup.config().expansion)
            assertEquals(4, setup.players.size)
            assertEquals(s.config.vpToWin, setup.points)
        }
        assertEquals(5, CardTester.setupFor(all.first { it.id == "landmark.wins" }.build().state, "").points)
    }
}

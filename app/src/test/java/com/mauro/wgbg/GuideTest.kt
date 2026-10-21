package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Cost
import wgbg.engine.ChivalryCost

class GuideTest {
    @Test
    fun `the build map reads the engine's own costs, per ruleset`() {
        val base = buildCosts(chivalry = false)
        assertEquals(listOf("Road", "Homestead", "Town", "Fortune card"), base.map { it.name })
        assertArrayEquals(Cost.SETTLEMENT, base[1].cards)
        val chivalry = buildCosts(chivalry = true)
        assertTrue("Fortune card" !in chivalry.map { it.name }, "no Fortune cards in Age of Chivalry")
        assertArrayEquals(ChivalryCost.WALL, chivalry.first { it.name == "Ramparts" }.cards)
        for (b in base + chivalry) assertTrue(b.cards.sum() > 0 && b.cards.size <= ALL_CARD_ART.size, b.name)
    }

    @Test
    fun `every card has a source and every progress card a meaning`() {
        assertEquals(ALL_CARD_NAMES.size, CARD_SOURCES.size)
        assertEquals(PROGRESS_NAMES.size, PROGRESS_HINTS.size)
        assertTrue(PROGRESS_HINTS.all { it.isNotBlank() } && FORTUNE_HINTS.size == FORTUNE.size)
    }
}

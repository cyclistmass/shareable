package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.DevCard
import wgbg.engine.Progress

class GuideArtTest {
    @Test
    fun `knight card art picks the theme's own deck, levels 1 to 3`() {
        assertEquals(listOf(R.drawable.card_knight_normal_1, R.drawable.card_knight_normal_2, R.drawable.card_knight_normal_3),
            (1..3).map { GuideArt.knightCard(it, pirate = false) })
        assertEquals(listOf(R.drawable.card_knight_pirate_1, R.drawable.card_knight_pirate_2, R.drawable.card_knight_pirate_3),
            (1..3).map { GuideArt.knightCard(it, pirate = true) })
        // The two themes never share a drawable (so a theme's art never leaks into the other's cards).
        for (lv in 1..3) assertNotEquals(GuideArt.knightCard(lv, pirate = false), GuideArt.knightCard(lv, pirate = true))
    }

    @Test
    fun `every Fortune card is its own Ledger card, each a different one`() {
        val ledger = listOf(R.drawable.card_fortune_sentinel, R.drawable.card_fortune_windfall, R.drawable.card_fortune_corner,
            R.drawable.card_fortune_trailblazers, R.drawable.card_fortune_landmark)
        val normal = FORTUNE.indices.map { GuideArt.fortuneArt(it, pirate = false) }
        assertEquals(ledger.toSet(), normal.toSet())
        assertEquals(FORTUNE.size, normal.toSet().size, "two Fortune cards share a picture: $normal")
        // A Fortune card never borrows a resource card any more: it would show that resource's name.
        for (c in FORTUNE.indices) for (pirate in listOf(false, true))
            assertTrue(GuideArt.fortuneArt(c, pirate) !in ALL_CARD_ART, "Fortune card $c shows a resource card, pirate=$pirate")
        // Break-it: an index outside the deck still gets a real card, never 0.
        for (c in listOf(-1, FORTUNE.size, 99)) assertNotEquals(0, GuideArt.fortuneArt(c, false))
    }

    @Test
    fun `the pirate theme keeps its own knight card for Sentinel and Ledger cards for the rest`() {
        val normal = GuideArt.fortuneArt(DevCard.KNIGHT, pirate = false)
        val pirate = GuideArt.fortuneArt(DevCard.KNIGHT, pirate = true)
        assertNotEquals(normal, pirate)
        assertEquals(R.drawable.card_knight_pirate_1, pirate)
        assertEquals(R.drawable.card_fortune_sentinel, normal)
        for (c in FORTUNE.indices) if (c != DevCard.KNIGHT) assertEquals(GuideArt.fortuneArt(c, false), GuideArt.fortuneArt(c, true))
    }

    @Test
    fun `every progress card shows its deck's commodity painting, not the Ledger card`() {
        for (c in 0 until Progress.COUNT) {
            val art = progressArt(c)
            assertTrue(art in COMMODITY_PICTURE, "progress card $c's art ($art) isn't one of the commodity paintings")
            assertTrue(art !in ALL_CARD_ART, "progress card $c would show a Ledger card's own title through its face")
        }
        // Each deck its own commodity: Academy Parchment, Guilds Fabric, Crown Silver.
        assertEquals(3, (0 until Progress.COUNT).map { progressArt(it) }.toSet().size)
    }
}

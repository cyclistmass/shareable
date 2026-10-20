package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.CARD_TYPES

class CardDetailTest {
    @Test
    fun `a long-pressed card says where it comes from and what it pays for`() {
        val timber = cardDetail(0, chivalry = false)
        assertEquals("Timber", timber.title)
        assertTrue(timber.lines.any { it == "From: Woodland" }, "${timber.lines}")
        assertTrue(timber.lines.any { it.startsWith("Pays for:") && "Road: 1" in it && "Homestead: 1" in it }, "${timber.lines}")
        val parchment = cardDetail(CARD_TYPES, chivalry = true)
        assertEquals("Commodity", parchment.kind)
        assertTrue(parchment.lines.any { "Academy" in it }, "${parchment.lines}")
        for (c in ALL_CARD_NAMES.indices) assertTrue(cardDetail(c, true).lines.isNotEmpty())
    }

    @Test
    fun `the guide and the Inventory use our terrain names, never the original game's (#91)`() {
        val original = listOf("Forest", "Hills", "Pasture", "Fields", "Mountains")
        for (chivalry in listOf(false, true)) for (c in ALL_CARD_NAMES.indices) for (line in cardDetail(c, chivalry).lines)
            for (w in original) assertFalse(Regex("\\b$w\\b").containsMatchIn(line), "card $c: $line")
        assertEquals(5, TERRAIN_NAMES.toSet().size)
        assertTrue(TERRAIN_NAMES.none { it in original })
        val main = java.io.File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        for (w in original) assertFalse("\"$w\"" in main, "MainActivity shows \"$w\"")
    }

    @Test
    fun `every progress card has its deck and full meaning`() {
        for (c in PROGRESS_NAMES.indices) {
            val d = progressDetail(c)
            assertEquals(PROGRESS_NAMES[c], d.title)
            assertTrue(d.kind!!.endsWith("progress card") && d.lines.single() == progressHint(c))
        }
    }
}

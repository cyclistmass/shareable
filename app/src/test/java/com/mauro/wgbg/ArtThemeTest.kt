package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtThemeTest {
    private class MapStore : FloatStore {
        val map = HashMap<String, Float>()
        override fun get(key: String, default: Float) = map[key] ?: default
        override fun put(key: String, value: Float) { map[key] = value }
    }

    @Test fun `a theme groups every piece under it`() {
        val pirate = ArtChoice(ArtSet.PIRATE)
        assertEquals(ArtSet.PIRATE, pirate[ArtObject.BUILDINGS])
        assertEquals(ArtSet.PIRATE, pirate[ArtObject.KNIGHTS])
        assertEquals(ArtSet.PIRATE, pirate[ArtObject.DICE])
        assertEquals(ArtSet.DEFAULT, pirate[ArtObject.MERCHANT], "no Pirate merchant yet: the generic pawn stands in")
        assertTrue(ArtObject.entries.none { pirate.swapped(it) })
    }

    @Test fun `tapping a piece cycles just that one and wraps round`() {
        val once = ArtChoice().cycle(ArtObject.KNIGHTS)
        assertEquals(ArtSet.PIRATE, once[ArtObject.KNIGHTS])
        assertEquals(ArtSet.DEFAULT, once[ArtObject.BUILDINGS])
        assertTrue(once.swapped(ArtObject.KNIGHTS))
        val twice = once.cycle(ArtObject.KNIGHTS)
        assertEquals(ArtSet.SPACE, twice[ArtObject.KNIGHTS])
        assertEquals(ArtChoice(), twice.cycle(ArtObject.KNIGHTS), "wraps back to the theme without a redundant swap")
        assertEquals(ArtSet.SPACE, ArtChoice().cycle(ArtObject.MERCHANT)[ArtObject.MERCHANT])
    }

    @Test fun `reset undoes the swaps and keeps the theme`() {
        val messy = ArtChoice(ArtSet.PIRATE).cycle(ArtObject.DICE).cycle(ArtObject.BUILDINGS)
        assertEquals(ArtSet.SPACE, messy[ArtObject.DICE])
        assertEquals(ArtChoice(ArtSet.PIRATE), messy.reset())
        assertEquals(ArtChoice(ArtSet.DEFAULT), messy.withTheme(ArtSet.DEFAULT), "picking a theme regroups everything")
    }

    @Test fun `no set without art can be picked`() {
        assertTrue(ArtSet.SPACE in ArtChoice.themes)
        assertEquals(ArtSet.entries, ArtChoice.themes)
        assertEquals(ArtChoice(), ArtChoice().with(ArtObject.MERCHANT, ArtSet.PIRATE))
        for (obj in ArtObject.entries) assertEquals(ArtSet.SPACE, ArtChoice(ArtSet.SPACE)[obj])
        for (obj in listOf(ArtObject.BOARD, ArtObject.CARDS, ArtObject.BANDIT))
            assertEquals(ArtSet.DEFAULT, ArtChoice(ArtSet.PIRATE)[obj], "Pirate keeps its existing shared art")
    }

    @Test fun `the choice is saved, old Pirate saves carry over, and reset clears it`() {
        val store = MapStore()
        store.put(EnvSettings.THEME, 1f) // a save from the Normal/Pirate toggle
        val env = EnvSettings(store)
        assertEquals(ArtChoice(ArtSet.PIRATE), env.art)
        env.art = env.art.cycle(ArtObject.KNIGHTS)
        val again = EnvSettings(store)
        assertEquals(ArtSet.SPACE, again.art[ArtObject.KNIGHTS])
        assertEquals(ArtSet.PIRATE, again.art[ArtObject.BUILDINGS])
        assertTrue(again.pirateTheme)
        again.reset()
        assertEquals(ArtChoice(), EnvSettings(store).art)
        store.put(EnvSettings.artKey(ArtObject.DICE), 7f) // junk
        assertEquals(ArtChoice(), EnvSettings(store).art, "an unknown set reads as following the theme")
    }

    @Test fun `Space and all independent swaps survive recreation and regroup on reset`() {
        val store = MapStore()
        val space = ArtChoice(ArtSet.SPACE)
        for (obj in ArtObject.entries) {
            val changed = space.with(obj, ArtSet.DEFAULT)
            EnvSettings(store).art = changed
            assertEquals(changed, EnvSettings(store).art)
            assertEquals(space, changed.reset())
            assertEquals(space, changed.withTheme(ArtSet.SPACE))
            for (other in ArtObject.entries.filter { it != obj }) assertEquals(ArtSet.SPACE, changed[other])
        }
        store.put(EnvSettings.THEME, 999f)
        assertEquals(ArtSet.DEFAULT, EnvSettings(store).art.theme)
    }

    @Test fun `inventory taps land on the piece drawn there`() {
        val w = 2400f; val h = 1080f; val inset = 260f
        val scale = minOf(w / 13.5f, (h - inset) / 9.7f)
        val left = w / 2f - 5 * 2.05f * scale / 2f
        fun at(u: Float, v: Float) = inventoryObjectAt(Offset(left + u * scale, v * scale), w, h, inset)
        assertEquals(ArtObject.MERCHANT, at(10.6f, 6.8f))
        assertEquals(ArtObject.BUILDINGS, at(0.5f, 6.6f), "the first player's homestead")
        assertEquals(ArtObject.BUILDINGS, at(9.65f, 6.6f), "the last player's town")
        assertEquals(ArtObject.KNIGHTS, at(1.3f, 8.75f))
        assertNull(at(5.3f, 8.75f), "the Raiders' ship has one look")
        assertNull(at(2f, 1.25f), "terrain")
        assertNull(inventoryObjectAt(Offset(10f, 10f), 1f, 1f, 0f), "not laid out yet")
    }
}

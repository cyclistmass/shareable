package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Resource

class TerrainPaletteTest {
    private class MapStore : FloatStore {
        val map = HashMap<String, Float>()
        override fun get(key: String, default: Float) = map[key] ?: default
        override fun put(key: String, value: Float) { map[key] = value }
    }

    private val terrains = (0 until Resource.COUNT) + Resource.NONE
    private val sample = intArrayOf(0xFF2E6B2A.toInt(), 0xFFB5532F.toInt(), 0xFF9CC25B.toInt(), 0xFFE2C25A.toInt(), 0xFF8A8C90.toInt(), 0xFFD8C08A.toInt())

    private fun graded(p: TerrainPalette, t: Int, px: IntArray = sample) = px.copyOf().also { TerrainPalette.gradePixels(it, p.grades(t)) }

    @Test
    fun `Today leaves every terrain untouched and the others grade every terrain`() {
        for (t in terrains) assertArrayEquals(sample, graded(TerrainPalette.TODAY, t))
        for (p in TerrainPalette.entries - TerrainPalette.TODAY) for (t in terrains)
            assertTrue(!graded(p, t).contentEquals(sample), "$p leaves terrain $t as it was")
    }

    @Test
    fun `the CSS matrices match the browser on known colours`() {
        // saturate(0) is grey with Rec. 709 weights; hue-rotate(0), sepia(0) and brightness(1) change nothing.
        val px = intArrayOf(0xFFFF0000.toInt()); TerrainPalette.gradePixels(px, listOf(TerrainPalette.Grade.Saturate(0f)))
        assertEquals(0xFF363636.toInt(), px[0])
        for (g in listOf(TerrainPalette.Grade.HueRotate(0f), TerrainPalette.Grade.Sepia(0f), TerrainPalette.Grade.Brightness(1f)))
            assertArrayEquals(sample, sample.copyOf().also { TerrainPalette.gradePixels(it, listOf(g)) }, "$g")
        // sepia(1) on white is Chrome's (255, 255, 239).
        val white = intArrayOf(-1); TerrainPalette.gradePixels(white, listOf(TerrainPalette.Grade.Sepia(1f)))
        assertEquals(0xFFFFFFEF.toInt(), white[0])
    }

    @Test
    fun `grading keeps alpha and clamps, never wrapping a channel`() {
        val px = intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt(), 0x40000000)
        TerrainPalette.gradePixels(px, listOf(TerrainPalette.Grade.Brightness(3f), TerrainPalette.Grade.Saturate(4f)))
        assertEquals(listOf(0x00, 0x80, 0x40), px.map { it ushr 24 })
        assertEquals(0x00FFFFFF, px[0], "bright white stays white, not wrapped to black")
        assertEquals(0x40000000, px[2], "black stays black")
    }

    @Test
    fun `each palette keeps the terrains apart`() {
        // A palette must never grade two terrains to the same mean colour (they would read alike on the board).
        for (p in TerrainPalette.entries) {
            val means = terrains.map { t -> graded(p, t, IntArray(16) { sample[terrains.indexOf(t)] })[0] }
            assertEquals(terrains.size, means.toSet().size, "$p: $means")
        }
    }

    @Test
    fun `the choice is saved, survives a restart and resets to Today`() {
        val store = MapStore()
        EnvSettings(store).terrainPalette = TerrainPalette.MOORLAND
        val again = EnvSettings(store)
        assertEquals(TerrainPalette.MOORLAND, again.terrainPalette)
        again.reset()
        assertEquals(TerrainPalette.TODAY, EnvSettings(store).terrainPalette)
    }

    @Test
    fun `a damaged saved palette falls back to Today`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 99f)) {
            val store = MapStore().apply { map[EnvSettings.PALETTE] = bad }
            assertEquals(TerrainPalette.TODAY, EnvSettings(store).terrainPalette, "saved $bad")
        }
        assertNotEquals(0, TerrainPalette.entries.size)
    }

    @Test
    fun `the 3D board's vertex colours take the same grade as the painted tiles`() {
        // A colour graded as linear vertex colour lands on the same sRGB value as the painted pixel, within rounding.
        fun lin(c: Int) = (c / 255f).let { if (it <= 0.04045f) it / 12.92f else Math.pow((it + 0.055) / 1.055, 2.4).toFloat() }
        fun srgb(c: Float) = if (c <= 0.0031308f) 12.92f * c * 255 else (1.055f * Math.pow(c.toDouble(), 1 / 2.4).toFloat() - 0.055f) * 255
        for (p in TerrainPalette.entries) for ((i, t) in terrains.withIndex()) {
            val px = sample[i]
            val rgba = floatArrayOf(lin(px shr 16 and 0xFF), lin(px shr 8 and 0xFF), lin(px and 0xFF), 0.5f)
            TerrainPalette.gradeLinearRgb(rgba, 4, p.grades(t))
            val want = graded(p, t, intArrayOf(px))[0]
            for ((k, shift) in listOf(16, 8, 0).withIndex())
                assertEquals((want shr shift and 0xFF).toFloat(), srgb(rgba[k]), 1.01f, "$p terrain $t channel $k")
            assertEquals(0.5f, rgba[3], "alpha untouched")
        }
    }

    @Test
    fun `the 3D tiles are named for their palette, and only the board tiles`() {
        val o = androidx.compose.ui.geometry.Offset(1f, 2f)
        val plain = listOf(LivePlacement("tile_grain_lod0", o), LivePlacement("tile_desert_lod1", o),
            LivePlacement("number_plate", o, .012f), LivePlacement("space_tile_clay", o))
        assertTrue(TerrainPalette.TODAY.live(plain) === plain, "Today copies nothing")
        val autumn = TerrainPalette.AUTUMN.live(plain)
        assertEquals(listOf("tile_grain_lod0@autumn", "tile_desert_lod1@autumn", "number_plate", "space_tile_clay"), autumn.map { it.model })
        assertEquals(plain.map { it.copy(model = "") }, autumn.map { it.copy(model = "") }, "same places, sizes and owners")
        // The graded name loads the plain GLB, graded for its own terrain.
        assertEquals("tile_grain_lod0", liveModelFile("tile_grain_lod0@autumn"))
        assertEquals(TerrainPalette.AUTUMN.grades(Resource.WHEAT), TerrainPalette.liveGrades("tile_grain_lod0@autumn"))
        assertEquals(TerrainPalette.OLD_MAP.grades(Resource.NONE), TerrainPalette.liveGrades("tile_desert_lod1@old_map"))
        for (m in listOf("tile_grain_lod0", "number_plate@autumn", "tile_grain_lod0@nonsense", "space_tile_clay"))
            assertEquals(null, TerrainPalette.liveGrades(m), m)
        // Every tile the board places has a 3D tile name the palette knows.
        val state = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(numPlayers = 4), 7)
        val tiles = livePlacements(state, pirate = false, lod = 0).filter { it.model.startsWith("tile_") }
        assertEquals(state.board.hexCount, tiles.size)
        for (t in tiles) assertTrue(TerrainPalette.liveTerrain(t.model) != null, t.model)
    }
}

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

    /**
     * Each terrain's average colour over its painted tile (`tile_<terrain>.webp`, opaque pixels), in the order of
     * [terrains]. The JVM cannot read WebP, so these are measured once; re-measure them when the tile art changes.
     */
    private val artMeans = intArrayOf(0xFF5D6424.toInt(), 0xFFAC5A2C.toInt(), 0xFF818C33.toInt(), 0xFFBA8A23.toInt(), 0xFF757567.toInt(), 0xFFDBAC57.toInt())

    @Test
    fun `each palette keeps every pair of terrains at least 10 apart (CIEDE2000), and the plates off the desert`() {
        // Bosun, 0.3.4: two resources sharing a colour is a defect (Autumn's Fleece and Grain were 3.8 apart).
        val desert = terrains.indexOf(Resource.NONE)
        for (p in TerrainPalette.entries) {
            val lab = terrains.mapIndexed { i, t -> labOf(graded(p, t, intArrayOf(artMeans[i]))[0]) }
            for (a in terrains.indices) for (b in a + 1 until terrains.size)
                assertTrue(deltaE2000(lab[a], lab[b]) >= 10.0, "$p: terrains ${terrains[a]} and ${terrains[b]} are ${deltaE2000(lab[a], lab[b])} apart")
            // The number plate, wood (E9CFA0) or brass (F2D676), stands off the desert it may sit on.
            for (plate in listOf(0xFFE9CFA0.toInt(), 0xFFF2D676.toInt()))
                assertTrue(deltaE2000(labOf(plate), lab[desert]) >= 5.0, "$p: plate ${Integer.toHexString(plate)} on the desert")
        }
        assertTrue(deltaE2000(doubleArrayOf(50.0, 2.6772, -79.7751), doubleArrayOf(50.0, 0.0, -82.7485)) in 2.0424..2.0426, "Sharma 2005 pair 1")
    }

    private fun labOf(argb: Int): DoubleArray {
        fun lin(c: Int) = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val r = lin(argb shr 16 and 0xFF); val g = lin(argb shr 8 and 0xFF); val b = lin(argb and 0xFF)
        val xyz = doubleArrayOf((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, 0.2126 * r + 0.7152 * g + 0.0722 * b, (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
        val f = xyz.map { if (it > 216.0 / 24389) Math.cbrt(it) else (24389.0 / 27 * it + 16) / 116 }
        return doubleArrayOf(116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2]))
    }

    /** CIEDE2000 colour difference (Sharma, Wu and Dalal 2005). */
    private fun deltaE2000(x: DoubleArray, y: DoubleArray): Double {
        fun rad(d: Double) = Math.toRadians(d)
        val (l1, a1, b1) = x.toList(); val (l2, a2, b2) = y.toList()
        val cb = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2
        val g = 0.5 * (1 - Math.sqrt(Math.pow(cb, 7.0) / (Math.pow(cb, 7.0) + Math.pow(25.0, 7.0))))
        val a1p = (1 + g) * a1; val a2p = (1 + g) * a2
        val c1 = Math.hypot(a1p, b1); val c2 = Math.hypot(a2p, b2)
        fun hue(b: Double, a: Double) = if (b == 0.0 && a == 0.0) 0.0 else (Math.toDegrees(Math.atan2(b, a)) + 360) % 360
        val h1 = hue(b1, a1p); val h2 = hue(b2, a2p)
        val dh = when { c1 * c2 == 0.0 -> 0.0; Math.abs(h2 - h1) <= 180 -> h2 - h1; h2 > h1 -> h2 - h1 - 360; else -> h2 - h1 + 360 }
        val dL = l2 - l1; val dC = c2 - c1; val dH = 2 * Math.sqrt(c1 * c2) * Math.sin(rad(dh / 2))
        val lb = (l1 + l2) / 2; val cbp = (c1 + c2) / 2
        val hb = when { c1 * c2 == 0.0 -> h1 + h2; Math.abs(h1 - h2) <= 180 -> (h1 + h2) / 2; h1 + h2 < 360 -> (h1 + h2 + 360) / 2; else -> (h1 + h2 - 360) / 2 }
        val t = 1 - 0.17 * Math.cos(rad(hb - 30)) + 0.24 * Math.cos(rad(2 * hb)) + 0.32 * Math.cos(rad(3 * hb + 6)) - 0.2 * Math.cos(rad(4 * hb - 63))
        val sl = 1 + 0.015 * (lb - 50) * (lb - 50) / Math.sqrt(20 + (lb - 50) * (lb - 50)); val sc = 1 + 0.045 * cbp; val sh = 1 + 0.015 * cbp * t
        val rt = -2 * Math.sqrt(Math.pow(cbp, 7.0) / (Math.pow(cbp, 7.0) + Math.pow(25.0, 7.0))) * Math.sin(rad(60 * Math.exp(-Math.pow((hb - 275) / 25, 2.0))))
        return Math.sqrt(Math.pow(dL / sl, 2.0) + Math.pow(dC / sc, 2.0) + Math.pow(dH / sh, 2.0) + rt * (dC / sc) * (dH / sh))
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

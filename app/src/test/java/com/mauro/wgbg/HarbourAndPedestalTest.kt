package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rules

/** Harbours sit square on their coast edge (Mauro: "not in the middle ... looks offset"); knights stand on a coloured pedestal (#101). */
class HarbourAndPedestalTest {
    @Test
    fun `every harbour points straight out from the middle of its own coast edge`() {
        for (seed in 1L..30L) for (config in listOf(GameConfig(), GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS))) {
            val b = Rules.newGame(config, seed).board
            for (port in b.ports) {
                val a = liveVertex(b, port.v1); val c = liveVertex(b, port.v2)
                val n = coastOutward(b, port.v1, port.v2)
                assertEquals(1f, n.getDistance(), 1e-5f)
                val edge = c - a
                assertEquals(0f, (n.x * edge.x + n.y * edge.y) / edge.getDistance(), 1e-4f, "square to its edge")
                val mid = (a + c) / 2f
                assertTrue((mid + n * 0.8f).getDistance() > mid.getDistance(), "out to sea, away from the island")
            }
        }
    }

    @Test
    fun `#136 3D harbours - piers start on the coast corners, the buoy and shallows sit on the sea`() {
        val b = Rules.newGame(GameConfig(), 7L).board
        for (tilt in listOf(0f, Camera.START_TILT, Camera.MAX_TILT)) for (rotation in listOf(0f, 1.2f, 3.1415927f)) {
            val p = LiveProjection(Offset(500f, 400f), 80f, tilt, rotation)
            for (port in b.ports) {
                val h = liveHarbour(b, port.v1, port.v2, p, PORT_BUOY_OUT)
                val a = liveVertex(b, port.v1); val c = liveVertex(b, port.v2)
                // The corners are where the tiles' top faces meet the coast, so the piers touch the land.
                assertEquals(p.project(a), h.a); assertEquals(p.project(c), h.b)
                val n = coastOutward(b, port.v1, port.v2)
                assertEquals(p.project((a + c) / 2f + n * PORT_BUOY_OUT, LIVE_SEA), h.token)
                assertEquals(p.project((a + c) / 2f, LIVE_SEA), h.mid)
                // Tilted, the sea shows below the tile tops: the buoy sits that much lower on screen than at tile height.
                val drop = -LIVE_SEA * kotlin.math.sin(tilt) * p.scale
                assertEquals(p.project((a + c) / 2f + n * PORT_BUOY_OUT).y + drop, h.token.y, 1e-3f)
            }
        }
        // The 3D water (#63) is built at the harbours' sea height and placed unraised.
        assertEquals(LIVE_SEA, LIVE_WATER_HEIGHT, "the harbours and the water agree on the water's height")
        assertEquals(0f, livePlacements(Rules.newGame(GameConfig(), 7L), false, 0).single { it.model == "live_water_mesh" }.height)
    }

    @Test
    fun `knights stand on a player-colour ring with a dark centre, at least 15 percent ring`() {
        val ring = 0.27f * KNIGHT_RING_SCALE; val band = 0.27f * KNIGHT_BAND_SCALE
        assertTrue((ring - band) / ring >= 0.15f, "ring share ${(ring - band) / ring}")
        assertTrue(KNIGHT_BAND_HEIGHT + 0.024f * KNIGHT_BAND_SCALE > 0.024f * KNIGHT_RING_SCALE, "dark disc on top of the ring")
        assertTrue(KNIGHT_ON_BASE >= KNIGHT_BAND_HEIGHT + 0.024f * KNIGHT_BAND_SCALE, "knight stands on the disc, not in it")
        assertEquals(PLAYER_COLORS[1], liveTint(KNIGHT_RING, "NumberPlate_PBR", 1))
        assertEquals(KNIGHT_BAND_COLOR, liveTint(KNIGHT_BAND, "NumberPlate_PBR", -1))
        assertEquals(PLAYER_COLORS[2], liveTint("default_town", "WoodBody", 2))
        assertNull(liveTint("default_town", "BrassTrim", 2))
        assertNull(liveTint("number_plate", "NumberPlate_PBR", -1))
        assertEquals("number_plate", liveModelFile(KNIGHT_RING)); assertEquals("default_town", liveModelFile("default_town"))
    }

    @Test
    fun `each knight on the 3D board gets its ring and dark disc underneath`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS), 3L)
        val v = (0 until s.board.vertexCount).first()
        s.ck!!.knightOwner[v] = 2; s.ck!!.knightLevel[v] = 1
        val placed = livePlacements(s, false, 0) { true }.filter { it.position == liveVertex(s.board, v) }
        val ring = placed.single { it.model == KNIGHT_RING }; val band = placed.single { it.model == KNIGHT_BAND }
        val knight = placed.single { "_knight_" in it.model }
        assertEquals(2, ring.owner); assertEquals(2, knight.owner)
        assertTrue(band.height > ring.height && knight.height > band.height)
        assertEquals(Offset(0f, 0f) + ring.position, knight.position)
    }
}

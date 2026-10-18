package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import wgbg.engine.*
import kotlin.math.*

class LiveBoardTest {
    private class Store : FloatStore {
        val values = mutableMapOf<String, Float>()
        override fun get(key: String, default: Float) = values[key] ?: default
        override fun put(key: String, value: Float) { values[key] = value }
    }

    @Test fun `live board is off by default and independently persisted and reset`() {
        val store = Store()
        val env = EnvSettings(store)
        assertFalse(env.liveBoard)
        env.liveBoard = true; env.liveLod = 1; env.pirateTheme = true
        val again = EnvSettings(store)
        assertTrue(again.liveBoard); assertEquals(1, again.liveLod); assertTrue(again.pirateTheme)
        assertFalse(again.tiles3d)
        again.reset()
        assertFalse(EnvSettings(store).liveBoard); assertEquals(0, EnvSettings(store).liveLod)
        again.liveLod = -3; assertEquals(0, again.liveLod)
        store.values[EnvSettings.LIVE_LOD] = 80f; assertEquals(1, EnvSettings(store).liveLod)
    }

    @Test fun `ground projection round trips every board vertex and hex through zoom pan tilt and orbit`() {
        val b = Rules.newGame(GameConfig(), 17).board
        val positions = (0 until b.vertexCount).map { liveVertex(b, it) } + (0 until b.hexCount).map { liveHex(b, it) }
        for (tilt in listOf(0f, Camera.START_TILT, .48f, Camera.MAX_TILT))
            for (rotation in (-8..8).map { it * PI.toFloat() / 4f })
                for (scale in listOf(64f, 180f, 720f)) {
                    val p = LiveProjection(Offset(1532f, 436f), scale, tilt, rotation)
                    for (at in positions) {
                        val roundTrip = p.unproject(p.project(at))
                        assertEquals(at.x, roundTrip.x, 0.00001f)
                        assertEquals(at.y, roundTrip.y, 0.00001f)
                    }
                }
    }

    @Test fun `projection follows Android screen axes and glTF height at cardinal rotations`() {
        val flat = LiveProjection(Offset.Zero, 100f, 0f, 0f)
        assertEquals(Offset(100f, 200f), flat.project(Offset(1f, 2f)))
        val turn = LiveProjection(Offset.Zero, 100f, 0f, PI.toFloat() / 2)
        assertEquals(-200f, turn.project(Offset(1f, 2f)).x, .001f)
        assertEquals(100f, turn.project(Offset(1f, 2f)).y, .001f)
        val tilted = LiveProjection(Offset.Zero, 100f, .48f, 0f)
        assertTrue(tilted.project(Offset.Zero, 1f).y < 0f)
    }

    @Test fun `runtime glyph transform cancels camera orbit rather than doubling it`() {
        for (i in 0..32) {
            val yaw = i * PI.toFloat() / 16
            val projection = LiveProjection(Offset.Zero, 100f, .48f, yaw)
            val p = LivePlacement("digit_6", Offset(2f, -1f), heading = yaw)
            val m = liveModelMatrix(p)
            val origin = projection.project(p.position)
            val localX = projection.project(p.position + Offset(m[0], m[2])) - origin
            val localZ = projection.project(p.position + Offset(m[8], m[10])) - origin
            assertEquals(100f, localX.x, .001f); assertEquals(0f, localX.y, .001f)
            assertEquals(0f, localZ.x, .001f); assertTrue(localZ.y > 0f)
        }
    }

    @Test fun `collection includes every imported model in both themes and LODs`() {
        val models = liveInventoryPlacements().map { it.model }.filter { !it.startsWith("digit_") }.toSet()
        assertEquals(33, models.size)
        assertEquals(12, models.count { it.startsWith("tile_") })
        assertEquals(9, models.count { it.startsWith("default_") })
        assertEquals(9, models.count { it.startsWith("pirate_") })
    }

    @Test fun `garbage projection and LOD inputs are rejected without mutating the game`() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { LiveProjection(Offset.Zero, scale, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { LiveProjection(Offset.Zero, 1f, 1.4f, 0f) }
        val s = Rules.newGame(GameConfig(), 3)
        val hash = s.stateHash()
        assertThrows(IllegalArgumentException::class.java) { livePlacements(s, false, 2) }
        assertEquals(hash, s.stateHash())
    }

    @Test fun `only the wood body may receive player colour`() {
        assertTrue(woodTint("WoodBody"))
        for (name in listOf("BrassTrim", "FixedDetails", "RuntimeDigits", "WoodBodyTrim", "woodbody", "")) assertFalse(woodTint(name))
    }

    @Test fun `both themes and LODs use real terrain numbers roads and expansion pieces without state changes`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS), 5)
        // A rendering fixture isolates all piece forms from rule progression.
        s.edgeOwner[0] = 2
        for (v in 0..2) { s.vertexOwner[v] = v; s.vertexBuilding[v] = if (v == 0) Piece.SETTLEMENT else Piece.CITY }
        s.ck!!.wall[1] = 1; s.ck!!.metroVertex[0] = 2; s.ck!!.metroOwner[0] = 2
        val hash = s.stateHash()
        for (pirate in listOf(false, true)) for (lod in 0..1) {
            val list = livePlacements(s, pirate, lod)
            val theme = if (pirate) "pirate" else "default"
            assertEquals(19, list.count { it.model.startsWith("tile_") && it.model.endsWith("lod$lod") })
            assertEquals(18, list.count { it.model == "number_plate" })
            assertEquals(2, list.count { it.model == "${theme}_road_v" && it.owner == 2 })
            assertTrue(list.filter { it.model.endsWith("_road_v") }.all { it.height == LIVE_ROAD_LIFT }, "roads stand proud of the tile edges")
            assertEquals(1, list.count { it.model == "${theme}_homestead" && it.owner == 0 })
            assertEquals(1, list.count { it.model == "${theme}_ramparts" && it.owner == 1 })
            assertEquals(1, list.count { it.model == "${theme}_capital_guilds" && it.owner == 2 })
            assertEquals(hash, s.stateHash())
            for (h in 0 until s.board.hexCount) if (s.board.hexNumber[h] > 0 && h != s.robberHex) {
                assertTrue(list.any { it.model == "digit_${s.board.hexNumber[h]}" && it.position == liveHex(s.board, h) + LIVE_PLATE })
            }
        }
    }

    @Test fun `the merchant stands as a tinted model on its tile when the model is there`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS), 5)
        assertTrue(livePlacements(s, false, 0) { true }.none { it.model == LIVE_MERCHANT }, "nobody holds it yet")
        s.ck!!.merchantHex = 7; s.ck!!.merchantOwner = 2
        assertTrue(livePlacements(s, false, 0).none { it.model == LIVE_MERCHANT }, "no model file: the overlay draws it")
        val m = livePlacements(s, true, 0) { true }.single { it.model == LIVE_MERCHANT }
        assertEquals(2, m.owner)
        assertEquals(liveHex(s.board, 7) + MERCHANT_SPOT, m.position)
        assertTrue(liveStanding(m.model), "it tips toward the camera like the Bandit")
    }

    @Test fun `knights become tinted standing models by theme, level and active, and stay sprites without a model`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS), 5)
        val ck = s.ck!!
        ck.knightOwner[4] = 1; ck.knightLevel[4] = 2; ck.knightFlags[4] = ck.knightFlags[4] or CkState.ACTIVE
        ck.knightOwner[9] = 3; ck.knightLevel[9] = 3
        assertEquals("default_knight_l2", liveKnightModel(s, 4, false))
        assertEquals("pirate_knight_l3_inactive", liveKnightModel(s, 9, true))
        assertNull(liveKnightModel(s, 0, false))
        assertNull(liveKnightModel(Rules.newGame(GameConfig(), 5), 4, false), "no knights in the base game")
        assertTrue(livePlacements(s, false, 0).none { "_knight_" in it.model }, "no model files: the overlay keeps drawing sprites")
        val all = livePlacements(s, true, 0) { true }
        val knights = all.filter { "_knight_" in it.model }
        assertEquals(listOf("pirate_knight_l2" to 1, "pirate_knight_l3_inactive" to 3), knights.map { it.model to it.owner })
        assertEquals(liveVertex(s.board, 4), knights[0].position)
        assertTrue(knights.all { liveStanding(it.model) }, "knights tip toward the camera like the buildings")
        // Knights swapped on their own (the Inventory): Pirate buildings, Default knights.
        val mixed = livePlacements(s, true, 0, knightsPirate = false) { true }
        assertEquals(listOf("default_knight_l2", "default_knight_l3_inactive"), mixed.filter { "_knight_" in it.model }.map { it.model })
        assertTrue(mixed.none { it.model.startsWith("default_") && "_knight_" !in it.model }, "the buildings stay Pirate")
    }

    @Test fun `legal placement taps pick the same move after a full orbit at every tilt`() {
        val s = Rules.newGame(GameConfig(), 91)
        val actions = Rules.legalActions(s)
        for (tilt in listOf(0f, .48f, .96f)) for (r in 0..15) {
            val p = LiveProjection(Offset(1400f, 500f), 130f, tilt, r * PI.toFloat() / 8)
            for (a in actions) spotOf(s.board, a)?.let { at ->
                assertTrue(a in movesAt(s.board, actions, p.unproject(p.project(at))))
            }
        }
    }

    @Test
    fun `a tap on a piece set row in the 3D collection picks that set (#119)`() {
        val rows = liveInventoryPlacements().filter { it.model.startsWith("default_") || it.model.startsWith("pirate_") }
        assertTrue(rows.isNotEmpty())
        for (r in rows) assertEquals(r.model.startsWith("pirate_"), liveInventoryThemeAt(r.position), r.model)
        assertEquals(null, liveInventoryThemeAt(Offset(0f, -4f)))    // the terrain rows
        assertEquals(null, liveInventoryThemeAt(Offset(0f, 5.4f)))   // the bandit row
    }
}

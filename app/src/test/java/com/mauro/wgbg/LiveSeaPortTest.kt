package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rules
import java.io.File
import kotlin.random.Random

/**
 * Codex's 3D water, boats, gulls and Raiders' ship (#63) on today's 3D board (0.3.4, Mauro: "bring the critters and
 * clouds into 3D and use Codex's water"). LiveSeaTest has Codex's own checks of the water and its poses.
 */
class LiveSeaPortTest {
    private val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()

    @Test
    fun `the painted 3D board floats on the animated water with surf on every coast edge, and no flat sea under it`() {
        for (ck in listOf(false, true)) for (pirate in listOf(false, true)) {
            val s = Rules.newGame(GameConfig(expansion = if (ck) Expansion.CITIES_AND_KNIGHTS else Expansion.BASE), 5L)
            val models = themedLivePlacements(s, ArtChoice(if (pirate) ArtSet.PIRATE else ArtSet.DEFAULT), 0) { true }.map { it.model }
            assertEquals(1, models.count { it == "live_water_mesh" })
            assertEquals(30, models.count { it == "coast_a" })
            assertTrue("sea" !in models, "the old flat sea")
            assertTrue(models.none { it.startsWith("harbour_") }, "the harbours stay the projected ones (#104)")
        }
    }

    @Test
    fun `a Space board keeps its starfield - no water, surf, boats or gulls from the painted sea`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS), 5L)
        val models = themedLivePlacements(s, ArtChoice(ArtSet.SPACE), 0) { true }.map { it.model }
        assertTrue(models.none { it == "live_water_mesh" || it == "coast_a" || it == "sea" }, models.filter { "sea" in it || "coast" in it }.toString())
        val call = main.substringAfter("LiveBoardView(placements").substringBefore("{ reason ->")
        assertTrue("sea = sea.takeIf { !inventory && !env.art.space(ArtObject.BOARD) }" in call, "the renderer gets no sea on a Space board")
    }

    @Test
    fun `a sloop's sail takes its owner's colour and a sail without one stays as modelled`() {
        for (owner in PLAYER_COLORS.indices) assertEquals(PLAYER_COLORS[owner], liveTint("live_sloop", "SailCloth", owner))
        assertNull(liveTint("live_sloop", "SailCloth", -1))
        assertNull(liveTint("live_sloop", "Hull", 1))
    }

    @Test
    fun `fuzz - the sea's 3D poses stay valid and on the water through long runs at every slider setting`() {
        val rnd = Random(63)
        var checked = 0
        for (seed in 1..12) {
            val board = Rules.newGame(GameConfig(), seed.toLong()).board
            val sea = Sea(board, seed)
            val coast = liveCoast(board)
            repeat(400) { step ->
                if (step % 50 == 0) {
                    sea.boats01 = rnd.nextFloat(); sea.birds01 = rnd.nextFloat(); sea.waves01 = rnd.nextFloat()
                    sea.density01 = if (rnd.nextInt(5) == 0) 0f else rnd.nextFloat(); sea.motion = rnd.nextFloat()
                }
                sea.update(rnd.nextFloat() * .1f)
                // Building the poses checks each one (LivePlacement's init); a bad one throws here.
                val poses = liveSeaPlacements(sea, raiders = seed % 2 == 0)
                assertEquals(sea.boatCount, poses.count { it.model == "live_sloop" })
                assertEquals(sea.birdCount, poses.count { it.model.startsWith("live_gull_") })
                for (p in poses) {
                    assertTrue(p.alpha in 0f..1f && p.scale > 0f)
                    if (p.model == "live_sloop") {
                        assertEquals(liveWaterHeight(coast, p.position, sea.time, sea.whitecapScale), p.height, 1e-5f)
                        assertTrue(liveCoastDistance(coast, p.position) > 0f, "a sloop on land at ${p.position}")
                    }
                    if (p.model.startsWith("live_gull_")) assertTrue(p.model.removePrefix("live_gull_").toInt() in 0..15)
                }
                if (seed % 2 == 0) assertEquals(1, poses.count { it.model == "live_raiders" })
                checked += poses.size
            }
        }
        assertTrue(checked > 10_000, "only $checked poses")
    }

    @Test
    fun `break it - a pose that is not a number, unscaled or see-through past 1 is refused`() {
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { LivePlacement("live_gull_0", Offset.Zero, height = bad) }
            assertThrows(IllegalArgumentException::class.java) { LivePlacement("live_sloop", Offset.Zero, heading = bad) }
            assertThrows(IllegalArgumentException::class.java) { LivePlacement("default_road_v", Offset.Zero, stretch = bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { LivePlacement("default_road_v", Offset.Zero, stretch = 0f) }
        assertThrows(IllegalArgumentException::class.java) { LivePlacement("sea_wake", Offset.Zero, alpha = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { LivePlacement("live_sloop", Offset.Zero, owner = PLAYER_COLORS.size) }
    }

    @Test
    fun `on the 3D board the sea keeps running, and the whale, clouds and splashes draw over it`() {
        // The scenery loop used to stop on the 3D board; now it stops there only for a Space board, whose renderer runs its own.
        assertTrue("if (!env.animating || (env.liveBoard && env.art.space(ArtObject.BOARD))) { last = 0L; delay(300); continue }" in main)
        val live = main.substringAfter("if (env.animating && !env.art.space(ArtObject.BOARD)) {").substringBefore("\n                }")
        for (call in listOf("noteSeaView(sea", "sea.drawLiveSplashes(", "sea.drawLiveCritters(", "sea.drawSky(")) assertTrue(call in live, call)
        assertTrue("includeBirds = false" in live, "gulls are 3D models there, not sprites too")
        // Every critter layer is drawn on the 3D board: water layers by drawLiveCritters, air and sky by drawSky.
        val sea = File("src/main/java/com/mauro/wgbg/Sea.kt").readText()
        val critters = sea.substringAfter("internal fun drawLiveCritters").substringBefore("\n    }\n")
        for (layer in listOf("\"under\"", "\"underwater\"", "\"surface\"")) assertTrue(layer in critters, layer)
        val sky = sea.substringAfter("fun drawSky(").substringBefore("\n    }\n")
        for (layer in listOf("drawCritters(this, \"air\"", "drawCritters(this, \"sky\"")) assertTrue(layer in sky, layer)
    }

    @Test
    fun `Codex is credited for the water and the MIT shader references ship with the app`() {
        val credits = File("../CREDITS.md").readText()
        assertTrue("Codex" in credits)
        for (f in listOf("MIT-water-stylized.txt", "MIT-water-boujie.txt")) {
            assertTrue(File("src/main/assets/licenses/$f").isFile, f)
            assertTrue(File("../docs/licenses/$f").isFile, f)
            assertTrue("MIT" in File("src/main/assets/licenses/$f").readText())
        }
        for (m in listOf("live_water_mesh.glb", "live_water.filamat", "coast_a.glb", "live_sloop.glb", "live_raiders.glb", "live_gull_0.glb",
                "live_gull_15.glb", "sea_wake.glb", "sea_wavelet.glb", "sea_shadow.glb")) assertTrue(File("src/main/assets/filament/$m").isFile, m)
        assertTrue(File("src/main/assets/filament").list()!!.none { it.startsWith("harbour_") || it == "coast_b.glb" }, "unused sea models")
    }
}

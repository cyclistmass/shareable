package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Piece
import wgbg.engine.Rules
import java.io.File
import kotlin.math.sin

/**
 * The 3D board's rings and highlights sit on the tiles the player sees, and a tap on a ring finds what it marks, in
 * every theme at every tilt. The Space board stands on a deck 0.22 up; its rings were drawn at height 0, so at full
 * tilt they sat visibly below the board top.
 */
class LiveOverlayTopTest {
    private val themes = listOf(ArtSet.DEFAULT, ArtSet.PIRATE, ArtSet.SPACE)
    private val tilts = listOf(0f, Camera.MAX_TILT / 2, Camera.MAX_TILT)

    private fun near(a: Offset, b: Offset, what: String) = assertTrue((a - b).getDistance() < 1e-2f, "$what: $a vs $b")

    @Test
    fun `rings sit where the pieces and plates are drawn, and taps on them come back to the same spot`() {
        val s = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY), 5)
        s.vertexOwner[0] = 0; s.vertexBuilding[0] = Piece.SETTLEMENT
        for (theme in themes) {
            val choice = ArtChoice(theme)
            val top = liveBoardTop(choice)
            val placements = themedLivePlacements(s, choice, 0) { true }
            val homestead = placements.single { it.model.endsWith("_homestead") }
            assertEquals(liveVertex(s.board, 0), homestead.position)
            val plate = placements.first { it.model == "number_plate" || it.model.startsWith("space_number_") }
            // The plate lies on the tile top, 0.012 up: the overlay's top is that surface.
            assertEquals(top + .012f, plate.height, 1e-6f, "$theme plate")
            for (tilt in tilts) for (rotation in listOf(0f, .7f)) {
                val p = LiveProjection(Offset(900f, 500f), 120f, tilt, rotation)
                val what = "$theme tilt=$tilt rotation=$rotation"
                // The ring on the homestead's vertex is where the homestead stands on screen.
                near(p.project(liveVertex(s.board, 0), top), p.project(homestead.position, homestead.height), "$what homestead ring")
                // Every vertex and tile centre: a tap where its ring is drawn unprojects to that spot.
                for (v in 0 until s.board.vertexCount) near(p.unproject(p.project(liveVertex(s.board, v), top), top), liveVertex(s.board, v), "$what vertex $v")
                for (h in 0 until s.board.hexCount) near(p.unproject(p.project(liveHex(s.board, h), top), top), liveHex(s.board, h), "$what hex $h")
            }
        }
    }

    @Test
    fun `only the Space board is lifted, and at full tilt height 0 would miss it by a visible margin`() {
        assertEquals(0f, liveBoardTop(ArtChoice(ArtSet.DEFAULT)))
        assertEquals(0f, liveBoardTop(ArtChoice(ArtSet.PIRATE)))
        assertEquals(0f, liveBoardTop(ArtChoice().with(ArtObject.BUILDINGS, ArtSet.SPACE)), "Space pieces on the Default board")
        assertEquals(SpaceArt.TILE_DECK_HEIGHT, liveBoardTop(ArtChoice(ArtSet.SPACE)))
        assertEquals(SpaceArt.TILE_DECK_HEIGHT, liveBoardTop(ArtChoice().with(ArtObject.BOARD, ArtSet.SPACE)))
        val p = LiveProjection(Offset.Zero, 120f, Camera.MAX_TILT, 0f)
        val gap = (p.project(Offset(1f, 1f)) - p.project(Offset(1f, 1f), SpaceArt.TILE_DECK_HEIGHT)).getDistance()
        assertEquals(SpaceArt.TILE_DECK_HEIGHT * sin(Camera.MAX_TILT) * 120f, gap, 1e-3f)
        assertTrue(gap > .15f * 120f * .5f, "the old offset was more than half a ring's width at full tilt: $gap px")
    }

    @Test
    fun `the game draws its rings and reads its taps on the board top`() {
        val src = File("src/main/java/com/mauro/wgbg")
        val overlay = File(src, "LiveBoardOverlay.kt").readText()
        val projects = Regex("""p\.project\(([^()]|\([^()]*\))*\)""").findAll(overlay).map { it.value }.toList()
        assertEquals(6, projects.size, projects.toString())
        for (call in projects) assertTrue(call.endsWith(", top)"), "an overlay point off the board top: $call")
        val main = File(src, "MainActivity.kt").readText()
        for (call in listOf("drawLiveTargets(", "drawLiveOverlay(")) {
            val uses = main.split(call).drop(1).map { it.take(400) }
            assertTrue(uses.isNotEmpty(), call)
            for (u in uses) assertTrue("liveBoardTop(env.art)" in u.substringBefore("pieceOn").substringBefore("{ v ->"), "$call without the board top")
        }
        val taps = main.lines().filter { "LiveProjection(l.center, l.scale, camera.tilt, boardRotation).unproject(at" in it }
        assertEquals(2, taps.size, taps.toString())
        assertTrue(taps.any { "unproject(at, liveBoardTop(env.art))" in it }, "a tap on the 3D board reads it at height 0")
        // A splash lands where the tap meets the 3D water (#63), which is lower than the board.
        assertTrue(taps.any { "unproject(at, LIVE_WATER_HEIGHT)" in it }, "a tap on the 3D water reads it at the board top")
    }
}

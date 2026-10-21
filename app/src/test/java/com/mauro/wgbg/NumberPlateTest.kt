package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.hypot

/** The brass number plate (Mauro, 0.3.4): today's plate in aged brass, same place and size, same paint, as readable. */
class NumberPlateTest {
    private val tilts = (0 until SpaceArt.TILT_STEPS).map { it * Camera.MAX_TILT / (SpaceArt.TILT_STEPS - 1) }

    /** Points over the whole painted part of the face (plate radii), where two-digit numbers and the pips sit. */
    private val painted = buildList {
        val n = 24
        for (i in -n..n) for (j in -n..n) {
            val x = i * BrassPlateLook.PAINTED / n; val y = j * BrassPlateLook.PAINTED / n
            if (hypot(x, y) <= BrassPlateLook.PAINTED) add(Offset(x, y))
        }
    }

    @Test
    fun `the paint reads on the brass wherever it sits, as well as on the wood`() {
        val wood = androidx.compose.ui.graphics.Color(0xFFE9CFA0)
        for (p in painted) {
            val face = BrassPlateLook.faceAt(p.x, p.y)
            // WCAG: 4.5 for the ink; 3 for the red 6 and 8, big bold digits (large text).
            assertTrue(contrast(NUMBER_INK, face) >= 4.5f, "ink on $face at $p: ${contrast(NUMBER_INK, face)}")
            assertTrue(contrast(NUMBER_RED, face) >= 3f, "red on $face at $p: ${contrast(NUMBER_RED, face)}")
        }
        // The worn brass texture over the face: even its darkest spots (2nd percentile) leave the paint readable.
        val tex = javax.imageio.ImageIO.read(java.io.File("src/main/res/drawable-nodpi/old_brass.jpg"))
        val pixels = (0 until tex.width step 2).flatMap { x -> (0 until tex.height step 2).map { y -> androidx.compose.ui.graphics.Color(tex.getRGB(x, y)) } }
        val dark = pixels.sortedBy { it.luminanceOf() }[pixels.size / 50]
        for (p in painted) {
            val face = androidx.compose.ui.graphics.lerp(BrassPlateLook.faceAt(p.x, p.y), dark, BrassPlateLook.TEXTURE)
            assertTrue(contrast(NUMBER_INK, face) >= 4.5f, "ink on textured $face at $p")
            assertTrue(contrast(NUMBER_RED, face) >= 3f, "red on textured $face at $p: ${contrast(NUMBER_RED, face)}")
        }
        // The polished middle, where the digits' strokes mostly are, is no worse than today's wood.
        assertTrue(contrast(NUMBER_RED, BrassPlateLook.faceAt(0f, 0f)) >= contrast(NUMBER_RED, wood) - 0.05f)
    }

    @Test
    fun `the face darkens only at the rim, and the rim is darker than the paint zone`() {
        val rim = BrassPlateLook.faceAt(0.99f, 0.99f)
        assertEquals(BrassPlateLook.RIM, rim)
        assertTrue(rim.luminanceOf() < BrassPlateLook.faceAt(0f, 0f).luminanceOf())
        // The gradient is lit from the upper left, like the wood token: brighter up left than down right.
        assertTrue(BrassPlateLook.faceAt(-0.5f, -0.5f).luminanceOf() > BrassPlateLook.faceAt(0.5f, 0.5f).luminanceOf())
    }

    @Test
    fun `same paint on every plate - the 6 and 8 red, the rest ink`() {
        for (n in 2..12) if (n != 7) assertEquals(if (n == 6 || n == 8) NUMBER_RED else NUMBER_INK, numberPaint(n), "$n")
        assertEquals(0xFFC62828.toInt(), NUMBER_RED.toArgbInt())
        assertEquals(0xFF2B2118.toInt(), NUMBER_INK.toArgbInt())
    }

    @Test
    fun `the flat plate stays lower right on its tile at every tilt step`() {
        // The flat board squashes the plate's offset by the tilt's cosine; the plate itself is drawn round.
        for (t in tilts) {
            val y = NUMBER_TOKEN_Y * cos(t)
            assertTrue(NUMBER_TOKEN_X > 0f && y > 0f, "tilt $t: (${NUMBER_TOKEN_X}, $y)")
            assertTrue(TILE3D_PLATE_X > 0f && TILE3D_PLATE_Y * cos(t) > 0f, "3D preview tile, tilt $t")
        }
    }

    @Test
    fun `the 3D brass plate and its digit stay lower right on the tile at every tilt step`() {
        val state = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(numPlayers = 4), 7)
        val placed = livePlacements(state, pirate = false, lod = 0)
        // One brass plate per numbered tile, each with its own digit quad.
        val numbered = (0 until state.board.hexCount).count { state.numberAt(it) > 0 }
        assertEquals(numbered, placed.count { it.model == LIVE_PLATE_MODEL })
        assertEquals(numbered, placed.count { it.model.startsWith("digit_") })
        for (t in tilts) {
            val cam = LiveProjection(Offset(1000f, 500f), 80f, t, 0f)
            for (h in 0 until state.board.hexCount) {
                if (state.numberAt(h) <= 0) continue
                val tile = cam.project(liveHex(state.board, h))
                val plate = cam.project(liveHex(state.board, h) + LIVE_PLATE, .012f)
                val digit = cam.project(liveHex(state.board, h) + LIVE_PLATE, .039f)
                assertTrue(plate.x > tile.x && plate.y > tile.y, "hex $h tilt $t: plate $plate, tile $tile")
                // The digit lies on its plate, well inside its radius on screen.
                assertTrue(hypot(digit.x - plate.x, digit.y - plate.y) < 0.25f * NUMBER_TOKEN_R * 80f, "hex $h tilt $t")
            }
        }
    }

    @Test
    fun `the 3D plate is tinted brass, counts as a number, and the knights' discs keep their own colours`() {
        assertEquals("number_plate", liveModelFile(LIVE_PLATE_MODEL))
        assertTrue(liveNumber(LIVE_PLATE_MODEL))
        assertEquals(BrassPlateLook.LIVE_COLOR, liveTint(LIVE_PLATE_MODEL, "NumberPlate_PBR", -1))
        // The pedestals load the same disc but are not plates: band dark, ring in the owner's colour.
        assertEquals("number_plate", liveModelFile(KNIGHT_RING))
        assertEquals(KNIGHT_BAND_COLOR, liveTint(KNIGHT_BAND, "NumberPlate_PBR", 1))
        assertEquals(PLAYER_COLORS[1], liveTint(KNIGHT_RING, "NumberPlate_PBR", 1))
        // The digits stay unlit paint, never tinted.
        assertEquals(null, liveTint("digit_8", "RuntimeDigits", -1))
    }

    @Test
    fun `the brass 3D colour reads under the paint too`() {
        assertTrue(contrast(NUMBER_RED, BrassPlateLook.LIVE_COLOR) >= 3f, "${contrast(NUMBER_RED, BrassPlateLook.LIVE_COLOR)}")
        assertTrue(contrast(NUMBER_INK, BrassPlateLook.LIVE_COLOR) >= 4.5f)
    }

    private fun androidx.compose.ui.graphics.Color.luminanceOf() = contrast(this, androidx.compose.ui.graphics.Color.Black)
    private fun androidx.compose.ui.graphics.Color.toArgbInt() =
        ((alpha * 255 + .5f).toInt() shl 24) or ((red * 255 + .5f).toInt() shl 16) or ((green * 255 + .5f).toInt() shl 8) or (blue * 255 + .5f).toInt()
}

package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

/** 3D board (#95): pieces draw over the terrain in front of them without moving on screen. */
class LiveDepthTest {
    /** Screen x, screen y and distance toward the eye of world point [p] under the renderer's lookAt camera. */
    private fun view(p: FloatArray, tilt: Float, rotation: Float): FloatArray {
        val st = sin(tilt); val ct = cos(tilt); val sr = sin(rotation); val cr = cos(rotation)
        val right = floatArrayOf(cr, 0f, -sr); val up = floatArrayOf(-ct * sr, st, -ct * cr); val eye = floatArrayOf(st * sr, ct, st * cr)
        fun dot(a: FloatArray) = a[0] * p[0] + a[1] * p[1] + a[2] * p[2]
        return floatArrayOf(dot(right), dot(up), dot(eye))
    }

    @Test
    fun `a piece moved toward the camera keeps its pixels and comes nearer, at every tilt and twist`() {
        val piece = LivePlacement("default_homestead", Offset(1.2f, -0.4f), 0.02f, scale = BUILDING_SCALE, heading = 0.4f, owner = 0)
        for (tilt in listOf(0f, 0.14f, 0.48f, 0.96f)) for (rotation in listOf(0f, 0.8f, 2.1f, -2.9f)) {
            val before = liveModelMatrix(piece)
            val at = floatArrayOf(before[12], before[13], before[14])
            val after = liveTowardCamera(before.copyOf(), tilt, rotation)
            val moved = floatArrayOf(after[12], after[13], after[14])
            val a = view(at, tilt, rotation); val b = view(moved, tilt, rotation)
            assertEquals(a[0], b[0], 1e-5f, "screen x at tilt $tilt rotation $rotation")
            assertEquals(a[1], b[1], 1e-5f, "screen y at tilt $tilt rotation $rotation")
            assertEquals(LIVE_PIECE_DEPTH_BIAS, b[2] - a[2], 1e-5f, "nearer the eye")
            // Only the translation moves: size, shape and lean are untouched.
            for (i in 0 until 12) assertEquals(before[i], after[i])
        }
    }

    @Test
    fun `the bias clears the tallest terrain prop at the steepest tilt`() {
        val tallestProp = 1.086f // tile_stone_lod0.glb's peak
        assertTrue(LIVE_PIECE_DEPTH_BIAS > tallestProp / cos(Camera.MAX_TILT), "a peak in front can't cover a piece")
    }

    @Test
    fun `pieces move, the board does not`() {
        for (m in listOf("default_homestead", "pirate_town", "default_road_v", "space_road_v", "default_ramparts", "default_capital_crown",
            "default_knight_l2", "default_merchant", "bandit", "space_bandit")) assertTrue(livePiece(m), m)
        for (m in listOf("sea", "tile_stone_lod0", "number_plate", "digit_8", "space_number_8")) assertFalse(livePiece(m), m)
    }
}

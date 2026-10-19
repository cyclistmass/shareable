package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The board's art: 85% saturation and 3% warmer (Mauro, 2026-10-12); pieces keep their own colour. */
class BoardColourTest {
    private fun apply(m: FloatArray, rgb: FloatArray) = FloatArray(3) { r -> (0 until 3).sumOf { c -> (m[r * 5 + c] * rgb[c]).toDouble() }.toFloat() }

    @Test
    fun `a grey turns a touch warm, green stays put`() {
        val out = apply(boardMatrix(BOARD_SATURATION), floatArrayOf(.5f, .5f, .5f))
        assertEquals(.5f * (1 + BOARD_WARMTH), out[0], 1e-5f)
        assertEquals(.5f, out[1], 1e-5f)
        assertEquals(.5f * (1 - BOARD_WARMTH), out[2], 1e-5f)
    }

    @Test
    fun `the board is less saturated than before and the tiles less than the sea`() {
        val red = floatArrayOf(.9f, .2f, .2f)
        val full = apply(saturationMatrix(1f), red); val board = apply(boardMatrix(BOARD_SATURATION), red); val tile = apply(boardMatrix(TILE_SATURATION), red)
        fun spread(c: FloatArray) = c.max() - c.min()
        assertTrue(spread(board) < spread(full)); assertTrue(spread(tile) < spread(board))
        assertEquals(0.85f, BOARD_SATURATION); assertEquals(0.72f * 0.85f, TILE_SATURATION, 1e-6f); assertEquals(0.72f * 0.85f * 1.08f, PAINTED_TILE_SATURATION, 1e-6f)
    }

    @Test
    fun `pieces are not dulled by the board's colour`() {
        val wood = floatArrayOf(.6f, .4f, .2f)
        val piece = apply(pieceMatrix(androidx.compose.ui.graphics.Color.White), wood)
        assertTrue(piece.max() - piece.min() > wood.max() - wood.min(), "pieces stay more vivid than their wood")
    }
}

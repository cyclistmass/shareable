package com.mauro.wgbg

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The hand row on small screens (0.3.4: one card wide at 640x320). */
class HandFanTest {
    private fun width(cards: Int, gap: Float) = cards * HAND_CARD_W + (cards - 1) * gap

    @Test
    fun `a hand that fits keeps the usual 5 dp overlap`() {
        assertEquals(-5f, handOverlap(1000f, 8))
        assertEquals(-5f, handOverlap(140f, 3)) // 3 cards are 140 dp
        for (n in listOf(0, 1)) assertEquals(-5f, handOverlap(10f, n))
    }

    @Test
    fun `a hand that doesn't fit fans closer to fit exactly`() {
        for ((room, n) in listOf(144f to 4, 300f to 10, 200f to 7)) {
            val gap = handOverlap(room, n)
            assertTrue(gap < -5f, "$n cards in $room dp")
            assertEquals(room, width(n, gap), 0.01f, "$n cards fill $room dp")
        }
    }

    @Test
    fun `each card keeps a tappable slice with its count, then the row scrolls`() {
        for (n in 2..20) for (room in listOf(0f, 30f, 60f, 144f, 300f)) {
            val gap = handOverlap(room, n)
            assertTrue(HAND_CARD_W + gap >= HAND_MIN_SLICE, "$n cards in $room dp show ${HAND_CARD_W + gap} dp each")
        }
        // At 640x320 the room is about 144 dp: at least three cards show whole or in slices, not one.
        val gap = handOverlap(144f, 6)
        assertTrue(((144f - HAND_CARD_W) / (HAND_CARD_W + gap)).toInt() + 1 >= 3)
    }

    @Test
    fun `garbage room never breaks the layout`() {
        for (room in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -50f)) {
            val gap = handOverlap(room, 5)
            assertTrue(gap.isFinite() && HAND_CARD_W + gap >= HAND_MIN_SLICE && gap <= -5f, "room $room: $gap")
        }
    }

    @Test
    fun `the row starts right of the side panel, never further in than before`() {
        assertEquals(384.dp, handStart(0.dp), "before the panel is measured")
        assertEquals(301.dp, handStart(285.dp), "a 640 dp screen's panel")
        assertEquals(384.dp, handStart(400.dp), "a wide panel keeps today's start")
    }
}

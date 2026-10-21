package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.ChivalryPiece
import wgbg.engine.GameConfig
import wgbg.engine.Rules
import kotlin.math.hypot

class RaidersTest {
    private val board = Rules.newGame(GameConfig(), 1).board
    private val landR = board.topology.vertexPos.maxOf { hypot(it.x * kotlin.math.sqrt(3f) / 2f, it.y / 2f) }

    @Test
    fun `the ship closes in on the island as the track advances, and stays on the water`() {
        var last = Float.MAX_VALUE
        for (pos in 0..ChivalryPiece.BARBARIAN_TRACK) {
            val (x, y) = raidersAt(pos, board, 0f)
            val d = hypot(x, y)
            assertTrue(d < last, "step $pos is not closer")
            assertTrue(d > landR + 0.5f, "on the island at $pos")
            last = d
        }
    }
}

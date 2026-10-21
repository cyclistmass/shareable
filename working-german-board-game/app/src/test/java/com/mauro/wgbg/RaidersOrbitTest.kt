package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import wgbg.engine.*
import kotlin.math.*

class RaidersOrbitTest {
    @Test fun `every track loops on open water and tightens at every angle`() {
        val sea = Sea(Rules.newGame(GameConfig(), 1).board)
        for (sample in 0..720) {
            val t = sample / 720f * (2 * PI / 0.025).toFloat()
            var last = Float.MAX_VALUE
            for (track in 0..ChivalryPiece.BARBARIAN_TRACK) {
                val p = raidersPose(track.toFloat(), sea.landR, t)
                val r = hypot(p.x, p.y)
                assertTrue(sea.isWater(androidx.compose.ui.geometry.Offset(p.x, p.y)))
                assertTrue(sea.inOpenSea(p.x, p.y, 0.35f))
                assertTrue(r < last, "track $track at angle $sample")
                last = r
                val next = raidersPose(track.toFloat(), sea.landR, t + 0.01f)
                assertTrue(hypot(next.x - p.x, next.y - p.y) < 0.003f)
            }
        }
    }
    @Test fun `track advance and battle reset ease instead of teleporting`() {
        val sea = Sea(Rules.newGame(GameConfig(), 1).board)
        for (target in listOf(ChivalryPiece.BARBARIAN_TRACK, 0)) {
            sea.raidersTarget = target
            repeat(60 * 30) {
                val old = raidersPose(sea.raidersTrack, sea.landR, sea.time)
                sea.update(1f / 60)
                val next = raidersPose(sea.raidersTrack, sea.landR, sea.time)
                assertTrue(hypot(next.x - old.x, next.y - old.y) < 0.02f)
            }
            assertEquals(target.toFloat(), sea.raidersTrack, 0.01f)
        }
    }
    @Test fun `battle runs in briefly before returning to the wide orbit`() {
        val sea = Sea(Rules.newGame(GameConfig(), 1).board)
        sea.observeRaiders(6, 0)
        repeat(30 * 60) { sea.update(1f / 60) }
        sea.observeRaiders(0, 1)
        repeat(5 * 60) { sea.update(1f / 60) }
        assertTrue(sea.raidersTrack > 6f)
        repeat(30 * 60) { sea.update(1f / 60) }
        assertEquals(0f, sea.raidersTrack, 0.01f)
    }
}

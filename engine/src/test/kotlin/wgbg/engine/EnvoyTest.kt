package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Envoy (Progress.DIPLOMAT) lifts an open road: one with an end where its owner has no other road, building or knight
 * (a player report: a road ending at its owner's own knight was offered).
 */
class EnvoyTest {
    /** Blue's two roads round hex (0,0): [first] from corner 0 to 1, [second] from 1 to 2. Red holds an Envoy, rolled. */
    private class Setup {
        val s = CK.playState()
        val c0 = T.corner(s, 0, 0, 0)
        val first = T.side(s, 0, 0, 0)
        val second = T.side(s, 0, 0, 1)

        init {
            T.road(s, 1, first, second)
            CK.giveProgress(s, 0, Progress.DIPLOMAT)
            s.hasRolled = true
        }

        fun offered(e: Int) = Action.playProgress(Progress.DIPLOMAT, e) in Rules.legalActions(s, ActionBuffer())
    }

    @Test
    fun `a road with an empty end is open`() {
        val t = Setup()
        assertTrue(Chivalry.isOpenRoad(t.s, t.first))
        assertTrue(t.offered(t.first))
    }

    @Test
    fun `the owner's own knight closes that end`() {
        val t = Setup()
        CK.knight(t.s, 1, t.c0)
        assertFalse(Chivalry.isOpenRoad(t.s, t.first))
        assertFalse(t.offered(t.first))
        assertTrue(t.offered(t.second), "the other road still has a loose end")
    }

    @Test
    fun `a rival's knight doesn't close it`() {
        val t = Setup()
        CK.knight(t.s, 2, t.c0)
        assertTrue(Chivalry.isOpenRoad(t.s, t.first))
        assertTrue(t.offered(t.first))
    }

    @Test
    fun `the owner's building closes that end`() {
        val t = Setup()
        T.settle(t.s, 1, t.c0)
        assertFalse(Chivalry.isOpenRoad(t.s, t.first))
        assertFalse(t.offered(t.first))
    }

    @Test
    fun `lifting a road that isn't open is rejected and changes nothing`() {
        val t = Setup()
        CK.knight(t.s, 1, t.c0, level = 2, active = true)
        val before = t.s.stateHash()
        assertThrows(IllegalArgumentException::class.java) {
            Rules.applyChecked(t.s, Action.playProgress(Progress.DIPLOMAT, t.first))
        }
        assertEquals(before, t.s.stateHash())
        assertEquals(1, t.s.edgeOwner[t.first])
    }

    @Test
    fun `lifting an open road takes it off the board`() {
        val t = Setup()
        Rules.applyChecked(t.s, Action.playProgress(Progress.DIPLOMAT, t.first))
        assertEquals(-1, t.s.edgeOwner[t.first])
    }
}

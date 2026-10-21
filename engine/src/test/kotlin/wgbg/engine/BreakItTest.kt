package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Throws garbage at the engine the way a broken or hostile client could: random codes, known
 * action types with random payloads, legal actions with flipped bits, and legal actions replayed
 * at the wrong moment. Every rejected action must leave the game exactly as it was; anything
 * the engine does accept must leave a valid game. No other exception may escape.
 */
class BreakItTest {
    private class Tally { var rejected = 0; var accepted = 0 }

    private fun attack(s: GameState, junk: Action, invariants: (GameState) -> Unit, t: Tally) {
        val before = s.stateHash()
        val c = s.copy()
        try {
            Rules.applyChecked(c, junk)
            invariants(c) // it was legal after all: the result must still be a valid game
            t.accepted++
        } catch (e: IllegalArgumentException) {
            assertEquals(before, c.stateHash(), "rejected $junk changed the game")
            t.rejected++
        }
        assertEquals(before, s.stateHash(), "the original game must never change")
    }

    private fun run(config: GameConfig, games: Int, invariants: (GameState) -> Unit): Tally {
        val t = Tally()
        val rng = Rng(12345)
        val buf = ActionBuffer()
        val stale = ArrayList<Action>() // legal moves from earlier moments, replayed at the wrong time
        val maxType = ChivalryActionType.ID_BASE + ChivalryActionType.entries.size
        for (g in 0 until games) {
            val s = Rules.newGame(config, g.toLong())
            var steps = 0
            while (!s.isOver && steps++ < 3000) {
                Rules.legalActions(s, buf)
                val legal = buf[rng.nextInt(buf.size)]
                if (steps % 3 == 0) { // attack every third position to keep the run time sane
                    val payload = { (rng.nextInt(1 shl 30).toLong() shl 18) xor rng.nextInt(1 shl 18).toLong() }
                    val junk = listOf(
                        Action((rng.nextInt(256).toLong() shl Action.TYPE_SHIFT) or payload()), // random code
                        Action((rng.nextInt(maxType).toLong() shl Action.TYPE_SHIFT) or rng.nextInt(1 shl 16).toLong()), // known type, junk payload
                        Action(legal.code xor (1L shl rng.nextInt(Action.TYPE_SHIFT))),          // one flipped bit
                        Action(legal.code xor (1L shl rng.nextInt(Action.TYPE_SHIFT)) xor (1L shl rng.nextInt(Action.TYPE_SHIFT))),
                        if (stale.isEmpty()) legal else stale[rng.nextInt(stale.size)],          // wrong moment
                    )
                    for (j in junk) attack(s, j, invariants, t)
                }
                if (stale.size < 4000) stale += legal else stale[rng.nextInt(stale.size)] = legal
                Rules.apply(s, legal)
            }
        }
        return t
    }

    @Test
    fun `base game survives garbage, mutated and wrong-time actions`() {
        val t = run(GameConfig(numPlayers = 4), games = 150, invariants = T::checkInvariants)
        println("base break-it: ${t.rejected} rejected, ${t.accepted} accepted (were legal)")
        assertTrue(t.rejected > 100_000, "should have thrown a lot of junk, rejected ${t.rejected}")
    }

    @Test
    fun `age of chivalry survives garbage, mutated and wrong-time actions`() {
        val t = run(CK.config(players = 4), games = 40, invariants = CK::checkInvariants)
        println("Age of Chivalry break-it: ${t.rejected} rejected, ${t.accepted} accepted (were legal)")
        assertTrue(t.rejected > 50_000, "should have thrown a lot of junk, rejected ${t.rejected}")
    }
}

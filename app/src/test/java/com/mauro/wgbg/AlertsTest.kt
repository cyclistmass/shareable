package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.ai.RandomBot
import wgbg.engine.ActionBuffer
import wgbg.engine.CkPiece
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rules
import java.io.File

/**
 * The turn bell and buzz, the Raiders' horn and the event die's wooden clunk (0.3.4): when each sounds, the Settings
 * switches, and that each is its own CC0 sound, credited.
 */
class AlertsTest {
    private class MapStore : FloatStore {
        val map = mutableMapOf<String, Float>()
        override fun get(key: String, default: Float) = map[key] ?: default
        override fun put(key: String, value: Float) { map[key] = value }
    }

    @Test
    fun `the horn sounds when the ship moves to within the set steps, once a move, never for the attack`() {
        assertTrue(raidersWarn(4, 3, 3))
        assertTrue(raidersWarn(2, 1, 3))
        assertFalse(raidersWarn(5, 4, 3), "still 4 away")
        assertFalse(raidersWarn(3, 3, 3), "the ship didn't move")
        assertFalse(raidersWarn(1, 0, 3), "the attack has its own sounds")
        assertFalse(raidersWarn(1, 7, 3), "back out to sea after the attack")
        assertTrue(raidersWarn(7, 6, 10), "10 steps: every move")
        assertFalse(raidersWarn(3, 2, 1))
        assertTrue(raidersWarn(2, 1, 1))
        assertFalse(raidersWarn(null, 3, 3)); assertFalse(raidersWarn(4, null, 3))
    }

    @Test
    fun `steps left come from the Raiders' track, and the base game has none`() {
        assertNull(raidersStepsLeft(Rules.newGame(GameConfig(numPlayers = 3), 1)))
        val ck = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS, numPlayers = 3), 1)
        assertEquals(CkPiece.BARBARIAN_TRACK, raidersStepsLeft(ck))
        ck.ck!!.barbarianPos = 5
        assertEquals(CkPiece.BARBARIAN_TRACK - 5, raidersStepsLeft(ck))
    }

    @Test
    fun `fuzz - over whole games the horn sounds only on the ship's moves inside the range`() {
        var horns = 0; var advances = 0
        for (seed in 1L..20L) {
            val s = Rules.newGame(GameConfig(expansion = Expansion.CITIES_AND_KNIGHTS, numPlayers = 4), seed)
            val bots = List(4) { RandomBot(it + seed) }
            val buf = ActionBuffer()
            var before = raidersStepsLeft(s)
            while (!s.isOver && s.turn < 400) {
                Rules.legalActions(s, buf)
                Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
                val after = raidersStepsLeft(s)
                if (after != null && before != null && after < before) advances++
                if (raidersWarn(before, after, 3)) { horns++; assertTrue(after!! in 1..3) }
                before = after
            }
        }
        assertTrue(advances > 50, "only $advances ship moves")
        assertTrue(horns in 1 until advances, "$horns horns for $advances ship moves")
    }

    @Test
    fun `the turn bell's key changes each time it's your turn, setup included, and is null otherwise`() {
        val s = Rules.newGame(GameConfig(numPlayers = 3), 4)
        val bots = List(3) { RandomBot(it.toLong()) }
        val buf = ActionBuffer()
        val keys = mutableListOf<Int>()
        var last: Int? = null
        while (!s.isOver && s.turn < 60) {
            val key = myTurnKey(s, 0)
            if (s.currentPlayer != 0) assertNull(key)
            if (key != null && key != last) keys += key
            last = key
            Rules.legalActions(s, buf)
            Rules.apply(s, bots[s.actingPlayer].choose(s, buf))
        }
        assertEquals(keys.size, keys.toSet().size, "a turn's key never comes back")
        assertTrue(keys.size >= 15, "two setup turns and a key each round: ${keys.size}")
        assertNull(myTurnKey(s, -1), "a watcher has no turn")
        assertNotEquals(myTurnKey(Rules.newGame(GameConfig(numPlayers = 3), 4).also { it.currentPlayer = 1 }, 1), null)
    }

    @Test
    fun `Settings - both alerts on and 3 steps by default, kept, clamped to 1 to 10, reset`() {
        val store = MapStore()
        val sound = SoundSettings(store)
        assertTrue(sound.turnAlert); assertTrue(sound.raidersAlert); assertEquals(3, sound.raidersSteps)
        sound.turnAlert = false; sound.raidersAlert = false; sound.raidersSteps = 7
        val again = SoundSettings(store)
        assertFalse(again.turnAlert); assertFalse(again.raidersAlert); assertEquals(7, again.raidersSteps)
        again.raidersSteps = 0; assertEquals(1, again.raidersSteps)
        again.raidersSteps = 11; assertEquals(10, again.raidersSteps)
        store.map[SoundSettings.RAIDERS_STEPS] = Float.NaN
        assertTrue(SoundSettings(store).raidersSteps in 1..10, "a broken saved value is still in range")
        again.reset()
        assertTrue(again.turnAlert); assertTrue(again.raidersAlert); assertEquals(3, again.raidersSteps)
    }

    @Test
    fun `each alert is its own CC0 sound, credited, and the old synthesised clonk is gone`() {
        val raw = File("src/main/res/raw")
        val files = listOf("event_clunk.ogg", "turn_bell.ogg", "raiders_horn.ogg", "dice_roll.ogg", "trade_chime.wav").map { File(raw, it) }
        for (f in files) assertTrue(f.isFile && f.length() in 1000..200_000, "${f.name} missing or the wrong size")
        assertEquals(files.size, files.map { it.readBytes().contentHashCode() }.toSet().size, "two alerts share a sound")
        assertFalse(File(raw, "raiders_clonk.ogg").exists(), "the ding Mauro called not ok")
        val credits = File("../CREDITS.md").readText()
        for (name in listOf("event_clunk.ogg", "turn_bell.ogg", "raiders_horn.ogg")) {
            val line = credits.lines().first { name in it }
            assertTrue("CC0" in credits.substringAfter(line).lines().take(3).joinToString() + line, "$name credited as CC0")
        }
        val sounds = File("src/main/java/com/mauro/wgbg/GameSounds.kt").readText()
        for (id in listOf("R.raw.event_clunk", "R.raw.turn_bell", "R.raw.raiders_horn")) assertTrue(id in sounds)
        assertFalse("raiders_clonk" in sounds)
    }

    @Test
    fun `the buzz feels different from the dice rattle and the clunk`() {
        val buzzes = listOf(Haptics.TURN_TIMINGS, Haptics.DICE_TIMINGS, Haptics.CLONK_TIMINGS).map { it.toList() }
        assertEquals(3, buzzes.toSet().size)
        assertEquals(Haptics.TURN_TIMINGS.size, Haptics.TURN_AMPLITUDES.size)
        assertTrue(Haptics.TURN_AMPLITUDES.all { it in 0..255 })
    }
}

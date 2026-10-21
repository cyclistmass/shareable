package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.mauro.wgbg.EnvSettings.Item

class EnvSettingsTest {
    private class MapStore : FloatStore {
        val map = HashMap<String, Float>()
        override fun get(key: String, default: Float) = map[key] ?: default
        override fun put(key: String, value: Float) { map[key] = value }
    }

    @Test
    fun `a broken saved art choice falls back instead of crashing`() {
        // roundToInt throws on NaN; a damaged settings file read NaN for the theme and the art getter threw on start.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val store = MapStore().apply { map[EnvSettings.THEME] = bad }
            for (o in ArtObject.entries) store.map[EnvSettings.artKey(o)] = bad
            assertEquals(ArtSet.DEFAULT, EnvSettings(store).art.theme)
        }
        assertEquals(-1, Float.NaN.savedIndex()); assertEquals(2, 1.6f.savedIndex())
    }

    @Test
    fun `every scenery slider starts at half`() {
        val env = EnvSettings(MapStore())
        for (i in Item.entries) assertEquals(0.5f, env[i])
    }

    @Test
    fun `sliders persist and are independent`() {
        val store = MapStore()
        EnvSettings(store).apply { this[Item.CLOUDS] = 0.9f; this[Item.BOATS] = 0f }
        val again = EnvSettings(store) // as after an app restart
        assertEquals(0.9f, again.clouds)
        assertEquals(0f, again.boats)
        assertEquals(0.5f, again.birds, "untouched sliders keep their default")
        again[Item.WAVES] = 1f
        assertEquals(0.9f, again.clouds, "one slider never moves another")
    }

    @Test
    fun `out of range values are clamped, including bad stored ones`() {
        val store = MapStore().apply { map["env-clouds"] = 7f; map["env-birds"] = -2f }
        val env = EnvSettings(store)
        assertEquals(1f, env.clouds)
        assertEquals(0f, env.birds)
    }

    @Test
    fun `volumes persist, are clamped and independent`() {
        val store = MapStore()
        SoundSettings(store).apply { musicVolume = 0.2f; soundVolume = 7f }
        val again = SoundSettings(store)
        assertEquals(0.2f, again.musicVolume)
        assertEquals(1f, again.soundVolume)
    }

    @Test
    fun `music plays at half the slider (-6 dB), and full slider is half scale`() {
        assertEquals(0.5f, musicLevel(1f))
        assertEquals(SoundSettings.DEFAULT_MUSIC / 2, musicLevel(SoundSettings.DEFAULT_MUSIC))
        assertEquals(0f, musicLevel(-1f)); assertEquals(0.5f, musicLevel(3f))
    }

    @Test
    fun `#131 unticked songs are saved by title, passed over in the set list, and reset ticks them all`() {
        val store = MapStore()
        val n = SoundSettings.SONGS.size
        SoundSettings(store).apply { assertEquals(true, (0 until n).all { songOn(it) }); setSongOn(1, false); setSongOn(2, false) }
        val again = SoundSettings(store)
        assertEquals(false, again.songOn(1)); assertEquals(true, again.songOn(3))
        assertEquals(3, nextOn(1, n) { again.songOn(it) }, "1 and 2 are skipped")
        assertEquals(0, nextOn(n, n) { again.songOn(it) }, "wraps round")
        assertEquals(-1, nextOn(0, n) { false }, "nothing ticked: nothing plays")
        assertEquals(-1, nextOn(0, 0) { true })
        again.reset()
        assertEquals(true, (0 until n).all { SoundSettings(store).songOn(it) })
    }

    @Test
    fun `reset puts every setting back to its default`() {
        val store = MapStore()
        val env = EnvSettings(store).apply { this[Item.CLOUDS] = 1f; this[Item.WAVES] = 0f }
        val sound = SoundSettings(store).apply { musicVolume = 0f; soundVolume = 0.1f }
        env.reset(); sound.reset()
        for (i in Item.entries) assertEquals(EnvSettings.DEFAULT, env[i])
        assertEquals(SoundSettings.DEFAULT_MUSIC, sound.musicVolume)
        assertEquals(SoundSettings.DEFAULT_SOUNDS, sound.soundVolume)
        assertEquals(EnvSettings.DEFAULT, EnvSettings(store).clouds, "and the reset is saved")
    }

    @Test
    fun `Reset all puts the look back too, and no Animations switch outlives it (#91)`() {
        val store = MapStore()
        EnvSettings(store).apply {
            art = ArtChoice(ArtSet.PIRATE); tiles3d = true; seeThrough = true; bugButton = true; liveBoard = true; density = 0f
        }
        val env = EnvSettings(store)
        assertEquals(false, env.animating, "Density 0 is the one off switch")
        env.reset()
        val again = EnvSettings(store)
        assertEquals(ArtChoice(ArtSet.DEFAULT), again.art)
        assertEquals(false, again.tiles3d); assertEquals(false, again.seeThrough); assertEquals(false, again.bugButton)
        assertEquals(false, again.liveBoard); assertEquals(true, again.animating)
        // An "Animations off" saved by 0.3.3 no longer freezes the scenery with no switch left to undo it.
        assertEquals(true, EnvSettings(MapStore().apply { map["animations"] = 0f }).animating)
    }

    @Test
    fun `knight theme is saved and reset goes back to Normal`() {
        val store = MapStore()
        EnvSettings(store).pirateTheme = true
        val again = EnvSettings(store)
        assertEquals(true, again.pirateTheme)
        again.reset()
        assertEquals(false, EnvSettings(store).pirateTheme)
    }

    @Test
    fun `tile edges default to a thin line, are saved and reset`() {
        val store = MapStore()
        assertEquals(EnvSettings.TileEdge.THIN, EnvSettings(store).tileEdge)
        EnvSettings(store).tileEdge = EnvSettings.TileEdge.NONE
        val again = EnvSettings(store)
        assertEquals(EnvSettings.TileEdge.NONE, again.tileEdge)
        again.reset()
        assertEquals(EnvSettings.TileEdge.THIN, EnvSettings(store).tileEdge)
    }

    @Test
    fun `#137 view sliders are off by default, switched on and saved from the gear, and reset turns them off`() {
        val store = MapStore()
        org.junit.jupiter.api.Assertions.assertFalse(EnvSettings(store).viewBars, "gestures by default: no sliders")
        EnvSettings(store).viewBars = true
        org.junit.jupiter.api.Assertions.assertTrue(EnvSettings(store).viewBars, "the player's choice survives a restart")
        EnvSettings(store).reset()
        org.junit.jupiter.api.Assertions.assertFalse(EnvSettings(store).viewBars, "reset hides them again")
    }

    @Test
    fun `density is saved, clamped and reset`() {
        val store = MapStore()
        EnvSettings(store).density = 1.7f
        val again = EnvSettings(store)
        org.junit.jupiter.api.Assertions.assertEquals(1f, again.density)
        again.reset()
        org.junit.jupiter.api.Assertions.assertEquals(EnvSettings.DEFAULT, again.density)
    }

    @Test
    fun `AI pace starts at 1 s, persists, resets, and a bad saved value falls back safely`() {
        val store = MapStore()
        assertEquals(1f, EnvSettings(store).aiPace)
        EnvSettings(store).aiPace = 3.5f
        assertEquals(3.5f, EnvSettings(store).aiPace, "kept after a restart")
        EnvSettings(store).apply { reset(); assertEquals(EnvSettings.AI_PACE_DEFAULT, aiPace) }
        EnvSettings(store).aiPace = 9f
        assertEquals(5f, EnvSettings(store).aiPace, "capped at 5 s")
        store.put(EnvSettings.AI_PACE, -3f)
        assertEquals(1f, EnvSettings(store).aiPace)
        store.put(EnvSettings.AI_PACE, Float.NaN)
        assertEquals(1f, EnvSettings(store).aiPace)
    }

    @Test
    fun `a roll still waits for the dice at every speed, and an end of turn goes straight on`() {
        val roll = wgbg.engine.ActionType.ROLL
        assertEquals((DiceSprites.FALL_S * 1000).toInt(), Pace.fallMs())
        for (speed in listOf(1, 2, 4, 8)) {
            org.junit.jupiter.api.Assertions.assertTrue(pauseAfter(roll, speed) > Pace.fallMs() * DiceSprites.HANDOVER, "speed $speed")
            assertEquals(0L, pauseAfter(wgbg.engine.ActionType.END_TURN, speed))
        }
    }

    @Test
    fun `an AI waits the AI pace between turns and half of it per move, faster when sped up, never under the plain pause`() {
        val road = wgbg.engine.ActionType.BUILD_ROAD; val roll = wgbg.engine.ActionType.ROLL; val end = wgbg.engine.ActionType.END_TURN
        assertEquals(1000L, botPauseAfter(end, 2, 1f), "1 s between turns by default")
        assertEquals(5000L, botPauseAfter(end, 2, 5f), "up to 5 s")
        assertEquals(2500L, botPauseAfter(road, 2, 5f), "a move inside the turn takes half")
        assertEquals(5000L, botPauseAfter(roll, 2, 5f), "a roll gets the full beat, so its results can be read")
        org.junit.jupiter.api.Assertions.assertTrue(botPauseAfter(road, 4, 4f) < botPauseAfter(road, 2, 4f))
        for (speed in listOf(1, 2, 4, 8)) for (pace in listOf(1f, 3f, 5f)) for (type in listOf(road, roll))
            org.junit.jupiter.api.Assertions.assertTrue(botPauseAfter(type, speed, pace) >= pauseAfter(type, speed), "$type $pace s at $speed")
        assertEquals(0L, botPauseAfter(wgbg.engine.ActionType.REJECT_TRADE, 2, 5f))
        assertEquals(1000L, botPauseAfter(wgbg.engine.ActionType.REJECT_TRADE, 2, 2f, personOffered = true), "#114: answers to your offer take the half beat")
    }
}

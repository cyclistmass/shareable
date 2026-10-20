package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #111: songs load off the main thread; one that finishes loading plays only if it's still the one wanted. */
class MusicLoadTest {
    @Test
    fun `a loaded song plays only when nothing changed meanwhile`() {
        assertTrue(musicStillWanted(3, 3, paused = false, held = false, volume = 0.5f, closed = false))
        assertFalse(musicStillWanted(3, 4, false, false, 0.5f, false), "a newer song or a stop came after it")
        assertFalse(musicStillWanted(3, 3, true, false, 0.5f, false), "the app went to the background")
        assertFalse(musicStillWanted(3, 3, false, true, 0.5f, false), "the player pressed Pause")
        assertFalse(musicStillWanted(3, 3, false, false, 0f, false), "music turned off")
        assertFalse(musicStillWanted(3, 3, false, false, 0.5f, true), "the game closed")
    }
}

package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Screens set up Firebase in the background, never while composing: a joiner hit an ANR typing a name on the game-start
 * screen (0.3.2), and the start menu did the same set-up on its first frame (#111).
 */
class FirebaseOffMainTest {
    @Test
    fun `no screen sets up Firebase inside a plain remember`() {
        val blocking = Regex("""remember \{ runCatching \{ LobbyRepository\.create""")
        for (f in File("src/main/java/com/mauro/wgbg").listFiles()!!.filter { it.extension == "kt" })
            assertTrue(blocking.find(f.readText()) == null, "${f.name} sets up Firebase on the main thread while composing")
    }
}

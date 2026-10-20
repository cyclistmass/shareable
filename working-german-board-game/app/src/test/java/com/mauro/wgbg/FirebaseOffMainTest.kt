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

    @Test
    fun `Join, Host and Resume wait for the online transport off the main thread`() {
        val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText()
        // transport.value is only read once it's set up, or on a background thread inside withTransport.
        val reads = Regex("""transport\.value""").findAll(main).count()
        assertTrue(reads == 2 && "if (transport.isInitialized()) return then(transport.value)" in main, "transport.value read on the main thread")
        assertTrue(Regex("""withTransport \{ t ->""").findAll(main).count() == 3, "Join, Host and Resume go through withTransport")
    }

    @Test
    fun `Report Send sets up Firebase off the main thread`() {
        val send = File("src/main/java/com/mauro/wgbg/CrashReport.kt").readText().substringAfter("fun sendCrashReport(")
        assertTrue(send.indexOf("Thread {") in 0 until send.indexOf("LobbyRepository.create"), "sendCrashReport sets up Firebase on the caller's (main) thread")
    }
}

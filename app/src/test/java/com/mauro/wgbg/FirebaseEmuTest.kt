package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Online play against the Firebase emulators needs no google-services.json (0.3.4): `--es firebase emu` sets up the
 * demo project in code, and it does so before anything builds the transport. A checkout without the file played the
 * two-phone game on the in-memory transport instead, and the joiner got "Unknown room".
 */
class FirebaseEmuTest {
    private val main = File("src/main/java/com/mauro/wgbg/MainActivity.kt").readText().replace("\r\n", "\n")
    private val repo = File("src/main/java/com/mauro/wgbg/FirestoreTransport.kt").readText().replace("\r\n", "\n")

    @Test
    fun `emu mode falls back to the demo project only when the build has no Firebase config`() {
        val create = repo.substringAfter("@Synchronized fun create(context: Context): GameTransport {").substringBefore("\n    }\n")
        assertTrue("FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)\n" +
            "            ?: emulatorHost?.let { FirebaseApp.initializeApp(context, emulatorOptions) }" in create, create)
        assertTrue("db.useEmulator(emulatorHost, 8080)" in create && "auth.useEmulator(emulatorHost, 9099)" in create)
        assertEquals("demo-wgbg", LobbyRepository.EMULATOR_PROJECT)
        assertTrue("setProjectId(EMULATOR_PROJECT)" in repo)
    }

    @Test
    fun `the emulators are chosen at launch, before the splash, account or crash reporter can build the transport`() {
        val set = main.indexOf("LobbyRepository.emulatorHost = if (firebaseEmu) \"10.0.2.2\" else null")
        assertTrue(set > 0 && set < main.indexOf("AppRoot(debugCamera()"), "set before the app's screens compose")
        assertTrue("val firebaseEmu = debug && intent.getStringExtra(\"firebase\") == \"emu\"" in main, "debug builds only")
        // Break it: no caller may pick its own host, or the first transport made would ignore the emulators.
        for (f in File("src/main/java/com/mauro/wgbg").listFiles()!!) {
            val text = f.readText()
            assertFalse(Regex("""LobbyRepository\.create\([^)]*,""").containsMatchIn(text), "${f.name} passes a host to create()")
            if (f.name != "MainActivity.kt") assertFalse("LobbyRepository.emulatorHost =" in text, "${f.name} sets the host")
        }
    }

    @Test
    fun `the scripts and docs start the emulators on the same demo project`() {
        assertTrue("PROJECT=\"\${2:-demo-wgbg}\"" in File("../tools/ci_online.sh").readText())
        assertTrue("--project demo-wgbg" in File("../tools/online_sim.sh").readText())
        val docs = File("../docs/online-play.md").readText()
        assertTrue("firebase emulators:start --only firestore,auth --project demo-wgbg" in docs)
        assertFalse("emulators:start --only firestore,auth --project workinggermangame" in docs)
    }
}

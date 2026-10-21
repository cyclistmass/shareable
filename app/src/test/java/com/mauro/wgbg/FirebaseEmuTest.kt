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
        assertTrue("db.useEmulator(emulatorHost, emulatorPorts.first)" in create && "auth.useEmulator(emulatorHost, emulatorPorts.second)" in create)
        assertTrue("@Volatile var emulatorPorts: Pair<Int, Int> = 8080 to 9099" in repo)
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
    fun `a release build never uses the demo project or the emulators, and keeps its own google-services config`() {
        // The emulators are only ever chosen from a debuggable build launched with `--es firebase emu`...
        assertTrue("val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0" in main)
        assertEquals(1, Regex("""LobbyRepository\.emulatorHost = """).findAll(main).count(), "one place picks the emulators")
        assertTrue("@Volatile var emulatorHost: String? = null" in repo, "no emulators unless asked")
        // ...and release builds are not debuggable.
        val gradle = File("build.gradle.kts").readText().replace("\r\n", "\n")
        val release = gradle.substringAfter("release {").substringBefore("\n        }\n")
        assertFalse(Regex("""isDebuggable\s*=\s*true""").containsMatchIn(release), release)
        // A build's own config (google-services.json) always comes first: the demo options are only the fallback when
        // there is none AND the emulators were asked for, and only then are the emulators used.
        val create = repo.substringAfter("@Synchronized fun create(context: Context): GameTransport {").substringBefore("\n    }\n")
        val own = create.indexOf("FirebaseApp.initializeApp(context)\n")
        val demo = create.indexOf("emulatorHost?.let { FirebaseApp.initializeApp(context, emulatorOptions) }")
        assertTrue(own in 0 until demo, create)
        assertEquals(1, Regex("""emulatorOptions\b""").findAll(repo).count() - 1, "the demo options are built once and used once")
        val emu = create.substringAfter("if (emulatorHost != null) {").substringBefore("} else")
        assertTrue("db.useEmulator(emulatorHost, emulatorPorts.first)" in emu && "auth.useEmulator(emulatorHost, emulatorPorts.second)" in emu)
        assertEquals(2, Regex("""useEmulator\(""").findAll(repo).count(), "useEmulator only behind the emulatorHost check")
    }

    @Test
    fun `a fake Google sign-in (#72 sims) needs a debug build on the emulators and nothing else sets it`() {
        assertTrue("LobbyRepository.fakeGoogle = firebaseEmu && intent.getBooleanExtra(\"fakegoogle\", false)" in main, "emu mode only")
        assertTrue("@Volatile var fakeGoogle: Boolean = false" in repo, "off unless asked")
        // Even if set, only a transport on the emulators takes it.
        assertTrue("FirestoreTransport(auth, db, fakeGoogle = emulatorHost != null && fakeGoogle)" in repo, repo)
        for (f in File("src/main/java/com/mauro/wgbg").listFiles()!!) {
            if (f.name != "MainActivity.kt") assertFalse("LobbyRepository.fakeGoogle =" in f.readText(), "${f.name} sets it")
        }
        assertEquals(1, Regex("""LobbyRepository\.fakeGoogle = """).findAll(main).count())
        // The start screen offers friends and invites to it without a Google client id (sim builds have none), and to
        // nothing else: the gate reads the transport's own flag.
        val splash = File("src/main/java/com/mauro/wgbg/Splash.kt").readText()
        assertTrue(".takeIf { it.fakeGoogle || GoogleAccount.webClientId(context) != null }" in splash, "the start screen's gate")
    }

    @Test
    fun `the scripts and docs start the emulators on the same demo project`() {
        assertTrue("PROJECT=\"\${2:-demo-wgbg}\"" in File("../tools/ci_online.sh").readText())
        assertTrue("--project demo-wgbg" in File("../tools/online_sim.sh").readText())
        val docs = File("../docs/online-play.md").readText()
        assertTrue("firebase emulators:start --only firestore,auth --project demo-wgbg" in docs)
        assertFalse("emulators:start --only firestore,auth --project workinggermangame" in docs)
    }

    @Test
    fun `expired rooms (#72, no TTL) clear once per launch, off the main thread, for a player already signed in, moves first`() {
        val splash = File("src/main/java/com/mauro/wgbg/Splash.kt").readText()
        assertTrue("withContext(kotlinx.coroutines.Dispatchers.IO) { LobbyRepository.cleanExpiredOnce(context) }" in splash, "background, from the start screen")
        assertTrue("if (cleaned) return\n        cleaned = true" in repo, "once per launch")
        val clean = repo.substringAfter("override fun cleanExpired(").substringBefore("private fun deleteRoom")
        assertTrue("auth.currentUser?.uid ?: return CompletableFuture.completedFuture(emptyList())" in clean, "never signs anyone in")
        assertFalse("signIn()" in clean, "never signs anyone in")
        assertTrue("whereEqualTo(\"host\", me)" in clean && "Expired.pick(" in clean, "only their own rooms, by the shared rule")
        assertTrue("Status.playing.name" in clean && "Expired.latest(at, " in clean, "a game still being played lives as long as its last move")
        val delete = repo.substringAfter("private fun deleteRoom").substringBefore("\n    }\n")
        assertTrue(delete.indexOf("collection(\"moves\")") in 0 until delete.indexOf("ref.delete()"), "moves before the room")
    }
}

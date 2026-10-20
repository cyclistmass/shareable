package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mauro.wgbg.Splash.Action

class SplashTest {
    private fun actions(local: Boolean, room: String?, signIn: Boolean) = Splash.buttons(local, room, signIn).map { it.action }

    @Test
    fun `#132 Resume shows only with something to resume, New game and Settings always`() {
        assertEquals(listOf(Action.NEW_GAME, Action.LOG_IN, Action.SETTINGS), actions(false, null, true))
        assertEquals(listOf(Action.RESUME_GAME, Action.NEW_GAME, Action.LOG_IN, Action.SETTINGS), actions(true, null, true))
        assertEquals(listOf(Action.RESUME_GAME, Action.RESUME_ROOM, Action.NEW_GAME, Action.SETTINGS), actions(true, "OAK-12", false))
    }

    @Test
    fun `exactly one green button, the first one`() {
        for (local in listOf(true, false)) for (room in listOf(null, "OAK-12")) {
            val b = Splash.buttons(local, room, true)
            assertEquals(1, b.count { it.go }, "$local $room")
            assertTrue(b.first().go)
        }
        assertEquals("Resume online game OAK-12", Splash.buttons(false, "OAK-12", true).first().label)
    }

    @Test
    fun `Log in reads Account once signed in, and is gone without Firebase`() {
        assertEquals("Log in", Splash.buttons(false, null, true).single { it.action == Action.LOG_IN }.label)
        assertEquals("Account", Splash.buttons(false, null, true, signedIn = true).single { it.action == Action.LOG_IN }.label)
        assertTrue(Splash.buttons(false, null, false).none { it.action == Action.LOG_IN })
    }

    @Test
    fun `button tags are unique and New game never clashes with the setup screen's tags`() {
        val tags = Splash.buttons(true, "OAK-12", true).map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        assertFalse("new-game" in tags, "new-game is the setup screen's own tag")
    }

    @Test
    fun `the Ko-fi button stays hidden until a real page name is filled in`() {
        assertFalse(Splash.kofiReady("https://ko-fi.com/"))
        assertFalse(Splash.kofiReady("https://ko-fi.com/[YOUR-KOFI-NAME]"))
        assertFalse(Splash.kofiReady("https://example.com/someone"))
        assertTrue(Splash.kofiReady("https://ko-fi.com/wgbg_games"))
        assertEquals(Splash.KOFI_NAME.isNotEmpty(), Splash.kofiReady(), "the shipped constant")
    }

    @Test
    fun `credits never name the original game`() {
        val text = (Splash.CREDITS.flatMap { listOf(it.first, it.second) } + Splash.ABOUT_LINE).joinToString(" ")
        for (word in listOf("Catan", "Settlers", "Siedler", "Seafarers", "Kosmos")) assertFalse(word in text, word)
    }

    @Test
    fun `the trademark notice is Mauro's wording and the only place the app names the original game`() {
        assertEquals("An original game. Not affiliated with or endorsed by Catan GmbH or Catan Studio. " +
            "CATAN is a trademark of Catan GmbH. All names, rules text and art are our own.", Splash.TRADEMARK)
        // Every other line of app code stays clear of the name (Catanatron, the bot project, is not it).
        val name = Regex("catan(?!atron)", RegexOption.IGNORE_CASE)
        val hits = java.io.File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
            .flatMap { f -> f.readLines().mapIndexed { i, l -> "${f.name}:${i + 1}: ${l.trim()}" } }
            .filter { name.containsMatchIn(it.substringAfter(": ")) }.toList()
        assertEquals(listOf("Splash.kt"), hits.map { it.substringBefore(":") }.distinct(), hits.joinToString(" | "))
        assertTrue(hits.all { "Catan GmbH" in it || "TRADEMARK" in it || "CATAN" in it }, hits.joinToString(" | "))
    }
}

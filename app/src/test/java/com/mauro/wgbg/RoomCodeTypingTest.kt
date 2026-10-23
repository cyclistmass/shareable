package com.mauro.wgbg

import androidx.compose.ui.text.AnnotatedString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Mauro's report (2026-10-22): typing a room code should put the dash in for you, and the hint shows a real code. */
class RoomCodeTypingTest {
    private fun shown(raw: String) = RoomCodeDash.filter(AnnotatedString(raw))

    @Test
    fun `the box keeps letters and digits only, in capitals`() {
        assertEquals("REEF604", Lobby.typedCode("reef-604"))
        assertEquals("REEF604", Lobby.typedCode(" Reef 6_0.4 "))
        assertEquals("", Lobby.typedCode("-- !?"))
        assertEquals(10, Lobby.typedCode("A".repeat(40)).length)
    }

    @Test
    fun `the dash is drawn between word and number, or after a whole word`() {
        assertEquals("REEF-604", shown("REEF604").text.text)
        assertEquals("REEF-", shown("REEF").text.text)
        assertEquals("REE", shown("REE").text.text)
        assertEquals("ROVER-4", shown("ROVER4").text.text)
        assertEquals("BANANA-42", shown("BANANA42").text.text, "a wrong word still reads as typed; the hint says it's no code")
        assertEquals("42", shown("42").text.text)
        assertEquals("", shown("").text.text)
    }

    @Test
    fun `no code word starts another, so a dash never comes early`() {
        for (a in Lobby.CODE_WORDS) for (b in Lobby.CODE_WORDS) if (a != b) assertTrue(!b.startsWith(a), "$a starts $b")
    }

    @Test
    fun `fuzz - typing any code letter by letter shows it with its dash, the cursor stays mapped, and it joins`() {
        val r = Random(11)
        repeat(300) {
            val code = Lobby.newCode(r)
            var raw = ""
            for (ch in code) {
                raw = Lobby.typedCode(raw + ch)
                val t = shown(raw)
                assertTrue(code.startsWith(t.text.text), "$raw shows ${t.text.text}, not a start of $code")
                for (o in 0..raw.length) {
                    val to = t.offsetMapping.originalToTransformed(o)
                    assertTrue(to in 0..t.text.length, "$raw $o")
                    assertEquals(o, t.offsetMapping.transformedToOriginal(to), "$raw $o")
                }
                for (o in 0..t.text.length) assertTrue(t.offsetMapping.transformedToOriginal(o) in 0..raw.length, "$raw shown $o")
            }
            assertEquals(code, shown(raw).text.text)
            assertEquals(code, Lobby.normalizeCode(raw))
        }
    }

    @Test
    fun `break it - garbage never crashes and never makes a code`() {
        val r = Random(5)
        val alphabet = "aZ09 -_!é\u0000\n漢"
        repeat(2000) {
            val raw = Lobby.typedCode(String(CharArray(r.nextInt(0, 20)) { alphabet[r.nextInt(alphabet.length)] }))
            val t = shown(raw)
            assertTrue(t.text.length - raw.length in 0..1)
        }
        assertNull(Lobby.normalizeCode(Lobby.typedCode("REEF")))
    }
}

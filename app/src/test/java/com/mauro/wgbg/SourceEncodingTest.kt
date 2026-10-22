package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Every source file is UTF-8 (0.3.5: an edit script wrote "Getting online…" as a Windows-1252 byte in Splash.kt and
 * its test, so the start screen would have read "Getting online�" and the test agreed with it).
 */
class SourceEncodingTest {
    @Test
    fun `every app source and resource file is valid UTF-8`() {
        val bad = listOf("src/main", "src/test").flatMap { root ->
            java.io.File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "md") }.toList()
        }.filter { f ->
            try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(f.readBytes())); false }
            catch (_: CharacterCodingException) { true }
        }.map { it.path }
        assertEquals(emptyList<String>(), bad)
    }
}

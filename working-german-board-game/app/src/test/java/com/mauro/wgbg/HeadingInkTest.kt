package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class HeadingInkTest {
    // Pre1 hand test (0.3.5): "Join a room" was BRASS_INK (near black) straight on dark wood, nearly invisible, and so
    // was "Quick game with friends". Dark ink belongs on a brass plate only; on wood a heading takes Wood.title's
    // default BRASS_LIGHT like every other panel.
    @Test
    fun `dark brass ink is only used on a brass plate`() {
        val onWood = File("src/main/java/com/mauro/wgbg").listFiles { f -> f.name.endsWith(".kt") }!!.sorted().flatMap { f ->
            val lines = f.readText().replace("\r\n", "\n").lines()
            lines.indices.filter { i ->
                "Wood.BRASS_INK" in lines[i] && "val BRASS_INK" !in lines[i] &&
                    lines.subList(maxOf(0, i - 6), i + 1).none { "brassPlate(" in it }
            }.map { "${f.name}:${it + 1}: ${lines[it].trim()}" }
        }
        assertEquals(emptyList<String>(), onWood)
    }
}

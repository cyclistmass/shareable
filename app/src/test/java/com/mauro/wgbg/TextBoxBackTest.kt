package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Back in a text box: keyboard up, only put it away (#121); keyboard down, leave in one Back (#146). */
class TextBoxBackTest {
    private val src = File("src/main/java/com/mauro/wgbg")

    @Test
    fun `Back puts an open keyboard away and otherwise goes on to the screen`() {
        assertEquals(TextBoxBack.PUT_KEYBOARD_AWAY, textBoxBack(keyboardUp = true))
        assertEquals(TextBoxBack.PASS_ON, textBoxBack(keyboardUp = false))
    }

    @Test
    fun `the helper asks the window whether the keyboard is up, not Compose's inset`() {
        val helper = File(src, "NewGameScreen.kt").readText().substringAfter("fun Modifier.backPutsKeyboardAway()").substringBefore("\n}\n")
        // The inset could still read "up" after the keyboard went (Android 14), costing an extra Back (#146).
        assertTrue("WindowInsets.ime" !in helper, "the IME inset lags the keyboard")
        assertTrue("isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())" in helper)
        // With the keyboard down Back is passed on, never spent letting go of the box.
        assertTrue("onBackPressed()" in helper)
        // Back is taken as a key on the box: a callback added here sat under the screen's BackHandler and never ran,
        // and a Back left to Compose became an "exit focus" move that ate the key after a hardware Enter (#146).
        assertTrue("onPreviewKeyEvent" in helper && "Key.Back" in helper)
        assertTrue("OnBackPressedCallback" !in helper && "BackHandler" !in helper)
        // Focus is never dropped just because the keyboard hid (the Join screen regression after a hardware Enter).
        assertTrue("LaunchedEffect" !in helper)
    }

    @Test
    fun `every text box uses the helper`() {
        val boxes = src.listFiles()!!.filter { it.extension == "kt" }.sumOf { f ->
            Regex("""BasicTextField\(""").findAll(f.readText()).count()
        }
        val helped = src.listFiles()!!.filter { it.extension == "kt" }.sumOf { f ->
            f.readLines().count { ".backPutsKeyboardAway()" in it && "fun " !in it }
        }
        assertEquals(2, boxes, "the parchment one-liner and the Report note; a new box needs the helper too")
        assertEquals(boxes, helped, "a text box without backPutsKeyboardAway()")
    }
}

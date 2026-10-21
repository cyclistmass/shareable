package com.mauro.wgbg

import android.content.pm.ApplicationInfo
import androidx.compose.ui.platform.ViewRootForTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The WGBG-UI screen line (Fable study #4): what a test driver reads instead of a 2-15 s uiautomator dump. */
class UiLineTest {
    @AfterEach fun reset() = UiLine.uninstallForTest()

    private fun node(text: String = "", tag: String = "", desc: String = "", click: Boolean = false, edit: Boolean = false,
                     l: Int = 0, t: Int = 0, r: Int = 10, b: Int = 10) = UiLine.Node(text, tag, desc, click, edit, l, t, r, b)

    @Test
    fun `a release build hooks nothing, so it never logs a line or answers for the screen`() {
        assertFalse(UiLine.enabled(0))
        assertFalse(UiLine.install(0))
        assertNull(ViewRootForTest.onViewCreatedCallback, "no Compose root is watched")
        assertTrue(UiLine.enabled(ApplicationInfo.FLAG_DEBUGGABLE))
        assertTrue(UiLine.install(ApplicationInfo.FLAG_DEBUGGABLE))
        assertNotNull(ViewRootForTest.onViewCreatedCallback)
        assertFalse(UiLine.install(ApplicationInfo.FLAG_DEBUGGABLE), "once a process: a recreated activity doesn't chain it twice")
    }

    @Test
    fun `a node reads like uiautomator's, so the scripts' greps work unchanged`() {
        val x = UiLine.xml(node(text = "have 2\n3:1", tag = "tab-Players", desc = "Timber", click = true, l = 100, t = 200, r = 300, b = 420))
        assertEquals("<node text=\"have 2&#10;3:1\" resource-id=\"tab-Players\" class=\"android.view.View\" content-desc=\"Timber\" " +
            "clickable=\"true\" scrollable=\"false\" bounds=\"[100,200][300,420]\" />", x)
        // ci_trade's cards(): the "have N" label, its rate after &#10;, and the x span from bounds.
        val m = Regex(".*text=\"have ([0-9]*)(&#10;([0-9]):1)?\".*bounds=\"\\[([0-9]*),[0-9]*\\]\\[([0-9]*),[0-9]*\\]\".*").matchEntire(x)
        assertEquals(listOf("2", "3", "100", "300"), m!!.groupValues.let { listOf(it[1], it[3], it[4], it[5]) })
        assertTrue("class=\"android.widget.EditText\"" in UiLine.xml(node(edit = true)), "ci_reach finds the code box by its class")
        assertTrue("scrollable=\"true\"" in UiLine.xml(UiLine.Node("", "", "", false, false, 0, 0, 9, 9, scrollable = true)), "ci_reach scrolls the panels marked so")
        // The window goes first, as uiautomator's root does: ci_update's screen_size greps its [0,0] bounds.
        val w = UiLine.xml(UiLine.Node("", "", "", false, false, 0, 0, 2400, 1080, window = true))
        assertTrue("class=\"android.widget.FrameLayout\"" in w && "bounds=\"[0,0][2400,1080]\"" in w, w)
        assertEquals("screen=- sheets=- items=", UiLine.line("", listOf(UiLine.Node("", "", "", false, false, 0, 0, 9, 9, window = true))), "not a tappable item")
    }

    @Test
    fun `quotes, angle brackets and ampersands are escaped the way uiautomator does`() {
        assertEquals("say &quot;hi&quot; &lt;b&gt; &amp; go", UiLine.esc("say \"hi\" <b> & go"))
        val doc = UiLine.document("GAME", listOf(node(text = "a"), node(text = "b")))
        assertTrue(doc.startsWith("<hierarchy screen=\"GAME\">\n"))
        assertTrue(doc.endsWith("</hierarchy>\n"), "the closing line tells a reader the file is whole")
        assertEquals(4, doc.lines().count { it.isNotEmpty() })
    }

    @Test
    fun `the line has the screen, open sheets and every tagged or clickable node at its centre`() {
        val l = UiLine.line("GAME", listOf(
            node(tag = "trade-panel", l = 0, t = 0, r = 100, b = 50),
            node(text = "Players", tag = "tab-Players", click = true, l = 10, t = 10, r = 30, b = 20),
            node(text = "Elizabeth I ⌛", click = true, l = 40, t = 10, r = 80, b = 20),
            node(text = "just a label"),
            node(desc = "Trade", click = true, l = 90, t = 90, r = 110, b = 110)))
        assertEquals("screen=GAME sheets=trade-panel items=trade-panel|@50,25;tab-Players|Players@20,15;|Elizabeth I ⌛@60,15;|Trade@100,100", l)
        // A button's text sits on its child (the unmerged tree, as accessibility lists it): the line shows the children's.
        val roll = UiLine.Node("", "roll", "", true, false, 0, 0, 10, 10, label = "Roll")
        assertEquals("screen=GAME sheets=- items=roll|Roll@5,5", UiLine.line("GAME", listOf(roll)))
    }

    @Test
    fun `a long screen is cut to fit one logcat line and says how many were left out`() {
        val many = List(400) { node(text = "Button number $it", click = true) }
        val l = UiLine.line("GAME", many)
        assertTrue(l.length <= 3800, "${l.length}")
        val shown = l.substringAfter("items=").substringBefore(" +").split(';').size
        assertTrue(l.endsWith(" +${400 - shown} more"), l.takeLast(40))
        // Separators inside a label can't break the parse: ';' splits items, newlines would split the log line.
        assertEquals("screen=- sheets=- items=|a,b c@5,5", UiLine.line("", listOf(node(text = "a;b\nc", click = true))))
    }
}

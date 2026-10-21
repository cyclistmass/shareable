package com.mauro.wgbg

import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import java.lang.ref.WeakReference

/**
 * Debug builds only: what's on screen, for test drivers, without `uiautomator dump` (2-4.5 s a look, about 15 s
 * over the moving board). Two ways out:
 * - a `WGBG-UI` logcat line each time the tappable things on screen change (debounced): the screen, the open
 *   sheets, and every tagged or clickable node as `tag|text@x,y` (screen px, the node's centre);
 * - on `am broadcast -a com.mauro.wgbg.UI -p com.mauro.wgbg`, the whole screen written to `files/ui.txt` in
 *   uiautomator's own attributes (text, resource-id, class, content-desc, clickable, bounds), a node a line, so
 *   `tools/lib/uiread.sh` hands it to the scripts' greps unchanged. That one is too big for logcat: lines stop at
 *   4 KB, and a dump per look would push the `WGBG-TURN` lines the scripts count out of the ring buffer.
 * Like uiautomator, it lists the focused window (a dialog over the game) and leaves out what's covered.
 * Release builds install nothing and log nothing.
 */
object UiLine {
    const val TAG = "WGBG-UI"
    const val ACTION = "com.mauro.wgbg.UI"
    const val FILE = "ui.txt"

    /** The screen being shown (MainActivity's `Screen`), for the line. */
    @Volatile var screen = ""

    fun enabled(appFlags: Int) = appFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** One thing on screen, its visible part in screen px. */
    data class Node(val text: String, val tag: String, val desc: String, val clickable: Boolean, val editable: Boolean,
                    val l: Int, val t: Int, val r: Int, val b: Int, val scrollable: Boolean = false) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '"' -> append("&quot;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '\n' -> append("&#10;"); else -> append(c)
        }
    }

    /** A node as uiautomator writes it, so `grep 'text="Trade"'`, `resource-id=`, `class=` and `bounds=` read it as before. */
    fun xml(n: Node) = "<node text=\"${esc(n.text)}\" resource-id=\"${esc(n.tag)}\" " +
        "class=\"${if (n.editable) "android.widget.EditText" else "android.view.View"}\" content-desc=\"${esc(n.desc)}\" " +
        "clickable=\"${n.clickable}\" scrollable=\"${n.scrollable}\" bounds=\"[${n.l},${n.t}][${n.r},${n.b}]\" />"

    /** The whole screen for `files/ui.txt`; the closing line tells a reader it's complete. */
    fun document(screen: String, nodes: List<Node>) =
        "<hierarchy screen=\"${esc(screen)}\">\n" + nodes.joinToString("") { xml(it) + "\n" } + "</hierarchy>\n"

    private val SHEET = Regex("sheet|panel|choice|dialog")

    /** The logcat line: the screen, the open sheets, then every tagged or clickable node, cut to fit logcat. */
    fun line(screen: String, nodes: List<Node>, max: Int = 3800): String {
        val sheets = nodes.map { it.tag }.filter { SHEET.containsMatchIn(it) }.distinct().joinToString(",").ifEmpty { "-" }
        val sb = StringBuilder("screen=${screen.ifEmpty { "-" }} sheets=$sheets items=")
        val items = nodes.filter { it.clickable || it.tag.isNotEmpty() }
        items.forEachIndexed { i, n ->
            val item = "${n.tag}|${n.text.ifEmpty { n.desc }.replace('\n', ' ').replace(';', ',')}@${n.cx},${n.cy}"
            if (sb.length + item.length + 1 > max) { sb.append(" +${items.size - i} more"); return sb.toString() }
            if (i > 0) sb.append(';')
            sb.append(item)
        }
        return sb.toString()
    }

    // ---- Android side (debug builds only; nothing below runs unless install() saw a debuggable app) ----

    private val roots = mutableListOf<WeakReference<ViewRootForTest>>()
    private var hooked = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var pending = false
    private var last = ""
    private val check = Runnable {
        pending = false
        val l = runCatching { line(screen, snapshot()) }.getOrNull() ?: return@Runnable
        if (l != last) { last = l; android.util.Log.i(TAG, l) }
    }

    /** Before setContent: every Compose root from now on (the activity's, each dialog's) is watched. */
    fun install(activity: android.app.Activity) { install(activity.applicationInfo.flags) }

    /** True if it hooked in now: a debuggable app, the first time. A release build hooks nothing, so nothing is ever logged. */
    internal fun install(appFlags: Int): Boolean {
        if (!enabled(appFlags) || hooked) return false
        hooked = true
        val before = ViewRootForTest.onViewCreatedCallback   // a Compose test rule's, if one is running
        ViewRootForTest.onViewCreatedCallback = { root -> before?.invoke(root); watch(root) }
        return true
    }

    internal fun uninstallForTest() { hooked = false; ViewRootForTest.onViewCreatedCallback = null }

    private fun watch(root: ViewRootForTest) {
        roots.removeAll { it.get() == null }
        roots += WeakReference(root)
        // A redraw may be a new screen: look again a little later (the board redraws all the time, so at most every 400 ms).
        root.view.viewTreeObserver.addOnDrawListener { if (!pending) { pending = true; main.postDelayed(check, 400) } }
    }

    /** Writes the whole screen to files/ui.txt (the broadcast's answer). Main thread. */
    fun write(dir: File) {
        val f = File(dir, FILE)
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(document(screen, snapshot()))
        tmp.renameTo(f)
    }

    /** What's on screen now: the focused window's roots (else all shown), uncovered nodes in tree order. */
    fun snapshot(): List<Node> {
        val live = roots.mapNotNull { it.get() }.filter { it.view.isAttachedToWindow && it.view.isShown }
        val use = live.filter { it.view.hasWindowFocus() }.ifEmpty { live }
        return use.flatMap { nodes(it) }
    }

    private fun important(n: SemanticsNode): Boolean {
        val c = n.config
        return c.isMergingSemanticsOfDescendants || SemanticsProperties.Text in c || SemanticsProperties.ContentDescription in c ||
            SemanticsProperties.EditableText in c || SemanticsActions.OnClick in c
    }

    /**
     * The merged tree, as accessibility (and so uiautomator) sees it. Topmost first, each node keeps the part of it no
     * node drawn above covers, the way Compose's accessibility does; then they're listed in tree order.
     */
    private fun nodes(root: ViewRootForTest): List<Node> {
        val top = root.semanticsOwner.rootSemanticsNode
        val at = IntArray(2).also { root.view.getLocationOnScreen(it) }
        val shown = HashMap<Int, Rect>()
        val free = Region(0, 0, root.view.width, root.view.height)
        fun uncover(n: SemanticsNode) {
            if (free.isEmpty && n.id != top.id) return
            if (!n.layoutInfo.isPlaced || !n.layoutInfo.isAttached) return
            val b = n.boundsInRoot
            val rect = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            val part = Region(rect)
            if (!part.op(free, Region.Op.INTERSECT)) return
            shown[n.id] = part.bounds
            for (c in n.children.asReversed()) uncover(c)
            if (important(n)) free.op(rect, Region.Op.DIFFERENCE)
        }
        uncover(top)
        val out = ArrayList<Node>()
        fun list(n: SemanticsNode) {
            val r = shown[n.id] ?: return
            val c = n.config
            val text = (c.getOrNull(SemanticsProperties.EditableText)?.text
                ?: c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }).orEmpty()
            val tag = c.getOrNull(SemanticsProperties.TestTag).orEmpty()
            val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            val click = SemanticsActions.OnClick in c
            val edit = SemanticsProperties.EditableText in c
            // Scrollable the way accessibility says so: it can scroll, and there's somewhere to go (ci_reach scrolls these).
            val scroll = SemanticsActions.ScrollBy in c && listOf(SemanticsProperties.VerticalScrollAxisRange, SemanticsProperties.HorizontalScrollAxisRange)
                .any { k -> c.getOrNull(k)?.let { it.maxValue() > 0f } == true }
            if (text.isNotEmpty() || tag.isNotEmpty() || desc.isNotEmpty() || click || edit || scroll)
                out += Node(text, tag, desc, click, edit, r.left + at[0], r.top + at[1], r.right + at[0], r.bottom + at[1], scroll)
            for (ch in n.children) list(ch)
        }
        list(top)
        return out
    }
}

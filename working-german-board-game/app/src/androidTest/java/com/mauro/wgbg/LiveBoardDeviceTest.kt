package com.mauro.wgbg

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.FileInputStream
import kotlin.math.*

/** Actual input events against the live view: native surface, Compose overlay, and two-pointer gestures. */
class LiveBoardDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    private fun log(tag: String): String = instrument.uiAutomation.executeShellCommand("logcat -d -s $tag:I").use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }

    private fun waitFor(label: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }

    private fun gesture(two: Boolean, start: (Int) -> Pair<Float, Float>, end: (Int) -> Pair<Float, Float>) {
        val count = if (two) 2 else 1
        val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float, pointers: Int = count) {
            val coordinates = Array(pointers) { i ->
                val a = start(i); val b = end(i)
                MotionEvent.PointerCoords().apply { x = a.first + (b.first - a.first) * fraction; y = a.second + (b.second - a.second) * fraction; pressure = 1f; size = .1f }
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pointers, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            assertTrue(instrument.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f, 1)
        if (two) send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f)
        for (step in 1..30) { send(MotionEvent.ACTION_MOVE, step / 30f); Thread.sleep(15) }
        if (two) send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 1f)
        send(MotionEvent.ACTION_UP, 1f, 1)
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(instrument.uiAutomation.injectInputEvent(event, true)); event.recycle()
            Thread.sleep(40)
        }
        Thread.sleep(650) // Let detectTapGestures finish its double-tap window.
    }

    /** The newest `spots=` line once it has stayed the same for [quiet] ms: the camera settles after a gesture, slowly on CI. */
    private fun settledSpots(quiet: Long = 1_000): String {
        var last = ""; var since = SystemClock.uptimeMillis()
        val deadline = since + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val now = log("WGBG-TURN").lineSequence().lastOrNull { it.contains("spots=") } ?: ""
            if (now != last) { last = now; since = SystemClock.uptimeMillis() }
            else if (now.isNotEmpty() && SystemClock.uptimeMillis() - since >= quiet) return now
            Thread.sleep(100)
        }
        fail("Timed out: spots settle"); return last
    }

    private fun phaseLine() = log("WGBG-TURN").lineSequence().last { it.contains("phase=") }

    /** Taps a [type] spot twice (pick, confirm) until [placed]; each try re-reads settled spots and moves to the next one. */
    private fun placeByTaps(type: String, label: String, inView: (Float, Float) -> Boolean = { _, _ -> true }, placed: () -> Boolean) {
        repeat(4) { attempt ->
            // Right after the previous placement the settled line can still be the old one (no $type yet) on a slow
            // CI emulator: wait for the new moment's spots instead of failing at once.
            // Spots off the edge come out negative or past the screen (after the twist and pan, the new homestead's
            // roads can lie there): drag the board to bring one in, the way a player would.
            val w = context.resources.displayMetrics.widthPixels.toFloat(); val h = context.resources.displayMetrics.heightPixels.toFloat()
            var line = ""
            fun all() = Regex("$type:[0-9]+@(-?[0-9]+),(-?[0-9]+)").findAll(settledSpots().also { line = it })
                .map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }.toList()
            fun read() = all().filter { (x, y) -> x in w * .05f..w * .95f && y in h * .1f..h * .9f && inView(x, y) }
            var spots = read()
            val wait = SystemClock.uptimeMillis() + 15_000
            var nudges = 0
            while (spots.isEmpty() && SystemClock.uptimeMillis() < wait) {
                val off = all().firstOrNull()
                if (off != null) {
                    val (sx, sy) = off
                    val dx = (w / 2 - sx).coerceIn(-w / 3, w / 3); val dy = (h / 2 - sy).coerceIn(-h / 3, h / 3)
                    gesture(false, { w / 2 - dx / 2 to h / 2 - dy / 2 }, { w / 2 + dx / 2 to h / 2 + dy / 2 })
                } else if (++nudges % 3 == 0) {
                    // No $type spots logged at all (CI once kept the homestead's line after the road moment began):
                    // a small pan moves the camera, which logs the moment again with fresh spots.
                    gesture(false, { w / 2 to h / 2 }, { w / 2 + 30f to h / 2 })
                }
                Thread.sleep(300); spots = read()
            }
            assertTrue("no $type spot on screen: ${line.take(400)} | now: ${phaseLine().take(200)}", spots.isNotEmpty())
            val (x, y) = spots[attempt % spots.size]
            tap(x, y); tap(x, y)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) { if (placed()) return; Thread.sleep(100) }
        }
        fail("Timed out: $label")
    }

    @Test fun twistPinchTiltPanAndPlacementOnLiveSurface() {
        context.getSharedPreferences("wgbg-settings", 0).edit().putFloat(EnvSettings.LIVE_BOARD, 1f)
            .putFloat(EnvSettings.DENSITY, 0f).putFloat(EnvSettings.THEME, 0f).commit()
        context.cacheDir.resolve("live-board-frames.jsonl").delete()
        instrument.uiAutomation.executeShellCommand("logcat -c").use { FileInputStream(it.fileDescriptor).readBytes() }
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("start", "base").putExtra("tilt", .48f))
        // The perf line comes after a 5 s window of frames; a cold start on an emulator can take most of 15 s first.
        waitFor("live board frame", 30_000) { log("WGBG-3D-PERF").contains("rotation=0.0") }
        val display = context.resources.displayMetrics
        val cx = display.widthPixels * .70f; val cy = display.heightPixels * .48f; val radius = display.heightPixels * .12f
        gesture(true, { i -> (cx + if (i == 0) -radius else radius) to cy },
            { i -> cx to (cy + if (i == 0) -radius else radius) })
        waitFor("90 degree twist") {
            val rotations = Regex("rotation=([-0-9.]+)").findAll(log("WGBG-3D-PERF")).map { it.groupValues[1].toFloat() }.toList()
            rotations.any { abs(it) in 1.0f..1.8f }
        }
        gesture(true, { i -> (cx + if (i == 0) -radius else radius) to cy },
            { i -> (cx + if (i == 0) -radius * 1.3f else radius * 1.3f) to cy })
        gesture(true, { i -> (cx + if (i == 0) -radius else radius) to cy },
            { i -> (cx + if (i == 0) -radius else radius) to (cy - radius * .5f) })
        gesture(false, { cx to cy }, { cx - 60f to cy + 20f })
        Thread.sleep(800)
        // AgentHooks supplies screen positions only; placement and confirmation use real pointer events.
        placeByTaps("BUILD_SETTLEMENT", "Homestead placed with taps", { x, y ->
            x in display.widthPixels * .4f..display.widthPixels * .90f && y in display.heightPixels * .15f..display.heightPixels * .85f
        }) { phaseLine().contains("phase=BUILD_INITIAL_ROAD") }
        val roadLine = phaseLine()
        placeByTaps("BUILD_ROAD", "road placed with taps") { phaseLine() != roadLine }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        context.cacheDir.resolve("live-device-after-twist.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue(context.getSharedPreferences("wgbg-settings", 0).getFloat(EnvSettings.LIVE_BOARD, 0f) > .5f)
        // The view must resume after BOTH a fallback switch and a stopped native surface.
        fun switch(live: Boolean) = context.sendBroadcast(Intent("com.mauro.wgbg.FILAMENT_VIEW").setPackage(context.packageName).putExtra("live", live))
        switch(false); Thread.sleep(800)
        context.cacheDir.resolve("live-board-frames.jsonl").delete()
        switch(true)
        waitFor("rendering resumes after sprite fallback") { context.cacheDir.resolve("live-board-frames.jsonl").exists() }
        instrument.runOnMainSync { activity.moveTaskToBack(true) }
        Thread.sleep(700)
        context.cacheDir.resolve("live-board-frames.jsonl").delete()
        instrument.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        waitFor("rendering resumes after backgrounding") { context.cacheDir.resolve("live-board-frames.jsonl").exists() }
    }
}

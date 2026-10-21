package com.mauro.wgbg

import androidx.compose.foundation.layout.displayCutoutPadding
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wgbg.engine.GameState

// ------------------------------------------------------------------ #133 card tester (debug builds): the screens

/** This session's results and fail screenshots (kept while the app runs; sent as one report). */
private object CardTestSession {
    val results = mutableStateListOf<CardResult>()
    val shots = mutableMapOf<String, Bitmap>()
}

/** The phone's checklist: scenario id -> "P|note" / "F|note", kept across restarts. */
private class CardChecklist(context: Context) {
    private val prefs = context.getSharedPreferences("card-tester", Context.MODE_PRIVATE)
    fun load(): Map<String, Pair<CheckState, String>> = CardTester.scenarios.associate { it.id to CardTester.decodeEntry(prefs.getString(it.id, null)) }
    fun put(id: String, pass: Boolean, note: String) = prefs.edit().putString(id, CardTester.encodeEntry(pass, note)).apply()
    fun clear() = prefs.edit().clear().apply()
}

/**
 * The card tester (#133), opened from the game-start screen in debug builds: the checklist, and each scenario played as
 * a local game from its prepared position with the test panel over it. Back (or Back to start) returns to game start.
 */
@Composable
fun CardTesterRoot(name: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val checklist = remember { CardChecklist(context.applicationContext) }
    var marks by remember { mutableStateOf(checklist.load()) }
    var running by remember { mutableStateOf<Pair<CardScenario, Prepared>?>(null) }
    var problem by remember { mutableStateOf("") }
    val run = running
    if (run != null) {
        val (sc, prepared) = run
        WatchGameScreen(Modifier.fillMaxSize().background(Color.Black), Camera.FIT, CardTester.setupFor(prepared.state, name),
            onExit = { running = null }, prepared = prepared.state,
            overlay = { s, tick ->
                CardTestPanel(sc, prepared, s, tick) { pass, note, rows, shot ->
                    CardTestSession.results.removeAll { it.id == sc.id }
                    CardTestSession.results += CardResult(sc.id, pass, note, rows, System.currentTimeMillis())
                    if (shot != null && !pass) CardTestSession.shots[sc.id] = shot else CardTestSession.shots.remove(sc.id)
                    checklist.put(sc.id, pass, note)
                    marks = checklist.load()
                    running = null
                }
            })
        return
    }
    BackHandler { onExit() }
    CardChecklistScreen(marks, problem, onPick = { sc ->
        problem = ""
        runCatching { sc.build() }.onSuccess { running = sc to it }.onFailure { problem = "${sc.id} couldn't be set up: ${it.message}" }
    }, onReset = { checklist.clear(); CardTestSession.results.clear(); CardTestSession.shots.clear(); marks = checklist.load() }, onExit = onExit)
}

@Composable
private fun CardChecklistScreen(marks: Map<String, Pair<CheckState, String>>, problem: String, onPick: (CardScenario) -> Unit,
                                onReset: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val states = marks.mapValues { it.value.first }
    val version = appVersion()
    fun report(): String {
        val (device, screen) = Feedback.device(context)
        return CardTester.report(CardTestSession.results.toList(), states, version, appVersionCode(context), device, screen, System.currentTimeMillis())
    }
    Column(Modifier.fillMaxSize().tableTop().displayCutoutPadding().padding(16.dp).testTag("card-tester"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Card tester", style = Wood.title(26.sp))
            Text(CardTester.progressLine(states), style = Wood.sans(14.sp, color = Wood.INK_SOFT), modifier = Modifier.testTag("card-test-progress"))
            Spacer(Modifier.weight(1f))
            val session = CardTestSession.results.size
            // Send goes like an in-app bug report: one per session, the fails' screenshots tiled into its two pictures.
            GoButton(if (session == 0) "Send report" else "Send report ($session)", "card-test-send", enabled = session > 0, Modifier.height(44.dp)) {
                status = "Sending…"
                val text = report()
                val fails = CardTestSession.results.filter { !it.pass }.map { CardTestSession.shots[it.id] }
                scope.launch {
                    val pics = withContext(Dispatchers.Default) {
                        CardTester.shotPlan(fails.size).mapNotNull { sheet -> contactSheet(sheet.map { fails[it] })?.let { Screenshot.jpegBase64(it) } }
                    }
                    sendCrashReport(context, text, pics) { ok, n ->
                        status = when {
                            !ok -> "Couldn't send (offline?); try Share"
                            n < pics.size -> "Sent (a picture didn't go)"
                            else -> "Sent, thank you"
                        }
                        if (ok) { CardTestSession.results.clear(); CardTestSession.shots.clear() }
                    }
                }
            }
            BrassButton("Share", "card-test-share", Modifier.height(44.dp)) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "WGBG card test report").putExtra(Intent.EXTRA_TEXT, report())
                runCatching { context.startActivity(Intent.createChooser(send, "Share the report")) }
            }
            if (confirmReset) StopButton("Clear all marks?", "card-test-reset-yes") { confirmReset = false; onReset() }
            else WoodButton("Reset", "card-test-reset", Modifier.height(44.dp)) { confirmReset = true }
            WoodButton("Back", "card-test-back", Modifier.height(44.dp)) { onExit() }
        }
        Text("Pick a card and a mode: the game opens at a position set up for it. Play it by taps, compare what should " +
            "happen with what did, then Pass or Fail (Fail keeps a screenshot).", style = Wood.sans(13.sp, color = Wood.INK_SOFT))
        if (status.isNotEmpty()) Text(status, style = Wood.sans(13.sp, color = Wood.WAITING), modifier = Modifier.testTag("card-test-status"))
        if (problem.isNotEmpty()) Text(problem, style = Wood.sans(13.sp, color = Color(0xFFFFB4A0)))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).brassEdgedWood().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((title, chivalry) in listOf("Base game: Fortune cards" to false, "Age of Chivalry: progress cards" to true)) {
                item { Text(title, style = Wood.title(18.sp), modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                items(CardTester.scenarios.filter { it.chivalry == chivalry }, key = { it.id }) { sc ->
                    val (state, note) = marks[sc.id] ?: (CheckState.LEFT to "")
                    Row(Modifier.fillMaxWidth().background(Wood.WOOD_DARK.copy(alpha = 0.6f)).clickable { onPick(sc) }
                        .padding(horizontal = 10.dp, vertical = 6.dp).testTag("card-test-${sc.id}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val (mark, color) = when (state) {
                            CheckState.PASSED -> "✓" to Color(0xFF8BD18B)
                            CheckState.FAILED -> "✗" to Color(0xFFFF8A7A)
                            CheckState.LEFT -> "○" to Wood.INK_SOFT
                        }
                        Text(mark, style = Wood.sans(18.sp, FontWeight.Bold, color), modifier = Modifier.width(22.dp))
                        Text(sc.card, style = Wood.sans(15.sp, FontWeight.Bold), modifier = Modifier.width(150.dp))
                        Text(sc.mode, style = Wood.sans(14.sp), modifier = Modifier.weight(1f))
                        if (note.isNotBlank()) Text(note, style = Wood.sans(12.sp, color = Wood.INK_SOFT), maxLines = 1, modifier = Modifier.widthIn(max = 220.dp))
                    }
                }
            }
        }
    }
}

/**
 * The test panel over the game: the card and mode, what to do, what should happen, and each fact's start / should /
 * now (now follows the game while it's still your turn, then stays put), with Pass, Fail and a note.
 */
@Composable
private fun BoxScope.CardTestPanel(sc: CardScenario, prepared: Prepared, s: GameState, tick: Int,
                                   onJudge: (pass: Boolean, note: String, rows: List<FactRow>, shot: Bitmap?) -> Unit) {
    val activity = LocalContext.current as? Activity
    var open by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(prepared.rows(prepared.state)) }
    val startTurn = prepared.state.turn
    LaunchedEffect(tick) { if (s.turn == startTurn && s.currentPlayer == 0) rows = prepared.rows(s) }
    if (!open) {
        WoodButton("Card test ▸", "card-test-show", Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { open = true }
        return
    }
    // Under your seat's panel, top left; Pass / Fail / Hide stay on top, the rest scrolls on a short screen.
    Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 64.dp, bottom = 8.dp).width(310.dp)
        .brassEdgedWood().padding(10.dp).testTag("card-test-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        GoButton("Pass", "card-test-pass", enabled = !busy, Modifier.height(40.dp).weight(1f)) { onJudge(true, note, rows, null) }
        StopButton("Fail", "card-test-fail", Modifier.height(40.dp).weight(1f)) {
            if (busy) return@StopButton
            busy = true
            // The screenshot shows the board and this panel as they stand.
            if (activity == null) onJudge(false, note, rows, null) else Screenshot.capture(activity) { onJudge(false, note, rows, it) }
        }
        WoodButton("Hide", "card-test-hide") { open = false }
      }
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(sc.card, style = Wood.title(20.sp))
        Text(sc.mode, style = Wood.sans(13.sp, FontWeight.SemiBold, Wood.BRASS_LIGHT))
        Text("Do: ${prepared.howTo}", style = Wood.sans(13.sp))
        Text("Should: ${prepared.expect}", style = Wood.sans(13.sp, color = Wood.INK_SOFT))
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.weight(1f))
            for (h in listOf("start", "should", "now")) Text(h, style = Wood.sans(11.sp, color = Wood.INK_SOFT), modifier = Modifier.width(44.dp))
        }
        for (r in rows) Row(Modifier.fillMaxWidth().testTag("card-test-fact"), verticalAlignment = Alignment.CenterVertically) {
            Text(r.label, style = Wood.sans(12.sp), modifier = Modifier.weight(1f))
            Text("${r.before}", style = Wood.sans(13.sp), modifier = Modifier.width(44.dp))
            Text("${r.expected}", style = Wood.sans(13.sp, FontWeight.Bold), modifier = Modifier.width(44.dp))
            Text("${r.actual}", style = Wood.sans(13.sp, FontWeight.Bold, if (r.ok) Color(0xFF8BD18B) else Color(0xFFFFB46A)), modifier = Modifier.width(44.dp))
        }
        Text(if (rows.all { it.ok }) "Every number matches." else "Numbers that differ show orange.", style = Wood.sans(11.sp, color = Wood.INK_SOFT))
        Text("Note (optional), sent with the result:", style = Wood.sans(11.sp, color = Wood.INK_SOFT))
        ParchmentField(note, { note = it }, "card-test-note", KeyboardCapitalization.Sentences)
      }
    }
}

/** Fail screenshots tiled into one picture (numbered in report order), for the report's two picture slots. */
private fun contactSheet(shots: List<Bitmap?>): Bitmap? {
    // A fail whose capture failed keeps its (black) tile, so tile numbers match the report.
    val pics = shots
    val first = pics.firstOrNull { it != null } ?: return null
    if (pics.size == 1) return first
    val (cols, rowsN) = CardTester.grid(pics.size)
    val tileW = Screenshot.MAX_EDGE / cols
    val tileH = tileW * first.height / first.width.coerceAtLeast(1)
    val out = Bitmap.createBitmap(tileW * cols, tileH * rowsN, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.BLACK)
    val paint = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; textSize = tileH / 8f; isFakeBoldText = true }
    pics.forEachIndexed { i, b ->
        val x = (i % cols) * tileW; val y = (i / cols) * tileH
        if (b != null) c.drawBitmap(b, null, android.graphics.Rect(x, y, x + tileW, y + tileH), null)
        c.drawText("${i + 1}", x + tileW / 30f, y + paint.textSize, paint)
    }
    return out
}

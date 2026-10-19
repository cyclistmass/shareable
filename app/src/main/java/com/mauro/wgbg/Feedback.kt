package com.mauro.wgbg

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bug reports and ideas from players (Mauro, 2026-10-12: "a button to make a suggestion or bug report … on the main
 * landing page menu as well", "save game report with game state and history", "bonus if it took a voice report").
 * From the game menu the report carries the whole game (setup, seed and every move: it replays to the exact
 * position) and the turn history. It goes to Firestore `crashes` like a crash report, or out through Share.
 */
object Feedback {
    enum class Kind(val label: String) { BUG("Bug"), IDEA("Idea") }

    /** The report text. [game] is the replayable game (a [LocalSave] encoding), [history] what the history panel shows. */
    fun format(kind: Kind, note: String, version: String, versionCode: Long, device: String, screen: String,
               game: String?, history: List<String>, position: String?, time: Long): String = buildString {
        appendLine("WGBG ${if (kind == Kind.BUG) "bug report" else "idea"}")
        appendLine("version: $version ($versionCode)")
        appendLine("device: $device")
        appendLine("screen: $screen")
        appendLine("time: ${java.time.Instant.ofEpochMilli(time)}")
        appendLine("note:")
        note.trim().ifEmpty { "(none)" }.lineSequence().forEach { appendLine("  $it") }
        if (position != null) { appendLine("position:"); appendLine("  $position") }
        if (game != null) { appendLine("game (replays move by move; same format as the local save):"); appendLine(game) }
        if (history.isNotEmpty()) { appendLine("history (oldest first):"); history.forEach { appendLine("  $it") } }
    }.trimEnd() + "\n"

    /**
     * [format], fitted to [max] characters (Firestore's limit): the oldest history lines go first, so the note, the
     * position and the replayable game always survive; only a giant note or game is cut at the end.
     */
    fun formatFitted(max: Int, kind: Kind, note: String, version: String, versionCode: Long, device: String, screen: String,
                     game: String?, history: List<String>, position: String?, time: Long): String {
        var keep = history
        while (true) {
            val r = format(kind, note, version, versionCode, device, screen, game, keep, position, time)
            if (r.length <= max) return r
            if (keep.isEmpty()) return r.take(max - 12) + "\n[truncated]"
            keep = keep.drop(maxOf(1, keep.size / 4))
        }
    }

    const val UPLOAD_MAX = 190_000 // under the rules' 200000: a whole 300-turn game replays (was 19 000, cut at ~250 turns)

    fun device(context: Context): Pair<String, String> {
        val dm = context.resources.displayMetrics
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" to
            "${dm.widthPixels}x${dm.heightPixels} px, density ${dm.density}"
    }

    /** The history panel as text, oldest first: a line per turn, then its events. [reports] are newest first. */
    fun historyLines(reports: List<TurnReport>, names: List<String>): List<String> = reports.asReversed().flatMap { r ->
        val who = names.getOrElse(r.player) { "Player ${r.player + 1}" }
        listOf("turn ${r.turn} · $who" + if (r.rolled) " rolled ${r.die1 + r.die2}" else "") + r.events.map { "  $it" }
    }

    /** Where the game stands, in one line: turn, phase, whose move, everyone's points, and the state hash (checks a replay). */
    fun position(s: wgbg.engine.GameState, names: List<String>): String =
        "turn ${s.turn} · ${s.phase} · ${names.getOrElse(s.actingPlayer) { "?" }} to move · points " +
            (0 until s.numPlayers).joinToString(", ") { "${names.getOrElse(it) { "P${it + 1}" }} ${s.victoryPoints(it)}" } +
            " · hash ${s.stateHash()}"

    /** Joins a dictated phrase onto what was already typed. */
    fun addSpoken(note: String, spoken: String): String =
        if (spoken.isBlank()) note else if (note.isBlank()) spoken.trim() else note.trimEnd() + " " + spoken.trim()
}

@Suppress("DEPRECATION")
internal fun appVersionCode(context: Context): Long = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}.getOrDefault(0L)

/**
 * The report panel over whatever is on screen. [game], [history] and [position] are null/empty from the start screen.
 */
@Composable
fun FeedbackPanel(game: String?, history: List<String>, position: String?, shot: android.graphics.Bitmap? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    // #128: the game as it was when the report opened, plus a picture picked from the gallery; each can be removed.
    val shots = remember { androidx.compose.runtime.mutableStateListOf<android.graphics.Bitmap>().apply { shot?.let { add(it) } } }
    val pickShot = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { Screenshot.load(context, it) }?.let { if (shots.size < 2) shots.add(it) }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var kind by remember { mutableStateOf(Feedback.Kind.BUG) }
    var note by remember { mutableStateOf("") }
    var withGame by remember { mutableStateOf(game != null) }
    var status by remember { mutableStateOf("") }
    val canSpeak = remember { RecognizerIntent.ACTION_RECOGNIZE_SPEECH.let { Intent(it).resolveActivity(context.packageManager) != null } }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        note = Feedback.addSpoken(note, said)
        status = if (said.isBlank()) "Didn't catch that" else ""
    }
    val version = appVersion()
    fun report(max: Int = Int.MAX_VALUE): String {
        val (device, screen) = Feedback.device(context)
        return Feedback.formatFitted(max, kind, note, version, appVersionCode(context), device, screen,
            game.takeIf { withGame }, if (withGame) history else emptyList(), position.takeIf { withGame }, System.currentTimeMillis())
    }
    // A dim backdrop that swallows taps, so the board under it isn't played by accident.
    Box(Modifier.fillMaxSize().background(Color(0xA0000000)).clickable(MutableInteractionSource(), null) {}.testTag("feedback"),
        contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).brassEdgedWood().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Report a bug or an idea", style = Wood.title(20.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (k in Feedback.Kind.entries) Choice(k.label, kind == k, "feedback-${k.name.lowercase()}",
                    Modifier.width(90.dp).height(36.dp), radius = 9.dp, size = 15.sp) { kind = k }
                if (game != null) Choice("Include this game", withGame, "feedback-with-game", Modifier.weight(1f).height(36.dp),
                    radius = 9.dp, size = 14.sp) { withGame = !withGame }
            }
            BasicTextField(
                value = note, onValueChange = { note = it.take(4000) },
                textStyle = Wood.sans(15.sp, FontWeight.Medium, Wood.PARCHMENT_INK),
                cursorBrush = SolidColor(Wood.PARCHMENT_INK),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // Back with the keyboard up must not close the sheet and lose the note (#121).
                modifier = Modifier.fillMaxWidth().height(110.dp).testTag("feedback-note").backPutsKeyboardAway(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().background(Wood.PARCHMENT, RoundedCornerShape(8.dp))
                        .border(2.dp, Wood.BRASS, RoundedCornerShape(8.dp)).padding(10.dp)) {
                        if (note.isEmpty()) Text(if (kind == Feedback.Kind.BUG) "What went wrong? What did you expect?" else "What would make it better?",
                            style = Wood.sans(14.sp, color = Wood.INK_SOFT))
                        inner()
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((i, b) in shots.withIndex()) Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.testTag("feedback-shot-$i")) {
                    androidx.compose.foundation.Image(b.asImageBitmap(), "Screenshot ${i + 1}",
                        Modifier.height(64.dp).border(1.dp, Wood.BRASS, RoundedCornerShape(4.dp)))
                    Text("✗", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Color(0xFFC0392B), RoundedCornerShape(10.dp)).clickable { shots.removeAt(i) }
                            .padding(horizontal = 6.dp).testTag("feedback-shot-remove-$i"))
                }
                if (shots.size < 2) WoodButton(if (shots.isEmpty()) "Add a picture" else "Add another", "feedback-add-shot", Modifier.height(40.dp)) {
                    runCatching { pickShot.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        .onFailure { status = "Can't open your pictures" }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canSpeak) BrassButton("Speak", "feedback-speak", Modifier.height(44.dp)) {
                    runCatching {
                        listen.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what happened"))
                    }.onFailure { status = "Voice input isn't available" }
                }
                GoButton("Send", "feedback-send", enabled = note.isNotBlank() || withGame, Modifier.height(44.dp)) {
                    status = "Sending…"
                    val text = report(Feedback.UPLOAD_MAX) + if (shots.isEmpty()) "" else "\nScreenshots: ${shots.size}"
                    val pics = shots.toList()
                    scope.launch {
                        val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { pics.mapNotNull { Screenshot.jpegBase64(it) } }
                        sendCrashReport(context, text, encoded) { ok, n ->
                            status = when {
                                !ok -> "Couldn't send (offline?); try Share"
                                n < encoded.size -> "Sent, thank you (the picture didn't go)"
                                else -> "Sent, thank you"
                            }
                            if (ok) { note = ""; shots.clear() }
                        }
                    }
                }
                BrassButton("Share", "feedback-share", Modifier.height(44.dp)) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "WGBG ${kind.label.lowercase()} report").putExtra(Intent.EXTRA_TEXT, report())
                    runCatching { context.startActivity(Intent.createChooser(send, "Share the report")) }
                }
                WoodButton("Close", "feedback-close", Modifier.height(44.dp), onClick = onClose)
            }
            if (status.isNotEmpty()) Text(status, color = Color(0xFFD9B865), fontSize = 13.sp, modifier = Modifier.testTag("feedback-status"))
        }
    }
}

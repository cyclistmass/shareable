package com.mauro.wgbg

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash reports (Mauro, 2026-10-10: "catch the crashes and dump a qr with the info, or push it to the db").
 * An uncaught exception is written to the app's files with the version, the device and the last moves;
 * the next launch shows it with a QR code, Send (to Firestore `crashes`) and Share.
 */
object CrashReport {
    private const val FILE = "last-crash.txt"
    private const val KEEP = 40
    private val recent = ArrayDeque<String>()

    /** Remembers one line of what just happened in a game (moves, rolls), for the next report. */
    @Synchronized fun note(line: String) {
        recent.addLast(line)
        while (recent.size > KEEP) recent.removeFirst()
    }

    @Synchronized fun recentLines(): List<String> = recent.toList()

    /** The report text: what crashed, where, on which build and device, after which moves. */
    fun format(error: Throwable, version: String, versionCode: Long, device: String, screen: String, moves: List<String>, time: Long): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("WGBG crash report")
            appendLine("version: $version ($versionCode)")
            appendLine("device: $device")
            appendLine("screen: $screen")
            appendLine("time: ${java.time.Instant.ofEpochMilli(time)}")
            appendLine("error: ${error::class.java.name}: ${error.message}")
            appendLine("last moves (oldest first):")
            moves.forEach { appendLine("  $it") }
            appendLine("stack:")
            append(trace)
        }
    }

    /** The first [max] characters of [report] for the QR code (a QR holds about 2 KB comfortably). */
    fun forQr(report: String, max: Int = 1500): String = if (report.length <= max) report else report.take(max - 12) + "\n[truncated]"

    /** Catches every uncaught exception: saves the report, then lets Android close the app as before. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                val dm = app.resources.displayMetrics
                val report = format(error, info.versionName ?: "?", code,
                    "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "${dm.widthPixels}x${dm.heightPixels} px, density ${dm.density}", recentLines(), System.currentTimeMillis())
                File(app.filesDir, FILE).writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { File(context.filesDir, FILE).delete() }
}

/** Shown at launch after a crash: the report as a QR code, and Send / Share / Dismiss. */
@Composable
fun CrashReportScreen(report: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0xFF14100C)).testTag("crash-report"), contentAlignment = Alignment.Center) {
        Row(Modifier.brassEdgedWood().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(CrashReport.forQr(report), Modifier.size(220.dp))
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The game crashed last time", color = Color(0xFFF1D58A), fontSize = 20.sp)
                Text("Sorry about that. Send the report so it can be fixed (or scan the code, or share it).",
                    color = Color(0xFFEDE6D8), fontSize = 13.sp)
                Text(report.lineSequence().firstOrNull { it.startsWith("error:") } ?: "", color = Color(0xFFE57373), fontSize = 11.sp, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoButton("Send report", "crash-send", enabled = true) {
                        status = "Sending…"
                        sendCrashReport(context, report) { ok, _ ->
                            status = if (ok) "Sent, thank you" else "Couldn't send (offline?); try Share"
                            if (ok) { CrashReport.clear(context); scope.launchDone(onDone) }
                        }
                    }
                    BrassButton("Share", "crash-share") {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "WGBG crash report")
                            .putExtra(Intent.EXTRA_TEXT, report)
                        context.startActivity(Intent.createChooser(send, "Share crash report"))
                    }
                    BrassButton("Dismiss", "crash-dismiss") { CrashReport.clear(context); onDone() }
                }
                if (status.isNotEmpty()) Text(status, color = Color(0xFFD9B865), fontSize = 13.sp)
            }
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchDone(onDone: () -> Unit) {
    launch { delay(1200); onDone() }
}

/** Uploads [report] to Firestore `crashes` (anonymous sign-in, write-only by the rules); calls back on the main thread. */
/** Sends [report] and its [shots] (#128); [done] gets whether the report went and how many shots did. */
fun sendCrashReport(context: Context, report: String, shots: List<String> = emptyList(), done: (Boolean, Int) -> Unit) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    // Firebase is set up off the main thread (#111): a Send tapped while it starts would otherwise wait on it there.
    Thread {
        runCatching {
            val t = LobbyRepository.create(context) as? FirestoreTransport
            if (t == null) { main.post { done(false, 0) }; return@Thread }
            t.sendCrash(report, shots).whenComplete { n, e -> main.post { done(e == null, n ?: 0) } }
        }.onFailure { main.post { done(false, 0) } }
    }.start()
}

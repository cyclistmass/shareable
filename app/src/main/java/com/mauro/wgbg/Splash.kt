package com.mauro.wgbg

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The splash / start screen (#132, Crest's drafts "1. Start screen" and "2. About", Mauro: "screens look fine"):
 * Resume, New game, Log in and Settings over the sea, with About and credits, the version and a Support link
 * along the bottom edge.
 */
object Splash {
    /** Mauro's Ko-fi page name. Until it is filled in, the Support and Ko-fi buttons stay hidden. */
    const val KOFI_NAME = "" // TODO(#132): Mauro's Ko-fi page name
    const val KOFI_URL = "https://ko-fi.com/$KOFI_NAME"
    const val SOURCE_URL = "https://github.com/cyclistmass/sharable"
    const val CREDITS_URL = "https://github.com/cyclistmass/sharable/blob/main/working-german-board-game/CREDITS.md"

    /** True once [url] names a real Ko-fi page (not the bare site, not a `[placeholder]`). */
    fun kofiReady(url: String = KOFI_URL): Boolean {
        val name = url.removePrefix("https://ko-fi.com/").trim('/')
        return url.startsWith("https://ko-fi.com/") && name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    enum class Action { RESUME_GAME, RESUME_ROOM, NEW_GAME, LOG_IN, SETTINGS }

    /** One main button: what it does, its label and whether it is the green go plate. */
    data class Button(val action: Action, val label: String, val go: Boolean, val tag: String)

    /**
     * The main buttons, top to bottom. Resume shows only for a saved local game ([localGame]) or an online room you
     * left for now ([room]); the first Resume is the green one, and New game is green when there is nothing to
     * resume. Log in shows only when the build can sign in ([canSignIn]), and reads Account once you are signed in.
     */
    fun buttons(localGame: Boolean, room: String?, canSignIn: Boolean, signedIn: Boolean = false): List<Button> = buildList {
        if (localGame) add(Button(Action.RESUME_GAME, "Resume game", go = true, tag = "resume-game"))
        if (room != null) add(Button(Action.RESUME_ROOM, "Resume online game $room", go = !localGame, tag = "resume-room"))
        add(Button(Action.NEW_GAME, "New game", go = isEmpty(), tag = "new-game-open"))
        if (canSignIn) add(Button(Action.LOG_IN, if (signedIn) "Account" else "Log in", go = false, tag = "log-in"))
        add(Button(Action.SETTINGS, "Settings", go = false, tag = "splash-settings"))
    }

    /** The About board's credits, short form (the full list is CREDITS.md). Never the original game's name. */
    val CREDITS = listOf(
        "Art" to "Our own: Blender models rendered on Mauro's PC, and paintings made by Mauro with ChatGPT image " +
            "generation and OpenAI Codex. Wood and old-brass textures from ambientCG (CC0).",
        "Music" to "Traditional sea shanties (public domain) in our own arrangements, played on samples from VSCO 2 " +
            "Community Edition and the Versilian Community Sample Library by Versilian Studios (CC0).",
        "Fonts" to "Alegreya SC and Alegreya Sans by Huerta Tipográfica (SIL Open Font License 1.1).",
        "Software" to "Kotlin, Jetpack Compose and AndroidX, Firebase, ZXing and Filament (Apache License 2.0).",
        "Thanks" to "To everyone who playtested and sent in-app reports.",
    )
    const val DISCLAIMER = "An independent game with rules compatible with the classic German settlement-building game; " +
        "not affiliated with or endorsed by its publishers. All names, card text and art are our own."
    const val ABOUT_LINE = "Made by Mauro DiBenedetto, with art and music built for the game. No ads, no tracking."
}

/** Opens [url] in the browser; false when no app on the phone can open it. */
internal fun openLink(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)

/** The sea behind the splash (the draft's radial gradient). */
private val SEA = Brush.radialGradient(listOf(Color(0xFF1E6E8A), Color(0xFF134B6B), Color(0xFF0B2E48)))
private val LINK = Wood.BRASS_LIGHT

@Composable
fun SplashScreen(
    resumeGame: Boolean,
    resumeRoom: String?,
    onResumeGame: () -> Unit,
    onResumeRoom: () -> Unit,
    onNewGame: () -> Unit,
    /** Ends the saved local game for good (asks first); the menu then offers only New game. */
    onEndGame: () -> Unit = {},
    env: EnvSettings,
    sound: SoundSettings,
) {
    val context = LocalContext.current
    // Sign-in needs Firebase and a Google client id; builds without them have no Log in button (as AccountSection).
    // Set up off the main thread (#111): the menu draws at once and Log in appears when Firebase is ready.
    val account by androidx.compose.runtime.produceState<FirestoreTransport?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { LobbyRepository.create(context) as? FirestoreTransport }.getOrNull()?.takeIf { GoogleAccount.webClientId(context) != null }
        }
    }
    var panel by remember { mutableStateOf<Splash.Action?>(null) }
    var about by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    // Re-read who is signed in each time the Log in panel closes.
    var accountSeen by remember { mutableIntStateOf(0) }
    val signedIn = remember(accountSeen, account) { account?.let { GoogleAccount.status(it.account).signedIn } ?: false }
    if (about) { BackHandler { about = false }; AboutScreen { about = false }; return }
    Box(Modifier.fillMaxSize().background(SEA).testTag("splash")) {
        Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Text("Working German Board Game", style = Wood.title(34.sp, Color(0xFFFFF6DE)).copy(shadow = Shadow(Color(0xB3000000), Offset(0f, 4f), 12f)),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Column(Modifier.width(300.dp).brassEdgedWood().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (b in Splash.buttons(resumeGame, resumeRoom, account != null, signedIn)) {
                    val tap = {
                        when (b.action) {
                            Splash.Action.RESUME_GAME -> onResumeGame()
                            Splash.Action.RESUME_ROOM -> onResumeRoom()
                            Splash.Action.NEW_GAME -> onNewGame()
                            Splash.Action.LOG_IN, Splash.Action.SETTINGS -> panel = b.action
                        }
                    }
                    if (b.go) GoButton(b.label, b.tag, true, Modifier.fillMaxWidth().height(48.dp)) { tap() }
                    else BrassButton(b.label, b.tag, Modifier.fillMaxWidth().height(44.dp), size = 16.sp) { tap() }
                    // A resumed game could only be left for now, never ended (Mauro, 2026-10-18: "i cant quit it").
                    if (b.action == Splash.Action.RESUME_GAME) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // A real button, not a bare link, so it reads as tappable (Fable review T10).
                        WoodButton("End saved game", "end-saved-game", Modifier.height(40.dp)) { confirmEnd = true }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BottomLink("ⓘ  About and credits", "about") { about = true }
                }
                Text("Version ${appVersion()}", style = Wood.sans(12.sp, color = Color(0xFFCFE7F2)), modifier = Modifier.testTag("app-version"))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (Splash.kofiReady()) BottomLink("Support the game", "support") { openLink(context, Splash.KOFI_URL) }
                }
            }
        }
        if (confirmEnd) {
            BackHandler { confirmEnd = false }
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) { confirmEnd = false },
                contentAlignment = Alignment.Center) {
                EndGameConfirm(onKeep = { confirmEnd = false }, onEnd = { confirmEnd = false; onEndGame() },
                    Modifier.clickable(remember { MutableInteractionSource() }, null) { })
            }
        }
        panel?.let { open ->
            val close = { panel = null; accountSeen++ }
            BackHandler { close() }
            // A tap on the sea around the panel closes it, like the in-game gear.
            Box(Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, null) { close() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxHeight().padding(vertical = 10.dp).clickable(remember { MutableInteractionSource() }, null) { }) {
                    WoodButton("Close", "splash-panel-close") { close() }
                    if (open == Splash.Action.SETTINGS) SettingsPanel(env, sound, Modifier.weight(1f, fill = false))
                    else Column(Modifier.width(360.dp).brassEdgedWood().padding(16.dp).testTag("log-in-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Log in", style = Wood.title(20.sp))
                        Text("Sign in to keep your online name and rooms on any phone. Playing as a guest works too.",
                            style = Wood.sans(13.sp, color = Wood.INK_SOFT))
                        AccountSection()
                    }
                }
            }
        }
    }
}

/** "End this game for good?": the one place a saved local game is thrown away, from the start menu or in the game. */
@Composable
fun EndGameConfirm(onKeep: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(320.dp).brassEdgedWood().padding(18.dp).testTag("end-game-confirm"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("End this game for good?", style = Wood.title(20.sp, Color(0xFFF1D58A)))
        Text("It can't be resumed after this.", style = Wood.sans(13.sp, color = Color(0xFFEDE6D8)))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The safe choice is the green one; only the one that ends the game is red (Fable review W2).
            GoButton("Keep it", "end-game-no", true, Modifier.heightIn(min = 44.dp)) { onKeep() }
            StopButton("End game", "end-game-yes", Modifier.heightIn(min = 44.dp)) { onEnd() }
        }
    }
}

@Composable
private fun BottomLink(label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp).testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(label, style = Wood.sans(15.sp, FontWeight.Bold, LINK))
    }
}

/** About and credits: who made it and how to support it on the left, the credits on the right. */
@Composable
fun AboutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxSize().tableTop().padding(14.dp).testTag("about"), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight().brassEdgedWood().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Working German Board Game", style = Wood.title(22.sp))
            Text("Version ${appVersion()} · Free and open source (GPL-3.0)", style = Wood.sans(13.sp, color = Wood.INK_SOFT))
            Text(Splash.ABOUT_LINE, style = Wood.sans(14.sp))
            if (Splash.kofiReady()) {
                Text("Enjoying the game?", style = Wood.sans(14.sp, FontWeight.Bold, Wood.BRASS_LIGHT))
                GoButton("Buy me a coffee on Ko-fi", "kofi", true, Modifier.fillMaxWidth().height(46.dp)) { openLink(context, Splash.KOFI_URL) }
            }
            Spacer(Modifier.weight(1f))
            BrassButton("Source code", "source-code", Modifier.fillMaxWidth().height(42.dp), size = 15.sp) { openLink(context, Splash.SOURCE_URL) }
            WoodButton("Close", "about-close", Modifier.fillMaxWidth()) { onClose() }
        }
        Column(Modifier.weight(1f).fillMaxHeight().brassEdgedWood().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Credits", style = Wood.title(22.sp))
            for ((head, body) in Splash.CREDITS) Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Wood.BRASS_LIGHT)); append("$head. "); pop()
                    append(body)
                }, style = Wood.sans(14.sp))
            Text(Splash.DISCLAIMER, style = Wood.sans(13.sp, color = Wood.INK_SOFT), modifier = Modifier.testTag("disclaimer"))
            BottomLink("Full credits and licence texts", "full-credits") { openLink(context, Splash.CREDITS_URL) }
        }
    }
}

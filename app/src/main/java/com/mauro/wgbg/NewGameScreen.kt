package com.mauro.wgbg

import androidx.compose.foundation.layout.displayCutoutPadding
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * The game-start screen (#3), ported from docs/design/NewGame.dc.html: your name and photo,
 * seats 2-4 as Friend / Bot / Empty with the bot's level, base game or Age of Chivalry, points,
 * and the dice. [onStart] gets the finished settings; [onOpenLobby], when given, shows
 * "Play online (open lobby)" and gets the same settings for the lobby.
 */
@Composable
fun NewGameScreen(
    onStart: (GameSetup) -> Unit,
    modifier: Modifier = Modifier,
    initial: GameSetup = GameSetup(),
    onOpenLobby: ((GameSetup) -> Unit)? = null,
    /** When given, shows "Join a room" (by code); gets these settings for your name. */
    onJoinRoom: ((GameSetup) -> Unit)? = null,
    /** An online game you left for now (room code): shows a green Resume button until it ends or is deleted. */
    resumeRoom: String? = null,
    onResume: (() -> Unit)? = null,
    /** A local game left mid-way (#82): the label of a green Resume button that picks it up where it was. */
    resumeGame: String? = null,
    onResumeGame: (() -> Unit)? = null,
    /** Look & art (#71): pick the theme, dice, tiles and see every piece, before the game; gets the choices on screen. */
    onLookAndArt: ((GameSetup) -> Unit)? = null,
    /** Plays the intro fly-over now (Mauro: a button on the start screen, not a setting in the game). */
    onPlayIntro: (() -> Unit)? = null,
    /** Opens the bug report / idea panel (Mauro: "put it on the main landing page menu as well"). */
    onFeedback: (() -> Unit)? = null,
    /** Debug builds only (#133): the card tester's checklist. */
    onCardTester: (() -> Unit)? = null,
    /** Gets every change as it's made (0.3.4), so the choices are saved before Play, Back or the app closing. */
    onChange: ((GameSetup) -> Unit)? = null,
) {
    var setup by remember { mutableStateOf(initial) }
    if (onChange != null) LaunchedEffect(Unit) { snapshotFlow { setup }.drop(1).collect { onChange(it) } }
    // The deck size to come back to when switching Random -> Card deck (72 by default, as drawn).
    var lastDecks by rememberSaveable { mutableIntStateOf(if (initial.decks > 0) initial.decks else 2) }
    val context = LocalContext.current
    // The seat whose name picker is open, or null.
    // The seat whose colour picker is open, or null.
    var colouring by remember { mutableStateOf<Int?>(null) }
    // The seat whose cameo picker is open, or null.
    var picturing by remember { mutableStateOf<Int?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            keepAccess(context, uri)
            setup = setup.copy(photo = uri.toString()).withSeatCameo(0, null)
        }
    }

    Row(
        modifier.fillMaxSize().tableTop().displayCutoutPadding().padding(14.dp).testTag("new-game"),   // wood under the panels, like the Lobby (Fable T6)
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Scrolls too: at 320 dp tall (CI's screen) Points, Report and Intro were cut off the bottom.
        Panel(Modifier.weight(1f), scroll = true) {
            Text("Players", style = Wood.title(22.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val cameos = setup.seatCameos
                PhotoToken(setup.photo, setup.name, seatInk(setup, 0, fill = true), cameo = cameos[0]) { picturing = 0 }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your name", style = Wood.sans(12.sp, color = Wood.INK_SOFT))
                    NameField(setup.name) { setup = setup.copy(name = it.take(MAX_NAME)) }
                }
                Text("Tap the picture to change it", style = Wood.sans(12.sp, color = Wood.INK_SOFT), modifier = Modifier.width(90.dp))
            }
            // Side by side when each seat gets about 120 dp; on a narrower panel (CI's 640x320 screen) "Greedy" read "Gr",
            // so the seats stack, each with its choices across the panel.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val names = setup.seatNames
                val cameos = setup.seatCameos
                // The initial a seat without a cameo shows: its theme name's, else its colour's (Colours names it so).
                val label = { i: Int -> names[i] ?: setup.seatColours[i]?.label ?: "${i + 1}" }
                // The clasp box's names: the theme's, each with the seat that has it (picking it swaps the two).
                val themeNames = setup.theme.names.map { it.name }
                val swaps = { i: Int -> { k: Int -> names.indexOf(themeNames[k]).takeIf { it >= 0 && it != i }?.let { "Seat ${it + 1}: swaps" } } }
                if (maxWidth >= 380.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 1 until GameSetup.MAX_SEATS) SeatCard(i, setup.seats[i], names[i], seatInk(setup, i), Modifier.weight(1f),
                        picture = { PhotoToken(null, label(i), seatInk(setup, i, fill = true), 26.dp, cameos[i], "seat-$i-picture") { picturing = i } },
                        onKind = { setup = setup.withSeat(i, it) }, onBot = { setup = setup.withBot(i, it) }, themeNames = themeNames, nameNote = swaps(i),
                        onName = { setup = setup.withSeatName(i, it) })
                } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 1 until GameSetup.MAX_SEATS) SeatCard(i, setup.seats[i], names[i], seatInk(setup, i), Modifier.fillMaxWidth(), stacked = true,
                        picture = { PhotoToken(null, label(i), seatInk(setup, i, fill = true), 26.dp, cameos[i], "seat-$i-picture") { picturing = i } },
                        onKind = { setup = setup.withSeat(i, it) }, onBot = { setup = setup.withBot(i, it) }, themeNames = themeNames, nameNote = swaps(i),
                        onName = { setup = setup.withSeatName(i, it) })
                }
            }
            picturing?.let { i ->
                CameoPicker(i, setup, setup.seats.indices.map { seatInk(setup, it, fill = true) },
                    onPick = { setup = setup.withSeatCameo(i, it); picturing = null },
                    onInitial = { setup = setup.copy(photo = null).withSeatCameo(0, null); picturing = null },
                    onPhoto = { picturing = null; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onDismiss = { picturing = null })
            }
            // Each seat's colour (Mauro, 0.3.4: "random or I can pick the color when I make the game"), yours too.
            Text("Colours", style = Wood.sans(14.sp, color = Wood.INK_SOFT))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Choice("Pick", !setup.randomColours, "colours-pick", Modifier.height(30.dp), radius = 8.dp, size = 12.sp) { setup = setup.withRandomColours(false) }
                Choice("Random", setup.randomColours, "colours-random", Modifier.height(30.dp), radius = 8.dp, size = 12.sp) { setup = setup.withRandomColours(true) }
                val colours = setup.seatColours
                for (i in setup.players) SeatColourButton(i, if (setup.randomColours) null else colours[i]) { colouring = i }
            }
            if (setup.randomColours) Text("Each game draws colours nobody will mix up.", style = Wood.sans(12.sp, color = Wood.INK_SOFT))
            setup.colourClashes.firstOrNull()?.let { (a, b) ->
                val c = setup.seatColours
                Text("${c[a]!!.label} and ${c[b]!!.label} look alike to some players (colour-blind). Pick another for Seat ${b + 1}?",
                    style = Wood.sans(12.sp, FontWeight.Bold, Wood.WAITING), modifier = Modifier.testTag("colour-clash"))
            }
            colouring?.let { i ->
                SeatColourPicker(i, setup, onPick = { setup = setup.withSeatColour(i, it); colouring = null }, onDismiss = { colouring = null })
            }
            // What the bots are called (Mauro, 0.3.4): their colours, or a theme; each seat then picks from its names.
            // The label goes above the chips: beside them it floated loose once they wrapped on a narrow screen.
            Text("Bot names", style = Wood.sans(14.sp, color = Wood.INK_SOFT))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in NameTheme.entries) Choice(t.label, setup.theme == t, "theme-${t.name}", Modifier.height(30.dp),
                    radius = 8.dp, size = 12.sp) { setup = setup.withTheme(t) }
            }
            Text("Game", style = Wood.title(18.sp), modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in Ruleset.entries) Choice(r.label, setup.ruleset == r, "ruleset-${r.name}",
                    Modifier.weight(1f).height(40.dp), radius = 9.dp, size = 15.sp) { setup = setup.withRuleset(r) }
            }
            // Points and the buttons share a row that wraps: on a phone (914 dp wide) they fit in one row so the panel needs
            // no scroll; on a narrow panel (CI's 640x320 screen) the buttons wrap below instead of running off the edge.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Points", style = Wood.sans(14.sp, color = Wood.INK_SOFT))
                    Choice("−", false, "points-down", Modifier.size(34.dp), size = 18.sp,
                        enabled = setup.points > GameSetup.POINTS_RANGE.first) { setup = setup.withPoints(setup.points - 1) }
                    Text("${setup.points}", style = Wood.sans(18.sp, FontWeight.Bold), modifier = Modifier.width(28.dp).testTag("points"),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Choice("+", false, "points-up", Modifier.size(34.dp), size = 18.sp,
                        enabled = setup.points < GameSetup.POINTS_RANGE.last) { setup = setup.withPoints(setup.points + 1) }
                }
                Spacer(Modifier.weight(1f))
                if (onFeedback != null) WoodButton("Report a bug or idea", "start-feedback", Modifier.height(40.dp)) { onFeedback() }
                if (onPlayIntro != null) WoodButton("Intro", "play-intro", Modifier.height(40.dp)) { onPlayIntro() }
                if (onLookAndArt != null) BrassButton("Look & art", "look-and-art", Modifier.height(40.dp)) { onLookAndArt(setup) }
                if (onCardTester != null) WoodButton("Card tester", "card-tester", Modifier.height(40.dp)) { onCardTester() }
            }
        }
        // Scrolls when it can't fit: a saved game's Resume used to push Host and Join off the bottom (2026-10-12).
        Panel(Modifier.width(300.dp), scroll = true) {
            Text("Dice", style = Wood.title(22.sp))
            DiceChoice("True random", "the phone's secure dice", setup.decks == 0, "dice-random") {
                setup = setup.withDecks(0)
            }
            DiceChoice("Card deck", "every total in fair shares", setup.decks > 0, "dice-deck") {
                setup = setup.withDecks(lastDecks)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Deck size", style = Wood.sans(13.sp, color = Wood.INK_SOFT), modifier = Modifier.width(64.dp))
                for (d in 1..GameSetup.MAX_DECKS) Choice("${36 * d}", setup.decks == d, "deck-$d", Modifier.weight(1f).height(34.dp),
                    enabled = setup.decks > 0) { lastDecks = d; setup = setup.withDecks(d) }
            }
            Text("Reshuffles when 5 cards are left.", style = Wood.sans(12.sp, color = Wood.INK_SOFT))
            Spacer(Modifier.height(4.dp))
            // Sign in on the start screen (Mauro, 2026-10-12); hidden when the build has no Firebase.
            AccountSection(compact = true)
            setup.problems.firstOrNull()?.let {
                Text(it, style = Wood.sans(13.sp, FontWeight.Bold, Wood.WAITING), modifier = Modifier.testTag("setup-problem"))
            }
            GoButton("Play", "play", setup.canStart, Modifier.fillMaxWidth().height(48.dp)) { onStart(setup) }
            if ((onOpenLobby != null || onJoinRoom != null) && !onlineName(setup.name))
                // Online needs a real name (friends see it); say so plainly, it read as "online is broken" when grey.
                Text("To play online, type your name above (not \"You\")", style = Wood.sans(13.sp, FontWeight.Bold, Wood.WAITING),
                    modifier = Modifier.testTag("online-name-hint"))
            if (resumeGame != null && onResumeGame != null)
                GoButton(resumeGame, "resume-game", true, Modifier.fillMaxWidth().height(40.dp)) { onResumeGame() }
            if (resumeRoom != null && onResume != null)
                GoButton("Resume online game $resumeRoom", "resume-room", true, Modifier.fillMaxWidth().height(40.dp)) { onResume() }
            // Online buttons turn green (go) once online is possible, i.e. you've typed a name (Mauro, 2026-10-11).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ready = onlineName(setup.name)
                if (onOpenLobby != null) {
                    if (ready) GoButton("Host a room", "open-lobby", true, Modifier.weight(1f).height(40.dp)) { onOpenLobby(setup) }
                    else Choice("Host a room", false, "open-lobby", Modifier.weight(1f).height(36.dp), radius = 10.dp, enabled = false) { }
                }
                if (onJoinRoom != null) {
                    if (ready) GoButton("Join a room", "join-room", true, Modifier.weight(1f).height(40.dp)) { onJoinRoom(setup) }
                    else Choice("Join a room", false, "join-room", Modifier.weight(1f).height(36.dp), radius = 10.dp, enabled = false) { }
                }
            }
            // Which build this is, for testers, inside the panel (it sat on the brass edge before).
            Text("v${appVersion()}", style = Wood.sans(12.sp, color = Wood.INK_SOFT), modifier = Modifier.align(Alignment.End).testTag("app-version"))
        }
    }
}

private const val MAX_NAME = 16

@Composable
private fun Panel(modifier: Modifier, scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().brassEdgedWood().padding(horizontal = 14.dp, vertical = 12.dp)
            .then(if (scroll) Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * Seat [i]'s colour as the screen shows it: the text shade for its title ([fill]: the piece colour, for your token).
 * With random colours nobody's is known yet: plain ink, and brass for your token.
 */
private fun seatInk(setup: GameSetup, i: Int, fill: Boolean = false): androidx.compose.ui.graphics.Color {
    val c = setup.seatColours[i]
    return when {
        setup.randomColours || c == null -> if (fill) Wood.BRASS else Wood.INK
        else -> androidx.compose.ui.graphics.Color(if (fill) c.argb else c.text)
    }
}

/** A seat's colour to tap and change: a dot in it and its name ("?" with random colours). */
@Composable
private fun SeatColourButton(i: Int, colour: SeatColour?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val pull = remember { mutableFloatStateOf(0f) }
    Row(
        Modifier.height(30.dp).clip(shape).background(Wood.WOOD_DARK).border(2.dp, Wood.BRASS, shape).claspPull(pull, onClick)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp).testTag("seat-$i-colour"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(14.dp).background(colour?.let { androidx.compose.ui.graphics.Color(it.argb) } ?: Wood.WOOD_DARK, CircleShape)
            .border(1.dp, Wood.BRASS_LIGHT, CircleShape))
        Text("${if (i == 0) "You" else "Seat ${i + 1}"}: ${colour?.label ?: "?"}", style = Wood.sans(12.sp, FontWeight.SemiBold))
        Clasp(pull.floatValue)
    }
}

/**
 * Every colour for seat [i]: which seat already has it (picking it swaps the two), and the ones that would look like
 * another seat's to someone colour-blind, greyed with the seat they clash with (still allowed: Mauro's default has one).
 */
@Composable
private fun SeatColourPicker(i: Int, setup: GameSetup, onPick: (SeatColour) -> Unit, onDismiss: () -> Unit) {
    val now = setup.seatColours
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).brassEdgedWood().padding(horizontal = 14.dp, vertical = 12.dp).testTag("seat-colour-picker"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${if (i == 0) "Your colour" else "Seat ${i + 1}'s colour"}", style = Wood.title(18.sp))
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in SeatColour.entries) {
                    val holder = now.indexOf(c).takeIf { it >= 0 && it != i }
                    val after = setup.withSeatColour(i, c)
                    val clash = after.colourClashes.firstOrNull { i in it.toList() }?.let { (a, b) -> if (a == i) b else a }
                    val note = listOfNotNull(holder?.let { "Seat ${it + 1}: swaps" }, clash?.let { "like Seat ${it + 1}'s ${after.seatColours[it]!!.label}" })
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (!setup.randomColours && now[i] == c) Wood.BRASS.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color(0x47000000), RoundedCornerShape(8.dp))
                            .clickable(role = Role.RadioButton) { onPick(c) }.padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("seat-colour-${c.name}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(18.dp).background(androidx.compose.ui.graphics.Color(c.argb), CircleShape).border(1.dp, Wood.BRASS_LIGHT, CircleShape))
                        Text(c.label + (if (note.isEmpty()) "" else "  (${note.joinToString(", ")})"),
                            style = Wood.sans(15.sp, FontWeight.Bold, if (clash != null) Wood.INK_SOFT else Wood.INK))
                    }
                }
            }
        }
    }
}

/** One of seats 2-4: Friend / Bot / Empty, then the bot level and what it means, and its theme name to tap and change. */
@Composable
private fun SeatCard(i: Int, seat: SeatSetup, name: String?, ink: androidx.compose.ui.graphics.Color, modifier: Modifier, stacked: Boolean = false,
                     picture: @Composable () -> Unit, onKind: (SeatKind) -> Unit, onBot: (BotLevel) -> Unit,
                     themeNames: List<String>, nameNote: (Int) -> String?, onName: (String) -> Unit) {
    Column(
        modifier.background(androidx.compose.ui.graphics.Color(0x47000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp).testTag("seat-$i"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Stacked, the seat's name sits beside its choices to keep each seat short. Its cameo (0.3.5) sits by the name,
        // to tap and change; an empty seat has none.
        val title = @Composable { m: Modifier ->
            Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (seat.kind != SeatKind.EMPTY) picture()
                Text("Seat ${i + 1}", style = Wood.sans(14.sp, FontWeight.Bold, ink))
            }
        }
        if (!stacked) title(Modifier)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stacked) title(Modifier.width(88.dp))
            for (k in SeatKind.entries) Choice(k.label, seat.kind == k, "seat-$i-${k.name}", Modifier.weight(1f).height(28.dp),
                radius = 7.dp, size = 12.sp) { onKind(k) }
        }
        when (seat.kind) {
            SeatKind.BOT -> {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (b in BotLevel.offered) Choice(b.label, seat.bot.plays == b, "seat-$i-bot-${b.name}", Modifier.weight(1f).height(26.dp),
                        radius = 7.dp, size = 11.sp) { onBot(b) }
                }
            }
            // There is no pass-the-phone mode yet: on Play a bot sits here, so say so (Fable review W3).
            SeatKind.FRIEND -> Text("Kept open for a friend when you Host a room. Play on this phone: a bot sits here.",
                style = Wood.sans(12.sp, color = Wood.INK_SOFT))
            SeatKind.EMPTY -> Text("Nobody sits here", style = Wood.sans(12.sp, color = Wood.INK_SOFT))
        }
        // The seat's name is a clasp box (0.3.5, Mauro's approved storyboard): it opens to the theme's names.
        if (name != null && seat.kind != SeatKind.EMPTY)
            ClaspBox(name, themeNames, themeNames.indexOf(name).takeIf { it >= 0 }, { onName(themeNames[it]) }, "seat-$i-name",
                "seat-name-picker", { "seat-name-$it" }, note = nameNote, ink = ink)
    }
}

@Composable
private fun DiceChoice(title: String, note: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(shape)
            .background(if (selected) Wood.BRASS else Wood.WOOD_DARK)
            .border(2.dp, if (selected) Wood.BRASS_LIGHT else Wood.BRASS, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (selected) Wood.WOOD_DARK else Wood.INK
        Text(title, style = Wood.sans(15.sp, if (selected) FontWeight.Bold else FontWeight.SemiBold, ink))
        Text(" · $note", style = Wood.sans(13.sp, if (selected) FontWeight.Medium else FontWeight.Normal, if (selected) ink else Wood.INK_SOFT),
            maxLines = 1)
    }
}

@Composable
private fun NameField(name: String, onChange: (String) -> Unit) = ParchmentField(name, onChange, "name")

/** What Back does in a text box: keyboard up, only put it away (#121); keyboard down, the screen's own Back (#146). */
internal enum class TextBoxBack { PUT_KEYBOARD_AWAY, PASS_ON }

internal fun textBoxBack(keyboardUp: Boolean) = if (keyboardUp) TextBoxBack.PUT_KEYBOARD_AWAY else TextBoxBack.PASS_ON

/**
 * For a text box: Back with the keyboard up only puts the keyboard away (#121). On Android 14 one Back did that AND
 * left the screen, closing the app from the start screen. Back is taken as a key on the box itself: an
 * OnBackPressedCallback added here sat under the screen's own BackHandler and never ran, and a Back the box didn't take
 * became Compose's "exit focus" move, which ate the key so leaving took one more Back after a hardware Enter (#146).
 * Whether the keyboard is up is asked of the window when Back arrives. With it down Back goes straight to the screen,
 * without letting go of the box first: a hardware Enter could put focus back on the first box, reopening the keyboard,
 * and Back stopped working on the Join screen.
 */
@Composable
internal fun Modifier.backPutsKeyboardAway(): Modifier {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    return onPreviewKeyEvent { e ->
        if (e.key != androidx.compose.ui.input.key.Key.Back) return@onPreviewKeyEvent false
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
            val keyboardUp = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            when (textBoxBack(keyboardUp)) {
                TextBoxBack.PUT_KEYBOARD_AWAY -> focus.clearFocus()
                TextBoxBack.PASS_ON -> dispatcher?.onBackPressed()
            }
        }
        true
    }
}

/** A one-line parchment text box with a brass edge (name, room code). */
@Composable
internal fun ParchmentField(value: String, onChange: (String) -> Unit, tag: String,
                            caps: KeyboardCapitalization = KeyboardCapitalization.Words) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Wood.sans(15.sp, FontWeight.SemiBold, Wood.PARCHMENT_INK),
        cursorBrush = SolidColor(Wood.PARCHMENT_INK),
        keyboardOptions = KeyboardOptions(capitalization = caps, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().height(32.dp).testTag(tag)
            .backPutsKeyboardAway(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(Wood.PARCHMENT, RoundedCornerShape(8.dp))
                    .border(2.dp, Wood.BRASS, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/**
 * A player's round token: their [cameo] (0.3.5) if they have one, else the photo you picked, else the initial on the
 * seat colour [ring]. A cameo or photo sits in a ring of the seat colour.
 */
@Composable
fun PhotoToken(photo: String?, name: String, ring: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 52.dp,
               cameo: String? = null, tag: String = "photo", modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val art = Cameos.ART[cameo]
    val image = rememberPhoto(photo.takeIf { art == null })
    val pictured = art != null || image != null
    Box(
        modifier.size(size).clip(CircleShape).background(if (pictured) Wood.WOOD_DARK else ring)
            .border(if (size < 40.dp) 2.dp else 3.dp, if (pictured) ring else Wood.BRASS_EDGE, CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = if (onClick != null) "Change picture" else "$name's picture" }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) Image(painterResource(art), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.trim().take(1).uppercase().ifEmpty { "?" }, style = Wood.title(size.value.times(0.4f).sp, Wood.INK))
    }
}

/** Decodes a picked photo off the main thread, downsampled to token size; null while loading or on failure. */
@Composable
private fun rememberPhoto(photo: String?): ImageBitmap? {
    val context = LocalContext.current
    val state by produceState<ImageBitmap?>(null, photo) {
        value = if (photo == null) null else withContext(Dispatchers.IO) { decodeSmall(context, Uri.parse(photo), 192) }
    }
    return state
}

private fun decodeSmall(context: Context, uri: Uri, target: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }?.asImageBitmap()
}.getOrNull()

/** Keeps read access to a picked photo across restarts where the picker allows it. */
private fun keepAccess(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

/** Online, other players see your name: it must be a real one, not the default "You". */
fun onlineName(name: String) = name.isNotBlank() && !name.trim().equals("You", ignoreCase = true)

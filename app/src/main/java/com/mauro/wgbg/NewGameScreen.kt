package com.mauro.wgbg

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
) {
    var setup by remember { mutableStateOf(initial) }
    // The deck size to come back to when switching Random -> Card deck (72 by default, as drawn).
    var lastDecks by rememberSaveable { mutableIntStateOf(if (initial.decks > 0) initial.decks else 2) }
    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            keepAccess(context, uri)
            setup = setup.copy(photo = uri.toString())
        }
    }

    Row(
        modifier.fillMaxSize().tableTop().padding(14.dp).testTag("new-game"),   // wood under the panels, like the Lobby (Fable T6)
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(Modifier.weight(1f)) {
            Text("Players", style = Wood.title(22.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhotoToken(setup.photo, setup.name, PLAYER_COLORS[0]) {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your name", style = Wood.sans(12.sp, color = Wood.INK_SOFT))
                    NameField(setup.name) { setup = setup.copy(name = it.take(MAX_NAME)) }
                }
                Text("Tap photo to pick one", style = Wood.sans(12.sp, color = Wood.INK_SOFT), modifier = Modifier.width(90.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 1 until GameSetup.MAX_SEATS) SeatCard(i, setup.seats[i], Modifier.weight(1f),
                    onKind = { setup = setup.withSeat(i, it) }, onBot = { setup = setup.withBot(i, it) })
            }
            Text("Game", style = Wood.title(18.sp), modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in Ruleset.entries) Choice(r.label, setup.ruleset == r, "ruleset-${r.name}",
                    Modifier.weight(1f).height(40.dp), radius = 9.dp, size = 15.sp) { setup = setup.withRuleset(r) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Points", style = Wood.sans(14.sp, color = Wood.INK_SOFT), modifier = Modifier.width(70.dp))
                Choice("−", false, "points-down", Modifier.size(34.dp), size = 18.sp,
                    enabled = setup.points > GameSetup.POINTS_RANGE.first) { setup = setup.withPoints(setup.points - 1) }
                Text("${setup.points}", style = Wood.sans(18.sp, FontWeight.Bold), modifier = Modifier.width(28.dp).testTag("points"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Choice("+", false, "points-up", Modifier.size(34.dp), size = 18.sp,
                    enabled = setup.points < GameSetup.POINTS_RANGE.last) { setup = setup.withPoints(setup.points + 1) }
                Spacer(Modifier.weight(1f))
                if (onFeedback != null) WoodButton("Report", "start-feedback", Modifier.height(40.dp)) { onFeedback() }
                if (onFeedback != null) Spacer(Modifier.width(8.dp))
                if (onPlayIntro != null) WoodButton("Intro", "play-intro", Modifier.height(40.dp)) { onPlayIntro() }
                if (onPlayIntro != null) Spacer(Modifier.width(8.dp))
                if (onLookAndArt != null) BrassButton("Look & art", "look-and-art", Modifier.height(40.dp)) { onLookAndArt(setup) }
            }
            if (onCardTester != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WoodButton("Card tester", "card-tester", Modifier.height(40.dp)) { onCardTester() }
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

/** One of seats 2-4: Friend / Bot / Empty, then the bot level and what it means. */
@Composable
private fun SeatCard(i: Int, seat: SeatSetup, modifier: Modifier, onKind: (SeatKind) -> Unit, onBot: (BotLevel) -> Unit) {
    Column(
        modifier.background(androidx.compose.ui.graphics.Color(0x47000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp).testTag("seat-$i"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("Seat ${i + 1}", style = Wood.sans(14.sp, FontWeight.Bold, Wood.SEAT_TEXT[i]))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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
        modifier = Modifier.fillMaxWidth().height(32.dp).testTag(tag),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(Wood.PARCHMENT, RoundedCornerShape(8.dp))
                    .border(2.dp, Wood.BRASS, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** Your round token: the photo if you picked one, else your initial on your seat color. */
@Composable
fun PhotoToken(photo: String?, name: String, ring: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 52.dp, onClick: (() -> Unit)? = null) {
    val image = rememberPhoto(photo)
    Box(
        Modifier.size(size).clip(CircleShape).background(if (image == null) ring else Wood.WOOD_DARK)
            .border(3.dp, if (image == null) Wood.BRASS_EDGE else ring, CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = if (onClick != null) "Change photo" else "$name's photo" }
            .testTag("photo"),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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

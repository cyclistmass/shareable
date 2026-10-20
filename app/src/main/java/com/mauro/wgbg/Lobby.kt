package com.mauro.wgbg

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The lobby (#19), ported from docs/design/LobbyA.dc.html with Lobby B's thin brass edge:
 * room code, invite link (copy, share, QR), the four seats with ready state and a bot picker
 * on open seats, recent players, and Start / Leave room. State is hoisted: every
 * change goes through [onChange]; OnlineLobbyScreen connects this seam to the transport.
 */
@Composable
fun LobbyScreen(
    lobby: Lobby,
    onChange: (Lobby) -> Unit,
    onStart: (GameSetup) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    reconnecting: Boolean = false,
) {
    val context = LocalContext.current
    var bigQr by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2000); copied = false } }
    Box(modifier.fillMaxSize().tableTop().testTag("lobby")) {
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Both sides scroll when they can't fit: at 320 dp tall (CI's screen) four seats fill the left, and the Recent row
            // and the right side's last lines went off the bottom.
            Column(
                Modifier.weight(1f).fillMaxHeight().brassEdgedWood().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.brassPlate(RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 2.dp)) {
                        Text("Room ${lobby.code}", style = Wood.title(24.sp, Wood.BRASS_INK), modifier = Modifier.testTag("room-code"))
                    }
                    Text("${lobby.taken} of ${GameSetup.MAX_SEATS} seats taken", style = Wood.sans(13.sp, color = Wood.INK_MUTED))
                    if (reconnecting) Text("Reconnecting…", style = Wood.sans(12.sp, color = Wood.INK_MUTED))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrassButton(if (copied) "Copied" else "Copy link", "copy-link", Modifier.height(34.dp)) { copyLink(context, lobby.link); copied = true }
                    BrassButton("Share…", "share-link", Modifier.height(34.dp)) { shareLink(context, lobby) }
                    BrassButton("Show QR", "show-qr", Modifier.height(34.dp)) { bigQr = true }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in lobby.seats.indices) SeatRow(lobby, i, onChange)
                }
                if (lobby.recent.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent:", style = Wood.sans(13.sp, color = Wood.INK_MUTED))
                    for (name in lobby.recent) Text(
                        name, style = Wood.sans(13.sp),
                        modifier = Modifier.background(Color(0x59000000), RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { shareLink(context, lobby, to = name) }
                            .padding(horizontal = 10.dp, vertical = 3.dp).testTag("recent-$name"),
                    )
                    Text("tap to invite", style = Wood.sans(13.sp, color = Wood.INK_MUTED))
                }
            }
            Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().brassEdgedWood().verticalScroll(rememberScrollState()).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    QrCode(lobby.link, Modifier.size(120.dp).clickable(role = Role.Button) { bigQr = true })
                    Text("Scan to join on another phone", style = Wood.sans(12.sp, color = Wood.INK_MUTED), textAlign = TextAlign.Center)
                    Column(Modifier.fillMaxWidth().drawBehind {
                        drawLine(Wood.BRASS_EDGE.copy(alpha = 0.45f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                    }.padding(top = 6.dp)) {
                        Text("${lobby.setup.ruleset.label} · ${lobby.setup.points} points", style = Wood.sans(13.sp))
                        Text("Dice: ${GameSetup.diceLabel(lobby.setup.decks).replaceFirstChar { it.lowercaseChar() }}",
                            style = Wood.sans(13.sp))
                    }
                }
                if (lobby.isHost) {
                    lobby.waitingOn.firstOrNull()?.let {
                        Text(it, style = Wood.sans(13.sp, FontWeight.Bold, Wood.WAITING), modifier = Modifier.testTag("waiting-on"))
                    }
                    GoButton("Start game", "start", lobby.canStart, Modifier.fillMaxWidth().height(46.dp)) { onStart(lobby.gameSetup()) }
                    BrassButton("Leave room", "leave-room", Modifier.fillMaxWidth().height(36.dp)) { confirmLeave = true }
                } else Text("Waiting for ${lobby.seats[0].label} to start", style = Wood.sans(14.sp, FontWeight.Bold, Wood.INK_MUTED))
            }
        }
        if (confirmLeave) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            androidx.activity.compose.BackHandler { confirmLeave = false }
            Column(Modifier.widthIn(max = 440.dp).brassEdgedWood().padding(18.dp).testTag("leave-room-confirm"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Leave room ${lobby.code}?", style = Wood.title(20.sp))
                Text("You go back to the game settings.", style = Wood.sans(14.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoButton("Stay", "leave-room-no", true, Modifier.heightIn(min = 44.dp)) { confirmLeave = false }
                    StopButton("Leave room", "leave-room-yes", Modifier.heightIn(min = 44.dp)) { confirmLeave = false; onSettings() }
                }
            }
        }
        if (bigQr) Box(
            Modifier.fillMaxSize().background(Color(0xE6080402)).clickable { bigQr = false }.testTag("qr-overlay"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QrCode(lobby.link, Modifier.size(260.dp))
                Text("Room ${lobby.code} · tap anywhere to close", style = Wood.sans(15.sp, FontWeight.Bold))
            }
        }
    }
}

/** One seat: token, name, and status; open seats show who fills them and (for the host) the bot picker. */
@Composable
private fun SeatRow(lobby: Lobby, i: Int, onChange: (Lobby) -> Unit) {
    val seat = lobby.seats[i]
    val shape = RoundedCornerShape(10.dp)
    val rowMod = if (seat.open) Modifier.drawBehind {
        drawRoundRect(
            Wood.BRASS_EDGE.copy(alpha = 0.6f), cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }.padding(horizontal = 10.dp, vertical = 4.dp)
    else Modifier.background(Color(0x52000000), shape).padding(horizontal = 10.dp, vertical = 5.dp)
    Row(rowMod.fillMaxWidth().testTag("lobby-seat-$i"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (seat.open) Box(
            Modifier.size(32.dp).background(PLAYER_COLORS[i], androidx.compose.foundation.shape.CircleShape)
                .border(2.dp, Wood.BRASS_EDGE, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("+", style = Wood.sans(16.sp, FontWeight.Bold, if (i == 3) Wood.PARCHMENT_INK else Wood.INK)) }
        else PhotoToken(seat.photo, seat.label, PLAYER_COLORS[i], size = 32.dp)
        when {
            seat.open -> {
                Text(
                    if (seat.bot != null) "Open seat · a bot plays if nobody joins" else "Open seat · for a person only",
                    style = Wood.sans(14.sp, color = Wood.INK_MUTED), modifier = Modifier.weight(1f), maxLines = 1,
                )
                if (lobby.isHost) BotPicker(seat.bot, i, allowNone = true) { onChange(lobby.setBot(i, it)) }
            }
            seat.isPerson -> {
                Text(
                    seat.name!! + if (i == 0) " (host)" else "",
                    style = Wood.sans(15.sp, FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1,
                )
                val mine = i == lobby.me
                Text(
                    if (seat.ready) "Ready" else "Not ready",
                    style = Wood.sans(13.sp, FontWeight.Bold, if (seat.ready) Wood.READY else Wood.WAITING),
                    modifier = Modifier
                        .then(if (mine) Modifier.border(1.dp, Wood.BRASS_DIM, RoundedCornerShape(7.dp))
                            .clickable(role = Role.Switch) { onChange(lobby.toggleReady()) }
                            .semantics { contentDescription = if (seat.ready) "Ready, tap to undo" else "Tap when ready" }
                        else Modifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp).testTag("ready-$i"),
                )
            }
            else -> {
                Text(seat.label, style = Wood.sans(15.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                if (lobby.isHost) BotPicker(seat.bot, i, allowNone = false) { onChange(lobby.setBot(i, it)) }
                else Text("Ready", style = Wood.sans(13.sp, FontWeight.Bold, Wood.READY))
            }
        }
    }
}

/**
 * Human / Easy / Greedy / Strong on open seats (Human: keep it for a person, no bot fills it), Easy / Greedy / Strong on
 * bot seats. The chosen one is a bright brass plate with a check; the rest are dimmed, so it's clear what you picked
 * (Mauro: it was easy to uninvite someone while setting bot strength).
 */
@Composable
private fun BotPicker(current: BotLevel?, seat: Int, allowNone: Boolean, onPick: (BotLevel?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val options: List<BotLevel?> = (if (allowNone) listOf<BotLevel?>(null) else emptyList()) + BotLevel.offered
        for (b in options) {
            val on = b == current?.plays
            val shape = RoundedCornerShape(7.dp)
            Box(
                Modifier.height(26.dp)
                    .then(if (on) Modifier.brassPlate(shape) else Modifier.background(Color(0x59000000), shape).border(1.dp, Wood.BRASS_DIM, shape))
                    .clickable(role = Role.RadioButton) { onPick(b) }
                    .padding(horizontal = 8.dp).testTag("lobby-seat-$seat-bot-${b?.name ?: "NONE"}"),
                contentAlignment = Alignment.Center,
            ) {
                Text((if (on) "✓ " else "") + (b?.label ?: "Human"), style = Wood.sans(11.sp, if (on) FontWeight.Bold else FontWeight.Normal,
                    if (on) Wood.BRASS_INK else Wood.INK.copy(alpha = 0.55f)))
            }
        }
    }
}

/** A QR code for [text] on a parchment card (dark modules, quiet zone included). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(
        modifier.background(Wood.PARCHMENT, RoundedCornerShape(6.dp)).border(3.dp, Wood.BRASS_DIM, RoundedCornerShape(6.dp))
            .padding(6.dp).semantics { contentDescription = "QR code for $text" }.testTag("qr"),
    ) {
        val cell = size.minDimension / matrix.width
        val left = (size.width - cell * matrix.width) / 2
        val top = (size.height - cell * matrix.height) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y])
            drawRect(Wood.PARCHMENT_INK, Offset(left + x * cell, top + y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

/** QR modules for [text]: medium error correction, a 1-module margin (the card adds the rest). */
fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))

private fun copyLink(context: Context, link: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText("Invite link", link))
}

/** The invite text: who, which room, the link. Shared through the Android share sheet. */
fun inviteText(lobby: Lobby, to: String? = null) =
    "${to?.let { "$it, j" } ?: "J"}oin my game: room ${lobby.code}, ${lobby.setup.ruleset.label}. ${lobby.link}"

private fun shareLink(context: Context, lobby: Lobby, to: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText(lobby, to))
    context.startActivity(Intent.createChooser(send, "Invite to room ${lobby.code}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}



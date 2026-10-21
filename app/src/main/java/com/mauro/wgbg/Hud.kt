package com.mauro.wgbg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.imageResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Card-border colors for Timber, Clay, Fleece, Grain, Stone (matches the card designs). */
internal val CARD_COLORS = listOf(Color(0xFF2E7D32), Color(0xFFBF5B30), Color(0xFF6E9A45), Color(0xFFC9962C), Color(0xFF4A5E80))
private val FORTUNE_COLOR = Color(0xFF5B3A6E)
internal val CARD_FACE = Color(0xFFF2E7D3)
private val HUD_TEXT = Color(0xFFEDE6D8)

/** The roll and what came of it, pinned to a corner of the board. */
@Composable
fun TurnHud(report: TurnReport?, previous: TurnReport?, names: List<String>, modifier: Modifier = Modifier,
            /** This phone's player (their turns read "Your turn"), or -1 when only bots play. */
            me: Int = -1,
            /** Earlier turns, newest first: shown below this turn so you can scroll back to see what everyone did (#65). */
            history: List<TurnReport> = emptyList(),
            /** Reports where the two dice are (root coordinates), so thrown dice can fly to them. */
            onDiceSlots: ((androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect) -> Unit)? = null,
            /** Age of Chivalry: where the event die rests (root coordinates). */
            onEventSlot: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
            /** Age of Chivalry: the panel has a third pad for the event die. */
            eventDie: Boolean = false,
            /** The panel's height; shorter when the player list above needs the room (Age of Chivalry). */
            height: androidx.compose.ui.unit.Dp = 210.dp,
            /** False while thrown dice are still in the air (the resting dice appear when they land). */
            showDice: Boolean = true,
            /** False while the first pieces are placed: no dice are rolled yet, so no empty pads either. */
            dicePads: Boolean = true,
            /** Dice set: false = Normal (carved red + ivory), true = Pirate (ship-timber + bone, dark brass pips). */
            pirate: Boolean = false,
            /** Settings > Event die: which pirate event die art (DiceArt.EVENT_STYLES). */
            eventStyle: Int = 0,
            /** Width; inside the one left side panel it matches the player list above (#73), and the news wraps. */
            width: androidx.compose.ui.unit.Dp = 360.dp,
            /** False inside the one left side panel, which draws the frame for both halves. */
            framed: Boolean = true,
            /** A double tap on the dice: open the roll histogram (Mauro). */
            onDiceDoubleTap: (() -> Unit)? = null) {
    if (report == null) return
    // Fixed size, so the dice always sit in the same spot. The dice and whose turn it is stay pinned at the top; this
    // turn's news and the earlier turns below scroll, with a brass scroll bar on the right (#65).
    Column(
        modifier.size(width = width, height = height)
            .then(if (framed) Modifier.brassEdgedWood() else Modifier)
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),   // the dice sit right under the players (Mauro)
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val doubleTapNow = androidx.compose.runtime.rememberUpdatedState(onDiceDoubleTap)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            // Named for screen readers (and for tools/ci_reach.sh, which double-taps it for the histogram).
            modifier = Modifier.testTag("dice-row").semantics { contentDescription = "Dice" }.then(if (onDiceDoubleTap != null) Modifier.pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { doubleTapNow.value?.invoke() })   // today's handler, not the first one (#122)
            } else Modifier)) {
            val color = PLAYER_COLORS[report.player]
            androidx.compose.foundation.layout.Spacer(Modifier.width((DIE_DP * 3 / 4).dp))   // clear of the camera hole (Mauro)
            if (dicePads) run {
                var r1 by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                Die3d(report.die1, red = true, pirate = pirate, pad = color, visible = showDice && report.rolled, modifier = Modifier.onGloballyPositioned { r1 = it.boundsInRoot() })
                Die3d(report.die2, red = false, pirate = pirate, pad = color, visible = showDice && report.rolled, modifier = Modifier.onGloballyPositioned { c -> r1?.let { a -> onDiceSlots?.invoke(a, c.boundsInRoot()) } })
                if (eventDie) EventDie3d(report.event, pirate = pirate, style = eventStyle, pad = color, visible = showDice && report.rolled && report.event >= 0,
                    modifier = Modifier.onGloballyPositioned { onEventSlot?.invoke(it.boundsInRoot()) })
                if (report.rolled && showDice) Text("= ${report.die1 + report.die2}", color = HUD_TEXT, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            // No "X's turn" here: that player's row is already highlighted above (Mauro).
        }
        val scroll = androidx.compose.foundation.rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().padding(end = 22.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (line in report.production) Text(line, color = HUD_TEXT, fontSize = 12.sp, maxLines = 2)
        for (line in report.events) Text(line, color = HUD_TEXT.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2)
        val earlier = history.filter { it !== report }
        if (earlier.isNotEmpty()) {
            // Scroll down for earlier turns: each one's player and roll, then everything that happened.
            for (t in earlier) {
                Spacer(Modifier.height(4.dp))
                Text(
                    // "Turn 3 · Blue rolled 9", not "Blue's turn · rolled 9 · turn 3" (Fable review T8).
                    "Turn ${t.turn + 1} · " + (if (t.player == me) "You" else names[t.player]) + (if (t.rolled) " rolled ${t.die1 + t.die2}" else ""),
                    color = PLAYER_COLORS[t.player].copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = 12.sp,
                )
                for (line in t.production) Text(line, color = HUD_TEXT.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 2)
                for (line in t.events) Text(line, color = HUD_TEXT.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 2)
            }
        } else if (previous != null && previous.rolled) {
            Text(
                "Last turn: ${names[previous.player]} rolled ${previous.die1 + previous.die2}. " +
                    previous.production.joinToString("; "),
                color = HUD_TEXT.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 2,
            )
        }
        }
        BrassScrollBar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(20.dp))
        }
    }
}

/** A thin worn-brass scroll bar for [state]: a dark groove with a brass thumb; hidden when nothing scrolls. */
@Composable
private fun BrassScrollBar(state: androidx.compose.foundation.ScrollState, modifier: Modifier) {
    if (state.maxValue <= 0) return
    // Drag the thumb (or tap the groove) to move through the history; a thumb swipe on the text works too (Mauro).
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Canvas(modifier.pointerInput(state) {
        detectTapGestures { at -> scope.launch { state.scrollTo((state.maxValue * at.y / size.height).toInt().coerceIn(0, state.maxValue)) } }
    }.pointerInput(state) {
        detectVerticalDragGestures { change, dy ->
            change.consume()
            scope.launch { state.scrollBy(dy * (state.maxValue + size.height).toFloat() / size.height.coerceAtLeast(1)) }
        }
    }) {
        // A wide touch strip, the bar itself drawn thin along its right edge.
        val bw = 5.dp.toPx(); val x = size.width - bw
        val r = androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2)
        drawRoundRect(Color(0x66000000), topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(bw, size.height), cornerRadius = r)
        val visible = size.height / (size.height + state.maxValue)
        val thumbH = (size.height * visible).coerceAtLeast(bw * 4)
        val top = (size.height - thumbH) * state.value / state.maxValue
        drawRoundRect(Color(0xFFB08A3E), topLeft = androidx.compose.ui.geometry.Offset(x, top),
            size = androidx.compose.ui.geometry.Size(bw, thumbH), cornerRadius = r)
    }
}

/** A small ivory die with pips, edged in the roller's color. */
@Composable
private fun Die(value: Int, edge: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(34.dp)) {
        val r = size.minDimension * 0.18f
        drawRoundRect(Color(0xFFFBF5E8), cornerRadius = CornerRadius(r))
        drawRoundRect(edge, cornerRadius = CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.1f))
        val q = size.minDimension / 4f
        val spots = when (value) {
            1 -> listOf(2 to 2)
            2 -> listOf(1 to 1, 3 to 3)
            3 -> listOf(1 to 1, 2 to 2, 3 to 3)
            4 -> listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3)
            5 -> listOf(1 to 1, 3 to 1, 2 to 2, 1 to 3, 3 to 3)
            else -> listOf(1 to 1, 3 to 1, 1 to 2, 3 to 2, 1 to 3, 3 to 3)
        }
        for ((x, y) in spots) drawCircle(Color(0xFF2B2118), size.minDimension * 0.09f, Offset(x * q, y * q))
    }
}

/** A player's hand as mini cards: one per resource they hold with a count, plus face-down Fortune cards. */
@Composable
fun MiniHand(hand: List<Int>, fortune: Int, modifier: Modifier = Modifier, facedown: Int = 0) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        hand.forEachIndexed { r, n -> if (n > 0) MiniCard(CARD_COLORS[r], RESOURCE_NAMES[r].take(2), n, face = CARD_FACE) }
        // Someone else's hand while you play: just how many cards, face down.
        if (facedown > 0) MiniCard(Color(0xFF8A6A2A), "?", facedown, face = Color(0xFF5A4030))
        if (fortune > 0) MiniCard(FORTUNE_COLOR, "F", fortune, face = Color(0xFF8E6BA3))
        if (hand.all { it == 0 } && facedown == 0 && fortune == 0) Text("no cards", color = HUD_TEXT.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

@Composable
private fun MiniCard(edge: Color, label: String, count: Int, face: Color) {
    Box(
        Modifier.size(width = 22.dp, height = 31.dp)
            .background(face, RoundedCornerShape(4.dp))
            .border(2.dp, edge, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (LocalArtChoice.current.space(ArtObject.CARDS) && label in listOf("F", "?"))
            androidx.compose.foundation.Image(artPainter(SpaceArt.cardBack), null, Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = edge, fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
            Text("$count", color = Color(0xFF2B2118), fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp)
        }
    }
}

/** A resting 3D die (the same sprites the throw uses) at the panel's dice size, showing [value] on top. */
@Composable
private fun Die3d(value: Int, red: Boolean, pirate: Boolean, pad: Color, visible: Boolean, modifier: Modifier = Modifier) {
    val face = if (visible) rememberDiceBitmap(ThemeArt.dice(LocalArtChoice.current, red, value), DIE_DP) else null
    val outline = androidx.compose.runtime.remember { androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f) }
    Canvas(modifier.size(DIE_DP.dp)) {
        // The die is centred in its slot (where the thrown die lands) at DIE_FACE_SHARE of it; the pad in the
        // roller's colour shows a thin even halo of DIE_HALO_DP round it (shown even while the dice fly).
        val side = size.width * DIE_FACE_SHARE
        val halo = DIE_HALO_DP.dp.toPx()
        val padTopLeft = Offset(size.width / 2 - side / 2 - halo, size.height / 2 - side / 2 - halo)
        val padSize = Size(side + 2 * halo, side + 2 * halo)
        val r = CornerRadius(padSize.minDimension * 0.2f)
        drawRoundRect(pad.copy(alpha = 0.85f), topLeft = padTopLeft, size = padSize, cornerRadius = r)
        drawRoundRect(Color.Black.copy(alpha = 0.35f), topLeft = padTopLeft, size = padSize, cornerRadius = r,
            style = outline)
        if (face == null) return@Canvas
        // The hand-made die (Codex art), square on the pad with the roll on top.
        drawImage(face, dstOffset = androidx.compose.ui.unit.IntOffset((size.width / 2 - side / 2).toInt(), (size.height / 2 - side / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
    }
}

/** The Age of Chivalry event die resting on its pad: the Raiders' ship or a gate (Guilds, Academy, Crown). */
@Composable
private fun EventDie3d(face: Int, pirate: Boolean, style: Int, pad: Color, visible: Boolean, modifier: Modifier = Modifier) {
    val img = if (visible) rememberDiceBitmap(ThemeArt.event(LocalArtChoice.current, face.coerceAtLeast(0), style), DIE_DP) else null
    Canvas(modifier.size(DIE_DP.dp)) {
        val side = size.width * DIE_FACE_SHARE
        val halo = DIE_HALO_DP.dp.toPx()
        val padTopLeft = Offset(size.width / 2 - side / 2 - halo, size.height / 2 - side / 2 - halo)
        val padSize = Size(side + 2 * halo, side + 2 * halo)
        drawRoundRect(pad.copy(alpha = 0.85f), topLeft = padTopLeft, size = padSize, cornerRadius = CornerRadius(padSize.minDimension * 0.2f))
        if (img != null) drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset((size.width / 2 - side / 2).toInt(), (size.height / 2 - side / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
    }
}

/** The resting-face image for a die: [red] or ivory/bone, Normal or [pirate] set, showing [value]. */
fun diceFace(red: Boolean, pirate: Boolean, value: Int): Int = DiceArt.face(red, pirate, value)

/** Size of the panel's dice; thrown dice land at exactly this size. */
const val DIE_DP = 36
/** The die's share of its slot (Mauro: 2% bigger than the first 0.9); the thrown die lands at the same size. */
const val DIE_FACE_SHARE = 0.918f
/** The roller's-colour halo round a resting die, dp (Mauro: 2). */
const val DIE_HALO_DP = 2

package com.mauro.wgbg

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wgbg.engine.Action
import wgbg.engine.ActionType
import wgbg.engine.Board
import wgbg.engine.CARD_TYPES
import wgbg.engine.DevCard
import wgbg.engine.GameState
import wgbg.engine.Phase
import kotlin.math.hypot
import kotlin.math.sqrt
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// ------------------------------------------------------------------ board targets (issue #5)

/** Where the human can act on the board right now, derived from the legal moves. */
class Targets(val vertices: Set<Int>, val edges: Set<Int>, val hexes: Set<Int>) {
    val isEmpty get() = vertices.isEmpty() && edges.isEmpty() && hexes.isEmpty()

    companion object {
        /** The glowing spots for a board card's choices (see [cardSpotIndex]). */
        fun ofCards(acts: List<Action>, second: Boolean = false): Targets {
            fun idx(k: CardSpot) = acts.filter { cardSpotKind(it.a) == k }.map { cardSpotIndex(it, second) }.toSet()
            return Targets(idx(CardSpot.VERTEX), idx(CardSpot.EDGE), idx(CardSpot.HEX))
        }

        fun of(legal: List<Action>) = Targets(
            vertices = legal.filter { it.type == ActionType.BUILD_SETTLEMENT || it.type == ActionType.BUILD_CITY }.map { it.payload.toInt() }.toSet() +
                legal.filter { it.type in CHIVALRY_VERTEX_KINDS }.map { chivalryVertex(it) }.filter { it >= 0 },
            edges = legal.filter { it.type == ActionType.BUILD_ROAD }.map { it.payload.toInt() }.toSet(),
            hexes = legal.filter { it.type == ActionType.MOVE_ROBBER }.map { it.robberHex }.toSet(),
        )
    }
}

private val SQRT3 = sqrt(3f)
fun vertexLattice(b: Board, v: Int) = b.topology.vertexPos[v].let { Offset(it.x * SQRT3 / 2f, it.y / 2f) }
fun edgeLattice(b: Board, e: Int) = b.topology.edgeVertices[e].let { (vertexLattice(b, it[0]) + vertexLattice(b, it[1])) / 2f }
fun hexLattice(b: Board, h: Int) = b.topology.hexVertices[h].map { vertexLattice(b, it) }.let { ps ->
    Offset(ps.map { it.x }.average().toFloat(), ps.map { it.y }.average().toFloat())
}

/**
 * The legal moves a tap at lattice point [p] means: a build at the nearest glowing corner or
 * edge, or the bandit moves for a tile (several when there is a choice of victim). Empty if the
 * tap is not near any target.
 */
/** Where on the board (lattice units) a player taps for move [a], or null for moves that aren't board taps. */
fun spotOf(b: Board, a: Action, second: Boolean = false): Offset? = when (a.type) {
    wgbg.engine.ChivalryActionType.PLAY_PROGRESS -> cardSpotKind(a.a)?.let { k ->
        val i = cardSpotIndex(a, second)
        when (k) { CardSpot.VERTEX -> vertexLattice(b, i); CardSpot.EDGE -> edgeLattice(b, i); CardSpot.HEX -> hexLattice(b, i) }
    }
    ActionType.BUILD_SETTLEMENT, ActionType.BUILD_CITY -> vertexLattice(b, a.payload.toInt())
    ActionType.BUILD_ROAD -> edgeLattice(b, a.payload.toInt())
    ActionType.MOVE_ROBBER -> hexLattice(b, a.robberHex)
    in CHIVALRY_VERTEX_KINDS -> chivalryVertex(a).takeIf { it >= 0 }?.let { vertexLattice(b, it) }
    else -> null
}

/** How near a tap must land to a spot, in hex sides: generous enough for fingers, tight enough to be unambiguous. */
const val TAP_RADIUS = 0.42f

fun movesAt(b: Board, legal: List<Action>, p: Offset, second: Boolean = false): List<Action> {
    var best: List<Action> = emptyList()
    var bestD = TAP_RADIUS
    for (a in legal) {
        val at = spotOf(b, a, second) ?: continue
        val tile = a.type == ActionType.MOVE_ROBBER || (a.type == wgbg.engine.ChivalryActionType.PLAY_PROGRESS && cardSpotKind(a.a) == CardSpot.HEX)
        val d = hypot(at.x - p.x, at.y - p.y) * if (tile) 0.5f else 1f // tiles are big targets
        if (d < bestD - 1e-4f) { bestD = d; best = listOf(a) }
        else if (kotlin.math.abs(d - bestD) < 1e-4f) best = best + a
    }
    return best
}

// ------------------------------------------------------------------ the human's controls

private val RES = RESOURCE_NAMES
internal val FORTUNE = listOf("Sentinel", "Windfall", "Corner the Market", "Trailblazers", "Landmark")

/** What each Fortune card does (same order as [FORTUNE]). */
val FORTUNE_HINTS = listOf(
    "Move the bandit and take a card from a player next to it. Three or more win the Strongest Watch.",
    "Pick 2 resources from the bank, alike or different.",
    "Name a resource: everyone gives you all of theirs.",
    "Build 2 roads for free.",
    "Worth 1 point (kept hidden until the end).",
)
private val PANEL = Color(0xE6141210)
private val INK_LIGHT = Color(0xFFEDE6D8)

/** One-line instruction for what the human should do now. */
fun prompt(s: GameState, legal: List<Action>, names: List<String>): String =
    if (legal.isEmpty() && !s.isOver) "Waiting for ${names[s.actingPlayer]}…" else chivalryPrompt(s, legal, names) ?: when {
    // Age of Chivalry: the second piece placed is a town, straight onto an empty corner.
    s.phase == Phase.BUILD_INITIAL_SETTLEMENT -> "Tap a glowing corner to place a ${if (legal.any { it.type == ActionType.BUILD_CITY }) "town" else "homestead"}"
    s.phase == Phase.BUILD_INITIAL_ROAD -> "Tap a glowing edge to place a road"
    // After a 7 the bandit comes first and nothing else can be played, which read as the Merchant asking about the
    // bandit (report rZghe3G, 0.2.8: the game was waiting on the bandit after a 7). Say why.
    s.phase == Phase.MOVE_ROBBER && s.hasRolled && s.lastRoll == 7 -> "You rolled 7: move the bandit first, then you can play cards"
    s.phase == Phase.MOVE_ROBBER -> "Tap a tile to move the bandit"
    s.phase == Phase.DISCARD -> "Too many cards on a 7: discard ${s.discardOwed[s.actingPlayer]}"
    s.phase == Phase.DECIDE_TRADE && s.tradeOffer.sum() > 0 && (0 until CARD_TYPES).all { s.tradeOffer[it] == 0 } ->
        "${names[s.currentPlayer]} asks for ${cards { s.tradeOffer[CARD_TYPES + it] }}: Counter with your price, or No"
    s.phase == Phase.DECIDE_TRADE && s.tradeOffer.sum() > 0 && (0 until CARD_TYPES).all { s.tradeOffer[CARD_TYPES + it] == 0 } ->
        "${names[s.currentPlayer]} offers ${cards { s.tradeOffer[it] }}: Counter with what you want, or No"
    s.phase == Phase.DECIDE_TRADE -> "${names[s.currentPlayer]} offers ${cards { s.tradeOffer[it] }} for ${cards { s.tradeOffer[CARD_TYPES + it] }}"
    s.phase == Phase.DECIDE_ACCEPTEES -> "Pick who to trade with, or cancel"
    legal.any { it.type == ActionType.ROLL } -> "Your turn: roll the dice"
    // Trailblazers, Pathfinders or Envoy (moving your own road): place the free road(s) before anything else.
    s.freeRoads > 0 -> "Place ${if (s.freeRoads > 1) "${s.freeRoads} free roads" else "your free road"} first: tap a glowing edge"
    legal.any { it.type == ActionType.BUILD_ROAD || it.type == ActionType.BUILD_SETTLEMENT || it.type == ActionType.BUILD_CITY } ->
        "Tap a glowing spot to build, or trade, or end your turn"
    else -> if (s.chivalry != null) "Trade, improve your towns, or end your turn" else "Trade, buy a Fortune card, or end your turn"
}

private fun cards(n: (Int) -> Int) = (0 until CARD_TYPES).filter { n(it) > 0 }.joinToString(", ") { "${n(it)} ${RES[it]}" }.ifEmpty { "nothing" }

/**
 * Why a tap on the board did nothing, when that's worth saying: moving the bandit onto the tile it
 * already stands on (it has to move). Null otherwise.
 */
fun boardTapHint(b: Board, legal: List<Action>, robberHex: Int, p: Offset): String? {
    if (robberHex < 0 || legal.none { it.type == ActionType.MOVE_ROBBER }) return null
    return if ((hexLattice(b, robberHex) - p).getDistance() < 0.75f) "The bandit is already here: pick another tile" else null
}

/**
 * Buttons for everything that isn't a tap on the board: roll, end turn, Fortune cards, bank and
 * discarding (trades live in [TradePanel]). Every button sends a move from [legal] (or a trade
 * offer the engine validates), so nothing here can bypass the rules.
 */
@Composable
fun HumanControls(
    s: GameState,
    me: Int,
    legal: List<Action>,
    names: List<String>,
    onMove: (Action) -> Unit,
    modifier: Modifier = Modifier,
    /** A board card's choices: picked by tapping the board instead of from a list (Mauro, 2026-10-11). */
    onBoardPick: (List<Action>) -> Unit = {},
    /** The Trade button, set beside Roll / End turn in the bottom-right corner (Mauro: "next to each other"). */
    tradeButton: (@Composable () -> Unit)? = null,
    /** Why you must discard, when it isn't a rolled 7 (Wrecker played against you); null for a 7. */
    discardCause: String? = null,
    /** A Fortune card tapped in the hand row ([HandRow]), to play now; [onFortuneDone] clears it. */
    fortuneAsk: Int? = null,
    onFortuneDone: () -> Unit = {},
    /** The camera cutout's rects (window px): a button level with one steps left of it, the rest stay flush (0.3.6). */
    holes: List<androidx.compose.ui.geometry.Rect> = emptyList(),
) {
    var sheet by remember { mutableStateOf<String?>(null) } // which picker is open
    // A Fortune card tapped in the hand: Sentinel and Trailblazers play at once, Windfall and Corner the Market open
    // their picker here.
    androidx.compose.runtime.LaunchedEffect(fortuneAsk) {
        when (fortuneAsk) {
            null -> return@LaunchedEffect
            DevCard.KNIGHT -> legal.firstOrNull { it.type == ActionType.PLAY_KNIGHT_CARD }?.let(onMove)
            DevCard.ROAD_BUILDING -> legal.firstOrNull { it.type == ActionType.PLAY_ROAD_BUILDING }?.let(onMove)
            DevCard.YEAR_OF_PLENTY -> if (legal.any { it.type == ActionType.PLAY_YEAR_OF_PLENTY }) sheet = "yop"
            DevCard.MONOPOLY -> if (legal.any { it.type == ActionType.PLAY_MONOPOLY }) sheet = "mono"
        }
        onFortuneDone()
    }
    var chivalryChoices by remember { mutableStateOf<Pair<String, List<Action>>?>(null) }
    // What a picker says under each card: open, it covers the hand row, so the counts would be nowhere on screen.
    val have = { card: Int -> cardChoiceUnder(s, me, me, card) }
    val types = legal.map { it.type }.toSet()
    // A picker left open after its moves stopped being legal (a new turn, a bot's move, a resumed game) hid the
    // Roll button under a prompt that said roll (#105). Drop any sheet that has nothing left to pick.
    if (sheet != null && !sheetStillLegal(sheet, chivalryChoices?.second, legal)) sheet = null
    // The buttons in a column hugging the right edge with the main one (Roll / End turn) at the very bottom, within
    // reach of a right thumb. Every card sheet (pick, give, discard, name a card) sits in one place: bottom centre,
    // the lower third of the screen, like the trade chest (Mauro: "same card puller/giver ... for thumb reach").
    Box(modifier) {
        // Board A: the prompt, points and hand live elsewhere (TurnPrompt, PlayerStrip, HandRow); this
        // panel only appears for Fortune cards and the pickers.
        // Fortune cards sit in the hand row now (tap to play, hold to read); this panel is only for the pickers.
        if (sheet != null) Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).chest().testTag("card-sheet"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (sheet) {
                "yop" -> Picker("Windfall: ${FORTUNE_HINTS[DevCard.YEAR_OF_PLENTY]}", legal.filter { it.type == ActionType.PLAY_YEAR_OF_PLENTY }
                    .map { a -> (RES[a.yopFirst] + if (a.yopSecond >= 0) " + ${RES[a.yopSecond]}" else "") to a }, onMove, have) { sheet = null }
                "mono" -> Picker("Corner the Market: everyone hands you all of the one you name:", legal.filter { it.type == ActionType.PLAY_MONOPOLY }
                    .map { a -> RES[a.payload.toInt()] to a }, onMove, have) { sheet = null }
                "chivalry" -> chivalryChoices?.let { (label, acts) ->
                    // The Transmuter picks two dice: show two rows of dice, not 36 "1 / 5" buttons (Mauro, 2026-10-12).
                    if (isAlchemist(acts)) { DicePicker(label, acts, onMove) { sheet = null }; return@let }
                    val hint = acts.firstOrNull { it.type == wgbg.engine.ChivalryActionType.PLAY_PROGRESS }?.let { progressHint(it.a) }
                    Picker(if (hint.isNullOrEmpty()) label else "$label: $hint", acts.map { a -> (if (a.type == wgbg.engine.ChivalryActionType.PLAY_PROGRESS) progressOption(s, a, names) else if (a.type == ActionType.MARITIME_TRADE) bankLabel(a) else chivalryLabel(s, a, names) ?: a.toString()) to a }, onMove, have) { sheet = null }
                }
            }
        }
        // On a 7: pick all the cards first, then discard them with one press (playtest: a tap per card,
        // on buttons that moved as card types ran out, lost the wrong card with no undo).
        if (ActionType.DISCARD in types) { Box(Modifier.align(Alignment.BottomCenter)) { DiscardSheet(s, me, legal, onMove, discardCause) }; return@Box }
        cardPick(s, legal, names)?.let { pick -> CardPickSheet(s, me, pick, names, onMove, Modifier.align(Alignment.BottomCenter)); return@Box }
        if (wgbg.engine.ChivalryActionType.CHOOSE_CARD in types) {
            CardChoiceSheet(s, me, legal.filter { it.type == wgbg.engine.ChivalryActionType.CHOOSE_CARD }, names, onMove, Modifier.align(Alignment.BottomCenter)); return@Box
        }
        val buttonTypes = setOf(ActionType.ROLL, ActionType.END_TURN, ActionType.BUY_DEVELOPMENT_CARD)
        if (sheet == null && (types.any { it in buttonTypes } || chivalryButtonGroups(s, legal, names).isNotEmpty() || tradeButton != null)) Column(
            Modifier.align(Alignment.BottomEnd).width(176.dp).testTag("thumb-buttons"),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            @Composable fun single(t: ActionType, label: String, primary: Boolean = false) {
                legal.firstOrNull { it.type == t }?.let { a -> MoveButton(label, primary) { onMove(a) } }
            }
            // Buying a Fortune card lives in the build menu on the right edge now.
            // Fortune cards are played from the hand row (tap the card).
            // Age of Chivalry moves that aren't board taps: one button per kind (or card), a picker if it has choices.
            for ((label, acts) in chivalryButtonGroups(s, legal, names)) {
                Box(Modifier.dodgeHolesEnd(holes)) {
                    if (acts.size == 1 && !isBoardCard(acts) && !asksFirst(acts[0])) MoveButton(label) { onMove(acts[0]) }
                    else if (isBoardCard(acts)) MoveButton("$label…") { onBoardPick(acts) }
                    else MoveButton("$label…") { chivalryChoices = label to acts; sheet = "chivalry" }
                }
            }
            // The main move last, in the bottom-right corner, with Trade right beside it: icons, not words (Mauro). Where
            // the build menu down the right edge reaches the corner (a short phone), they sit left of it.
            val clear = buildMenuReaches(s.chivalry != null, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp, TURN_PLATE)
            Row(Modifier.dodgeHolesEnd(holes).padding(end = if (clear) BUILD_PLATE + 10.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                tradeButton?.invoke()
                // Roll and End turn share one plate (0.3.5 reports): the dice until you roll, then the hourglass. The rules
                // never offer both at once.
                legal.firstOrNull { it.type == ActionType.ROLL }?.let { a ->
                    ArtPlateButton(R.drawable.icon_roll_on, R.drawable.icon_roll_off, "Roll", "move-Roll", onClick = moveTap { onMove(a) }) }
                    ?: legal.firstOrNull { it.type == ActionType.END_TURN }?.let { a ->
                        ArtPlateButton(R.drawable.icon_end_turn_on, R.drawable.icon_end_turn_off, "End turn", "move-End turn", onClick = moveTap { onMove(a) }) }
            }
        }
    }
}

/**
 * The discard pile after tapping card [r] ([add] = tap, else hold to take one back): never more of a card
 * than you hold, never more cards in all than you owe.
 */
fun discardPick(pile: IntArray, r: Int, add: Boolean, have: Int, owed: Int): IntArray {
    val next = pile.copyOf()
    if (add) { if (next[r] < have && next.sum() < owed) next[r]++ } else if (next[r] > 0) next[r]--
    return next
}

/** The discard moves for a full [pile], one card per move, in card order. */
fun discardMoves(pile: IntArray): List<Action> = pile.indices.flatMap { r -> List(pile[r]) { Action.discard(r) } }

@Composable
private fun DiscardSheet(s: GameState, me: Int, legal: List<Action>, onMove: (Action) -> Unit, cause: String? = null) {
    // The same chest as the trade and the science bonus: title, the cards with "have N" under them, the chest's
    // buttons on the right (Mauro: "the discard menu for 7 should be the same style as trade and science bonus").
    val owed = s.discardOwed[me]
    var pile by remember(s.turn, owed) { mutableStateOf(IntArray(tradeCards(s))) }
    val full = pile.sum() == owed
    Row(Modifier.chest().testTag("discard-sheet").semantics { testTagsAsResourceId = true }, verticalAlignment = Alignment.CenterVertically) {
        // Weighted, so the buttons are measured first and always on screen; the cards shrink, then scroll, to fit what's
        // left (0.3.4: 8 cards at font 1.3 on a 640 dp phone covered Discard, and a player who had to discard was stuck).
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${cause ?: "A 7: you hold too many cards"}. Choose $owed to discard (${pile.sum()} chosen)", color = CHEST_INK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Tap a card to add it · hold to take it back", color = CHEST_INK.copy(alpha = 0.7f), fontSize = 11.sp)
            ChestCardRow(pile.indices.count { holding(s, me, it) > 0 }, 42.dp) { w ->
                for (r in pile.indices) {
                    val have = holding(s, me, r)
                    if (have == 0) continue
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Today's pile and counts, whatever the handler first saw (the #122 stale-tap pattern).
                        val pick by androidx.compose.runtime.rememberUpdatedState({ add: Boolean -> pile = discardPick(pile, r, add, have, owed) })
                        Box(
                            Modifier.size(width = w, height = w * (60f / 42f))
                                .pointerInput(r) {
                                    detectTapGestures(onTap = { pick(true) }, onLongPress = { pick(false) })
                                }.testTag("discard-card-$r"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(artPainter(ALL_CARD_ART[r]), ALL_CARD_NAMES[r], Modifier.fillMaxSize())
                            if (pile[r] > 0) Text("−${pile[r]}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xE0C0392B), CircleShape).padding(horizontal = 6.dp))
                        }
                        UnderCard("have $have", w, CHEST_INK)
                    }
                }
            }
        }
        Box(Modifier.width(10.dp))
        // Fixed buttons: the cards never move while you pick them.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChestButton("Discard $owed", CHEST_YES, "discard-go", 104.dp, if (full) ({ val moves = discardMoves(pile); pile = IntArray(pile.size); moves.forEach(onMove) }) else null)
            ChestButton("Clear", CHEST_NO, "discard-clear", 104.dp, if (pile.sum() > 0) ({ pile = IntArray(pile.size) }) else null)
            legal.firstOrNull { it.type == ActionType.DISCARD && it.discardResource < 0 }?.let { a -> ChestButton("Random", CHEST_GOLD, "discard-random", 104.dp) { onMove(a) } }
        }
    }
}

@Composable
private fun MoveButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    // Primary (Roll / End turn): a big brass plate; the rest: wood with a brass rim.
    // Behind the move guard: a double tap never sends a second move (or hits the button that took this one's place).
    if (primary) BrassButton(label, "move-$label", Modifier.fillMaxWidth().height(54.dp), size = 18.sp, onClick = moveTap(onClick))
    else WoodButton(label, "move-$label", Modifier.fillMaxWidth(), onClick = moveTap(onClick))
}

/** True when [acts] are the Transmuter's (Alchemist's) dice choices. */
internal fun isAlchemist(acts: List<Action>) = acts.isNotEmpty() && acts.all { it.type == wgbg.engine.ChivalryActionType.PLAY_PROGRESS && it.a == wgbg.engine.Progress.ALCHEMIST }

/** The Transmuter's choice: tap a face on the red die and on the ivory die, then set them. */
@Composable
private fun DicePicker(label: String, acts: List<Action>, onMove: (Action) -> Unit, close: () -> Unit) {
    var red by remember { mutableStateOf(0) }
    var ivory by remember { mutableStateOf(0) }
    Text("$label: choose what the two number dice show", color = INK_LIGHT, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    @Composable fun row(isRed: Boolean, picked: Int, pick: (Int) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (isRed) "Red die" else "Ivory die", color = INK_LIGHT, fontSize = 13.sp, modifier = Modifier.width(76.dp))
        for (v in 1..6) Image(artPainter(DiceArt.face(isRed, false, v)), "$v",
            Modifier.size(44.dp).background(if (picked == v) Color(0xFFD9B865) else Color.Transparent, RoundedCornerShape(8.dp)).padding(3.dp)
                .clickable { pick(v) }.testTag("die-${if (isRed) "red" else "ivory"}-$v"))
    }
    row(true, red) { red = it }
    row(false, ivory) { ivory = it }
    val chosen = acts.firstOrNull { it.b == red && it.c == ivory }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ChestButton("Back", CHEST_NO, "pick-back") { close() }
        GoButton(if (chosen != null) "Set the dice: ${red + ivory}" else "Pick both dice", "dice-set", chosen != null, Modifier.height(44.dp),
            onClick = moveTap { chosen?.let { close(); onMove(it) } })
    }
}

@Composable
private fun ColumnScope.Picker(title: String, options: List<Pair<String, Action>>, onMove: (Action) -> Unit, under: (Int) -> String, close: () -> Unit) {
    Text(title, color = INK_LIGHT, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    // Choices that are cards show the card art, as in the trade chest; the rest stay worded buttons. Weighted and
    // scrolling, so Back stays on screen however many rows they wrap to (0.3.4: Windfall's 15 pairs on a 320 dp screen).
    FlowRow(Modifier.weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, a) in options) {
            val cards = optionCards(a)
            if (cards != null) Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = moveTap { close(); onMove(a) }).testTag("pick-$label")) {
                // "have N" under each card, Windfall's pair included (players: the open picker hides the hand row), so the
                // pair sits side by side rather than overlapping.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (c in cards) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(artPainter(ALL_CARD_ART[c]), ALL_CARD_NAMES[c], Modifier.size(width = 42.dp, height = 60.dp))
                        UnderCard(under(c), 42.dp, INK_LIGHT)
                    }
                }
                Text(label, color = INK_LIGHT, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            } else MoveButton(label) { close(); onMove(a) }
        }
    }
    ChestButton("Back", CHEST_NO, "pick-back") { close() }
}

/** The cards a picker choice stands for (Windfall's two, the card a Corner names), or null if it isn't cards. */
internal fun optionCards(a: Action): List<Int>? = when (a.type) {
    ActionType.PLAY_YEAR_OF_PLENTY -> listOfNotNull(a.yopFirst, a.yopSecond.takeIf { it >= 0 })
    ActionType.PLAY_MONOPOLY -> listOf(a.payload.toInt())
    wgbg.engine.ChivalryActionType.PLAY_PROGRESS -> when (a.a) {
        wgbg.engine.Progress.RESOURCE_MONOPOLY, wgbg.engine.Progress.TRADE_MONOPOLY, wgbg.engine.Progress.MERCHANT_FLEET -> listOf(a.b)
        else -> null
    }
    else -> null
}.takeIf { cs -> cs != null && cs.all { it in ALL_CARD_ART.indices } }

/** A pick-a-card moment that isn't a board tap (#123): what it asks, the cards to tap, and the way out, if any. */
internal data class CardPick(val title: String, val options: List<Action>, val done: Action? = null)

/**
 * The card pick [legal] asks for, or null. Mauro (#123): "all card pickers should be card pickers": the Spy's
 * take, a progress card to discard, the deck to draw from after the Raiders, and Harbour Charter's offers were
 * worded buttons (one per card), easy to miss as a pick at all. Grand Factor, Feast, Harbour Charter's give-back
 * and the science bonus are CHOOSE_CARD, already drawn by [CardChoiceSheet].
 */
internal fun cardPick(s: GameState, legal: List<Action>, names: List<String>): CardPick? {
    val chivalry = s.chivalry ?: return null
    val kinds = legal.map { it.type }.toSet()
    fun of(t: wgbg.engine.ActionKind) = legal.filter { it.type == t }
    return when {
        wgbg.engine.ChivalryActionType.TAKE_PROGRESS in kinds ->
            CardPick("Informer: take one of ${names.getOrElse(chivalry.pendingTarget) { "their" }}'s progress cards", of(wgbg.engine.ChivalryActionType.TAKE_PROGRESS))
        wgbg.engine.ChivalryActionType.DISCARD_PROGRESS in kinds ->
            CardPick("Too many progress cards: discard one", of(wgbg.engine.ChivalryActionType.DISCARD_PROGRESS))
        wgbg.engine.ChivalryActionType.CHOOSE_PROGRESS_DECK in kinds ->
            CardPick("Raiders beaten: draw a progress card from a deck", of(wgbg.engine.ChivalryActionType.CHOOSE_PROGRESS_DECK))
        wgbg.engine.ChivalryActionType.COMMERCIAL_HARBOR_OFFER in kinds ->
            CardPick("Harbour Charter: give a player one of your resources; they give you a commodity back",
                of(wgbg.engine.ChivalryActionType.COMMERCIAL_HARBOR_OFFER), legal.firstOrNull { it == Action.CHIVALRY_DONE })
        else -> null
    }
}

/** [pick] as cards to tap, in the chest at the bottom centre like every other card sheet (#123). */
@Composable
internal fun CardPickSheet(s: GameState, me: Int, pick: CardPick, names: List<String>, onMove: (Action) -> Unit, modifier: Modifier = Modifier) {
    val chivalry = s.chivalry ?: return
    Column(modifier.chest().testTag("card-pick-sheet"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pick.title, color = CHEST_INK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        // Harbour Charter: a row of your resources for each player you can still offer one to.
        val rows = if (pick.options.all { it.type == wgbg.engine.ChivalryActionType.COMMERCIAL_HARBOR_OFFER }) pick.options.groupBy { it.b }
            else mapOf(-1 to pick.options)
        // The rows scroll (down, and each one sideways) and Done stays on screen below them (0.3.4: three Harbour
        // Charter rows ran past a 320 dp screen, taking Done with them).
        Column(Modifier.weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((who, acts) in rows) Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (who >= 0) Text("To ${names.getOrElse(who) { "player ${who + 1}" }}", color = CHEST_INK, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
                for (a in acts) Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(onClick = moveTap { onMove(a) }).testTag("pick-card-${a.a}-${a.b}")) {
                    when (a.type) {
                        wgbg.engine.ChivalryActionType.TAKE_PROGRESS, wgbg.engine.ChivalryActionType.DISCARD_PROGRESS ->
                            ProgressCardFace(PROGRESS_NAMES.getOrElse(a.a) { "Progress" }, TRACK_COLORS[wgbg.engine.Progress.track(a.a)],
                                progressArt(a.a), progressHint(a.a), w = 64, h = 90)
                        wgbg.engine.ChivalryActionType.CHOOSE_PROGRESS_DECK ->
                            ProgressCardFace(DECK_NAMES[a.a], TRACK_COLORS[a.a], deckArt(a.a),
                                "${chivalry.deckSize[a.a]} left", w = 64, h = 90)
                        else -> Image(artPainter(ALL_CARD_ART[a.a]), ALL_CARD_NAMES[a.a], Modifier.size(width = 50.dp, height = 70.dp))
                    }
                    val under = when (a.type) {
                        wgbg.engine.ChivalryActionType.DISCARD_PROGRESS -> "have ${chivalry.progressHand[me * wgbg.engine.Progress.COUNT + a.a]}"
                        wgbg.engine.ChivalryActionType.COMMERCIAL_HARBOR_OFFER -> "have ${s.hand(me, a.a)}"
                        else -> null
                    }
                    under?.let { Text(it, color = CHEST_INK, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                }
            }
        }
        pick.done?.let { d -> ChestButton("Done", CHEST_NO, "pick-done") { onMove(d) } }
    }
}

// ------------------------------------------------------------------ confirming board taps

/** The question asked before a tapped board move is made. */
fun confirmTitle(s: GameState, a: Action): String = when (a.type) {
    ActionType.BUILD_SETTLEMENT -> if (s.phase == Phase.BUILD_INITIAL_SETTLEMENT) "Place your homestead here?" else "Build a homestead here?"
    ActionType.BUILD_CITY -> if (s.phase == Phase.BUILD_INITIAL_SETTLEMENT) "Place your town here?" else "Upgrade this homestead to a town?"
    ActionType.BUILD_ROAD -> if (s.phase == Phase.BUILD_INITIAL_ROAD || s.freeRoads > 0) "Place a free road here?" else "Build a road here?"
    ActionType.MOVE_ROBBER -> "Move the bandit here?"
    wgbg.engine.ChivalryActionType.PLAY_PROGRESS -> "Play ${PROGRESS_NAMES.getOrElse(a.a) { "this card" }} here?"
    wgbg.engine.ChivalryActionType.CHOOSE_VERTEX -> when (s.phase) {
        Phase.BARBARIAN_LOSS -> "The Raiders won: lose this town to them?"
        Phase.DESERTER_REMOVE -> "Turncoat: remove this knight of yours?"
        else -> "Choose what to do here"
    }
    wgbg.engine.ChivalryActionType.IMPROVE_CITY -> "Improve your towns?"
    else -> "Choose what to do here"
}

/**
 * Where a road's glow starts and ends along its edge (0 = first corner, 1 = second): clear of a homestead, town or
 * knight standing at either end, so a road being laid never covers the piece it grows from (Mauro: "the road should
 * not hide the building, it should be under it").
 */
fun edgeGlowSpan(pieceAt0: Boolean, pieceAt1: Boolean): Pair<Float, Float> =
    (if (pieceAt0) EDGE_GLOW_CLEAR else 0.2f) to (if (pieceAt1) 1f - EDGE_GLOW_CLEAR else 0.8f)

/** How far a road glow stays from a corner with a piece on it, in edge lengths. */
const val EDGE_GLOW_CLEAR = 0.42f

/** Whether a homestead, town or knight stands on corner [v]. */
fun pieceAt(s: GameState, v: Int): Boolean = s.vertexOwner[v] >= 0 || (s.chivalry?.knightOwner?.get(v) ?: -1) >= 0

/** An Age of Chivalry knight stands on corner [v]. */
fun knightAt(s: GameState, v: Int): Boolean = (s.chivalry?.knightOwner?.get(v) ?: -1) >= 0

/** The first homesteads and roads are being placed: no dice, no trading yet. */
fun inSetup(s: GameState): Boolean = s.phase == Phase.BUILD_INITIAL_SETTLEMENT || s.phase == Phase.BUILD_INITIAL_ROAD

/**
 * Whether the confirm box goes to the bottom of the screen instead of the top: when the tapped spot ([spotY] on a board
 * [boardHeight] tall) is in the top 45%, so the box never covers the spot itself. (0.2.7 online test: a Raiders town
 * near the top edge sat under the box, and a double tap on the town landed on its cancel button.)
 */
fun confirmBelow(spotY: Float?, boardHeight: Float): Boolean = spotY != null && boardHeight > 0f && spotY < boardHeight * 0.45f

/** A warning worth a second look (the bandit on your own land), or null. */
fun confirmWarning(s: GameState, me: Int, a: Action): String? {
    if (a.type != ActionType.MOVE_ROBBER) return null
    val h = a.robberHex
    val mine = s.board.topology.hexVertices[h].any { s.vertexOwner[it] == me }
    if (!mine) return null
    val r = s.board.hexResource[h]
    val what = if (r in 0 until CARD_TYPES) "your own ${RES[r]}" else "nothing"
    return "Careful: this tile touches your own buildings. The bandit would block $what."
}

/** Button text for one confirming choice. */
fun confirmLabel(s: GameState, a: Action, names: List<String>): String =
    chivalryLabel(s, a, names) ?: if (a.type != ActionType.MOVE_ROBBER) "Confirm"
    else if (a.robberVictim < 0) "Move it (nobody to rob)"
    else "Rob ${names[a.robberVictim]} (${s.cardCount(a.robberVictim)} cards)"

/** One row of the spot picker (#124): the build-menu entry the move belongs to, its name, and what it costs. */
internal data class SpotChoice(val action: Action, val icon: MoveIcon?, val name: String, val cost: IntArray?)

/**
 * The choices at a tapped spot that takes more than one thing, one row each (#124, Mauro: "I can't tell I'm
 * building a house"). [cost] is null when the move is free (setup, a free road, a free promotion).
 */
internal fun spotChoices(s: GameState, chosen: List<Action>, names: List<String>): List<SpotChoice> = chosen.map { a ->
    val kind = BuildKind.entries.firstOrNull { it.kind == a.type }
    val free = inSetup(s) || (a.type == ActionType.BUILD_ROAD && s.freeRoads > 0) ||
        (a.type == wgbg.engine.ChivalryActionType.PROMOTE_KNIGHT && (s.chivalry?.freePromotions ?: 0) > 0)
    val cost = if (free) null else when (a.type) {
        ActionType.BUILD_ROAD -> wgbg.engine.Cost.ROAD
        ActionType.BUILD_SETTLEMENT -> wgbg.engine.Cost.SETTLEMENT
        ActionType.BUILD_CITY -> wgbg.engine.Cost.CITY
        wgbg.engine.ChivalryActionType.BUILD_KNIGHT -> wgbg.engine.ChivalryCost.KNIGHT
        wgbg.engine.ChivalryActionType.PROMOTE_KNIGHT -> wgbg.engine.ChivalryCost.PROMOTE
        wgbg.engine.ChivalryActionType.ACTIVATE_KNIGHT -> wgbg.engine.ChivalryCost.ACTIVATE
        wgbg.engine.ChivalryActionType.BUILD_CITY_WALL -> wgbg.engine.ChivalryCost.WALL
        else -> null
    }
    val name = when (a.type) {
        ActionType.BUILD_ROAD -> "Road"
        ActionType.BUILD_SETTLEMENT -> "Homestead"
        ActionType.BUILD_CITY -> "Town"
        else -> kind?.label ?: confirmLabel(s, a, names)
    }
    SpotChoice(a, kind?.icon, name, cost)
}

/** The spot picker: a vertical list, one tappable row per choice (icon, name, cost), then a cancel. */
@Composable
internal fun SpotPicker(choices: List<SpotChoice>, onPick: (Action) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, c) in choices.withIndex()) Row(
            // Capped so the box stays mid-screen, clear of the build buttons and the zoom/tilt bars (seen on device).
            Modifier.widthIn(min = 260.dp, max = 400.dp).brassPlate().clickable(onClick = moveTap { onPick(c.action) })
                .testTag("spot-choice-$i").semantics(mergeDescendants = true) { contentDescription = c.name }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (c.icon != null) IconPlateButton(c.icon, c.name, "spot-icon-$i", width = 40.dp, height = 36.dp, onClick = moveTap { onPick(c.action) })
            Text(c.name, color = Color(0xFF2A1A0C), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            val cost = c.cost
            if (cost == null) Text("free", color = Color(0xFF2A1A0C), fontSize = 14.sp)
            else Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (r in cost.indices) repeat(cost[r]) {
                    Image(artPainter(ALL_CARD_ART[r]), ALL_CARD_NAMES[r], Modifier.size(width = 20.dp, height = 28.dp))
                }
            }
        }
    }
}

/** The Fortune cards (DevCard ids) [legal] lets the player play right now, for the hand row. */
internal fun fortunePlayable(legal: List<Action>): Set<Int> = buildSet {
    for (a in legal) when (a.type) {
        ActionType.PLAY_KNIGHT_CARD -> add(DevCard.KNIGHT)
        ActionType.PLAY_ROAD_BUILDING -> add(DevCard.ROAD_BUILDING)
        ActionType.PLAY_YEAR_OF_PLENTY -> add(DevCard.YEAR_OF_PLENTY)
        ActionType.PLAY_MONOPOLY -> add(DevCard.MONOPOLY)
        else -> {}
    }
}

/** Whether an open picker still has a legal move to offer (#105: a stale one hid the Roll button). */
internal fun sheetStillLegal(sheet: String?, chivalryActs: List<Action>?, legal: List<Action>): Boolean = when (sheet) {
    null -> false
    "yop" -> legal.any { it.type == ActionType.PLAY_YEAR_OF_PLENTY }
    "mono" -> legal.any { it.type == ActionType.PLAY_MONOPOLY }
    "chivalry" -> chivalryActs != null && chivalryActs.any { it in legal }
    else -> true
}

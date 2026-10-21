package com.mauro.wgbg

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wgbg.engine.Action
import wgbg.engine.ActionType
import wgbg.engine.CARD_TYPES
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Port
import wgbg.engine.Rules

// ------------------------------------------------------------------ trade logic (UI-free, unit tested)

/** What the trade panel is for right now, from [me]'s seat. */
enum class TradeMode {
    /** Our turn after rolling: build an offer to the players, or trade with the bank. */
    COMPOSE,
    /** Someone offered us a trade: Yes, No, or Counter (only Counter or No for a one-sided ask, #135). */
    RESPOND,
    /** Our offer went round the table: pick an acceptance or a counter-offer to trade on, or cancel. */
    PICK_PARTNER,
    /** A trade is on but someone else has to answer. */
    WAITING,
    /** No trading now (before rolling, other turns, setup...). */
    CLOSED,
}

fun tradeMode(s: GameState, me: Int): TradeMode {
    val acting = s.actingPlayer == me
    return when (s.phase) {
        Phase.DECIDE_TRADE -> if (acting) TradeMode.RESPOND else TradeMode.WAITING
        Phase.DECIDE_ACCEPTEES -> if (acting) TradeMode.PICK_PARTNER else TradeMode.WAITING
        Phase.PLAY_TURN -> if (acting && s.currentPlayer == me && s.hasRolled && s.freeRoads == 0) TradeMode.COMPOSE else TradeMode.CLOSED
        else -> TradeMode.CLOSED
    }
}

/** True when the game is waiting on [me] to answer a trade: the panel opens itself. */
/** Why the chest can't trade right now, in the player's words (Mauro's playtest: after Envoy it wrongly said "Trade on your turn"). */
internal fun tradeClosedReason(s: GameState, me: Int): String = when {
    s.currentPlayer != me -> "Trade on your turn"
    !s.hasRolled -> "Roll first, then trade"
    s.phase == Phase.PLAY_TURN && s.freeRoads > 0 -> "Place your free road first, then trade"
    else -> "Finish what you're doing, then trade"
}

fun tradeNeedsMe(s: GameState, me: Int) = tradeMode(s, me).let {
    it == TradeMode.RESPOND || it == TradeMode.PICK_PARTNER
}

/** Card kinds a trade covers: the five resources, plus three commodities in Age of Chivalry. */
fun tradeCards(s: GameState) = if (s.chivalry != null) wgbg.engine.CHIVALRY_CARD_TYPES else CARD_TYPES

/** Cards of kind [i] in [p]'s hand (resources, or commodities in Age of Chivalry). */
fun holding(s: GameState, p: Int, i: Int) = if (i < CARD_TYPES) s.hand(p, i) else s.card(p, i)

/** The offer action for these counts, in the ruleset's own encoding. */
fun offerFor(s: GameState, give: IntArray, get: IntArray): Action? = runCatching {
    if (s.chivalry != null) Action.offerTradeCk(give, get) else Action.offerTrade(give, get)
}.getOrNull()

/** True when [me] holds every card a trade offered to them asks them to give (#44). */
fun canPayOffer(s: GameState, me: Int): Boolean {
    val give = termsFor(s, me).second
    return give.indices.all { give[it] <= holding(s, me, it) }
}

/** The cards [p] lacks to pay the open offer, e.g. "1 Grain, 1 Stone" (#26). */
fun shortfallText(s: GameState, p: Int): String {
    val short = Rules.offerShortfall(s, p)
    return short.indices.filter { short[it] > 0 }.joinToString(", ") { "${short[it]} ${ALL_CARD_NAMES[it]}" }
}

/** How long an offer you can't pay waits before it is declined for you, seconds (Mauro: 5). */
const val AUTO_NO_SECONDS = 5

/**
 * The open offer seen from [me]'s side: (what I get, what I give). For the offerer that is the
 * offer itself; for anyone else it is mirrored.
 */
fun termsFor(s: GameState, me: Int): Pair<IntArray, IntArray> {
    val chivalry = s.chivalry
    val n = tradeCards(s)
    val gives = IntArray(n) { if (it < CARD_TYPES) s.tradeOffer[it] else chivalry!!.offer[it - CARD_TYPES] }
    val gets = IntArray(n) { if (it < CARD_TYPES) s.tradeOffer[CARD_TYPES + it] else chivalry!!.offer[wgbg.engine.Commodity.COUNT + it - CARD_TYPES] }
    return if (me == s.currentPlayer) gets to gives else gives to gets
}

/** The offerer's (get, give) with taker [q] (#135): [q]'s counter-offer, or the offer as it stands if they said yes. */
fun takerTerms(s: GameState, q: Int): Pair<IntArray, IntArray> {
    val c = s.counterOf(q) ?: return termsFor(s, s.currentPlayer)
    val chivalry = s.chivalry != null
    val n = tradeCards(s)
    return IntArray(n) { if (chivalry) c.chivalryOfferWant(it) else c.offerWant(it) } to IntArray(n) { if (chivalry) c.chivalryOfferGive(it) else c.offerGive(it) }
}

/** The counter a responder means by "I get [get], I give [give]", in the ruleset's encoding (counters are offerer-side). */
fun counterFor(s: GameState, get: IntArray, give: IntArray): Action? = runCatching {
    if (s.chivalry != null) Action.counterTradeCk(give = get, want = give) else Action.counterTrade(give = get, want = give)
}.getOrNull()

/** [me] made the open offer and may withdraw it now, while the others are still answering (#114). */
fun canWithdraw(s: GameState, me: Int) = s.phase == Phase.DECIDE_TRADE && s.currentPlayer == me && s.actingPlayer != me

/** The open offer only gives or only asks (#135): answer it with a counter, there is nothing to say yes to. */
fun isOpenAsk(s: GameState) = s.phase == Phase.DECIDE_TRADE && !Rules.isTwoSided(s)

fun bankRate(s: GameState, p: Int, r: Int): Int {
    if (s.chivalry != null) return wgbg.engine.Chivalry.tradeRate(s, p, r)
    val m = s.portMask[p]
    return if (m and (1 shl r) != 0) 2 else if (m and Port.GENERIC_BIT != 0) 3 else 4
}

// ------------------------------------------------------------------ the chest

/** Parchment, Fabric, Silver edge colours (Academy green, Guilds gold, Crown blue). */
/** Parchment, Fabric, Silver: their deck colours (forest green, pale white-green, blue-grey). */
private val COMMODITY_COLORS = listOf(TRACK_COLORS[wgbg.engine.Track.SCIENCE], TRACK_COLORS[wgbg.engine.Track.TRADE], TRACK_COLORS[wgbg.engine.Track.POLITICS])

/** Resource cards, indexed like RESOURCE_NAMES: Ledger pages (#86, Mauro's pick; tools/import_ledger_cards.py). */
val CARD_ART = listOf(R.drawable.card_timber, R.drawable.card_clay, R.drawable.card_fleece, R.drawable.card_grain, R.drawable.card_stone)
/** Age of Chivalry commodities, in ALL_CARD_NAMES order: Parchment, Fabric, Silver, as Ledger pages too. */
val COMMODITY_ART = listOf(R.drawable.card_parchment, R.drawable.card_weave, R.drawable.card_silver)
/** The painted commodity still lifes (Crest's, #62), the picture inside a progress card's named face. */
val COMMODITY_PICTURE = listOf(R.drawable.card_paint_parchment, R.drawable.card_paint_weave, R.drawable.card_paint_silver)
/** Every card's art in ALL_CARD_NAMES order: the five resources, then the three commodities. */
val ALL_CARD_ART = CARD_ART + COMMODITY_ART

private val WOOD_DARK = Color(0xFF2A1B10)
private val WOOD_MID = Color(0xFF4A3020)
private val BRASS = Color(0xFFC9A04A)
private val BRASS_LIGHT = Color(0xFFF1D58A)
private val BRASS_DARK = Color(0xFF7A5A22)
private val INK = Color(0xFFEDE6D8)
private val YES = Color(0xFF3E9B4F)
private val NO = Color(0xFFC0392B)
private val COUNTER = Color(0xFFE0B23A)

/** A dark wooden chest with brass bands and press-fit corners; shared by the trade and other prompts. */
fun Modifier.chest(): Modifier = this
    .drawWithCache {
        val r = CornerRadius(14.dp.toPx())
        val wood = Brush.verticalGradient(listOf(WOOD_MID, WOOD_DARK, WOOD_MID, WOOD_DARK))
        val brass = Brush.linearGradient(listOf(BRASS_DARK, BRASS_LIGHT, BRASS, BRASS_DARK), Offset.Zero, Offset(size.width, size.height))
        val rim = Stroke(4.dp.toPx())
        val c = 18.dp.toPx()
        val corner = CornerRadius(4.dp.toPx())
        onDrawBehind {
        drawRoundRect(wood, cornerRadius = r)
        // Planks.
        val plank = size.height / 5
        for (i in 1 until 5) drawLine(Color.Black.copy(alpha = 0.35f), Offset(8f, plank * i), Offset(size.width - 8f, plank * i), 2f)
        // Brass bands and rim.
        drawRoundRect(brass, cornerRadius = r, style = rim)
        // Corner plates, press-fit: no rivets (Mauro: "no rivets, screws if you need them").
        for (ix in 0..1) for (iy in 0..1) {
            val x = if (ix == 0) 0f else size.width - c
            val y = if (iy == 0) 0f else size.height - c
            drawRoundRect(brass, Offset(x, y), Size(c, c), corner)
            drawRoundRect(BRASS_DARK.copy(alpha = 0.5f), Offset(x, y), Size(c, c), corner, style = Stroke(1.dp.toPx()))
        }
        }
    }
    .padding(horizontal = 14.dp, vertical = 8.dp)

// ------------------------------------------------------------------ the panel

/**
 * The trade panel: Players and Harbour tabs; the top row is what you get, the bottom row what
 * you give (tap a card to add one, tap its "−" to take one back); Yes / No / Counter on the right.
 * Every button sends a legal move (or a trade the engine validates), so nothing bypasses the rules.
 */
@Composable
fun TradePanel(
    s: GameState,
    me: Int,
    legal: List<Action>,
    names: List<String>,
    onMove: (Action) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Answers to our offer by seat: ✓ yes, ✗ no, ⇄ countered. */
    responses: Map<Int, String> = emptyMap(),
    /** Changes after every move; [s] is mutated in place, so this makes the panel redraw. */
    version: Int = 0,
    /** Seconds until an offer you can't pay is declined for you (#44), or null. */
    autoNoSeconds: Int? = null,
    /** Width the chest may fill; the cards grow to use it (Mauro: "the trade panel could be a little bigger"). */
    room: Dp = 480.dp,
    /** Harbour tab chosen, kept by the caller: the chest leaves the screen for a moment after each bank trade, and
     *  a tab held here went back to Players every time (player report, 0.3.1). */
    harbour: androidx.compose.runtime.MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    version
    val mode = tradeMode(s, me)
    // Fresh selections whenever the trade situation changes.
    val key = listOf(s.turn, s.phase, s.actingPlayer, s.counters.contentHashCode(), s.hands.sum())
    var maritime by harbour
    var countering by remember(key) { mutableStateOf(false) }
    var get by remember(key) { mutableStateOf(IntArray(tradeCards(s))) }
    var give by remember(key) { mutableStateOf(IntArray(tradeCards(s))) }
    // Picking a partner (#135): the takers are everyone who said yes or countered; the cards show the picked one's terms.
    val takers = legal.filter { it.type == ActionType.CONFIRM_TRADE }.map { it.payload.toInt() }
    var pick by remember(key) { mutableStateOf(takers.firstOrNull() ?: -1) }
    val (termGet, termGive) = if (mode == TradeMode.PICK_PARTNER && pick >= 0) takerTerms(s, pick) else termsFor(s, me)
    val editing = mode == TradeMode.COMPOSE || countering
    val showGet = if (editing) get else termGet
    val showGive = if (editing) give else termGive
    val bankTab = maritime && mode == TradeMode.COMPOSE
    // #125: after a trade, what you hold now and what changed.
    val handNow = List(tradeCards(s)) { holding(s, me, it) }
    var handBefore by remember { mutableStateOf(handNow) }
    var afterTrade by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(handNow) {
        if (handNow != handBefore) { afterTrade = tradeSummary(handBefore, handNow); handBefore = handNow }
    }
    androidx.compose.runtime.LaunchedEffect(s.turn) { afterTrade = null }

    if (mode == TradeMode.CLOSED) {
        Text(status(s, me, mode, names, false, false), color = INK, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.chest().testTag("trade-panel").semantics { testTagsAsResourceId = true })
        return
    }
    Row(modifier.chest().testTag("trade-panel").semantics { testTagsAsResourceId = true }, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Either tab starts clean: bank picks carried into a player offer made it unsendable (#122).
                Tab("Players", !bankTab) { if (maritime) { maritime = false; get = IntArray(tradeCards(s)); give = IntArray(tradeCards(s)) } }
                if (mode == TradeMode.COMPOSE) Tab("Harbour", bankTab) { maritime = true; get = IntArray(tradeCards(s)); give = IntArray(tradeCards(s)) }
                // Everyone's answer to your offer, up here beside the tabs: a row at the bottom ran off the
                // chest on a landscape phone.
                if (!bankTab && s.currentPlayer == me) for (p in 0 until s.numPlayers) if (p != me) {
                    val mark = responses[p] ?: if (mode == TradeMode.WAITING && s.actingPlayer == p) "…" else ""
                    // A long name ends in "…" and the answer mark always shows: three 16-letter names ran off the chest (0.3.4).
                    val ink = when (mark) { "✓" -> Color(0xFF8BD18B); "✗" -> Color(0xFFE57373); "⇄" -> COUNTER; else -> INK.copy(alpha = 0.7f) }
                    Row(Modifier.testTag("answer-$p"), verticalAlignment = Alignment.CenterVertically) {
                        Text(names[p], Modifier.widthIn(max = 84.dp), color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(" $mark", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
            Text(status(s, me, mode, names, countering, bankTab, pick), color = INK, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.width(214.dp))
            afterTrade?.let { Text(it, color = Color(0xFF8BD18B), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("trade-after")) }
            if (editing) Text(if (bankTab) "Top: cards you want · bottom: pay a lot for each" else "Tap top to ask · bottom to offer · either alone is fine",
                color = INK.copy(alpha = 0.7f), fontSize = 11.sp)
            // One row of cards (Mauro, #49): the top half of a card asks for it, the bottom half offers it.
            AskOfferRow(showGet, showGive, editing, tradeCardWidth(room, showGet.size),
                // #113: the bank tab shows how many you hold as well as the rate.
                under = { r -> if (bankTab) "have ${holding(s, me, r)}\n${bankRate(s, me, r)}:1" else "have ${holding(s, me, r)}" }) { r, tap ->
                val (g, v) = askOffer(get, give, r, tap, holding(s, me, r), if (bankTab) bankRate(s, me, r) else 0)
                get = g; give = v
            }
            // One button per taker: ✓ said yes to your offer, ⇄ countered. Tap to see their terms above, then Trade.
            if (mode == TradeMode.PICK_PARTNER) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (q in takers)
                    Pill("${names[q]} ${if (s.counterOf(q) != null) "⇄" else "✓"}", if (q == pick) YES else WOOD_MID, tag = "partner-$q") { pick = q }
            }
        }
        Spacer(Modifier.width(10.dp))
        // Yes / No / Counter.
        // A bank trade can be several moves (#122); everything else is one.
        val bankMoves = if (bankTab) bankTrades(s, me, get, give) else null
        val yes: Action? = when {
            bankTab -> bankMoves?.first()
            mode == TradeMode.COMPOSE -> offerFor(s, give, get)?.takeIf { Rules.isLegal(s, it) }
            countering -> counterFor(s, get, give)?.takeIf { Rules.isLegal(s, it) }
            mode == TradeMode.RESPOND -> Action.ACCEPT_TRADE.takeIf { it in legal }
            mode == TradeMode.PICK_PARTNER -> Action.confirmTrade(pick).takeIf { pick >= 0 && it in legal }
            else -> null
        }
        val no: (() -> Unit)? = when {
            countering -> { { countering = false } }
            mode == TradeMode.COMPOSE -> { { if (get.any { it > 0 } || give.any { it > 0 }) { get = IntArray(tradeCards(s)); give = IntArray(tradeCards(s)) } else onClose() } }
            mode == TradeMode.RESPOND -> { { onMove(Action.REJECT_TRADE) } }
            mode == TradeMode.PICK_PARTNER -> { { onMove(Action.CANCEL_TRADE) } }
            canWithdraw(s, me) -> { { onMove(Action.CANCEL_TRADE) } }
            else -> null
        }
        val counter: (() -> Unit)? = if (mode == TradeMode.RESPOND && !countering) {
            { get = termGet.copyOf(); give = termGive.copyOf(); countering = true }
        } else null
        val labels = tradeButtonLabels(mode, countering, bankTab, get.any { it > 0 } || give.any { it > 0 }, canWithdraw(s, me))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(labels.first, YES, "trade-yes", yes?.let { a -> { (bankMoves ?: listOf(a)).forEach(onMove); if (mode == TradeMode.COMPOSE) { get = IntArray(tradeCards(s)); give = IntArray(tradeCards(s)) } } })
            BigButton(if (autoNoSeconds != null && mode == TradeMode.RESPOND) "${labels.second} ($autoNoSeconds)" else labels.second, NO, "trade-no", no)
            // Counter only means something when answering someone else's offer.
            if (mode == TradeMode.RESPOND) BigButton("Counter", COUNTER, "trade-counter", counter)
        }
    }
}

/**
 * What the green and red buttons say: Yes/No only when answering someone; your own offer is "Offer"
 * (or "Trade" with the bank) and "Clear"/"Close"; picking a partner is "Trade"/"Cancel"; a counter is "Send"/"Back".
 */
internal fun tradeButtonLabels(mode: TradeMode, countering: Boolean, bank: Boolean, entered: Boolean, withdraw: Boolean = false): Pair<String, String> = when {
    countering -> "Send" to "Back"
    withdraw -> "Trade" to "Withdraw"
    mode == TradeMode.COMPOSE -> (if (bank) "Trade" else "Offer") to (if (entered) "Clear" else "Close")
    mode == TradeMode.PICK_PARTNER -> "Trade" to "Cancel"
    else -> "Yes" to "No"
}

private fun status(s: GameState, me: Int, mode: TradeMode, names: List<String>, countering: Boolean, bank: Boolean, pick: Int = -1): String = when {
    bank -> "Trade with the bank"
    countering -> "Your counter to ${names[s.currentPlayer]}"
    mode == TradeMode.COMPOSE -> "Offer the other players"
    mode == TradeMode.RESPOND && isOpenAsk(s) ->
        if (termsFor(s, me).first.sum() == 0) "${names[s.currentPlayer]} asks: Counter with your price" else "${names[s.currentPlayer]} offers: Counter with what you want"
    mode == TradeMode.RESPOND -> "${names[s.currentPlayer]} offers you a trade"
    mode == TradeMode.PICK_PARTNER && pick >= 0 -> "${names[pick]} ${if (s.counterOf(pick) != null) "countered" else "said yes"} · Trade or Cancel"
    mode == TradeMode.PICK_PARTNER -> "Pick who to trade with"
    mode == TradeMode.WAITING -> "Waiting for ${names[s.actingPlayer]}…"
    else -> tradeClosedReason(s, me)
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) BRASS else WOOD_DARK, RoundedCornerShape(8.dp))
            .border(1.dp, BRASS, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp).testTag("tab-$label"),
    ) { Text(label, color = if (selected) WOOD_DARK else INK, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

/** What a tap on a trade card does (#49). */
enum class CardTap { ASK, OFFER, TAKE_BACK }

/**
 * The new (get, give) after a [tap] on card [r]. Asking adds one (up to 9), offering adds one (up to [have]
 * held); a card is never both asked and offered, so tapping the other half switches it over. Take-back
 * removes one from whichever side holds it. With the bank ([bankRate] > 0) offering pays one more lot of
 * [bankRate] of that card (#122: several lots, each card at its own rate), and take-back returns one lot.
 */
fun askOffer(get: IntArray, give: IntArray, r: Int, tap: CardTap, have: Int, bankRate: Int = 0): Pair<IntArray, IntArray> {
    val g = get.copyOf(); val v = give.copyOf()
    if (bankRate > 0) {
        when (tap) {
            CardTap.ASK -> { v[r] = 0; g[r] = (g[r] + 1).coerceAtMost(9) }
            CardTap.OFFER -> if (have >= v[r] + bankRate) { g[r] = 0; v[r] += bankRate }
            CardTap.TAKE_BACK -> if (g[r] > 0) g[r]-- else if (v[r] > 0) v[r] = (v[r] - bankRate).coerceAtLeast(0)
        }
        return g to v
    }
    when (tap) {
        CardTap.ASK -> { if (v[r] > 0) v[r] = 0; g[r] = (g[r] + 1).coerceAtMost(9) }
        CardTap.OFFER -> if (have > 0) { if (g[r] > 0) g[r] = 0; v[r] = (v[r] + 1).coerceAtMost(have) }
        CardTap.TAKE_BACK -> if (g[r] > 0) g[r]-- else if (v[r] > 0) v[r]--
    }
    return g to v
}

/**
 * A bank trade as the moves that make it (#122): one MARITIME_TRADE per card asked, each paid by one lot of a
 * paid card at [me]'s rate for it, so "2 Grain at 2:1 and 4 Stone at 4:1 for a Clay and a Fleece" is two moves.
 * Null unless the lots paid match the cards asked and every move is legal in turn (rates, cards held, the bank's
 * stock), checked on a copy of [s].
 */
fun bankTrades(s: GameState, me: Int, get: IntArray, give: IntArray): List<Action>? {
    val pay = ArrayList<Int>()
    for (r in give.indices) if (give[r] > 0) {
        val rate = bankRate(s, me, r)
        if (give[r] % rate != 0) return null
        repeat(give[r] / rate) { pay.add(r) }
    }
    val want = ArrayList<Int>()
    for (r in get.indices) repeat(get[r]) { want.add(r) }
    if (want.isEmpty() || want.size != pay.size) return null
    val moves = pay.zip(want) { g, w -> Action.maritimeTrade(g, w) }
    val sim = s.copy()
    for (m in moves) { if (sim.actingPlayer != me || !Rules.isLegal(sim, m)) return null; Rules.apply(sim, m) }
    return moves
}

/**
 * How wide each trade card is drawn: as big as [room] allows once the buttons (78 dp + gap) and the chest's
 * edges are taken out, between the old 42 dp and 58 dp, so 8 Age of Chivalry cards still fit a small phone.
 */
/** Whether the chest's row of [cards] fits in [room] at the smallest card width (else the chest needs more room). */
internal fun chestFits(room: Dp, cards: Int): Boolean = room >= 124.dp + (42.dp + 6.dp) * cards

internal fun tradeCardWidth(room: Dp, cards: Int): Dp =
    ((room - 124.dp) / cards.coerceAtLeast(1) - 6.dp).coerceIn(42.dp, 58.dp)

/**
 * One row of cards for a trade: green "+N" on a card's top edge is asked for, red "−N" on its bottom edge is
 * offered. Tap the top half to ask, the bottom half to offer; the "−" under a card (or a long press) takes one back.
 */
@Composable
private fun AskOfferRow(get: IntArray, give: IntArray, editable: Boolean, cardWidth: Dp, under: (Int) -> String?, onTap: (Int, CardTap) -> Unit) {
    // The tap handlers below live as long as their card; they must call today's [onTap], not the one from the card's
    // first touch, or a card used in one trade went dead for the next (#122: "can't do a second maritime trade").
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in get.indices) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val asked = get[r]; val offered = give[r]
            val lit = asked > 0 || offered > 0
            Box(
                Modifier.size(width = cardWidth, height = cardWidth * (60f / 42f))
                    .then(if (lit) Modifier.border(2.dp, if (asked > 0) Color(0xFF8BD18B) else Color(0xFFE57373), RoundedCornerShape(5.dp)) else Modifier)
                    .then(if (editable) Modifier.pointerInput(r) {
                        detectTapGestures(
                            onTap = { at -> tap(r, if (at.y < size.height / 2f) CardTap.ASK else CardTap.OFFER) },
                            onLongPress = { tap(r, CardTap.TAKE_BACK) },
                        )
                    } else Modifier)
                    .testTag("card-$r"),
                contentAlignment = Alignment.Center,
            ) {
                // The illustrated card; dimmed until it is part of the trade.
                if (r < ALL_CARD_ART.size) Image(artPainter(ALL_CARD_ART[r]), ALL_CARD_NAMES[r], Modifier.fillMaxSize())   // bright: no reason to dim them (Mauro)
                else Box(Modifier.fillMaxSize().background(Color(0xFFEFE3C8).copy(alpha = if (lit) 1f else 0.5f), RoundedCornerShape(5.dp))
                    .border(2.dp, COMMODITY_COLORS[r - CARD_ART.size], RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                    // Commodities (Age of Chivalry) until their card art lands.
                    Text(ALL_CARD_NAMES[r], color = Color(0xFF2B2118), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                if (editable && !lit) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x66FFFFFF)))   // the halfway line
                if (asked > 0) Text("+$asked", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopCenter).background(Color(0xE0388E3C), CircleShape).padding(horizontal = 6.dp))
                if (offered > 0) Text("−$offered", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xE0C0392B), CircleShape).padding(horizontal = 6.dp))
            }
            // How many you hold (or the bank rate), big enough to read at a glance (#66).
            under(r)?.let { Text(it, color = INK, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, softWrap = false, lineHeight = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            // A "−" to take one back, down to none, like the discard sheet (Mauro: "need to be able to get back to
            // zero"; the long press that also does it was never found). The row keeps its height so nothing jumps.
            Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                if (editable && lit) Text("−", color = INK, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onTap(r, CardTap.TAKE_BACK) }.padding(horizontal = 12.dp).testTag("take-back-$r"))
            }
        }
    }
}

/** What a "Choose a card" moment is for, in words: the science bonus, Harbour Charter, Grand Factor or Feast. */
internal fun cardChoiceTitle(s: GameState, names: List<String>): String = when (s.phase) {
    Phase.AQUEDUCT -> "Science bonus: nothing came your way, so take any one card free"
    Phase.COMMERCIAL_HARBOR_RESPOND -> "${names[s.currentPlayer]} played Harbour Charter: give back one commodity"
    Phase.MASTER_MERCHANT -> "Grand Factor: take a card from ${names[s.chivalry?.pendingTarget ?: 0]}"
    Phase.WEDDING -> "${names[s.currentPlayer]} played Feast against you: give them ${s.chivalry?.giveOwed?.getOrNull(s.actingPlayer) ?: 2} card(s), one at a time"
    else -> "Choose a card"
}

/**
 * The trade chest's card picker, reused wherever the game asks you to pick one card (Mauro): the illustrated cards
 * on offer, with how many you (or, for Grand Factor, they) hold under each. One tap picks.
 */
@Composable
fun CardChoiceSheet(s: GameState, me: Int, choices: List<Action>, names: List<String>, onMove: (Action) -> Unit, modifier: Modifier = Modifier) {
    val owner = if (s.phase == Phase.MASTER_MERCHANT) s.chivalry?.pendingTarget ?: me else me
    Column(modifier.chest().testTag("card-choice").semantics { testTagsAsResourceId = true }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(cardChoiceTitle(s, names), color = INK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in choices.sortedBy { it.a }) Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onMove(a) }.testTag("choose-card-${a.a}")) {
                Image(artPainter(ALL_CARD_ART[a.a]), ALL_CARD_NAMES[a.a], Modifier.size(width = 42.dp, height = 60.dp))
                Text("${if (owner == me) "have" else "has"} ${s.card(owner, a.a)}", color = INK, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Five resource cards; tap adds one (or picks it, for the bank), the "−" under a card takes one back. */
@Composable
private fun CardRow(counts: IntArray, editable: Boolean, single: Boolean, badge: (Int) -> String?, change: (Int, Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in counts.indices) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val n = counts[r]
            val lit = n > 0
            Box(
                Modifier.size(width = 34.dp, height = 48.dp) // small enough that the chest fits a landscape phone
                    .then(if (lit) Modifier.border(2.dp, BRASS_LIGHT, RoundedCornerShape(5.dp)) else Modifier)
                    .then(if (editable) Modifier.clickable { change(r, 1) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // The illustrated card; dimmed until it is part of the trade.
                if (r < ALL_CARD_ART.size) Image(artPainter(ALL_CARD_ART[r]), ALL_CARD_NAMES[r], Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(Color(0xFFEFE3C8).copy(alpha = if (lit) 1f else 0.45f), RoundedCornerShape(5.dp))
                    .border(2.dp, COMMODITY_COLORS[r - CARD_ART.size], RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                    // Commodities (Age of Chivalry) until their card art lands.
                    Text(ALL_CARD_NAMES[r], color = Color(0xFF2B2118), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                if (lit) Text("$n", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xCC2A1B10), CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))
            }
            Box(Modifier.height(15.dp), contentAlignment = Alignment.Center) {
                if (editable && lit && !single) Text("−", color = INK, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { change(r, -1) }.padding(horizontal = 10.dp))
                else badge(r)?.let { Text(it, color = INK.copy(alpha = 0.7f), fontSize = 10.sp) }
            }
        }
    }
}

/** The chest's buttons (green yes, red no, gold counter), shared by every card sheet so they all look alike. */
internal val CHEST_YES = Color(0xFF3E9B4F)
internal val CHEST_NO = Color(0xFFC0392B)
internal val CHEST_GOLD = Color(0xFFE0B23A)
/** The chest's text colour. */
internal val CHEST_INK = Color(0xFFEDE6D8)

@Composable
internal fun ChestButton(label: String, color: Color, tag: String, width: androidx.compose.ui.unit.Dp = 78.dp, onClick: (() -> Unit)?) = BigButton(label, color, tag, onClick, width)

@Composable
private fun BigButton(label: String, color: Color, tag: String, onClick: (() -> Unit)?, width: androidx.compose.ui.unit.Dp = 78.dp) {
    // The same 9-slice brass plates as every other button (green go, red stop, gold counter), not a flat rounded box
    // (Fable review T3: the chest buttons looked like another app); dimmed by the plate when there is nothing to do.
    val on = onClick != null
    val plate = when { color.green > color.red -> Plate.GO; color.red > 0.6f && color.green < 0.4f -> Plate.STOP
        color.red > 0.8f && color.green > 0.6f -> Plate.GOLD; else -> Plate.BRASS }
    PlateBox(plate, enabled = on, onClick = { onClick?.invoke() }, modifier = Modifier.size(width = width, height = 38.dp).testTag(tag)) {
        Text(label, style = Wood.sans(14.sp, FontWeight.Bold, when { !on -> INK.copy(alpha = 0.45f); plate == Plate.GOLD -> Color(0xFF3A2410); else -> Color.White }),
            maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Pill(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.background(color, RoundedCornerShape(16.dp)).border(1.dp, BRASS_LIGHT, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag),
    ) { Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}


/**
 * The Trade button appears once setup is over (no trading while placing the first homesteads) and the turn's dice
 * are rolled: the rules allow no trade before the roll, and opening the panel then left the game stuck (Mauro, 2026-10-12).
 */
fun tradeButtonVisible(s: GameState) = !inSetup(s) && s.hasRolled && s.phase == Phase.PLAY_TURN   // #123: never over a pending pick

/**
 * The line shown after a trade (#125): each card that changed, with what you hold now and by how much it moved,
 * e.g. "Now: 4 Grain (+1) · 0 Stone (−3)". Null when nothing changed.
 */
internal fun tradeSummary(before: List<Int>, after: List<Int>): String? {
    val parts = after.indices.filter { after[it] != before.getOrElse(it) { 0 } }.map { r ->
        val d = after[r] - before.getOrElse(r) { 0 }
        "${after[r]} ${ALL_CARD_NAMES[r]} (${if (d > 0) "+$d" else "−${-d}"})"
    }
    return if (parts.isEmpty()) null else "Now: " + parts.joinToString(" · ")
}

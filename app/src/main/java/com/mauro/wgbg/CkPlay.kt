package com.mauro.wgbg

import wgbg.engine.Action
import wgbg.engine.ActionKind
import wgbg.engine.CkActionType
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Progress
import wgbg.engine.Track

// ------------------------------------------------------------------ Age of Chivalry: our names and labels (#4)

/** Improvement tracks / progress decks by engine track index: Guilds (trade), Crown (politics), Academy (science). */
val DECK_NAMES = listOf("Guilds", "Crown", "Academy")

/** Progress cards in engine order, with our own names (never the original game's). */
val PROGRESS_NAMES = listOf(
    // Academy
    "Transmuter", "Hoist", "Master Mason", "Tinkerer", "Waterworks", "Healer", "Deep Shafts", "Printing Press", "Pathfinders", "Forge",
    // Guilds
    "Harbour Charter", "Grand Factor", "Peddler", "Swift Hulls", "Corner the Field", "Corner the Wares",
    // Crown
    "Abbot", "Charter of Rights", "Turncoat", "Envoy", "Plot", "Wrecker", "Informer", "Marshal", "Feast",
)

/** What each progress card does, in one line (engine order, matching [PROGRESS_NAMES]). */
val PROGRESS_HINTS = listOf(
    // Academy
    "Before you roll: choose what the two number dice show.",
    "Improve a town upgrade (Guilds, Academy or Crown) for one commodity less.",
    "Build Ramparts on one of your towns for free.",
    "Two tiles trade numbers; 2, 12, 6 and 8 never move.",
    "Each Grainfield touching your homesteads or towns pays you 2 Grain.",
    "Turn a homestead into a town for 2 Stone and 1 Grain.",
    "Each Quarry touching your homesteads or towns pays you 2 Stone.",
    "Worth 1 point; played at once.",
    "Build 2 roads for free.",
    "Up to 2 of your knights go up a strength, free.",
    // Guilds
    "Offer each other player a resource; each must give you a commodity back.",
    "Pick a player ahead of you on points: see their hand and take 2 cards.",
    "Put the merchant on a tile next to you: trade its resource 2 for 1, and hold 1 point while it's yours.",
    "This turn, trade one card type with the bank 2 for 1.",
    "Name a resource: each other player hands you 2 of it, or all they have.",
    "Name a commodity: each other player hands you 1 of it, if they have one.",
    // Crown
    "Move the bandit and take a random card from each player on that tile.",
    "Worth 1 point; played at once.",
    "A player takes one of their knights off; you may set down one of yours of that strength.",
    "Lift any road with a loose end; if it was yours, you may lay it again.",
    "Knock an enemy knight off one of your roads.",
    "Everyone level with you on points or ahead throws away half their cards.",
    "Look at a player's progress cards and take one.",
    "Supply all your knights for free.",
    "Everyone ahead of you on points hands you 2 cards of their choosing.",
)

/** What a progress card's choice points at on the board: a corner, an edge or a tile; null if it isn't a board spot. */
enum class CardSpot { VERTEX, EDGE, HEX }
fun cardSpotKind(card: Int): CardSpot? = when (card) {
    Progress.ENGINEER, Progress.MEDICINE, Progress.INTRIGUE -> CardSpot.VERTEX
    Progress.DIPLOMAT -> CardSpot.EDGE
    Progress.MERCHANT, Progress.BISHOP, Progress.INVENTOR -> CardSpot.HEX
    else -> null
}

/** True when every one of [acts] is a progress card played on a board spot (so it's picked on the board, not from a list). */
fun isBoardCard(acts: List<Action>) = acts.isNotEmpty() && acts.all { it.type == CkActionType.PLAY_PROGRESS && cardSpotKind(it.a) != null }

/**
 * The board spot (corner, edge or tile index) for a board card's choice [a]; the Inventor picks two tiles, the
 * first tap choosing [a]'s first tile and, with [second], the second tap its other tile.
 */
fun cardSpotIndex(a: Action, second: Boolean = false): Int = if (second && a.a == Progress.INVENTOR) a.c else a.b

/**
 * The engine lists each Tinkerer swap once, lower tile first, so a first tap could only pick a swap's lower tile
 * and a second tap only a higher one (report 6akrzuq, 0.3.0: "it only highlights some"). While picking, every
 * swap is offered both ways round; [asLegalPick] turns the chosen one back into the engine's order.
 */
fun pickChoices(acts: List<Action>): List<Action> = acts + acts.filter { it.a == Progress.INVENTOR && it.b != it.c }
    .map { Action.playProgress(Progress.INVENTOR, it.c, it.b) }

fun asLegalPick(a: Action): Action =
    if (a.type == CkActionType.PLAY_PROGRESS && a.a == Progress.INVENTOR && a.b > a.c) Action.playProgress(Progress.INVENTOR, a.c, a.b) else a

/** The hint for progress card [i], or empty. */
fun progressHint(i: Int) = PROGRESS_HINTS.getOrElse(i) { "" }

/** C&K actions that are a tap on a board corner, and the corner each one means. */
val CK_VERTEX_KINDS: Set<ActionKind> = setOf(
    CkActionType.BUILD_KNIGHT, CkActionType.ACTIVATE_KNIGHT, CkActionType.PROMOTE_KNIGHT, CkActionType.MOVE_KNIGHT,
    CkActionType.DISPLACE_KNIGHT, CkActionType.CHASE_ROBBER, CkActionType.RELOCATE_KNIGHT, CkActionType.BUILD_CITY_WALL,
    CkActionType.CHOOSE_VERTEX,
)

/** The corner a C&K board action points at (a move's destination), or -1. */
fun ckVertex(a: Action): Int = when (a.type) {
    CkActionType.MOVE_KNIGHT, CkActionType.DISPLACE_KNIGHT -> a.b
    in CK_VERTEX_KINDS -> a.payload.toInt()
    else -> -1
}

private fun card(i: Int) = ALL_CARD_NAMES.getOrElse(i) { "card $i" }
private fun progress(i: Int) = PROGRESS_NAMES.getOrElse(i) { "card $i" }
private fun deck(t: Int) = DECK_NAMES.getOrElse(t) { "deck $t" }

/** A short button / confirm label for a C&K action, in our names; null for non-C&K actions. */
fun ckLabel(s: GameState, a: Action, names: List<String>): String? = when (a.type) {
    CkActionType.BUILD_KNIGHT -> "Place a new knight here"
    CkActionType.ACTIVATE_KNIGHT -> "Supply this knight"
    CkActionType.PROMOTE_KNIGHT -> "Promote this knight"
    CkActionType.MOVE_KNIGHT -> "Move the knight here"
    CkActionType.DISPLACE_KNIGHT -> "Charge and displace the knight here"
    CkActionType.CHASE_ROBBER -> "Chase the bandit off"
    CkActionType.RELOCATE_KNIGHT -> "Set your knight down here"
    CkActionType.BUILD_CITY_WALL -> "Build Ramparts here"
    CkActionType.CHOOSE_VERTEX -> when (s.phase) {
        Phase.BARBARIAN_LOSS -> "Lose this town"
        Phase.DESERTER_REMOVE -> "Remove this knight"
        else -> "Choose this corner"
    }
    CkActionType.IMPROVE_CITY -> "Improve ${deck(a.a)}" + if (a.b > 0) " (claim the Capital)" else ""
    CkActionType.PLAY_PROGRESS -> "Play ${progress(a.a)}"
    CkActionType.DISCARD_PROGRESS -> "Discard ${progress(a.a)}"
    CkActionType.TAKE_PROGRESS -> "Take ${progress(a.a)}"
    CkActionType.CHOOSE_CARD -> "Choose ${card(a.a)}"
    CkActionType.CHOOSE_PROGRESS_DECK -> "Draw from ${deck(a.a)}"
    CkActionType.COMMERCIAL_HARBOR_OFFER -> "Offer ${card(a.a)} to ${names.getOrElse(a.b) { "player ${a.b}" }}"
    CkActionType.CK_DONE -> "Done"
    CkActionType.OFFER_TRADE_CK -> "Offer this trade"
    else -> null
}

/** Detail for one choice of a progress card with several ways to play it, in words (never bare numbers). */
fun progressOption(s: GameState, a: Action, names: List<String>): String {
    val c = a.a
    return when (c) {
        Progress.RESOURCE_MONOPOLY, Progress.TRADE_MONOPOLY, Progress.MERCHANT_FLEET -> card(a.b)
        Progress.MASTER_MERCHANT, Progress.DESERTER, Progress.SPY -> names.getOrElse(a.b) { "Player ${a.b + 1}" }
        Progress.CRANE -> {
            // What it costs with the Hoist against without (Mauro, 2026-10-13: Hoist went off before he saw what it did).
            val next = (s.ck?.level(s.currentPlayer, a.b) ?: 0) + 1
            val what = card(Track.commodity(a.b))
            "Improve ${deck(a.b)} to level $next: ${maxOf(0, next - 1)} $what instead of $next"
        }
        else -> if (a.c != 0) "${a.b} / ${a.c}" else "${a.b}"
    }
}

/** Button groups for the non-board C&K moves: one entry per kind (and per card), each with its actions. */
fun ckButtonGroups(s: GameState, legal: List<Action>, names: List<String>): List<Pair<String, List<Action>>> {
    val groups = legal.filter { it.type is CkActionType && it.type !in CK_VERTEX_KINDS && it.type != CkActionType.OFFER_TRADE_CK && offered(s, it) }
        .groupBy { if (it.type == CkActionType.PLAY_PROGRESS) "Play ${progress(it.a)}" else ckLabel(s, it, names) ?: it.toString() }
        .map { (label, actions) -> label to actions }
    return groups // bank and player trades go through the trade chest
}

/** Label for one bank trade choice (card indices may be commodities). */
fun bankLabel(a: Action) = "Give ${card(a.maritimeGive)} for ${card(a.maritimeGet)}"

/** The prompt line for a C&K turn, by what the legal moves ask for. */
fun ckPrompt(s: GameState, legal: List<Action>, names: List<String> = PLAYER_NAMES): String? {
    val kinds = legal.map { it.type }.toSet()
    val by = names.getOrElse(s.currentPlayer) { "Someone" }
    return when {
        // A card played against you: who played what, and what you have to do (Mauro).
        s.phase == Phase.DESERTER_REMOVE -> "$by played Turncoat against you: tap one of your knights to give up"
        s.phase == Phase.WEDDING -> "$by played Feast against you: give them ${s.ck?.giveOwed?.getOrNull(s.actingPlayer) ?: 2} card(s)"
        s.phase == Phase.COMMERCIAL_HARBOR_RESPOND -> "$by played Harbour Charter: give back one commodity for the card you got"
        s.phase == Phase.BARBARIAN_LOSS -> "The Raiders won: tap one of your towns to lose to them"
        s.phase == Phase.SPY -> "Informer: take one of ${names.getOrElse(s.ck?.pendingTarget ?: -1) { "their" }}'s progress cards"
        s.phase == Phase.COMMERCIAL_HARBOR -> "Harbour Charter: give each player a resource for a commodity, or Done"
        CkActionType.CHOOSE_PROGRESS_DECK in kinds -> "Raiders beaten: draw a progress card from a deck"
        CkActionType.DISCARD_PROGRESS in kinds -> "Too many progress cards: discard one"
        CkActionType.CHOOSE_CARD in kinds -> "Choose a card"
        CkActionType.RELOCATE_KNIGHT in kinds -> "Tap a glowing corner to set your knight down"
        CkActionType.CHOOSE_VERTEX in kinds -> "Tap a glowing corner"
        else -> null
    }
}

/**
 * Town improvements offered by tapping one of [me]'s towns at lattice point [p] (Mauro: "put the city upgrade on the
 * city"): every legal improvement, in deck order; one that wins the Capital only for the tapped town. Empty when the tap
 * isn't on a town of yours, or no improvement can be paid for now.
 */
fun townMovesAt(s: GameState, legal: List<Action>, p: androidx.compose.ui.geometry.Offset, me: Int): List<Action> {
    if (me < 0 || legal.none { it.type == CkActionType.IMPROVE_CITY }) return emptyList()
    val town = townAt(s, p, me) ?: return emptyList()
    return legal.filter { it.type == CkActionType.IMPROVE_CITY && (it.b == 0 || it.b - 1 == town) }.sortedBy { it.a }
}

/** The town of [me]'s within a finger's reach of lattice point [p], nearest first, or null. */
fun townAt(s: GameState, p: androidx.compose.ui.geometry.Offset, me: Int): Int? {
    fun d(v: Int) = vertexLattice(s.board, v).let { kotlin.math.hypot(it.x - p.x, it.y - p.y) }
    return (0 until s.board.topology.vertexCount).filter { s.vertexOwner[it] == me && s.vertexBuilding[it] == wgbg.engine.Piece.CITY && d(it) < TAP_RADIUS }
        .minByOrNull { d(it) }
}

/**
 * What a board tap at [p] offers: the moves at the nearest spot ([boardLegal], narrowed by the build menu), plus the town
 * improvements when the tap is on one of your towns and nothing nearer claims it (Ramparts on that town share the box).
 * A picked build-menu entry ([menuPicked]) keeps the tap to that entry.
 */
fun tapMoves(s: GameState, boardLegal: List<Action>, legal: List<Action>, p: androidx.compose.ui.geometry.Offset, me: Int, menuPicked: Boolean): List<Action> {
    val board = movesAt(s.board, boardLegal, p)
    if (menuPicked) return board
    val town = townMovesAt(s, legal, p, me)
    if (town.isEmpty() || board.isEmpty()) return board.ifEmpty { town }
    val townPos = vertexLattice(s.board, townAt(s, p, me)!!)
    val boardPos = spotOf(s.board, board[0]) ?: return board
    return if (kotlin.math.hypot(boardPos.x - townPos.x, boardPos.y - townPos.y) < 1e-3f) board + town
    else if (kotlin.math.hypot(boardPos.x - p.x, boardPos.y - p.y) < kotlin.math.hypot(townPos.x - p.x, townPos.y - p.y)) board else town
}

/**
 * Moves that carry a knight to another corner. They light up only once the player picks that knight (0.3.4, Mauro: with
 * a knight that could move, every corner lit up as a move target and it wasn't clear what for).
 */
val KNIGHT_MOVES: Set<ActionKind> = setOf(CkActionType.MOVE_KNIGHT, CkActionType.DISPLACE_KNIGHT)

/** The corners of the knights in [legal] that can move now. */
fun movableKnights(legal: List<Action>): Set<Int> = legal.filter { it.type in KNIGHT_MOVES }.map { it.a }.toSet()

/** [boardLegal] as the board offers it while knight [picked] is chosen (null: none): only that knight's moves. */
fun withKnightPick(boardLegal: List<Action>, picked: Int?): List<Action> = boardLegal.filter { it.type !in KNIGHT_MOVES || it.a == picked }

/**
 * The knight a tap at lattice point [p] picks or lets go of: a movable knight within reach, unless another move is
 * offered right there (Supply or Promote on that knight come first; the build menu's Move knight offers only moves).
 * Returns the new pick, null to let go, or [picked] unchanged when the tap isn't on a movable knight.
 */
fun knightPickAt(s: GameState, boardLegal: List<Action>, p: androidx.compose.ui.geometry.Offset, picked: Int?): Int? {
    fun d(v: Int) = vertexLattice(s.board, v).let { kotlin.math.hypot(it.x - p.x, it.y - p.y) }
    val knight = movableKnights(boardLegal).filter { d(it) < TAP_RADIUS }.minByOrNull { d(it) } ?: return picked
    if (boardLegal.any { it.type !in KNIGHT_MOVES && it.type in CK_VERTEX_KINDS && ckVertex(it) == knight }) return picked
    return if (picked == knight) null else knight
}

/** The board's glowing spots with knight [picked] chosen: its targets, or with none picked, each knight that can move. */
fun knightTargets(boardLegal: List<Action>, picked: Int?): Targets = Targets.of(withKnightPick(boardLegal, picked)).let {
    if (picked != null) it else Targets(it.vertices + movableKnights(boardLegal), it.edges, it.hexes)
}

/** Track of the improvement an IMPROVE_CITY action raises (for colouring its button). */
fun improveTrack(a: Action) = if (a.type == CkActionType.IMPROVE_CITY) a.a else -1

@Suppress("unused")
private val TRACK_ORDER_CHECK = listOf(Track.TRADE, Track.POLITICS, Track.SCIENCE) // DECK_NAMES follows this order

/**
 * A progress card aimed at something (a track, a player) shows its picker even when there's only one choice, so you
 * see what it will do before it's spent (Mauro, 2026-10-13: one tap on "Play Hoist" improved Guilds at once).
 */
fun asksFirst(a: Action) = a.type == CkActionType.PLAY_PROGRESS &&
    a.a in intArrayOf(Progress.CRANE, Progress.MASTER_MERCHANT, Progress.DESERTER, Progress.SPY)

/** A legal move worth offering: a progress card only when it would do something (the engine still accepts the rest). */
fun offered(s: GameState, a: Action) =
    a.type != CkActionType.PLAY_PROGRESS || wgbg.engine.CitiesAndKnights.progressHasEffect(s, s.currentPlayer, a.a)

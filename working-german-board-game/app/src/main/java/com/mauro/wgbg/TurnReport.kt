package com.mauro.wgbg

import wgbg.engine.Action
import wgbg.engine.ActionType
import wgbg.engine.CARD_TYPES
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Resource

/** Our names for the five resources, in engine order (WOOD, BRICK, SHEEP, WHEAT, ORE). */
val RESOURCE_NAMES = listOf("Timber", "Clay", "Fleece", "Grain", "Stone")
/** Every card a hand can hold, by engine card index: the resources, then the Age of Chivalry commodities. */
val ALL_CARD_NAMES = RESOURCE_NAMES + listOf("Parchment", "Fabric", "Silver")

/** What happened in one player's turn: the roll, who got what, and what they did. */
data class TurnReport(
    val player: Int,
    val turn: Int,
    val die1: Int = 0,
    val die2: Int = 0,
    /** Age of Chivalry event die face 0..5 (0-2 the Raiders' ship, 3-5 a gate: trade, politics, science), -1 if none. */
    val event: Int = -1,
    /** Per-player resource changes from the roll (production), or empty on a 7. */
    val production: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    /** Cards each player got from the roll, by player then resource (#47). */
    val rollIn: List<IntArray> = emptyList(),
    /** Cards each player discarded on a 7 (public: they go back to the bank face up). */
    val rollOut: List<IntArray> = emptyList(),
    /** The bandit's robbery this turn, if any (which card is secret but to the two players). */
    val steal: Steal? = null,
) {
    val rolled get() = die1 > 0
}

/** [thief] took one [card] from [victim]. */
data class Steal(val thief: Int, val victim: Int, val card: Int)

/**
 * Something [by]'s turn cost [victim] (cards, a road, a knight, a progress card), with the card or bandit that did it:
 * shown to the victim (Mauro: "the person getting shafted needs to know who and why"). [seq] counts up from 1.
 */
data class Hit(val seq: Int, val victim: Int, val by: Int, val cause: String, val why: String?, val lost: String)

/**
 * Watches every action applied to a game and builds [TurnReport]s. Call [apply] instead of
 * `Rules.apply` so it can diff hands around each action.
 */
class TurnTracker(private val names: List<String>) {
    var current: TurnReport? = null
        private set
    var last: TurnReport? = null
        private set
    /** Finished turns, newest first, up to [HISTORY] (the info box scrolls back through them, #65). */
    var history: List<TurnReport> = emptyList()
        private set
    /** What other players' turns cost each player, newest last (the last [HISTORY] of them). */
    var hits: List<Hit> = emptyList()
        private set
    /** Every roll this game: how many times each total 2..12 came up (index = total), and each event die face 0..5. */
    val rollCounts = IntArray(13)
    val eventCounts = IntArray(6)
    /** The progress card the turn's player played last this turn (engine index), or -1. */
    var turnCard = -1
        private set

    /** Per player: cards by kind, then roads, knights and progress cards held. */
    private fun holdings(s: GameState): Array<IntArray> = Array(s.numPlayers) { p ->
        val kinds = if (s.ck != null) wgbg.engine.CK_CARD_TYPES else CARD_TYPES
        val ck = s.ck
        IntArray(kinds + 3) { i ->
            when {
                i < kinds -> s.card(p, i)
                i == kinds -> s.edgeOwner.count { it == p }
                i == kinds + 1 -> ck?.knightOwner?.count { it == p } ?: 0
                else -> ck?.progressCount(p) ?: 0
            }
        }
    }

    fun apply(s: GameState, who: Int, action: Action, applyFn: () -> Unit) {
        if (current == null || current!!.player != s.currentPlayer || current!!.turn != s.turn) {
            current?.let { if (it.rolled || it.events.isNotEmpty()) { last = it; history = (listOf(it) + history).take(HISTORY) } }
            current = TurnReport(s.currentPlayer, s.turn)
            turnCard = -1
        }
        val owner = s.currentPlayer
        val held = holdings(s)
        val phaseBefore = s.phase
        var d1 = 0
        var d2 = 0
        var ev = -1
        val alchemist = action.type == wgbg.engine.CkActionType.PLAY_PROGRESS && (action.payload and 0xff).toInt() == wgbg.engine.Progress.ALCHEMIST
        if (action.type == ActionType.ROLL && action.payload != 0L) {
            d1 = action.a; d2 = action.b // dice thrown outside the engine travel in the action
            ev = ((action.payload ushr 16) and 0xff).toInt() - 1
        } else if (action.type == ActionType.ROLL) {
            // The roll is the first thing that uses the RNG, so a copy shows the dice it will draw.
            val peek = s.rng.copy()
            d1 = peek.nextInt(6) + 1
            d2 = peek.nextInt(6) + 1
            if (s.ck != null) ev = peek.nextInt(6)
        } else if (alchemist) {
            // The Transmuter (Alchemist) is this turn's roll: the player picked both number dice, the event die is thrown.
            d1 = ((action.payload ushr 8) and 0xff).toInt(); d2 = ((action.payload ushr 16) and 0xff).toInt()
            ev = s.rng.copy().nextInt(6)
        }
        applyFn()
        val heldAfter = holdings(s)
        noteHits(s, owner, who, action, held, heldAfter, phaseBefore)
        // Every card kind the hand holds, commodities too: with resources only, a discarded, stolen or traded commodity
        // never showed (reports Mnmfs73, jpRLBzr, 0.3.0: "not showing Commodities that were discarded").
        val kinds = if (s.ck != null) wgbg.engine.CK_CARD_TYPES else CARD_TYPES
        val gained = Array(s.numPlayers) { p -> IntArray(kinds) { c -> heldAfter[p][c] - held[p][c] } }
        val r = current!!
        val n = names[who]
        if ((action.type == ActionType.ROLL || alchemist) && d1 > 0) { rollCounts[(d1 + d2).coerceIn(2, 12)]++; if (ev in 0..5) eventCounts[ev]++ }
        current = when {
            alchemist || action.type == ActionType.ROLL -> r.copy(
                die1 = d1, die2 = d2, event = ev,
                rollIn = gained.map { g -> IntArray(g.size) { maxOf(0, g[it]) } },
                rollOut = List(s.numPlayers) { IntArray(kinds) },
                production = if (d1 + d2 == 7) listOf("Bandit! Players over the limit must discard.")
                else (0 until s.numPlayers).mapNotNull { p -> changes(gained[p])?.let { "${names[p]} $it" } }
                    .ifEmpty { listOf("Nobody produced anything.") },
            )
            action.type == ActionType.END_TURN -> r
            action.type == ActionType.REJECT_TRADE || action.type == ActionType.ACCEPT_TRADE ||
                action.type == ActionType.COUNTER_TRADE || action.type == wgbg.engine.CkActionType.COUNTER_TRADE_CK -> r
            action.type == ActionType.MOVE_ROBBER -> {
                val victim = action.robberVictim
                val card = if (victim >= 0) gained[victim].indexOfFirst { it < 0 } else -1
                r.plus(if (victim >= 0) "$n moved the bandit and took a card from ${names[victim]}" else "$n moved the bandit")
                    .let { if (victim >= 0 && card >= 0) it.copy(steal = Steal(who, victim, card)) else it }
            }
            action.type == ActionType.DISCARD -> r.plus("$n discarded " + gained[who].withIndex().filter { it.value < 0 }.joinToString(", ") { "${-it.value} ${ALL_CARD_NAMES[it.index]}" })
                .let { rep -> if (rep.rollOut.size > who) rep.copy(rollOut = rep.rollOut.mapIndexed { p, out -> if (p != who) out else IntArray(out.size) { out[it] + maxOf(0, -gained[who][it]) } }) else rep }
            action.type == ActionType.MARITIME_TRADE -> r.plus("$n traded with the bank: ${tradeText(gained[who])}")
            action.type == ActionType.OFFER_TRADE || action.type == wgbg.engine.CkActionType.OFFER_TRADE_CK -> r.plus("$n offered a trade")
            action.type == ActionType.CONFIRM_TRADE -> {
                val partner = action.payload.toInt()
                r.plus("$n traded with ${names[partner]}: ${tradeText(gained[who])}")
            }
            action.type == ActionType.CANCEL_TRADE -> r.plus("$n withdrew the trade offer")
            action.type == ActionType.PLAY_YEAR_OF_PLENTY || action.type == ActionType.PLAY_MONOPOLY ->
                r.plus("$n played ${describe(action.type)} and got ${changes(gained[who]) ?: "nothing"}")
            action.type == wgbg.engine.CkActionType.CHOOSE_VERTEX -> r.plus(when (phaseBefore) {
                Phase.BARBARIAN_LOSS -> "$n lost a town to the Raiders"
                Phase.DESERTER_REMOVE -> "$n gave up a knight to Turncoat"
                else -> "$n ${describe(action.type)}"
            })
            else -> r.plus("$n ${describe(action.type)}")
        }
    }

    /**
     * Records a [Hit] for every other player who lost something on [owner]'s turn through no choice of their own:
     * not trades they agreed to, not their own discards on a rolled 7. Card effects that the victim carries out
     * (giving cards for a Wedding, removing a knight for a Turncoat) count, named after the card that caused them.
     */
    private fun noteHits(s: GameState, owner: Int, who: Int, action: Action, was: Array<IntArray>, now: Array<IntArray>, phaseBefore: Phase) {
        if (action.type.toString() == "PLAY_PROGRESS" && who == owner) turnCard = (action.payload and 0xff).toInt()
        if ("TRADE" in action.type.toString()) return
        val sevenDiscard = action.type == ActionType.DISCARD && phaseBefore == Phase.DISCARD && turnCard < 0
        if (sevenDiscard) return
        val kinds = was[0].size - 3
        for (p in 0 until s.numPlayers) {
            if (p == owner) continue
            val lost = buildList {
                val cards = (0 until kinds).filter { now[p][it] < was[p][it] }
                if (cards.isNotEmpty()) add(cards.joinToString(", ") { "${was[p][it] - now[p][it]} ${ALL_CARD_NAMES[it]}" })
                if (now[p][kinds] < was[p][kinds]) add("a road")
                if (now[p][kinds + 1] < was[p][kinds + 1]) add("a knight")
                if (now[p][kinds + 2] < was[p][kinds + 2]) add("a progress card")
            }
            if (lost.isEmpty()) continue
            val (cause, why) = when {
                action.type == ActionType.MOVE_ROBBER -> "moved the bandit to you" to null
                turnCard >= 0 -> "played ${PROGRESS_NAMES.getOrElse(turnCard) { "a card" }}" to PROGRESS_HINTS.getOrNull(turnCard)
                else -> "took it" to null
            }
            hits = (hits + Hit((hits.lastOrNull()?.seq ?: 0) + 1, p, owner, cause, why, lost.joinToString(", "))).takeLast(HISTORY)
            // The bandit's theft is already in the turn's lines; a card's cost is added here.
            if (action.type != ActionType.MOVE_ROBBER) current = current?.plus("${names[owner]} $cause: ${names[p]} lost ${lost.joinToString(", ")}")
        }
    }

    private fun TurnReport.plus(e: String) = copy(events = (events + e).takeLast(6))

    /** "+2 Timber, +1 Grain" for the positive entries, null if none. */
    private fun changes(delta: IntArray): String? =
        delta.withIndex().filter { it.value > 0 }.joinToString(", ") { "+${it.value} ${ALL_CARD_NAMES[it.index]}" }.ifEmpty { null }

    private fun tradeText(delta: IntArray): String {
        val gave = delta.withIndex().filter { it.value < 0 }.joinToString(", ") { "${-it.value} ${ALL_CARD_NAMES[it.index]}" }
        val got = delta.withIndex().filter { it.value > 0 }.joinToString(", ") { "${it.value} ${ALL_CARD_NAMES[it.index]}" }
        return "gave $gave, got $got"
    }

    companion object {
        /** How many finished turns the info box keeps. */
        const val HISTORY = 40
        init { check(Resource.COUNT == RESOURCE_NAMES.size) }
    }
}

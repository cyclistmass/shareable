package com.mauro.wgbg

import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.BoardLayout
import wgbg.engine.CARD_TYPES
import wgbg.engine.CitiesAndKnights
import wgbg.engine.CkActionType
import wgbg.engine.CkPiece
import wgbg.engine.Commodity
import wgbg.engine.DevCard
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Piece
import wgbg.engine.Progress
import wgbg.engine.Resource
import wgbg.engine.Rng
import wgbg.engine.Rules
import wgbg.engine.Track

// ------------------------------------------------------------------ #133 card tester (debug builds): the scenarios

/**
 * A number the tester panel watches for one scenario: what it reads at the start, what it should read once the card
 * has done its work, and (in the panel) what it reads now.
 */
class Fact(val label: String, val read: (GameState) -> Int, val target: (Int) -> Int)

/** A fact's row in a result: the start value, what it should be, and what it was when the tester judged it. */
data class FactRow(val label: String, val before: Int, val expected: Int, val actual: Int) {
    val ok get() = actual == expected
}

/**
 * One scenario, built and ready: the position (player 0, you, to act), what to tap, what should happen, the facts to
 * watch, and the scripted play the unit tests make in your place (opponents' answers are left to the bots).
 */
class Prepared(
    val state: GameState,
    val howTo: String,
    val expect: String,
    val facts: List<Fact>,
    val script: List<(GameState) -> Action>,
) {
    val before: List<Int> = facts.map { it.read(state) }
    val expected: List<Int> = facts.mapIndexed { i, f -> f.target(before[i]) }

    /** The facts against [now]. */
    fun rows(now: GameState): List<FactRow> = facts.mapIndexed { i, f -> FactRow(f.label, before[i], expected[i], f.read(now)) }
}

/**
 * A card x mode to check by hand: [card] in our names, [mode] in a few words. [playable] says whether the card should be
 * offered at all in this position (edge modes: it shouldn't); [isPlay] picks out the card's own play moves.
 */
class CardScenario(
    val id: String,
    val card: String,
    val mode: String,
    val chivalry: Boolean,
    val playable: Boolean,
    val isPlay: (Action) -> Boolean,
    /** An extra rule on the legal moves at the start (e.g. "Stone isn't offered"), checked by the unit tests. */
    val rule: ((GameState, List<Action>) -> Boolean)? = null,
    val build: () -> Prepared,
)

/** A finished check: passed or failed, the note, and the facts as they stood. */
data class CardResult(val id: String, val pass: Boolean, val note: String, val rows: List<FactRow>, val time: Long)

/** Where each scenario stands on the phone's checklist (kept across restarts). */
enum class CheckState { LEFT, PASSED, FAILED }

object CardTester {
    /** Seed for every scenario's board and bots, so a scenario is the same position every time. */
    const val SEED = 133L
    const val PLAYERS = 4

    // ------------------------------------------------------------ the checklist (SharedPreferences, as text)

    /** One checklist entry as stored: "P|note" or "F|note". */
    fun encodeEntry(pass: Boolean, note: String) = (if (pass) "P|" else "F|") + note.replace("\n", " ").take(300)

    fun decodeEntry(text: String?): Pair<CheckState, String> = when {
        text == null -> CheckState.LEFT to ""
        text.startsWith("P|") -> CheckState.PASSED to text.drop(2)
        text.startsWith("F|") -> CheckState.FAILED to text.drop(2)
        else -> CheckState.LEFT to ""
    }

    /** "12 of 85 done · 11 passed · 1 failed". */
    fun progressLine(states: Map<String, CheckState>, all: List<CardScenario> = scenarios): String {
        val passed = all.count { states[it.id] == CheckState.PASSED }
        val failed = all.count { states[it.id] == CheckState.FAILED }
        return "${passed + failed} of ${all.size} done · $passed passed · $failed failed"
    }

    // ------------------------------------------------------------ the report

    /**
     * Which fail screenshots go in which uploaded picture. Firestore keeps two pictures per report, so with more
     * than two fails they're tiled into two contact sheets: the result is, per sheet, the fail indices it holds.
     */
    fun shotPlan(fails: Int, sheets: Int = 2): List<List<Int>> {
        if (fails <= 0) return emptyList()
        if (fails <= sheets) return (0 until fails).map { listOf(it) }
        val per = (fails + sheets - 1) / sheets
        return (0 until fails).chunked(per)
    }

    /** Columns and rows for a sheet of [n] tiles (as square as it gets, wider than tall). */
    fun grid(n: Int): Pair<Int, Int> {
        val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt().coerceAtLeast(1)
        return cols to (n + cols - 1) / cols
    }

    /**
     * The session's report, sent like an in-app bug report (#133): every result with its note and facts, which
     * picture shows each fail, and what's still left on the checklist. Fitted under [max] characters.
     */
    fun report(results: List<CardResult>, states: Map<String, CheckState>, version: String, versionCode: Long,
               device: String, screen: String, time: Long, max: Int = Feedback.UPLOAD_MAX): String {
        val byId = scenarios.associateBy { it.id }
        val fails = results.filter { !it.pass }
        val plan = shotPlan(fails.size)
        fun shotOf(r: CardResult): String? {
            val i = fails.indexOf(r)
            if (i < 0) return null
            val sheet = plan.indexOfFirst { i in it }
            return if (plan[sheet].size == 1) "screenshot ${sheet + 1}" else "screenshot ${sheet + 1}, tile ${plan[sheet].indexOf(i) + 1}"
        }
        val text = buildString {
            appendLine("WGBG card test report")
            appendLine("version: $version ($versionCode)")
            appendLine("device: $device")
            appendLine("screen: $screen")
            appendLine("time: ${java.time.Instant.ofEpochMilli(time)}")
            appendLine("checklist: ${progressLine(states)}")
            appendLine("results this session (${results.size}):")
            for (r in results) {
                val s = byId[r.id]
                appendLine("  ${if (r.pass) "PASS" else "FAIL"}  ${r.id} · ${s?.card ?: "?"} · ${s?.mode ?: "?"}" +
                    (shotOf(r)?.let { " [$it]" } ?: ""))
                if (r.note.isNotBlank()) appendLine("    note: ${r.note.trim()}")
                for (f in r.rows) appendLine("    ${f.label}: start ${f.before}, should be ${f.expected}, was ${f.actual}${if (f.ok) "" else "  <-- differs"}")
            }
            val left = scenarios.filter { (states[it.id] ?: CheckState.LEFT) == CheckState.LEFT }
            if (left.isNotEmpty()) {
                appendLine("left to test (${left.size}):")
                left.forEach { appendLine("  ${it.id} · ${it.card} · ${it.mode}") }
            }
        }.trimEnd() + "\n"
        return if (text.length <= max) text else text.take(max - 12) + "\n[truncated]"
    }

    // ------------------------------------------------------------ running a scenario without a person (unit tests)

    /** The card's plays the game would offer you now (an Age of Chivalry card only when it would do something). */
    fun offeredPlays(sc: CardScenario, s: GameState): List<Action> =
        if (s.currentPlayer != 0 || s.actingPlayer != 0) emptyList()
        else Rules.legalActions(s).filter { sc.isPlay(it) && offered(s, it) }

    /** Opponents answer whatever they're asked (their first legal choice) until it's you again or the game ends. */
    fun answerOthers(s: GameState) {
        var guard = 0
        while (!s.isOver && s.actingPlayer != 0 && guard++ < 500) Rules.apply(s, Rules.legalActions(s).first())
    }

    /** Plays [p]'s script on a copy, with the opponents answering; throws if a scripted move isn't legal. */
    fun runScript(p: Prepared): GameState {
        val s = p.state.copy()
        for (step in p.script) {
            answerOthers(s)
            val a = step(s)
            require(Rules.isLegal(s, a)) { "scripted $a isn't legal in ${s.phase}" }
            Rules.apply(s, a)
        }
        answerOthers(s)
        return s
    }

    /** The game-start settings the tester's game runs under: you and three Greedy bots, on the scenario's ruleset. */
    fun setupFor(state: GameState, name: String): GameSetup = GameSetup(
        name = name.ifBlank { "You" },
        seats = listOf(SeatSetup(SeatKind.FRIEND)) + List(GameSetup.MAX_SEATS - 1) { SeatSetup(SeatKind.BOT, BotLevel.GREEDY) },
        ruleset = if (state.ck != null) Ruleset.CHIVALRY else Ruleset.BASE,
        points = state.config.vpToWin.coerceIn(GameSetup.POINTS_RANGE),
        // The scenarios name the bots by colour ("Blue gives you 3 Grain"), so the tester's game always does too.
        theme = NameTheme.COLOURS,
    )

    // ------------------------------------------------------------ the scenarios

    val scenarios: List<CardScenario> by lazy { baseScenarios() + chivalryScenarios() }
}

// ------------------------------------------------------------------ building positions

/** The bots by the names the tester's game shows (colours, [CardTester.setupFor]); the scenario texts use these. */
internal val TESTER_NAMES = listOf("you") + (1 until GameSetup.MAX_SEATS).map(::colourName)
private fun who(p: Int) = TESTER_NAMES[p]
private fun Who(p: Int) = TESTER_NAMES[p].replaceFirstChar { it.uppercase() }

/** A position under construction, with the helpers the scenarios share. Everything goes through the bank or a deck. */
internal class Bench(val s: GameState) {
    var howTo = ""
    var expect = ""
    val facts = mutableListOf<Fact>()
    val script = mutableListOf<(GameState) -> Action>()
    val b get() = s.board
    val ck get() = s.ck!!

    fun change(label: String, d: Int, read: (GameState) -> Int) { facts += Fact(label, read) { it + d } }
    fun becomes(label: String, v: Int, read: (GameState) -> Int) { facts += Fact(label, read) { v } }
    fun same(label: String, read: (GameState) -> Int) { facts += Fact(label, read) { it } }
    fun then(a: Action) { script += { a } }
    fun then(f: (GameState) -> Action) { script += f }

    fun prepared() = Prepared(s, howTo, expect, facts.toList(), script.toList())

    // Cards.
    fun bankOf(c: Int) = if (c < Resource.COUNT) s.bank[c] else ck.commodityBank[c - Resource.COUNT]
    fun give(p: Int, c: Int, n: Int) {
        require(bankOf(c) >= n) { "bank short of card $c" }
        if (c < Resource.COUNT) { s.bank[c] -= n; s.hands[p * CARD_TYPES + c] += n }
        else { ck.commodityBank[c - Resource.COUNT] -= n; ck.commodities[p * Commodity.COUNT + c - Resource.COUNT] += n }
    }
    fun empty(p: Int) {
        for (r in 0 until Resource.COUNT) { s.bank[r] += s.hands[p * CARD_TYPES + r]; s.hands[p * CARD_TYPES + r] = 0 }
        s.ck?.let { ck -> for (l in 0 until Commodity.COUNT) { ck.commodityBank[l] += ck.commodities[p * Commodity.COUNT + l]; ck.commodities[p * Commodity.COUNT + l] = 0 } }
    }
    fun emptyCard(p: Int, c: Int) { val n = s.card(p, c); give(p, c, -n) }
    /** Hands the bank's whole pile of [c] (but [keep]) to the opponents, round the table. */
    fun drainBank(c: Int, keep: Int = 0) { var q = 1; while (bankOf(c) > keep) { give(q, c, 1); q = q % 3 + 1 } }

    // Fortune cards.
    fun dev(p: Int, card: Int, new: Boolean = false) {
        require(s.devDeck[card] > 0)
        s.devDeck[card]--; s.devHand[p * DevCard.COUNT + card]++
        if (new) s.devNew[p * DevCard.COUNT + card]++
    }
    fun played(p: Int, card: Int, n: Int) { s.devDeck[card] -= n; s.devPlayed[p * DevCard.COUNT + card] += n }

    // Progress cards: taken out of their deck, so every card stays counted.
    fun progress(p: Int, card: Int) { stack(card); draw(p, card) }
    private fun draw(p: Int, card: Int) {
        val t = Progress.track(card)
        ck.deckHead[t] = (ck.deckHead[t] + 1) % Progress.PER_DECK
        ck.deckSize[t]--
        ck.progressHand[p * Progress.COUNT + card]++
    }
    /** Puts [card] on top of its deck. */
    fun stack(card: Int) {
        val t = Progress.track(card)
        val per = Progress.PER_DECK
        for (i in 0 until ck.deckSize[t]) {
            val idx = t * per + (ck.deckHead[t] + i) % per
            if (ck.deck[idx] == card) {
                val top = t * per + ck.deckHead[t]
                ck.deck[idx] = ck.deck[top]; ck.deck[top] = card
                return
            }
        }
        error("no ${Progress.NAMES[card]} left in its deck")
    }

    // The board.
    fun buildings(p: Int, kind: Int) = (0 until b.vertexCount).filter { s.vertexOwner[it] == p && s.vertexBuilding[it] == kind }
    fun recompute() { for (q in 0 until s.numPlayers) s.roadLength[q] = Rules.longestRoad(s, q); Rules.updateLongestRoad(s) }
    fun settle(p: Int, v: Int) {
        s.vertexOwner[v] = p; s.vertexBuilding[v] = Piece.SETTLEMENT
        s.piecesLeft[p * Piece.COUNT + Piece.SETTLEMENT]--
        s.portMask[p] = s.portMask[p] or b.vertexPortBit[v]
        recompute()
    }
    fun upgrade(v: Int) {
        val p = s.vertexOwner[v]
        require(s.vertexBuilding[v] == Piece.SETTLEMENT)
        s.vertexBuilding[v] = Piece.CITY
        s.piecesLeft[p * Piece.COUNT + Piece.CITY]--; s.piecesLeft[p * Piece.COUNT + Piece.SETTLEMENT]++
    }
    fun downgrade(v: Int) {
        val p = s.vertexOwner[v]
        require(s.vertexBuilding[v] == Piece.CITY)
        s.vertexBuilding[v] = Piece.SETTLEMENT
        s.piecesLeft[p * Piece.COUNT + Piece.CITY]++; s.piecesLeft[p * Piece.COUNT + Piece.SETTLEMENT]--
    }
    fun road(p: Int, e: Int) {
        require(s.edgeOwner[e] < 0)
        s.edgeOwner[e] = p; s.piecesLeft[p * Piece.COUNT + Piece.ROAD]--
        recompute()
    }
    /** One more road for [p], on its first legal spot. */
    fun extend(p: Int): Int {
        val e = (0 until b.edgeCount).first { Rules.canBuildRoadAt(s, p, it) }
        road(p, e)
        return e
    }
    /** Lays roads until [p] has only [left] road pieces. */
    fun roadsLeft(p: Int, left: Int) { while (Rules.piecesLeft(s, p, Piece.ROAD) > left) extend(p) }
    /** A new homestead for [p], building roads out to a free corner first. */
    fun newSettlement(p: Int): Int {
        repeat(12) {
            (0 until b.vertexCount).firstOrNull { Rules.canBuildSettlementAt(s, p, it) }?.let { settle(p, it); return it }
            extend(p)
        }
        error("no room for a homestead")
    }
    /** An empty corner on [p]'s roads (where a knight can stand); [skip] corners already taken by the scenario. */
    fun knightSpot(p: Int, skip: Set<Int> = emptySet()): Int? =
        (0 until b.vertexCount).firstOrNull { it !in skip && s.vertexOwner[it] < 0 && (s.ck == null || ck.knightOwner[it] < 0) && Rules.hasRoadAt(s, p, it) }
    fun knight(p: Int, v: Int, level: Int = 1, active: Boolean = false) {
        require(ck.knightOwner[v] < 0 && s.vertexOwner[v] < 0)
        ck.knightOwner[v] = p; ck.knightLevel[v] = level; ck.knightFlags[v] = if (active) wgbg.engine.CkState.ACTIVE else 0
        ck.knightsLeft[p * CkPiece.MAX_KNIGHT_LEVEL + level - 1]--
        recompute()
    }
    fun level(p: Int, track: Int, level: Int) {
        ck.trackLevel[p * Track.COUNT + track] = level
        if (level >= Track.METROPOLIS_LEVEL && ck.metroOwner[track] < 0) {
            ck.metroOwner[track] = p
            ck.metroVertex[track] = (0 until b.vertexCount).first { s.vertexOwner[it] == p && s.vertexBuilding[it] == Piece.CITY && !ck.isMetropolis(it) }
        }
    }
    /** Reseeds the game's dice so the next event die the engine rolls itself shows [face] (a Transmuter's roll). */
    fun rigEvent(face: Int) {
        var seed = 0L
        while (Rng(seed).nextInt(6) != face) seed++
        s.rng.seed = seed
    }

    // Words.
    fun tile(h: Int): String {
        val r = b.hexResource[h]
        return if (r < 0) "the desert" else "the ${RESOURCE_NAMES[r]} ${s.numberAt(h)} tile"
    }
    fun touches(h: Int, p: Int) = (0 until 6).any { s.vertexOwner[b.hexVerts[h * 6 + it]] == p }
    fun touchesAny(h: Int) = (0 until 6).any { s.vertexOwner[b.hexVerts[h * 6 + it]] >= 0 }
    fun victims(h: Int): Int = if (s.ck != null) CitiesAndKnights.robberVictims(s, 0, h) else Rules.robberVictims(s, 0, h)
}

/** Pips under a number: how many of the 36 rolls make it. */
private fun pips(n: Int) = if (n == 0) 0 else 6 - kotlin.math.abs(7 - n)

/**
 * The scenario's start: the beginner board, four players placed by a simple rule (best corner by pips; your corners can
 * be steered by [mine]), then your turn after the roll with an empty hand and three cards per opponent.
 */
private fun opening(chivalry: Boolean, vpToWin: Int? = null, mine: (GameState, Int) -> Boolean = { _, _ -> true }): Bench {
    val exp = if (chivalry) Expansion.CITIES_AND_KNIGHTS else Expansion.BASE
    val config = GameConfig(expansion = exp, numPlayers = CardTester.PLAYERS, layout = BoardLayout.BEGINNER,
        vpToWin = vpToWin ?: if (chivalry) 13 else 10)
    val s = Rules.newGame(config, CardTester.SEED)
    val buf = ActionBuffer()
    fun score(v: Int) = (0 until 3).sumOf { i -> s.board.vertexHex[v * 3 + i].let { h -> if (h < 0) 0 else pips(s.board.hexNumber[h]) } }
    while (s.phase == Phase.BUILD_INITIAL_SETTLEMENT || s.phase == Phase.BUILD_INITIAL_ROAD) {
        val acts = Rules.legalActions(s, buf).toList()
        val a = if (s.phase == Phase.BUILD_INITIAL_ROAD) acts.first()
        else (if (s.actingPlayer == 0) acts.filter { mine(s, it.payload.toInt()) } else acts).maxBy { score(it.payload.toInt()) }
        Rules.apply(s, a)
    }
    val bench = Bench(s)
    for (p in 0 until s.numPlayers) bench.empty(p)
    for (q in 1 until s.numPlayers) { bench.give(q, Resource.WOOD, 1); bench.give(q, Resource.BRICK, 1); bench.give(q, Resource.SHEEP, 1) }
    s.phase = Phase.PLAY_TURN
    s.currentPlayer = 0; s.actingPlayer = 0
    s.turn = 8; s.hasRolled = true; s.lastRoll = 8
    s.ck?.let { it.lastRed = 4; it.lastEvent = 3 }
    return bench
}

/** Your corners touch a tile of [resource] (or, with [touch] false, never do). */
private fun near(resource: Int, touch: Boolean = true): (GameState, Int) -> Boolean = { s, v ->
    (0 until 3).any { i -> s.board.vertexHex[v * 3 + i].let { h -> h >= 0 && s.board.hexResource[h] == resource } } == touch
}

private fun scenario(id: String, card: String, mode: String, chivalry: Boolean, playable: Boolean, isPlay: (Action) -> Boolean,
                     rule: ((GameState, List<Action>) -> Boolean)? = null, build: () -> Bench) =
    CardScenario(id, card, mode, chivalry, playable, isPlay, rule) { build().prepared() }

private fun isType(t: wgbg.engine.ActionKind): (Action) -> Boolean = { it.type == t }
private fun isProgress(card: Int): (Action) -> Boolean = { it.type == CkActionType.PLAY_PROGRESS && it.a == card }
private fun firstOf(t: wgbg.engine.ActionKind): (GameState) -> Action = { s -> Rules.legalActions(s).first { it.type == t } }

private val NOT_OFFERED = "Tap Pass if there's no way to play it (and its card says why); Fail if it can be played."

// ------------------------------------------------------------------ base game: the Fortune cards

private fun baseScenarios(): List<CardScenario> {
    val sentinel = isType(ActionType.PLAY_KNIGHT_CARD)
    val windfall = isType(ActionType.PLAY_YEAR_OF_PLENTY)
    val corner = isType(ActionType.PLAY_MONOPOLY)
    val trail = isType(ActionType.PLAY_ROAD_BUILDING)
    val never: (Action) -> Boolean = { false }
    fun Bench.sentinelHex(victim: Boolean): Int = (0 until b.hexCount).filter { it != s.robberHex }.let { hs ->
        if (victim) hs.first { victims(it) != 0 } else hs.firstOrNull { !touchesAny(it) } ?: hs.first { victims(it) == 0 }
    }
    fun Bench.banditFact(h: Int) = becomes("Bandit on ${tile(h)} (1 = yes)", 1) { if (it.robberHex == h) 1 else 0 }
    return listOf(
        scenario("sentinel.rob", "Sentinel", "move the bandit and rob a player", false, true, sentinel) {
            opening(false).apply {
                dev(0, DevCard.KNIGHT)
                val h = sentinelHex(true)
                val v = Integer.numberOfTrailingZeros(victims(h))
                howTo = "Tap Play Sentinel, tap ${tile(h)}, and rob ${Who(v)}."
                expect = "The bandit moves to ${tile(h)}, you take 1 card from ${Who(v)}, and you've played one more Sentinel."
                change("Your cards", +1) { it.handSize(0) }
                change("${Who(v)}'s cards", -1) { it.handSize(v) }
                change("Sentinels you've played", +1) { it.knightsPlayed(0) }
                banditFact(h)
                then(Action.PLAY_KNIGHT_CARD); then(Action.moveRobber(h, v))
            }
        },
        scenario("sentinel.no-victim", "Sentinel", "bandit to a tile nobody else builds on", false, true, sentinel) {
            opening(false).apply {
                dev(0, DevCard.KNIGHT)
                val h = sentinelHex(false)
                howTo = "Tap Play Sentinel and tap ${tile(h)}."
                expect = "The bandit moves to ${tile(h)}; nobody there to rob, so no card changes hands."
                change("Your cards", 0) { it.handSize(0) }
                change("Sentinels you've played", +1) { it.knightsPlayed(0) }
                banditFact(h)
                then(Action.PLAY_KNIGHT_CARD); then(Action.moveRobber(h, -1))
            }
        },
        scenario("sentinel.strongest-watch", "Sentinel", "third Sentinel wins the Strongest Watch", false, true, sentinel) {
            opening(false).apply {
                played(0, DevCard.KNIGHT, 2)
                dev(0, DevCard.KNIGHT)
                val h = sentinelHex(false)
                howTo = "You've played 2 Sentinels. Tap Play Sentinel and tap ${tile(h)}."
                expect = "Your third Sentinel wins the Strongest Watch: 2 more points for you."
                change("Your points", +2) { it.victoryPoints(0) }
                change("Sentinels you've played", +1) { it.knightsPlayed(0) }
                becomes("Strongest Watch is yours (1 = yes)", 1) { if (it.largestArmyOwner == 0) 1 else 0 }
                then(Action.PLAY_KNIGHT_CARD); then(Action.moveRobber(h, -1))
            }
        },
        scenario("sentinel.before-roll", "Sentinel", "played before rolling", false, true, sentinel) {
            opening(false).apply {
                s.hasRolled = false
                dev(0, DevCard.KNIGHT)
                val h = sentinelHex(false)
                howTo = "Don't roll yet. Tap Play Sentinel and tap ${tile(h)}. Judge before you roll."
                expect = "A Fortune card may be played before the roll: the bandit moves, and the Roll button is still there."
                change("Sentinels you've played", +1) { it.knightsPlayed(0) }
                banditFact(h)
                same("Dice still to roll (1 = yes)") { if (it.hasRolled) 0 else 1 }
                then(Action.PLAY_KNIGHT_CARD); then(Action.moveRobber(h, -1))
            }
        },
        scenario("sentinel.bought-this-turn", "Sentinel", "bought this turn: not yet", false, false, sentinel) {
            opening(false).apply {
                dev(0, DevCard.KNIGHT, new = true)
                howTo = "You bought this Sentinel this turn. Look for a Play Sentinel button. $NOT_OFFERED"
                expect = "A Fortune card can't be played the turn it's bought: no Play Sentinel button."
                same("Sentinels in your hand") { it.devHand[DevCard.KNIGHT] }
            }
        },
        scenario("sentinel.second-card", "Sentinel", "a Fortune card already played this turn", false, false, sentinel) {
            opening(false).apply {
                played(0, DevCard.YEAR_OF_PLENTY, 1)
                s.devPlayedThisTurn = true
                dev(0, DevCard.KNIGHT)
                howTo = "You played a Windfall this turn. Look for a Play Sentinel button. $NOT_OFFERED"
                expect = "Only one Fortune card per turn: no Play Sentinel button until your next turn."
                same("Sentinels in your hand") { it.devHand[DevCard.KNIGHT] }
            }
        },
        scenario("windfall.two-kinds", "Windfall", "two different resources", false, true, windfall) {
            opening(false).apply {
                dev(0, DevCard.YEAR_OF_PLENTY)
                howTo = "Tap Play Windfall… and pick Grain + Stone."
                expect = "You take 1 Grain and 1 Stone from the bank."
                change("Your Grain", +1) { it.hand(0, Resource.WHEAT) }
                change("Your Stone", +1) { it.hand(0, Resource.ORE) }
                change("Bank's Grain", -1) { it.bank[Resource.WHEAT] }
                then(Action.yearOfPlenty(Resource.WHEAT, Resource.ORE))
            }
        },
        scenario("windfall.same-kind", "Windfall", "two of the same", false, true, windfall) {
            opening(false).apply {
                dev(0, DevCard.YEAR_OF_PLENTY)
                howTo = "Tap Play Windfall… and pick Grain + Grain."
                expect = "You take 2 Grain from the bank."
                change("Your Grain", +2) { it.hand(0, Resource.WHEAT) }
                change("Bank's Grain", -2) { it.bank[Resource.WHEAT] }
                then(Action.yearOfPlenty(Resource.WHEAT, Resource.WHEAT))
            }
        },
        scenario("windfall.empty-pile", "Windfall", "the bank is out of one resource", false, true, windfall,
            rule = { _, legal -> legal.filter { it.type == ActionType.PLAY_YEAR_OF_PLENTY }.none { it.yopFirst == Resource.ORE || it.yopSecond == Resource.ORE } }) {
            opening(false).apply {
                drainBank(Resource.ORE)
                dev(0, DevCard.YEAR_OF_PLENTY)
                howTo = "The others hold all the Stone. Tap Play Windfall…: no choice may include Stone. Pick Timber + Grain."
                expect = "Stone isn't offered (the bank has none); you take 1 Timber and 1 Grain."
                change("Your cards", +2) { it.handSize(0) }
                same("Bank's Stone") { it.bank[Resource.ORE] }
                then(Action.yearOfPlenty(Resource.WOOD, Resource.WHEAT))
            }
        },
        scenario("windfall.one-card-left", "Windfall", "the bank holds a single card", false, true, windfall,
            rule = { _, legal -> legal.filter { it.type == ActionType.PLAY_YEAR_OF_PLENTY }.let { w -> w.isNotEmpty() && w.all { it.yopSecond < 0 && it.yopFirst == Resource.WHEAT } } }) {
            opening(false).apply {
                for (r in 0 until Resource.COUNT) drainBank(r, keep = if (r == Resource.WHEAT) 1 else 0)
                dev(0, DevCard.YEAR_OF_PLENTY)
                howTo = "The bank holds just 1 Grain. Tap Play Windfall…: the only choice should be that one Grain. Take it."
                expect = "With one card in the bank, Windfall gives just that card."
                change("Your cards", +1) { it.handSize(0) }
                change("Cards in the bank", -1) { it.bank.sum() }
                then(Action.yearOfPlenty(Resource.WHEAT))
            }
        },
        scenario("corner.takes-all", "Corner the Market", "everyone hands over all of it", false, true, corner) {
            opening(false).apply {
                give(1, Resource.WHEAT, 3); give(2, Resource.WHEAT, 2)
                dev(0, DevCard.MONOPOLY)
                howTo = "Tap Play Corner the Market… and name Grain."
                expect = "Blue gives you 3 Grain and Purple 2 (all they have); White has none."
                change("Your Grain", +5) { it.hand(0, Resource.WHEAT) }
                change("The others' Grain", -5) { s -> (1..3).sumOf { s.hand(it, Resource.WHEAT) } }
                then(Action.monopoly(Resource.WHEAT))
            }
        },
        scenario("corner.nobody-has-it", "Corner the Market", "nobody holds the one you name", false, true, corner) {
            opening(false).apply {
                for (q in 1..3) emptyCard(q, Resource.BRICK)
                dev(0, DevCard.MONOPOLY)
                howTo = "Nobody holds Clay. Tap Play Corner the Market… and name Clay."
                expect = "The card is used up and you get nothing."
                change("Your cards", 0) { it.handSize(0) }
                change("Corner the Market cards in your hand", -1) { it.devHand[DevCard.MONOPOLY] }
                then(Action.monopoly(Resource.BRICK))
            }
        },
        scenario("trailblazers.two-roads", "Trailblazers", "two free roads", false, true, trail) {
            opening(false).apply {
                dev(0, DevCard.ROAD_BUILDING)
                howTo = "Tap Play Trailblazers, then tap two glowing edges."
                expect = "Two roads go down without paying."
                change("Your roads on the board", +2) { it.piecesOnBoard(0, Piece.ROAD) }
                change("Your cards", 0) { it.handSize(0) }
                then(Action.PLAY_ROAD_BUILDING); then(firstOf(ActionType.BUILD_ROAD)); then(firstOf(ActionType.BUILD_ROAD))
            }
        },
        scenario("trailblazers.one-piece-left", "Trailblazers", "only one road piece left", false, true, trail) {
            opening(false).apply {
                roadsLeft(0, 1)
                dev(0, DevCard.ROAD_BUILDING)
                howTo = "You have 1 road piece left. Tap Play Trailblazers and place it."
                expect = "Only one free road (you have no more pieces); then your turn goes on."
                change("Your roads on the board", +1) { it.piecesOnBoard(0, Piece.ROAD) }
                becomes("Road pieces left", 0) { Rules.piecesLeft(it, 0, Piece.ROAD) }
                then(Action.PLAY_ROAD_BUILDING); then(firstOf(ActionType.BUILD_ROAD))
            }
        },
        scenario("trailblazers.no-pieces", "Trailblazers", "no road pieces left", false, false, trail) {
            opening(false).apply {
                roadsLeft(0, 0)
                dev(0, DevCard.ROAD_BUILDING)
                howTo = "All 15 of your roads are on the board. Look for a Play Trailblazers button. $NOT_OFFERED"
                expect = "No road pieces left: Trailblazers can't be played."
                same("Road pieces left") { Rules.piecesLeft(it, 0, Piece.ROAD) }
            }
        },
        scenario("landmark.hidden-point", "Landmark", "a hidden point, never played", false, false, never) {
            opening(false).apply {
                dev(0, DevCard.VICTORY_POINT)
                howTo = "You hold a Landmark. Check your points against what the others see; look for a Play button (there is none)."
                expect = "Your points count the Landmark (1 more than the others see); it's never played."
                same("Your points") { it.victoryPoints(0) }
                same("Your points as the others see them") { it.publicVictoryPoints(0) }
            }
        },
        scenario("landmark.wins", "Landmark", "the hidden point wins the game", false, false, never) {
            opening(false, vpToWin = 5).apply {
                val (a, c) = buildings(0, Piece.SETTLEMENT)
                upgrade(a)
                dev(0, DevCard.VICTORY_POINT)
                give(0, Resource.WHEAT, 2); give(0, Resource.ORE, 3)
                howTo = "The game is to 5 points. You have 4 (with the Landmark). Upgrade your homestead to a town."
                expect = "The town takes you to 5 with the hidden Landmark, and you win at once."
                change("Your points", +1) { it.victoryPoints(0) }
                becomes("You've won (1 = yes)", 1) { if (it.winner == 0) 1 else 0 }
                then(Action.buildCity(c))
            }
        },
    )
}

// ------------------------------------------------------------------ Age of Chivalry: every progress card

private fun chivalryScenarios(): List<CardScenario> {
    val P = Progress
    fun name(card: Int) = PROGRESS_NAMES[card]
    fun play(card: Int, a1: Int = 0, a2: Int = 0) = Action.playProgress(card, a1, a2)
    fun Bench.city() = buildings(0, Piece.CITY).first()
    fun Bench.homestead() = buildings(0, Piece.SETTLEMENT).first()
    fun Bench.knights(p: Int) = (0 until b.vertexCount).count { ck.knightOwner[it] == p }
    fun Bench.blocked(card: Int, how: String) {
        howTo = "${how}Look for a way to play ${name(card)}. $NOT_OFFERED"
        expect = "${name(card)} can't be played: ${progressBlocked(s, 0, card)}."
        same("${name(card)} cards in your hand") { it.ck!!.progressHand[card] }
    }
    fun knightCount(p: Int): (GameState) -> Int = { s -> s.ck!!.knightOwner.count { it == p } }
    fun strength(p: Int): (GameState) -> Int = { s -> (0 until s.board.vertexCount).sumOf { if (s.ck!!.knightOwner[it] == p) s.ck!!.knightLevel[it] else 0 } }
    fun progressCount(p: Int): (GameState) -> Int = { it.ck!!.progressCount(p) }
    val list = mutableListOf<CardScenario>()
    fun add(id: String, card: Int, mode: String, playable: Boolean = true, rule: ((GameState, List<Action>) -> Boolean)? = null,
            build: () -> Bench) { list += scenario(id, name(card), mode, true, playable, isProgress(card), rule, build) }

    // Transmuter: choose the production dice, before rolling only.
    add("transmuter.set-dice", P.ALCHEMIST, "set the dice before rolling") {
        opening(true).apply {
            s.hasRolled = false
            progress(0, P.ALCHEMIST)
            // The best number around your pieces, and what it pays you (homestead 1, town 2) by the rules.
            fun gain(n: Int) = (0 until b.hexCount).filter { s.numberAt(it) == n && it != s.robberHex }.sumOf { h ->
                (0 until 6).sumOf { i -> val v = b.hexVerts[h * 6 + i]; if (s.vertexOwner[v] != 0) 0 else if (s.vertexBuilding[v] == Piece.CITY) 2 else 1 }
            }
            val n = (2..12).filter { it != 7 }.maxBy { gain(it) }
            val red = maxOf(1, n - 6); val ivory = n - red
            howTo = "Don't roll. Tap Play ${name(P.ALCHEMIST)}…, set the red die to $red and the ivory die to $ivory."
            expect = "The roll is $n, as you set it, and your tiles showing $n pay you ${gain(n)} cards."
            becomes("Dice total", n) { it.lastRoll }
            change("Your cards", gain(n)) { it.cardCount(0) }
            then(play(P.ALCHEMIST, red, ivory))
        }
    }
    add("transmuter.after-roll", P.ALCHEMIST, "after rolling: too late", playable = false) {
        opening(true).apply { progress(0, P.ALCHEMIST); blocked(P.ALCHEMIST, "You've rolled already. ") ; expect = "${name(P.ALCHEMIST)} is only played before you roll." }
    }
    // Printing Press and Charter of Rights: laid open at once when drawn (a gate on the event die with the Transmuter).
    for ((card, track) in listOf(P.PRINTER to Track.SCIENCE, P.CONSTITUTION to Track.POLITICS)) {
        list += scenario("${if (card == P.PRINTER) "printing-press" else "charter-of-rights"}.revealed", name(card),
            "drawn: shown at once for 1 point", true, true, isProgress(P.ALCHEMIST)) {
            opening(true).apply {
                s.hasRolled = false
                progress(0, P.ALCHEMIST)
                level(0, track, 1)
                stack(card)
                rigEvent(wgbg.engine.EventDie.SHIP_FACES + track)
                howTo = "Don't roll. Tap Play ${name(P.ALCHEMIST)}…, set red 1 and ivory 1. The event die comes up ${DECK_NAMES[track]}, " +
                    "and your level-1 ${DECK_NAMES[track]} draws its top card: a ${name(card)}."
                expect = "The ${name(card)} is laid open at once (never held) and is worth 1 point."
                change("Your points", +1) { it.victoryPoints(0) }
                change("Progress cards in your hand (the ${name(P.ALCHEMIST)} goes)", -1, progressCount(0))
                change("Point cards laid open", +1) { it.ck!!.progressVp[0] }
                then(play(P.ALCHEMIST, 1, 1))
            }
        }
    }
    // Hoist: an improvement for one commodity less.
    add("hoist.one-less", P.CRANE, "improve for one commodity less") {
        opening(true).apply {
            progress(0, P.CRANE); level(0, Track.TRADE, 1); give(0, Commodity.CLOTH, 1)
            howTo = "Tap Play ${name(P.CRANE)}… and pick Guilds (it should say 1 Fabric instead of 2)."
            expect = "Guilds goes from level 1 to 2 for 1 Fabric."
            change("Guilds level", +1) { it.ck!!.level(0, Track.TRADE) }
            change("Your Fabric", -1) { it.card(0, Commodity.CLOTH) }
            then(play(P.CRANE, Track.TRADE, 0))
        }
    }
    add("hoist.first-level-free", P.CRANE, "first level costs nothing") {
        opening(true).apply {
            progress(0, P.CRANE)
            howTo = "You hold no commodities. Tap Play ${name(P.CRANE)}… and pick Academy (0 Parchment instead of 1)."
            expect = "Academy goes to level 1 for nothing."
            change("Academy level", +1) { it.ck!!.level(0, Track.SCIENCE) }
            change("Your cards", 0) { it.cardCount(0) }
            then(play(P.CRANE, Track.SCIENCE, 0))
        }
    }
    add("hoist.claims-capital", P.CRANE, "level 4 claims the Capital") {
        opening(true).apply {
            progress(0, P.CRANE); level(0, Track.SCIENCE, 3); give(0, Commodity.PAPER, 3)
            val v = city()
            howTo = "Academy is at level 3. Tap Play ${name(P.CRANE)}… and pick Academy to level 4 (3 Parchment instead of 4)."
            expect = "Academy reaches level 4 and your town becomes the Academy Capital: 2 more points."
            change("Academy level", +1) { it.ck!!.level(0, Track.SCIENCE) }
            change("Your points", +2) { it.victoryPoints(0) }
            change("Your Parchment", -3) { it.card(0, Commodity.PAPER) }
            then(play(P.CRANE, Track.SCIENCE, v + 1))
        }
    }
    add("hoist.short", P.CRANE, "not enough commodities even with it", playable = false) {
        opening(true).apply {
            progress(0, P.CRANE)
            for (t in 0 until Track.COUNT) level(0, t, 2)
            for (c in Commodity.PAPER..Commodity.COIN) give(0, c, 1)
            blocked(P.CRANE, "Every improvement is at level 2 and you hold 1 of each commodity (level 3 needs 2 with the card). ")
        }
    }
    add("hoist.no-town", P.CRANE, "no town to improve", playable = false) {
        opening(true).apply { progress(0, P.CRANE); downgrade(city()); blocked(P.CRANE, "You have no town (the Raiders took it). ") }
    }
    // Master Mason: free Ramparts.
    add("mason.ramparts", P.ENGINEER, "free Ramparts on your town") {
        opening(true).apply {
            progress(0, P.ENGINEER)
            val v = city()
            howTo = "Tap Play ${name(P.ENGINEER)}… and tap your town."
            expect = "Ramparts go on your town for free, and you may hold 2 more cards on a 7."
            change("Your Ramparts", +1) { CkPiece.WALLS - it.ck!!.wallsLeft[0] }
            change("Cards you may hold on a 7", +2) { CitiesAndKnights.discardLimit(it, 0) }
            change("Your cards", 0) { it.cardCount(0) }
            then(play(P.ENGINEER, v))
        }
    }
    add("mason.all-walled", P.ENGINEER, "every town already has Ramparts", playable = false) {
        opening(true).apply {
            progress(0, P.ENGINEER)
            ck.wall[city()] = 1; ck.wallsLeft[0]--
            blocked(P.ENGINEER, "Your only town has Ramparts already. ")
        }
    }
    // Tinkerer: swap two numbers (never 2, 12, 6 or 8).
    for (guard in listOf(false, true)) add(if (guard) "tinkerer.red-numbers-stay" else "tinkerer.swap", P.INVENTOR,
        if (guard) "2, 12, 6 and 8 can't be moved" else "swap two numbers",
        rule = if (guard) { s, legal -> legal.filter(isProgress(P.INVENTOR)).none { !Progress.inventorMovable(s.numberAt(it.b)) || !Progress.inventorMovable(s.numberAt(it.c)) } } else null) {
        opening(true).apply {
            progress(0, P.INVENTOR)
            val movable = (0 until b.hexCount).filter { Progress.inventorMovable(s.numberAt(it)) }
            val h1 = movable.first { touches(it, 0) }
            val h2 = movable.filter { s.numberAt(it) != s.numberAt(h1) }.maxBy { pips(s.numberAt(it)) }
            val (lo, hi) = minOf(h1, h2) to maxOf(h1, h2)
            val n1 = s.numberAt(h1); val n2 = s.numberAt(h2)
            howTo = (if (guard) "Tap Play ${name(P.INVENTOR)}… and try the 6, 8, 2 and 12 tiles first: none may take the tap. Then " else "Tap Play ${name(P.INVENTOR)}…, ") +
                "tap ${tile(h1)} and ${tile(h2)}."
            expect = (if (guard) "The 2, 12, 6 and 8 tiles can't be picked. " else "") + "${tile(h1).replaceFirstChar { it.uppercase() }} now shows $n2 and ${tile(h2)} shows $n1."
            becomes("Number on ${tile(h1)}", n2) { it.numberAt(h1) }
            becomes("Number on ${tile(h2)}", n1) { it.numberAt(h2) }
            then(play(P.INVENTOR, lo, hi))
        }
    }
    // Waterworks (Grain) and Deep Shafts (Stone): 2 per Grainfield / Quarry by your pieces.
    for ((card, r) in listOf(P.IRRIGATION to Resource.WHEAT, P.MINING to Resource.ORE)) {
        val key = if (card == P.IRRIGATION) "waterworks" else "deep-shafts"
        val land = TERRAIN_NAMES[r].lowercase()
        val res = RESOURCE_NAMES[r]
        fun Bench.lands() = (0 until b.hexCount).count { b.hexResource[it] == r && touches(it, 0) }
        add("$key.by-your-$land", card, "2 $res per ${TERRAIN_NAMES[r]} by your pieces") {
            opening(true, mine = near(r)).apply {
                progress(0, card)
                val k = lands()
                howTo = "Tap Play ${name(card)}."
                expect = "Your pieces touch $k $land tile(s): you take ${2 * k} $res."
                change("Your $res", 2 * k) { it.hand(0, r) }
                change("Bank's $res", -2 * k) { it.bank[r] }
                then(play(card))
            }
        }
        add("$key.short-bank", card, "the bank has less than you're owed") {
            opening(true, mine = near(r)).apply {
                progress(0, card); drainBank(r, keep = 1)
                howTo = "The bank holds only 1 $res. Tap Play ${name(card)}."
                expect = "You're owed ${2 * lands()} but take the 1 $res the bank has."
                change("Your $res", +1) { it.hand(0, r) }
                becomes("Bank's $res", 0) { it.bank[r] }
                then(play(card))
            }
        }
        add("$key.no-$land", card, "no $land by your pieces", playable = false) {
            opening(true, mine = near(r, touch = false)).apply { progress(0, card); blocked(card, "None of your pieces touch a $land. ") }
        }
        add("$key.empty-bank", card, "the bank is out of $res", playable = false) {
            opening(true, mine = near(r)).apply { progress(0, card); drainBank(r); blocked(card, "The others hold all the $res. ") }
        }
    }
    // Healer: a town for 2 Stone and 1 Grain.
    add("healer.upgrade", P.MEDICINE, "a town for 2 Stone and 1 Grain") {
        opening(true).apply {
            progress(0, P.MEDICINE); give(0, Resource.ORE, 2); give(0, Resource.WHEAT, 1)
            val v = homestead()
            howTo = "Tap Play ${name(P.MEDICINE)}… and tap your homestead."
            expect = "Your homestead becomes a town for 2 Stone and 1 Grain (not the usual 3 and 2): 1 more point."
            change("Your towns", +1) { it.piecesOnBoard(0, Piece.CITY) }
            change("Your points", +1) { it.victoryPoints(0) }
            change("Your Stone", -2) { it.hand(0, Resource.ORE) }
            change("Your Grain", -1) { it.hand(0, Resource.WHEAT) }
            then(play(P.MEDICINE, v))
        }
    }
    add("healer.short", P.MEDICINE, "can't pay 2 Stone and 1 Grain", playable = false) {
        opening(true).apply { progress(0, P.MEDICINE); give(0, Resource.ORE, 1); give(0, Resource.WHEAT, 1); blocked(P.MEDICINE, "You hold 1 Stone and 1 Grain. ") }
    }
    add("healer.no-town-piece", P.MEDICINE, "all 4 towns already built", playable = false) {
        opening(true).apply {
            progress(0, P.MEDICINE)
            repeat(3) { upgrade(newSettlement(0)) }
            newSettlement(0)
            give(0, Resource.ORE, 2); give(0, Resource.WHEAT, 1)
            blocked(P.MEDICINE, "All 4 of your towns are on the board (you hold the Stone and Grain). ")
        }
    }
    // Pathfinders: two free roads.
    add("pathfinders.two-roads", P.ROAD_BUILDING, "two free roads") {
        opening(true).apply {
            progress(0, P.ROAD_BUILDING)
            howTo = "Tap Play ${name(P.ROAD_BUILDING)}, then tap two glowing edges."
            expect = "Two roads go down without paying."
            change("Your roads on the board", +2) { it.piecesOnBoard(0, Piece.ROAD) }
            change("Your cards", 0) { it.cardCount(0) }
            then(play(P.ROAD_BUILDING)); then(firstOf(ActionType.BUILD_ROAD)); then(firstOf(ActionType.BUILD_ROAD))
        }
    }
    add("pathfinders.stop-after-one", P.ROAD_BUILDING, "stop after one road (Done)") {
        opening(true).apply {
            progress(0, P.ROAD_BUILDING)
            howTo = "Tap Play ${name(P.ROAD_BUILDING)}, place one road, then tap Done."
            expect = "You may stop after one road; the second is given up and your turn goes on."
            change("Your roads on the board", +1) { it.piecesOnBoard(0, Piece.ROAD) }
            becomes("Free roads still to place", 0) { it.freeRoads }
            then(play(P.ROAD_BUILDING)); then(firstOf(ActionType.BUILD_ROAD)); then(Action.CK_DONE)
        }
    }
    add("pathfinders.one-piece-left", P.ROAD_BUILDING, "only one road piece left") {
        opening(true).apply {
            roadsLeft(0, 1); progress(0, P.ROAD_BUILDING)
            howTo = "You have 1 road piece left. Tap Play ${name(P.ROAD_BUILDING)} and place it."
            expect = "Only one free road (no more pieces)."
            change("Your roads on the board", +1) { it.piecesOnBoard(0, Piece.ROAD) }
            becomes("Road pieces left", 0) { Rules.piecesLeft(it, 0, Piece.ROAD) }
            then(play(P.ROAD_BUILDING)); then(firstOf(ActionType.BUILD_ROAD))
        }
    }
    add("pathfinders.no-pieces", P.ROAD_BUILDING, "no road pieces left", playable = false) {
        opening(true).apply { roadsLeft(0, 0); progress(0, P.ROAD_BUILDING); blocked(P.ROAD_BUILDING, "All 15 of your roads are on the board. ") }
    }
    // Forge: up to two free promotions.
    add("forge.two", P.SMITH, "promote two knights free") {
        opening(true).apply {
            progress(0, P.SMITH)
            val a = knightSpot(0)!!; knight(0, a); val c = knightSpot(0, setOf(a))!!; knight(0, c)
            howTo = "You have two basic knights. Tap Play ${name(P.SMITH)}, then tap each knight."
            expect = "Both knights go up to strong, for nothing."
            change("Your knights' strength", +2, strength(0))
            change("Your cards", 0) { it.cardCount(0) }
            then(play(P.SMITH)); then(Action.promoteKnight(a)); then(Action.promoteKnight(c))
        }
    }
    add("forge.only-one-can", P.SMITH, "only one knight can go up") {
        opening(true).apply {
            progress(0, P.SMITH)
            val a = knightSpot(0)!!; knight(0, a); val c = knightSpot(0, setOf(a))!!; knight(0, c, level = 2)
            howTo = "One basic and one strong knight, and no Fortress (Crown level 3). Tap Play ${name(P.SMITH)}, then tap the basic knight."
            expect = "The basic knight goes up to strong; the strong one can't become mighty without the Fortress, so the second promotion is dropped."
            change("Your knights' strength", +1, strength(0))
            becomes("Free promotions still to use", 0) { it.ck!!.freePromotions }
            then(play(P.SMITH)); then(Action.promoteKnight(a))
        }
    }
    add("forge.mighty-with-fortress", P.SMITH, "with the Fortress, strong knights become mighty") {
        opening(true).apply {
            progress(0, P.SMITH); level(0, Track.POLITICS, 3)
            val a = knightSpot(0)!!; knight(0, a, level = 2); val c = knightSpot(0, setOf(a))!!; knight(0, c, level = 2)
            howTo = "Crown is at level 3 (the Fortress). Tap Play ${name(P.SMITH)}, then tap each strong knight."
            expect = "Both strong knights become mighty."
            change("Your knights' strength", +2, strength(0))
            then(play(P.SMITH)); then(Action.promoteKnight(a)); then(Action.promoteKnight(c))
        }
    }
    add("forge.nothing-to-promote", P.SMITH, "no knight to promote", playable = false) {
        opening(true).apply { progress(0, P.SMITH); blocked(P.SMITH, "You have no knights. ") }
    }
    // Harbour Charter: a resource to each opponent, a commodity back from each.
    add("charter.everyone", P.COMMERCIAL_HARBOR, "swap with every opponent") {
        opening(true).apply {
            progress(0, P.COMMERCIAL_HARBOR)
            give(0, Resource.WHEAT, 3)
            for (q in 1..3) give(q, Commodity.PAPER + q - 1, 1)
            howTo = "Tap Play ${name(P.COMMERCIAL_HARBOR)}…, offer Grain to Blue, Purple and White in turn, then tap Done."
            expect = "Each of them takes a Grain and gives you a commodity back: 3 resources out, 3 commodities in."
            change("Your resources", -3) { it.handSize(0) }
            change("Your commodities", +3) { it.ck!!.commodityCount(0) }
            change("Your cards", 0) { it.cardCount(0) }
            then(play(P.COMMERCIAL_HARBOR))
            for (q in 1..3) then(Action.commercialHarborOffer(Resource.WHEAT, q))
            then(Action.CK_DONE)
        }
    }
    add("charter.skips-empty", P.COMMERCIAL_HARBOR, "an opponent with no commodity is skipped",
        rule = null) {
        opening(true).apply {
            progress(0, P.COMMERCIAL_HARBOR)
            give(0, Resource.WHEAT, 3)
            give(1, Commodity.PAPER, 1); give(2, Commodity.CLOTH, 1)
            howTo = "White holds no commodity. Tap Play ${name(P.COMMERCIAL_HARBOR)}…, offer Grain to Blue and Purple (White mustn't be offered), then Done."
            expect = "Two swaps; White can't be picked."
            change("Your resources", -2) { it.handSize(0) }
            change("Your commodities", +2) { it.ck!!.commodityCount(0) }
            then(play(P.COMMERCIAL_HARBOR))
            then(Action.commercialHarborOffer(Resource.WHEAT, 1))
            then { s -> require(Rules.legalActions(s).none { it.type == CkActionType.COMMERCIAL_HARBOR_OFFER && it.b == 3 }) { "White was offered" }
                Action.commercialHarborOffer(Resource.WHEAT, 2) }
            then(Action.CK_DONE)
        }
    }
    add("charter.no-commodities", P.COMMERCIAL_HARBOR, "nobody else has a commodity", playable = false) {
        opening(true).apply { progress(0, P.COMMERCIAL_HARBOR); give(0, Resource.WHEAT, 2); blocked(P.COMMERCIAL_HARBOR, "No opponent holds a commodity. ") }
    }
    add("charter.empty-hand", P.COMMERCIAL_HARBOR, "you have no resource to give", playable = false) {
        opening(true).apply {
            progress(0, P.COMMERCIAL_HARBOR); give(0, Commodity.COIN, 2); for (q in 1..3) give(q, Commodity.PAPER, 1)
            blocked(P.COMMERCIAL_HARBOR, "You hold only commodities, no resources. ")
        }
    }
    // Grand Factor: take 2 cards from a player with more points.
    add("factor.take-two", P.MASTER_MERCHANT, "take 2 cards from a leader") {
        opening(true).apply {
            progress(0, P.MASTER_MERCHANT); upgrade(buildings(1, Piece.SETTLEMENT).first())
            howTo = "Blue leads with 4 points. Tap Play ${name(P.MASTER_MERCHANT)}…, pick Blue, then take 2 of Blue's cards."
            expect = "You see Blue's hand and take the 2 cards you choose."
            change("Your cards", +2) { it.cardCount(0) }
            change("Blue's cards", -2) { it.cardCount(1) }
            then(play(P.MASTER_MERCHANT, 1)); then(firstOf(CkActionType.CHOOSE_CARD)); then(firstOf(CkActionType.CHOOSE_CARD))
        }
    }
    add("factor.one-card", P.MASTER_MERCHANT, "the leader holds only 1 card") {
        opening(true).apply {
            progress(0, P.MASTER_MERCHANT); upgrade(buildings(1, Piece.SETTLEMENT).first())
            emptyCard(1, Resource.WOOD); emptyCard(1, Resource.BRICK)
            howTo = "Blue leads but holds 1 card. Tap Play ${name(P.MASTER_MERCHANT)}…, pick Blue, take the card."
            expect = "You take Blue's only card, and the card is done."
            change("Your cards", +1) { it.cardCount(0) }
            becomes("Blue's cards", 0) { it.cardCount(1) }
            then(play(P.MASTER_MERCHANT, 1)); then(firstOf(CkActionType.CHOOSE_CARD))
        }
    }
    add("factor.nobody-ahead", P.MASTER_MERCHANT, "nobody has more points", playable = false) {
        opening(true).apply { progress(0, P.MASTER_MERCHANT); blocked(P.MASTER_MERCHANT, "Everyone has 3 points, as you do. ") }
    }
    // Peddler: the merchant by your pieces: 1 point and 2:1 for its resource.
    add("peddler.place", P.MERCHANT, "place the merchant: 1 point and 2:1") {
        opening(true).apply {
            progress(0, P.MERCHANT)
            val h = (0 until b.hexCount).first { b.hexResource[it] >= 0 && touches(it, 0) && CitiesAndKnights.tradeRate(s, 0, b.hexResource[it]) > 2 }
            val r = b.hexResource[h]
            howTo = "Tap Play ${name(P.MERCHANT)}… and tap ${tile(h)}."
            expect = "The merchant stands on ${tile(h)}: 1 point for you, and you trade ${RESOURCE_NAMES[r]} with the bank 2 for 1."
            change("Your points", +1) { it.victoryPoints(0) }
            becomes("${RESOURCE_NAMES[r]} you give the bank for 1 card", 2) { CitiesAndKnights.tradeRate(it, 0, r) }
            then(play(P.MERCHANT, h))
        }
    }
    add("peddler.take-it-over", P.MERCHANT, "take the merchant from another player") {
        opening(true).apply {
            progress(0, P.MERCHANT)
            val hb = (0 until b.hexCount).first { b.hexResource[it] >= 0 && touches(it, 1) && !touches(it, 0) }
            ck.merchantHex = hb; ck.merchantOwner = 1
            val h = (0 until b.hexCount).first { it != hb && b.hexResource[it] >= 0 && touches(it, 0) }
            howTo = "Blue's merchant stands on ${tile(hb)}. Tap Play ${name(P.MERCHANT)}… and tap ${tile(h)}."
            expect = "The merchant comes to you: you gain its point and Blue loses it."
            change("Your points", +1) { it.victoryPoints(0) }
            change("Blue's points", -1) { it.victoryPoints(1) }
            then(play(P.MERCHANT, h))
        }
    }
    // Swift Hulls: one card type 2:1 for the rest of the turn.
    add("hulls.resource", P.MERCHANT_FLEET, "a resource 2 for 1") {
        opening(true).apply {
            progress(0, P.MERCHANT_FLEET)
            val r = (0 until Resource.COUNT).first { CitiesAndKnights.tradeRate(s, 0, it) > 2 && it != Resource.WHEAT }
            give(0, r, 2)
            howTo = "Tap Play ${name(P.MERCHANT_FLEET)}… and pick ${RESOURCE_NAMES[r]}; then trade 2 ${RESOURCE_NAMES[r]} with the bank for 1 Grain."
            expect = "${RESOURCE_NAMES[r]} goes 2 for 1 this turn: 2 ${RESOURCE_NAMES[r]} buy 1 Grain."
            change("Your ${RESOURCE_NAMES[r]}", -2) { it.hand(0, r) }
            change("Your Grain", +1) { it.hand(0, Resource.WHEAT) }
            then(play(P.MERCHANT_FLEET, r)); then(Action.maritimeTrade(r, Resource.WHEAT))
        }
    }
    add("hulls.commodity", P.MERCHANT_FLEET, "a commodity 2 for 1") {
        opening(true).apply {
            progress(0, P.MERCHANT_FLEET); give(0, Commodity.CLOTH, 2)
            howTo = "Tap Play ${name(P.MERCHANT_FLEET)}… and pick Fabric; then trade 2 Fabric with the bank for 1 Stone."
            expect = "Fabric goes 2 for 1 this turn (it's 4 for 1 without the card)."
            change("Your Fabric", -2) { it.card(0, Commodity.CLOTH) }
            change("Your Stone", +1) { it.hand(0, Resource.ORE) }
            then(play(P.MERCHANT_FLEET, Commodity.CLOTH)); then(Action.maritimeTrade(Commodity.CLOTH, Resource.ORE))
        }
    }
    // Corner the Field: up to 2 of a resource from each; Corner the Wares: 1 commodity from each.
    add("field.up-to-two", P.RESOURCE_MONOPOLY, "up to 2 from each player") {
        opening(true).apply {
            progress(0, P.RESOURCE_MONOPOLY); give(1, Resource.WHEAT, 3); give(2, Resource.WHEAT, 1)
            howTo = "Blue holds 3 Grain, Purple 1, White none. Tap Play ${name(P.RESOURCE_MONOPOLY)}… and name Grain."
            expect = "Blue gives 2 (no more than 2), Purple its 1: 3 Grain for you."
            change("Your Grain", +3) { it.hand(0, Resource.WHEAT) }
            change("Blue's Grain", -2) { it.hand(1, Resource.WHEAT) }
            change("Purple's Grain", -1) { it.hand(2, Resource.WHEAT) }
            then(play(P.RESOURCE_MONOPOLY, Resource.WHEAT))
        }
    }
    add("field.nobody-has-it", P.RESOURCE_MONOPOLY, "nobody holds the one you name") {
        opening(true).apply {
            progress(0, P.RESOURCE_MONOPOLY); for (q in 1..3) emptyCard(q, Resource.BRICK)
            howTo = "Nobody holds Clay. Tap Play ${name(P.RESOURCE_MONOPOLY)}… and name Clay."
            expect = "The card is used and you get nothing."
            change("Your cards", 0) { it.cardCount(0) }
            change("Progress cards in your hand", -1, progressCount(0))
            then(play(P.RESOURCE_MONOPOLY, Resource.BRICK))
        }
    }
    add("wares.one-each", P.TRADE_MONOPOLY, "1 from each player") {
        opening(true).apply {
            progress(0, P.TRADE_MONOPOLY); give(1, Commodity.COIN, 2); give(2, Commodity.COIN, 1)
            howTo = "Blue holds 2 Silver, Purple 1, White none. Tap Play ${name(P.TRADE_MONOPOLY)}… and name Silver."
            expect = "Blue and Purple give you 1 Silver each (never more than 1)."
            change("Your Silver", +2) { it.card(0, Commodity.COIN) }
            change("Blue's Silver", -1) { it.card(1, Commodity.COIN) }
            change("Purple's Silver", -1) { it.card(2, Commodity.COIN) }
            then(play(P.TRADE_MONOPOLY, Commodity.COIN))
        }
    }
    add("wares.nobody-has-it", P.TRADE_MONOPOLY, "nobody holds the one you name") {
        opening(true).apply {
            progress(0, P.TRADE_MONOPOLY)
            howTo = "Nobody holds Fabric. Tap Play ${name(P.TRADE_MONOPOLY)}… and name Fabric."
            expect = "The card is used and you get nothing."
            change("Your cards", 0) { it.cardCount(0) }
            change("Progress cards in your hand", -1, progressCount(0))
            then(play(P.TRADE_MONOPOLY, Commodity.CLOTH))
        }
    }
    // Abbot: the bandit moves and robs one card from each player there.
    add("abbot.rob-each", P.BISHOP, "rob everyone on the tile") {
        opening(true).apply {
            ck.barbarianAttacks = 1
            progress(0, P.BISHOP)
            val h = (0 until b.hexCount).filter { it != s.robberHex }.maxBy { Integer.bitCount(victims(it)) }
            val n = Integer.bitCount(victims(h))
            val who = (1..3).filter { victims(h) and (1 shl it) != 0 }
            howTo = "The Raiders have landed once, so the bandit is loose. Tap Play ${name(P.BISHOP)}… and tap ${tile(h)}."
            expect = "The bandit moves to ${tile(h)} and you take 1 random card from each of ${who.joinToString(" and ") { Who(it) }}."
            change("Your cards", +n) { it.cardCount(0) }
            change("The others' cards", -n) { s -> (1..3).sumOf { s.cardCount(it) } }
            becomes("Bandit on ${tile(h)} (1 = yes)", 1) { if (it.robberHex == h) 1 else 0 }
            then(play(P.BISHOP, h))
        }
    }
    add("abbot.empty-tile", P.BISHOP, "a tile nobody else builds on") {
        opening(true).apply {
            ck.barbarianAttacks = 1
            progress(0, P.BISHOP)
            val h = (0 until b.hexCount).filter { it != s.robberHex }.let { hs -> hs.firstOrNull { !touchesAny(it) } ?: hs.first { victims(it) == 0 } }
            howTo = "Tap Play ${name(P.BISHOP)}… and tap ${tile(h)}."
            expect = "The bandit moves to ${tile(h)}; nobody to rob."
            change("Your cards", 0) { it.cardCount(0) }
            becomes("Bandit on ${tile(h)} (1 = yes)", 1) { if (it.robberHex == h) 1 else 0 }
            then(play(P.BISHOP, h))
        }
    }
    add("abbot.before-raiders", P.BISHOP, "before the Raiders first land", playable = false) {
        opening(true).apply { progress(0, P.BISHOP); blocked(P.BISHOP, "The Raiders haven't landed yet. ") }
    }
    // Turncoat: an opponent gives up a knight; you place one of the same strength.
    fun Bench.blueKnight(level: Int = 1): Int { val v = knightSpot(1)!!; knight(1, v, level); return v }
    add("turncoat.swap", P.DESERTER, "their knight leaves, yours comes on") {
        opening(true).apply {
            progress(0, P.DESERTER); blueKnight()
            howTo = "Blue has one basic knight. Tap Play ${name(P.DESERTER)}… and pick Blue; Blue removes it, then tap a glowing corner for yours."
            expect = "Blue loses a basic knight and you place a basic knight of your own."
            change("Your knights", +1, knightCount(0))
            change("Blue's knights", -1, knightCount(1))
            then(play(P.DESERTER, 1)); then(firstOf(CkActionType.CHOOSE_VERTEX))
        }
    }
    add("turncoat.no-spot", P.DESERTER, "you have nowhere to place it") {
        opening(true).apply {
            progress(0, P.DESERTER); blueKnight()
            while (true) { val v = knightSpot(0) ?: break; knight(0, v, level = 2) }
            howTo = "Your strong knights fill every free corner on your roads. Tap Play ${name(P.DESERTER)}… and pick Blue."
            expect = "Blue loses its knight, but you have nowhere to put one, so it's simply gone."
            change("Your knights", 0, knightCount(0))
            change("Blue's knights", -1, knightCount(1))
            then(play(P.DESERTER, 1))
        }
    }
    add("turncoat.none-left", P.DESERTER, "you have no knight of that strength left") {
        opening(true).apply {
            progress(0, P.DESERTER); blueKnight()
            val a = knightSpot(0)!!; knight(0, a); val c = knightSpot(0, setOf(a))!!; knight(0, c)
            if (knightSpot(0) == null) extend(0)
            howTo = "Both your basic knights are on the board. Tap Play ${name(P.DESERTER)}… and pick Blue."
            expect = "Blue loses its basic knight; you have no basic knight left to place, so you get none."
            change("Your knights", 0, knightCount(0))
            change("Blue's knights", -1, knightCount(1))
            then(play(P.DESERTER, 1))
        }
    }
    add("turncoat.nobody-has-knights", P.DESERTER, "nobody else has a knight", playable = false) {
        opening(true).apply { progress(0, P.DESERTER); blocked(P.DESERTER, "No opponent has a knight. ") }
    }
    // Envoy: remove an open road; your own may go straight back down.
    add("envoy.rival-road", P.DIPLOMAT, "remove another player's open road") {
        opening(true).apply {
            progress(0, P.DIPLOMAT)
            val e = (0 until b.edgeCount).first { s.edgeOwner[it] == 1 && CitiesAndKnights.isOpenRoad(s, it) }
            howTo = "Tap Play ${name(P.DIPLOMAT)}… and tap Blue's road (the open end of it)."
            expect = "Blue's road goes back to Blue's supply."
            change("Blue's roads on the board", -1) { it.piecesOnBoard(1, Piece.ROAD) }
            then(play(P.DIPLOMAT, e))
        }
    }
    add("envoy.own-road", P.DIPLOMAT, "move one of your own roads") {
        opening(true).apply {
            progress(0, P.DIPLOMAT)
            val e = (0 until b.edgeCount).first { s.edgeOwner[it] == 0 && CitiesAndKnights.isOpenRoad(s, it) }
            howTo = "Tap Play ${name(P.DIPLOMAT)}…, tap one of your own roads, then place it again on a glowing edge."
            expect = "Your road comes up and goes straight back down for free: the same number of roads."
            change("Your roads on the board", 0) { it.piecesOnBoard(0, Piece.ROAD) }
            becomes("Free roads still to place", 0) { it.freeRoads }
            change("Progress cards in your hand", -1, progressCount(0))
            then(play(P.DIPLOMAT, e)); then(firstOf(ActionType.BUILD_ROAD))
        }
    }
    // Plot: knock an enemy knight off your road.
    fun Bench.plotKnight(retreat: Boolean): Int {
        val v = (0 until b.vertexCount).first { s.vertexOwner[it] < 0 && Rules.hasRoadAt(s, 0, it) }
        knight(1, v)
        if (retreat) {
            val e = (0 until 3).map { b.vertexEdge[v * 3 + it] }.first { e -> e >= 0 && s.edgeOwner[e] < 0 && b.otherEnd(e, v).let { w -> s.vertexOwner[w] < 0 && ck.knightOwner[w] < 0 } }
            road(1, e)
        }
        return v
    }
    add("plot.relocates", P.INTRIGUE, "the knight retreats along its road") {
        opening(true).apply {
            progress(0, P.INTRIGUE)
            val v = plotKnight(retreat = true)
            howTo = "Blue's knight stands on the end of your road. Tap Play ${name(P.INTRIGUE)}… and tap that knight."
            expect = "The knight leaves your road; Blue sets it down further along Blue's own road."
            becomes("Blue knight on your road end (1 = yes)", 0) { if (it.ck!!.knightOwner[v] == 1) 1 else 0 }
            change("Blue's knights", 0, knightCount(1))
            then(play(P.INTRIGUE, v))
        }
    }
    add("plot.back-to-supply", P.INTRIGUE, "nowhere to retreat: back to supply") {
        opening(true).apply {
            progress(0, P.INTRIGUE)
            val v = plotKnight(retreat = false)
            howTo = "Blue's knight stands on the end of your road with no Blue road to retreat along. Tap Play ${name(P.INTRIGUE)}… and tap it."
            expect = "The knight has nowhere to go, so it goes back to Blue's supply."
            becomes("Blue knight on your road end (1 = yes)", 0) { if (it.ck!!.knightOwner[v] == 1) 1 else 0 }
            change("Blue's knights", -1, knightCount(1))
            then(play(P.INTRIGUE, v))
        }
    }
    add("plot.no-target", P.INTRIGUE, "no enemy knight on your roads", playable = false) {
        opening(true).apply { progress(0, P.INTRIGUE); blocked(P.INTRIGUE, "No enemy knight stands on your roads. ") }
    }
    // Wrecker: everyone with at least your points discards half.
    add("wrecker.halves", P.SABOTEUR, "the others discard half") {
        opening(true).apply {
            progress(0, P.SABOTEUR)
            give(1, Resource.WHEAT, 1); give(2, Resource.WHEAT, 2); emptyCard(3, Resource.WOOD); emptyCard(3, Resource.BRICK)
            howTo = "Everyone has 3 points, like you. Blue holds 4 cards, Purple 5, White 1. Tap Play ${name(P.SABOTEUR)}."
            expect = "Blue and Purple each discard 2 (half, rounded down); White keeps its 1."
            change("Blue's cards", -2) { it.cardCount(1) }
            change("Purple's cards", -2) { it.cardCount(2) }
            change("White's cards", 0) { it.cardCount(3) }
            change("Your cards", 0) { it.cardCount(0) }
            then(play(P.SABOTEUR))
        }
    }
    add("wrecker.nobody", P.SABOTEUR, "you lead: it hits nobody", playable = false) {
        opening(true).apply { progress(0, P.SABOTEUR); upgrade(homestead()); blocked(P.SABOTEUR, "You lead with 5 points. ") }
    }
    // Informer: take a progress card from another player.
    add("informer.take", P.SPY, "take one of their progress cards") {
        opening(true).apply {
            progress(0, P.SPY); progress(1, P.WARLORD); progress(1, P.MERCHANT_FLEET)
            howTo = "Blue holds 2 progress cards. Tap Play ${name(P.SPY)}…, pick Blue, and take one of them."
            expect = "You see Blue's progress cards and take the one you choose."
            change("Progress cards in your hand (the ${name(P.SPY)} goes, one comes)", 0, progressCount(0))
            change("Blue's progress cards", -1, progressCount(1))
            then(play(P.SPY, 1)); then(firstOf(CkActionType.TAKE_PROGRESS))
        }
    }
    add("informer.nobody", P.SPY, "nobody else holds a progress card", playable = false) {
        opening(true).apply { progress(0, P.SPY); blocked(P.SPY, "") }
    }
    // Marshal: supply all your knights free.
    add("marshal.all", P.WARLORD, "supply all your knights") {
        opening(true).apply {
            progress(0, P.WARLORD)
            val a = knightSpot(0)!!; knight(0, a); val c = knightSpot(0, setOf(a))!!; knight(0, c)
            howTo = "Your two knights stand unsupplied. Tap Play ${name(P.WARLORD)}."
            expect = "Both knights are supplied (raised) for nothing."
            change("Your raised knights", +2) { s -> (0 until s.board.vertexCount).count { s.ck!!.knightOwner[it] == 0 && s.ck!!.isActive(it) } }
            change("Your cards", 0) { it.cardCount(0) }
            then(play(P.WARLORD))
        }
    }
    add("marshal.already-raised", P.WARLORD, "every knight already supplied", playable = false) {
        opening(true).apply {
            progress(0, P.WARLORD)
            val a = knightSpot(0)!!; knight(0, a, active = true)
            blocked(P.WARLORD, "Your only knight is already supplied. ")
        }
    }
    add("marshal.no-knights", P.WARLORD, "no knights", playable = false) {
        opening(true).apply { progress(0, P.WARLORD); blocked(P.WARLORD, "") }
    }
    // Feast: every player with more points gives you 2 cards of their choice.
    add("feast.two-each", P.WEDDING, "the leaders give 2 cards each") {
        opening(true).apply {
            progress(0, P.WEDDING)
            upgrade(buildings(1, Piece.SETTLEMENT).first()); upgrade(buildings(2, Piece.SETTLEMENT).first())
            howTo = "Blue and Purple have 4 points, you 3. Tap Play ${name(P.WEDDING)}."
            expect = "Blue and Purple each give you 2 cards of their choice; White (3 points) gives nothing."
            change("Your cards", +4) { it.cardCount(0) }
            change("Blue's cards", -2) { it.cardCount(1) }
            change("Purple's cards", -2) { it.cardCount(2) }
            change("White's cards", 0) { it.cardCount(3) }
            then(play(P.WEDDING))
        }
    }
    add("feast.one-card", P.WEDDING, "a leader holds only 1 card") {
        opening(true).apply {
            progress(0, P.WEDDING); upgrade(buildings(1, Piece.SETTLEMENT).first())
            emptyCard(1, Resource.WOOD); emptyCard(1, Resource.BRICK)
            howTo = "Blue has 4 points but 1 card. Tap Play ${name(P.WEDDING)}."
            expect = "Blue gives you its only card."
            change("Your cards", +1) { it.cardCount(0) }
            becomes("Blue's cards", 0) { it.cardCount(1) }
            then(play(P.WEDDING))
        }
    }
    add("feast.nobody-ahead", P.WEDDING, "nobody has more points", playable = false) {
        opening(true).apply { progress(0, P.WEDDING); blocked(P.WEDDING, "Everyone has 3 points, as you do. ") }
    }
    return list
}

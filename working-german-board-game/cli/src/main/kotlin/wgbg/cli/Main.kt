package wgbg.cli

import wgbg.ai.GreedyBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.CARD_TYPES
import wgbg.engine.DevCard
import wgbg.engine.DiceDeck
import wgbg.engine.DiceStats
import wgbg.engine.GameConfig
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Piece
import wgbg.engine.Port
import wgbg.engine.Resource
import wgbg.engine.Rules
import wgbg.engine.TrueRandomDice
import java.io.File
import java.security.SecureRandom
import kotlin.system.exitProcess

/*
 * Play-by-file command-line game (issue #8). Several players (people, Claude sessions, or
 * built-in bots) share one game folder; each runs `state` to see their own view and `act`
 * to move. The file holds only config, seed and the action log, so anyone can replay and
 * verify the whole game; the engine rejects any illegal move; dice are truly random
 * (OS secure generator) and recorded in the log.
 *
 *   wgbg new   <dir> --seats Claude,Alice,bot [--vp 10] [--dice random|36|72|108|144]
 *   wgbg state <dir> --seat N        your view + numbered legal moves when it's your turn
 *   wgbg act   <dir> --seat N <move#>
 *   wgbg wait  <dir> --seat N [--timeout 600]   blocks until it's your turn (or game over)
 *   wgbg offer <dir> --seat N --give "2 Timber" --want "1 Stone"   any offer on your turn
 *   wgbg counter <dir> --seat N --give "1 Grain" --want "1 Clay"   answer an open offer with other terms
 *   wgbg log   <dir>                 replays and verifies the whole game
 *   wgbg dice  <dir> [--sample N]    histogram of the game's rolls (and N fresh true-random rolls)
 */

/** Card names by engine card index (resources, then Age of Chivalry commodities): never index past these. */
private val RES = listOf("Timber", "Clay", "Fleece", "Grain", "Stone", "Parchment", "Fabric", "Silver")
private val FORTUNE = listOf("Sentinel", "Windfall", "Corner the Market", "Trailblazers", "Landmark")

internal class Record(val dir: File, val seats: List<String>, val seed: Long, val vp: Int, val actions: MutableList<Action>, /** 0 = true-random dice, else 1-4 stacked 36-card decks. */ val dice: Int = 0) {
    val config get() = GameConfig(numPlayers = seats.size, vpToWin = vp, generateTradeOffers = true, maxTurns = 400)

    fun replay(): GameState {
        val s = Rules.newGame(config, seed)
        for (a in actions) Rules.applyChecked(s, a) // the whole history is re-verified every time
        return s
    }

    fun save() {
        val tmp = File(dir, "game.txt.tmp")
        tmp.writeText(buildString {
            appendLine("# working-german-board-game play-by-file record; do not edit by hand")
            appendLine("seats=${seats.joinToString(",")}")
            appendLine("seed=$seed")
            appendLine("vp=$vp")
            appendLine("dice=" + if (dice == 0) "random" else "deck:$dice")
            for (a in actions) appendLine(a.code)
        })
        val f = File(dir, "game.txt")
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        fun load(dir: File): Record {
            val lines = File(dir, "game.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            val kv = lines.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val acts = lines.filter { '=' !in it }.map { Action(it.trim().toLong()) }.toMutableList()
            val dice = kv["dice"]?.removePrefix("deck:")?.toIntOrNull() ?: 0
            return Record(dir, kv.getValue("seats").split(","), kv.getValue("seed").toLong(), kv.getValue("vp").toInt(), acts, dice)
        }
    }
}

/** Serializes writers across processes with an exclusive lock file. */
private fun <T> locked(dir: File, block: () -> T): T {
    val lock = File(dir, "game.lock")
    val t0 = System.currentTimeMillis()
    while (!lock.createNewFile()) {
        if (System.currentTimeMillis() - t0 > 15_000) lock.delete() // stale lock from a crashed process
        Thread.sleep(50)
    }
    try { return block() } finally { lock.delete() }
}

private val trueDice = TrueRandomDice()

/** The rolls so far as deck cards (for deck dice). */
private fun Record.drawnCards() = actions.filter { it.type == ActionType.ROLL && it.payload != 0L }.map { DiceDeck.cardOf(it.a, it.b) }

/** The next roll: true-random dice, or the next card of this game's dice deck, dealt from the log. */
internal fun Record.roll(): Action = if (dice == 0) trueDice.next().toAction() else DiceDeck.nextFromLog(drawnCards(), dice).toAction()

private fun Record.diceText() = if (dice == 0) "true random" else
    "${36 * dice}-card deck, ${DiceDeck.leftInStack(drawnCards(), dice).sum() - 5} cards before the reshuffle"

/**
 * Lets built-in bots take every turn that is theirs, so people/agents only wait on each other, and declines for anyone
 * an offer they can't pay (#26: Decline was all they could do with it, and the game sat waiting on them).
 */
internal fun runBots(r: Record, s: GameState) {
    val buf = ActionBuffer()
    // One bot per seat for the whole run: a bot keeps per-turn memory (like its two-offers-a-turn
    // limit), and a fresh bot for every move forgot it and could offer forever.
    val bots = HashMap<Int, GreedyBot>()
    var guard = 0
    while (!s.isOver && (r.seats[s.actingPlayer].equals("bot", ignoreCase = true) || Rules.cantPayOffer(s, s.actingPlayer)) && guard++ < 50_000) {
        Rules.legalActions(s, buf)
        val p = s.actingPlayer
        var a = if (Rules.cantPayOffer(s, p)) Action.REJECT_TRADE else bots.getOrPut(p) { GreedyBot(r.seed * 31 + p + r.actions.size) }.choose(s, buf)
        if (a.type == ActionType.ROLL) a = r.roll()
        Rules.applyChecked(s, a)
        r.actions += a
    }
}

// ------------------------------------------------------------------ text rendering

private fun hexText(s: GameState, h: Int): String {
    val r = s.board.hexResource[h]
    val name = if (r == Resource.NONE) "Desert" else "${RES[r]} ${s.numberAt(h)}"
    return "H$h $name" + if (h == s.robberHex) " (bandit)" else ""
}

private fun vertexText(s: GameState, v: Int): String {
    val hexes = s.board.topology.vertexHexes[v].joinToString(", ") { h ->
        val r = s.board.hexResource[h]
        if (r == Resource.NONE) "Desert" else "${RES[r]} ${s.numberAt(h)}" + if (h == s.robberHex) "*" else ""
    }
    val port = s.board.ports.firstOrNull { it.v1 == v || it.v2 == v }?.let { if (it.type == Port.GENERIC) " +3:1 harbor" else " +2:1 ${RES[it.type]} harbor" } ?: ""
    return "v$v [$hexes]$port"
}

private fun bankRate(s: GameState, p: Int, r: Int): Int {
    val mask = s.portMask[p]
    return when {
        mask and (1 shl r) != 0 -> 2
        mask and Port.GENERIC_BIT != 0 -> 3
        else -> 4
    }
}

private fun cards(counts: (Int) -> Int) = (0 until CARD_TYPES).filter { counts(it) > 0 }.joinToString(", ") { "${counts(it)} ${RES[it]}" }.ifEmpty { "nothing" }

private fun describe(s: GameState, a: Action, names: List<String>): String {
    val p = s.actingPlayer
    return when (a.type) {
        ActionType.ROLL -> "Roll the dice"
        ActionType.END_TURN -> "End your turn"
        ActionType.BUILD_SETTLEMENT -> "Build a homestead at ${vertexText(s, a.payload.toInt())}"
        ActionType.BUILD_CITY -> "Upgrade to a town at ${vertexText(s, a.payload.toInt())}"
        ActionType.BUILD_ROAD -> s.board.topology.edgeVertices[a.payload.toInt()].let { "Build a road e${a.payload} between v${it[0]} and v${it[1]}" }
        ActionType.BUY_DEVELOPMENT_CARD -> "Buy a Fortune card (1 Fleece, 1 Grain, 1 Stone)"
        ActionType.PLAY_KNIGHT_CARD -> "Play Sentinel (move the bandit)"
        ActionType.PLAY_ROAD_BUILDING -> "Play Trailblazers (2 free roads)"
        ActionType.PLAY_YEAR_OF_PLENTY -> "Play Windfall: take ${RES[a.yopFirst]}" + if (a.yopSecond >= 0) " and ${RES[a.yopSecond]}" else ""
        ActionType.PLAY_MONOPOLY -> "Play Corner the Market on ${RES[a.payload.toInt()]}"
        ActionType.MARITIME_TRADE -> "Trade with the bank: give ${bankRate(s, p, a.maritimeGive)} ${RES[a.maritimeGive]} for 1 ${RES[a.maritimeGet]}"
        ActionType.MOVE_ROBBER -> "Move the bandit to ${hexText(s, a.robberHex)}" + if (a.robberVictim >= 0) " and take a card from ${names[a.robberVictim]}" else ""
        ActionType.DISCARD -> "Discard 1 ${RES[a.discardResource]}"
        ActionType.OFFER_TRADE -> "Offer the players: you give ${cards { a.offerGive(it) }}, you get ${cards { a.offerWant(it) }}"
        ActionType.ACCEPT_TRADE -> "Accept ${names[s.currentPlayer]}'s offer"
        ActionType.REJECT_TRADE -> "Decline ${names[s.currentPlayer]}'s offer" +
            if (Action.ACCEPT_TRADE !in Rules.legalActions(s)) " (it gives or asks only, so it can't be taken as it stands; to counter: counter --seat $p --give ... --want ...)" else ""
        ActionType.COUNTER_TRADE -> "Counter-offer to ${names[s.currentPlayer]}: you give ${cards { a.offerWant(it) }}, you get ${cards { a.offerGive(it) }}"
        ActionType.CONFIRM_TRADE -> s.counterOf(a.payload.toInt())?.let { c ->
            "Take ${names[a.payload.toInt()]}'s counter-offer: you give ${cards { c.offerGive(it) }}, you get ${cards { c.offerWant(it) }}"
        } ?: "Do the trade with ${names[a.payload.toInt()]}"
        ActionType.CANCEL_TRADE -> "Cancel your offer"
        else -> a.toString()
    }
}

private fun lastRollText(r: Record): String {
    val roll = r.actions.lastOrNull { it.type == ActionType.ROLL } ?: return "no roll yet"
    return if (roll.payload != 0L) "${roll.a} + ${roll.b} = ${roll.a + roll.b}" else "rolled"
}

private fun render(r: Record, s: GameState, seat: Int?): String = buildString {
    val names = r.seats
    appendLine("=== ${r.dir.name}, turn ${s.turn}, phase ${s.phase} ===")
    if (s.isOver) appendLine(if (s.winner >= 0) ">>> ${names[s.winner]} WINS <<<" else ">>> game ended without a winner (turn limit) <<<")
    else appendLine("Current player: ${names[s.currentPlayer]}, waiting on: ${names[s.actingPlayer]}")
    appendLine("Last roll: ${lastRollText(r)}")
    appendLine("Dice: ${r.diceText()}")
    if (s.phase == Phase.DECIDE_TRADE || s.phase == Phase.DECIDE_ACCEPTEES) {
        appendLine("Open offer from ${names[s.currentPlayer]}: gives ${cards { s.tradeOffer[it] }}, wants ${cards { s.tradeOffer[CARD_TYPES + it] }}")
    }
    for (q in names.indices) s.counterOf(q)?.let { c ->
        appendLine("Counter-offer from ${names[q]} to ${names[s.currentPlayer]}: ${names[q]} gives ${cards { c.offerWant(it) }}, wants ${cards { c.offerGive(it) }}")
    }
    appendLine()
    appendLine("Scores (first to ${r.vp}):")
    for (p in names.indices) {
        val vp = if (p == seat) s.victoryPoints(p) else s.publicVictoryPoints(p)
        val awards = listOfNotNull("Grand Highway".takeIf { s.longestRoadOwner == p }, "Strongest Watch".takeIf { s.largestArmyOwner == p })
        appendLine("  ${names[p].padEnd(10)} $vp VP, ${s.handSize(p)} cards, ${s.devCount(p)} Fortune, ${s.knightsPlayed(p)} Sentinels played, road ${s.roadLength[p]}" +
            (if (awards.isEmpty()) "" else ", " + awards.joinToString(", ")))
    }
    if (seat != null) {
        appendLine()
        appendLine("Your hand (${names[seat]}): ${cards { s.hand(seat, it) }}")
        val fortune = (0 until DevCard.COUNT).filter { s.devHand[seat * DevCard.COUNT + it] > 0 }
            .joinToString(", ") { "${s.devHand[seat * DevCard.COUNT + it]} ${FORTUNE[it]}" }
        if (fortune.isNotEmpty()) appendLine("Your Fortune cards: $fortune")
        val rates = (0 until CARD_TYPES).joinToString(", ") { "${RES[it]} ${bankRate(s, seat, it)}:1" }
        appendLine("Your bank rates: $rates")
    }
    appendLine()
    appendLine("Recent moves:")
    for (line in recentMoves(r, 10)) appendLine("  $line")
    appendLine()
    appendLine("Board:")
    appendLine("  " + (0 until s.board.hexCount).joinToString(" | ") { hexText(s, it) })
    for (p in names.indices) {
        val built = (0 until s.board.vertexCount).filter { s.vertexOwner[it] == p }
            .joinToString("; ") { (if (s.vertexBuilding[it] == Piece.CITY) "TOWN " else "home ") + vertexText(s, it) }
        val roads = (0 until s.board.topology.edgeCount).filter { s.edgeOwner[it] == p }.joinToString(" ") { "e$it" }
        appendLine("  ${names[p]}: ${built.ifEmpty { "nothing yet" }}")
        if (roads.isNotEmpty()) appendLine("    roads: $roads")
    }
    if (seat != null && !s.isOver && s.actingPlayer == seat) {
        val legal = Rules.legalActions(s)
        appendLine()
        appendLine("YOUR MOVE - choose one with: act --seat $seat <number>")
        legal.forEachIndexed { i, a -> appendLine("  [$i] ${describe(s, a, names)}") }
    } else if (seat != null && !s.isOver) {
        appendLine()
        appendLine("Not your move. Run: wait --seat $seat")
    }
}

/** Turns a menu line ("Build a road ...") into a log line ("built a road ..."). */
private fun pastTense(t: String) = t
    .replace("Build a ", "built a ").replace("Upgrade to a town", "upgraded to a town").replace("Buy a ", "bought a ")
    .replace("Play ", "played ").replace("Trade with the bank: give ", "traded with the bank: gave ")
    .replace("Move the bandit", "moved the bandit").replace(" and take a card", " and took a card")
    .replace("Discard ", "discarded ").replace("Offer the players: you give ", "offered: gives ").replace(", you get ", ", wants ")
    .replace("Do the trade with ", "traded with ").replace("Cancel your offer", "cancelled the offer")
    .replace("Counter-offer to ", "countered ").replace(": you give ", ": gives ").replace(", you get ", ", wants ")
    .replace("End your turn", "ended the turn").replace("Accept ", "accepted ").replace("Decline ", "declined ")

/** The last [n] moves in words, replayed from the log (everything here is public information). */
internal fun recentMoves(r: Record, n: Int): List<String> {
    val s = Rules.newGame(r.config, r.seed)
    val out = ArrayList<String>()
    for (a in r.actions) {
        val who = r.seats[s.actingPlayer]
        val before = IntArray(r.seats.size * CARD_TYPES) { s.hands[it] }
        var text = when (a.type) {
            ActionType.ROLL -> "rolled " + (if (a.payload != 0L) "${a.a} + ${a.b} = ${a.a + a.b}" else "")
            ActionType.REJECT_TRADE -> if (Rules.cantPayOffer(s, s.actingPlayer))
                "declined the offer automatically: doesn't hold ${cards { Rules.offerShortfall(s, s.actingPlayer)[it] }}" else "declined the offer"
            ActionType.ACCEPT_TRADE -> "accepted the offer"
            ActionType.BUY_DEVELOPMENT_CARD -> "bought a Fortune card"
            else -> describe(s, a, r.seats).let(::pastTense)
        }
        Rules.applyChecked(s, a)
        if (a.type == ActionType.ROLL && s.lastRoll != 7) {
            val got = r.seats.indices.mapNotNull { p ->
                cards { s.hands[p * CARD_TYPES + it] - before[p * CARD_TYPES + it] }.takeIf { it != "nothing" }?.let { "${r.seats[p]} +$it" }
            }
            text += if (got.isEmpty()) " (nobody produced)" else " (" + got.joinToString("; ") + ")"
        }
        out += "$who $text"
    }
    return out.takeLast(n)
}

// ------------------------------------------------------------------ commands

/** Parses "2 Timber, 1 Clay" (also "2xTimber", "timber" = 1) into counts per resource. */
internal fun parseCards(text: String): IntArray {
    val out = IntArray(CARD_TYPES)
    for (part in text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val m = Regex("""(?i)(\d+)?\s*x?\s*([a-z]+)""").matchEntire(part) ?: fail("can't read \"$part\" (try \"2 Timber\")")
        val n = m.groupValues[1].ifEmpty { "1" }.toInt()
        val r = RES.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) }
        if (r < 0) fail("unknown card \"${m.groupValues[2]}\": use ${RES.joinToString()}")
        if (n !in 1..15) fail("1-15 of a card per trade")
        out[r] += n
    }
    return out
}

/** A text histogram of totals against the odds, with the fairness numbers under it. */
internal fun diceReport(title: String, d: DiceStats): String = buildString {
    appendLine("== $title ==")
    if (d.rolls == 0) { appendLine("no rolls yet"); return@buildString }
    val top = (0 until 11).maxOf { maxOf(d.counts[it].toDouble(), d.expected[it]) }
    val width = 40
    for (i in 0 until 11) {
        val bar = (d.counts[i] * width / top).toInt()
        val mark = (d.expected[i] * width / top).toInt()
        val line = CharArray(width + 1) { c -> when { c < bar -> '#'; c == mark -> '|'; else -> ' ' } }
        appendLine("%3d %s %6d  (expected %.1f)".format(i + 2, String(line), d.counts[i], d.expected[i]))
    }
    appendLine("# = rolled, | = what fair dice average")
    appendLine("7s: ${d.sevens} of ${d.rolls} (%.1f%%; fair dice: 16.7%%)".format(100.0 * d.sevens / d.rolls))
    val six = d.mostSevensIn(6)
    appendLine("Longest run of 7s: ${d.longestSevenRun}. Most 7s in any 6 rolls: $six (chance in a given 6 rolls: %.3f%%)".format(100 * DiceStats.chanceOfAtLeastSevens(six, 6)))
    appendLine("Chi-square %.1f, p = %.3f%s".format(d.chiSquare, d.pValue, if (d.rolls < 180) " (rough: under 180 rolls)" else if (d.pValue < 0.001) " - suspicious" else " - consistent with fair dice"))
}

private fun arg(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun fail(msg: String): Nothing { System.err.println(msg); exitProcess(2) }

fun main(argv: Array<String>) {
    val args = argv.toList()
    val cmd = args.getOrNull(0) ?: fail("usage: wgbg new|state|act|wait|offer|counter|log|dice <dir> ...")
    val dir = File(args.getOrNull(1) ?: fail("missing game folder"))
    val seat = arg(args, "--seat")?.toInt()
    when (cmd) {
        "new" -> {
            val seats = (arg(args, "--seats") ?: fail("--seats A,B,C")).split(",").map { it.trim() }
            if (seats.size !in 2..4) fail("2-4 seats for v1")
            dir.mkdirs()
            if (File(dir, "game.txt").exists()) fail("a game already exists in $dir")
            val diceArg = arg(args, "--dice") ?: "random"
            val decks = when (diceArg) { "random" -> 0; "36", "72", "108", "144" -> diceArg.toInt() / 36; else -> fail("--dice random|36|72|108|144") }
            val r = Record(dir, seats, SecureRandom().nextLong(), arg(args, "--vp")?.toInt() ?: 10, mutableListOf(), decks)
            locked(dir) { val s = r.replay(); runBots(r, s); r.save() }
            println("New game in $dir for ${seats.joinToString()} (seats 0..${seats.size - 1}), dice: ${r.diceText()}. Seat 0 places first.")
        }
        "state" -> {
            val r = Record.load(dir)
            print(render(r, r.replay(), seat))
        }
        "act" -> {
            seat ?: fail("--seat N")
            val idx = args.last().toIntOrNull() ?: fail("move number missing")
            locked(dir) {
                val r = Record.load(dir)
                val s = r.replay()
                if (s.isOver) fail("the game is over")
                if (s.actingPlayer != seat) fail("not your move: waiting on ${r.seats[s.actingPlayer]}")
                val legal = Rules.legalActions(s)
                var a = legal.getOrNull(idx) ?: fail("no move [$idx]; run state again")
                val text = describe(s, a, r.seats)
                if (a.type == ActionType.ROLL) a = r.roll() // true-random or deck dice, recorded
                Rules.applyChecked(s, a)
                r.actions += a
                runBots(r, s)
                r.save()
                println("${r.seats[seat]}: $text" + if (a.type == ActionType.ROLL) " -> ${a.a} + ${a.b} = ${a.a + a.b}" else "")
                println()
                print(render(r, s, seat))
            }
        }
        "wait" -> {
            seat ?: fail("--seat N")
            val timeout = (arg(args, "--timeout")?.toLong() ?: 600L) * 1000
            val t0 = System.currentTimeMillis()
            while (true) {
                val r = runCatching { Record.load(dir) }.getOrNull()
                if (r != null) {
                    val s = r.replay()
                    if (s.isOver || s.actingPlayer == seat) { print(render(r, s, seat)); return }
                }
                if (System.currentTimeMillis() - t0 > timeout) { println("Still not your move after ${timeout / 1000}s."); return }
                Thread.sleep(1000)
            }
        }
        "dice" -> {
            // Histogram of this game's rolls, and optionally a fresh sample from the same true-random dice.
            val sample = arg(args, "--sample")?.toInt() ?: 0
            if (File(dir, "game.txt").exists()) {
                val r = Record.load(dir)
                val sums = r.actions.filter { it.type == ActionType.ROLL && it.payload != 0L }.map { it.a + it.b }
                print(diceReport("This game (${sums.size} rolls)", DiceStats(sums)))
            } else if (sample == 0) fail("no game in $dir; add --sample N for a fresh sample")
            if (sample > 0) {
                val src = TrueRandomDice()
                print(diceReport("$sample fresh rolls from the same dice (${SecureRandom().algorithm})", DiceStats(List(sample) { src.next().sum })))
            }
        }
        "offer", "counter" -> {
            // Any trade terms, not just the 1-for-1 offers in the move list. --give is always what YOU give.
            seat ?: fail("--seat N")
            val give = parseCards(arg(args, "--give") ?: fail("--give \"2 Timber, 1 Clay\""))
            val want = parseCards(arg(args, "--want") ?: fail("--want \"1 Stone\""))
            locked(dir) {
                val r = Record.load(dir)
                val s = r.replay()
                if (s.isOver) fail("the game is over")
                if (s.actingPlayer != seat) fail("not your move: waiting on ${r.seats[s.actingPlayer]}")
                // Counters are written from the offerer's side: what they would give (our want) and get (our give).
                val a = if (cmd == "offer") Action.offerTrade(give, want) else Action.counterTrade(give = want, want = give)
                if (!Rules.isLegal(s, a)) fail(
                    if (cmd == "offer") "can't offer that now: offers are made on your turn after rolling, with cards you hold (give only or ask only is fine)"
                    else "can't counter now: a counter answers an open offer to you, with cards you hold",
                )
                val text = describe(s, a, r.seats)
                Rules.applyChecked(s, a)
                r.actions += a
                runBots(r, s)
                r.save()
                println("${r.seats[seat]}: $text")
                println()
                print(render(r, s, seat))
            }
        }
        "log" -> {
            val r = Record.load(dir)
            val s = Rules.newGame(r.config, r.seed)
            r.actions.forEachIndexed { i, a ->
                val who = r.seats[s.actingPlayer]
                val text = describe(s, a, r.seats) + if (a.type == ActionType.ROLL && a.payload != 0L) " -> ${a.a + a.b}" else ""
                Rules.applyChecked(s, a)
                println("${i.toString().padStart(4)}  $who: $text")
            }
            println("Verified ${r.actions.size} moves. " + if (s.isOver) "Winner: ${if (s.winner >= 0) r.seats[s.winner] else "none"}" else "Game in progress.")
        }
        else -> fail("unknown command $cmd")
    }
}

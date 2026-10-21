package com.mauro.wgbg

import wgbg.engine.DiceDeck
import wgbg.engine.DiceMode
import wgbg.engine.DiceSource
import wgbg.engine.Expansion
import wgbg.engine.GameConfig

// ------------------------------------------------------------------ new-game settings (UI-free, unit tested)

/** Who sits in a seat: a friend on another phone, a bot, or nobody. */
enum class SeatKind(val label: String) { FRIEND("Friend"), BOT("Bot"), EMPTY("Empty") }

/** Bot strength, weakest first. STRONG is the trained bot (#6); until it lands it plays as Greedy. */
enum class BotLevel(val label: String) {
    EASY("Easy"), GREEDY("Greedy"), STRONG("Strong");

    /** How the level plays today, which is what a picker shows as chosen: Strong is Greedy until #6. */
    val plays get() = if (this == STRONG) GREEDY else this

    companion object {
        /** The levels a picker offers: no "Strong" until it plays differently from Greedy (Fable review W4). */
        val offered get() = entries.filter { it.plays == it }
    }
}

/**
 * [name]: the theme name picked for this seat (null: the theme's next free name, or the colour under Colours).
 * [colour]: the colour picked for it (null: the first default colour nobody has, [SeatColour.resolve]).
 */
data class SeatSetup(val kind: SeatKind, val bot: BotLevel = BotLevel.GREEDY, val name: String? = null, val colour: SeatColour? = null)

/** Rulesets the screen offers, with our names (never the original game's). */
enum class Ruleset(val label: String, val expansion: Expansion, val defaultPoints: Int, val players: IntRange) {
    BASE("Base game", Expansion.BASE, 10, 2..4),
    CHIVALRY("Age of Chivalry", Expansion.CHIVALRY, 13, 3..4),
}

/**
 * Everything the game-start screen (#3) decides. Seat 0 is always you; seats 1-3 are
 * Friend/Bot/Empty. [decks] is 0 for true-random dice, or 1-4 stacked 36-card dice decks.
 */
data class GameSetup(
    val name: String = "You",
    /** content:// URI of the chosen photo, or null for the plain colored token. */
    val photo: String? = null,
    val seats: List<SeatSetup> = listOf(
        SeatSetup(SeatKind.FRIEND), SeatSetup(SeatKind.BOT), SeatSetup(SeatKind.BOT), SeatSetup(SeatKind.BOT),
    ),
    val decks: Int = 0,
    val ruleset: Ruleset = Ruleset.BASE,
    val points: Int = ruleset.defaultPoints,
    /**
     * #126: the first player comes from the seed. Not in [encode] (always on for new games); only a local game saved
     * before 0.2.10 turns it off, so it replays the way it was played ([LocalSave.decode]).
     */
    val randomFirst: Boolean = true,
    /** What the other seats are called (0.3.4); Colours names each player by their colour, as before. */
    val theme: NameTheme = NameTheme.COLOURS,
    /** Each game draws its seat colours at random (0.3.4) instead of the ones picked; never two that clash. */
    val randomColours: Boolean = false,
) {
    init {
        require(seats.size == MAX_SEATS) { "always $MAX_SEATS seats (empty ones are EMPTY)" }
    }

    /** Seats that will play, in seat order (empty seats drop out). */
    val players: List<Int> get() = seats.indices.filter { it == 0 || seats[it].kind != SeatKind.EMPTY }

    /** Each seat's theme name, by seat (null for you, an empty seat, or under Colours). */
    val seatNames: List<String?> get() = theme.resolve(seats.map { it.name }, seats.indices.map { it != 0 && seats[it].kind != SeatKind.EMPTY })

    /**
     * The names the game shows, by engine player: [you] for seat 0 (null in Watch mode, where a bot sits there and takes
     * a theme name too), each other seat's theme name, else the player's colour ([seed]: the game's, for random colours).
     */
    fun playerNames(you: String?, seed: Long = 0L): List<String> {
        val names = theme.resolve(seats.map { it.name }, seats.indices.map { (it != 0 || you == null) && seats[it].kind != SeatKind.EMPTY })
        val colours = playerColours(seed)
        return players.mapIndexed { p, seat -> if (seat == 0 && you != null) you else names[seat] ?: colours[p].label }
    }

    /** The colour each seat plays in, in seat order (null where nobody sits); picked or default, never two the same. */
    val seatColours: List<SeatColour?> get() = SeatColour.resolve(seats.map { it.colour }, seats.indices.map { it in players })

    /**
     * The colours of a game started with [seed], by seat: the picked ones, or with [randomColours] a set drawn from the
     * seed (the same game always gets the same set: a resumed game, and every phone in an online room).
     */
    fun seatColoursFor(seed: Long): List<SeatColour?> {
        if (!randomColours) return seatColours
        val drawn = SeatColour.draw(players.size, kotlin.random.Random(seed * 1_000_003 + 0x5EA7))
        return seats.indices.map { s -> players.indexOf(s).takeIf { it >= 0 }?.let(drawn::get) }
    }

    /** Every playing seat pinned to [byPlayer]'s colour for its engine player (a game saved before 0.3.4). */
    fun withColours(byPlayer: List<SeatColour>): GameSetup = copy(randomColours = false,
        seats = seats.mapIndexed { i, s -> players.indexOf(i).takeIf { it >= 0 }?.let { s.copy(colour = byPlayer[it]) } ?: s })

    /** The colours of a game started with [seed], by engine player. */
    fun playerColours(seed: Long): List<SeatColour> = seatColoursFor(seed).let { c -> players.map { c[it]!! } }

    /** Seats (by index) whose colours clash; none with random colours, which never draws a clash. */
    val colourClashes: List<Pair<Int, Int>> get() = if (randomColours) emptyList() else SeatColour.clashes(seatColours)

    /**
     * Seat i plays in [colour] (you too). No two seats share one: if another seat has it, the two swap. Every playing
     * seat's colour is pinned, so a pick never shifts the others; picking turns random colours off.
     */
    fun withSeatColour(i: Int, colour: SeatColour): GameSetup {
        if (i !in seats.indices || i !in players) return this
        val now = seatColours
        return copy(randomColours = false, seats = seats.mapIndexed { j, s ->
            when {
                j == i -> s.copy(colour = colour)
                now[j] == colour -> s.copy(colour = now[i])
                else -> s.copy(colour = now[j] ?: s.colour)
            }
        })
    }

    fun withRandomColours(on: Boolean) = copy(randomColours = on)

    /**
     * Every seat's colour for an online room started with [seed]: the game's colours for the seats that play, and for the
     * rest (a person may still take one in the lobby) the first colours nobody has, ones that don't clash where possible.
     */
    fun roomColours(seed: Long): List<SeatColour> {
        val out = seatColoursFor(seed).toMutableList()
        for (i in out.indices) if (out[i] == null) {
            val free = SeatColour.DEFAULT.filter { it !in out }
            out[i] = free.firstOrNull { c -> out.none { it != null && c.clashes(it) } } ?: free.first()
        }
        return out.map { it!! }
    }

    val hasFriends get() = seats.drop(1).any { it.kind == SeatKind.FRIEND }

    /** What stops Start, as text for the screen; empty means ready. */
    val problems: List<String>
        get() = buildList {
            if (name.isBlank()) add("Enter your name")
            if (players.size !in ruleset.players) add("${ruleset.label} needs ${ruleset.players.first}-${ruleset.players.last} players")
            if (points !in POINTS_RANGE) add("Points must be ${POINTS_RANGE.first}-${POINTS_RANGE.last}")
            if (decks !in 0..MAX_DECKS) add("Dice: random or 1-$MAX_DECKS decks")
        }

    val canStart get() = problems.isEmpty()

    fun config(): GameConfig = GameConfig(expansion = ruleset.expansion, numPlayers = players.size, vpToWin = points,
        randomFirstPlayer = randomFirst)

    fun dice(): DiceSource = if (decks == 0) DiceDeck.of(DiceMode.RANDOM) else DiceDeck.of(DiceMode.DECK, decks)

    /** Seat i becomes [kind]; seat 0 (you) never changes. */
    fun withSeat(i: Int, kind: SeatKind) = if (i == 0) this else copy(seats = seats.toMutableList().also { it[i] = it[i].copy(kind = kind) })

    fun withBot(i: Int, level: BotLevel) = if (i == 0) this else copy(seats = seats.toMutableList().also { it[i] = it[i].copy(kind = SeatKind.BOT, bot = level) })

    /** A new theme starts every seat on that theme's names in order (the old picks belong to the old theme). */
    fun withTheme(t: NameTheme) = copy(theme = t, seats = seats.map { it.copy(name = null) })

    /**
     * Seat i goes by [name], one of the theme's. No two seats share a name: if another seat has it, the two swap.
     * Every seat's current name is pinned, so the pick never shifts the names of the seats around it.
     */
    fun withSeatName(i: Int, name: String): GameSetup {
        if (i == 0 || theme.who(name) == null || seats[i].kind == SeatKind.EMPTY) return this
        val now = seatNames
        return copy(seats = seats.mapIndexed { j, s ->
            when {
                j == i -> s.copy(name = name)
                now[j] == name -> s.copy(name = now[i])
                else -> s.copy(name = now[j] ?: s.name)
            }
        })
    }

    /** Switching ruleset moves points to its default unless the player had changed them. */
    fun withRuleset(r: Ruleset) = copy(ruleset = r, points = if (points == ruleset.defaultPoints) r.defaultPoints else points)

    fun withPoints(p: Int) = copy(points = p.coerceIn(POINTS_RANGE))

    fun withDecks(d: Int) = copy(decks = d.coerceIn(0, MAX_DECKS))

    /**
     * Saved as one line (#68, #69: the start screen comes back with your last game and your name):
     * v3|name|photo|ruleset|points|decks|kind:bot:seatname:colour,…(one per seat)|theme|random colours (R or empty)
     * (names and photo URL-encoded). v2 had no colours, v1 no theme and no seat names; both still read.
     */
    fun encode(): String = listOf("v3", enc(name), enc(photo ?: ""), ruleset.name, points.toString(), decks.toString(),
        seats.joinToString(",") { "${it.kind.name}:${it.bot.name}:${enc(it.name ?: "")}:${it.colour?.name ?: ""}" }, theme.name,
        if (randomColours) "R" else "").joinToString("|")

    companion object {
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = java.net.URLDecoder.decode(s, "UTF-8")

        /** The setup [encode] wrote, or null if [text] is missing, from an unknown version or damaged. */
        fun decode(text: String?): GameSetup? = runCatching {
            val f = text!!.split("|"); require(f.size == 7 && f[0] == "v1" || f.size == 8 && f[0] == "v2" || f.size == 9 && f[0] == "v3" && f[8] in listOf("", "R"))
            val theme = if (f.size >= 8) NameTheme.valueOf(f[7]) else NameTheme.COLOURS
            val seats = f[6].split(",").map { s ->
                val p = s.split(":"); require(p.size == f.size - 5)
                // A name the theme doesn't have (a list changed since) is dropped: the seat takes the next free one.
                SeatSetup(SeatKind.valueOf(p[0]), BotLevel.valueOf(p[1]), p.getOrNull(2)?.let(::dec)?.takeIf { theme.who(it) != null },
                    p.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let(SeatColour::valueOf))
            }
            require(seats.size == MAX_SEATS && seats[0].kind == SeatKind.FRIEND)
            GameSetup(name = dec(f[1]).take(40).ifBlank { "You" }, photo = dec(f[2]).ifBlank { null }, seats = seats,
                decks = f[5].toInt().coerceIn(0, MAX_DECKS), ruleset = Ruleset.valueOf(f[3]),
                points = f[4].toInt().coerceIn(POINTS_RANGE), theme = theme, randomColours = f.getOrNull(8) == "R")
        }.getOrNull()

        const val MAX_SEATS = 4
        const val MAX_DECKS = 4
        val POINTS_RANGE = 5..20

        /** The dice choices in screen order: random, then 36/72/108/144-card decks. */
        val DICE_CHOICES = (0..MAX_DECKS).toList()

        fun diceLabel(decks: Int) = if (decks == 0) "Random" else "${36 * decks}-card deck"
    }
}

/** The name as players see it in the log: "Mauro " read "Mauro  moved the bandit" (player report, 0.3.1). */
internal fun GameSetup.trimmedName() = copy(name = name.trim())

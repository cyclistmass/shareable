package com.mauro.wgbg

import kotlin.random.Random

// ------------------------------------------------------------------ lobby state (UI-free, unit tested)
// Online LobbySession feeds this through the same seam as the local/stub lobby.

/** Where invite links point. Placeholder until #7 picks the real domain and App Link. */
/** Invite links open the join page on GitHub Pages (a real address), which opens the game with wgbg://join/CODE. */
const val JOIN_BASE = "https://cyclistmass.github.io/shareable/join/#"

/** The room code in an invite link or a wgbg://join/CODE link, or null. */
fun joinCodeFrom(link: String?): String? {
    if (link == null) return null
    val tail = link.substringAfterLast('#', link.substringAfterLast('/'))
    return Lobby.normalizeCode(tail)
}

/**
 * One lobby seat. A person sits in it ([name]), or it is open (anyone can join; [bot] is the
 * bot that takes it at start if nobody does, null = it stays empty), or it is reserved for [bot].
 * [botName] is the host's theme name for the bot (0.3.4); a person's [name] always wins over it.
 */
data class LobbySeat(
    val name: String? = null,
    val bot: BotLevel? = null,
    val ready: Boolean = false,
    val photo: String? = null,
    val open: Boolean = name == null,
    val botName: String? = null,
) {
    val isPerson get() = name != null
    /** Somebody (person or bot) will play from this seat. */
    val plays get() = isPerson || bot != null
    val label get() = name ?: if (open) "Open seat" else botName?.let { "$it, ${bot!!.label} bot" } ?: "${bot!!.label} bot"
}

/** A room: its code, the host's settings, and four seats (seat 0 is the host). */
data class Lobby(
    val code: String,
    val setup: GameSetup,
    val seats: List<LobbySeat>,
    /** Which seat this phone is. */
    val me: Int = 0,
    /** People played with before, for one-tap invites (stubbed until #7). */
    val recent: List<String> = emptyList(),
    val host: Boolean? = null,
    /** Each seat's colour (0.3.4: the host picks them on New game), in seat order. */
    val colours: List<SeatColour> = SeatColour.DEFAULT.take(GameSetup.MAX_SEATS),
) {
    init {
        require(seats.size == GameSetup.MAX_SEATS)
        require(me in seats.indices)
    }

    val link get() = JOIN_BASE + code
    val isHost get() = host ?: (me == 0)
    /** People seated, for "3 of 4 seats taken". */
    val taken get() = seats.count { it.isPerson }

    /** Start: host only, every person ready, and the seats make a valid game with the host's settings. */
    val canStart get() = isHost && seats.all { !it.isPerson || it.ready } && gameSetup().canStart

    /** Why Start is off, for the screen; empty when it isn't. */
    val waitingOn: List<String>
        get() = buildList {
            seats.filter { it.isPerson && !it.ready }.forEach { add("${it.name} isn't ready") }
            addAll(gameSetup().problems)
        }

    fun toggleReady() = if (!seats[me].isPerson) this else update(me) { it.copy(ready = !it.ready) }

    /** Host only: sets the bot of a seat no person sits in (null on an open seat = leave it empty). */
    fun setBot(i: Int, level: BotLevel?) =
        if (!isHost || i == 0 || seats[i].isPerson || (level == null && !seats[i].open)) this else update(i) { it.copy(bot = level) }

    /** A person takes the first open seat; returns null when there is none. */
    fun join(name: String, photo: String? = null): Lobby? {
        val i = seats.indexOfFirst { it.open }.takeIf { it > 0 } ?: return null
        return update(i) { LobbySeat(name = wgbg.net.uniqueSeatName(name, seats.mapNotNull { it.name }), photo = photo) }
    }

    /** A person leaves: the seat reopens, keeping any bot that was waiting for it. */
    fun leave(i: Int) = if (i == 0 || !seats[i].isPerson) this else update(i) { LobbySeat(bot = BotLevel.GREEDY) }

    /** The settings the game starts with: lobby seats decide who is a friend, bot or empty. */
    fun gameSetup(): GameSetup = setup.copy(
        seats = seats.mapIndexed { i, s ->
            when {
                i == 0 || s.isPerson -> SeatSetup(SeatKind.FRIEND)
                s.bot != null -> SeatSetup(SeatKind.BOT, s.bot)
                else -> SeatSetup(SeatKind.EMPTY)
            }
        },
    )

    private fun update(i: Int, f: (LobbySeat) -> LobbySeat) = copy(seats = seats.toMutableList().also { it[i] = f(it[i]) })

    companion object {
        /** Short words that read aloud cleanly; codes look like ROVER-427 (older rooms have two digits, ROVER-42). */
        val CODE_WORDS = listOf(
            "ROVER", "HARBOR", "ANCHOR", "BARLEY", "CEDAR", "CLOVER", "COBALT", "DUNE", "EMBER", "FERRY",
            "FJORD", "GROVE", "HAVEN", "HERON", "ISLE", "JUNIPER", "KESTREL", "LANTERN", "MAPLE", "MEADOW",
            "OTTER", "PIER", "QUARRY", "RAVEN", "REEF", "SAFFRON", "TIMBER", "TIDE", "VALE", "WILLOW",
        )
        private val CODE_REGEX = Regex("([A-Z]+)-([1-9][0-9]{1,2})")

        fun newCode(random: Random = Random.Default) = "${CODE_WORDS[random.nextInt(CODE_WORDS.size)]}-${random.nextInt(100, 1000)}"  // 30 words x 900: room for many rooms (#80)

        /** Accepts what people type: any case, spaces or a missing dash ("rover 42", "rover42"). */
        fun normalizeCode(typed: String): String? {
            val t = typed.trim().uppercase().replace(Regex("[\\s_-]+"), "")
            val m = Regex("([A-Z]+)([1-9][0-9]{1,2})").matchEntire(t) ?: return null
            return "${m.groupValues[1]}-${m.groupValues[2]}".takeIf(::isValidCode)
        }

        fun isValidCode(code: String) = CODE_REGEX.matchEntire(code)?.let { it.groupValues[1] in CODE_WORDS } ?: false

        /** A fresh room from the game-start settings: you in seat 0, chosen bots reserved, friend seats open (Greedy fills). */
        fun host(setup: GameSetup, code: String = newCode()) = Lobby(
            code = code,
            setup = setup,
            seats = setup.seats.mapIndexed { i, s ->
                when {
                    // The host is ready from the start: they press Start themselves (Mauro couldn't start a game
                    // until he found his own "Not ready" toggle). Friends who join still mark themselves ready.
                    i == 0 -> LobbySeat(name = setup.name, photo = setup.photo, ready = true)
                    // Bots keep the host's theme names, the one waiting in a friend's seat too (0.3.4).
                    s.kind == SeatKind.BOT -> LobbySeat(bot = s.bot, open = false, botName = setup.seatNames[i])
                    s.kind == SeatKind.FRIEND -> LobbySeat(bot = BotLevel.GREEDY, botName = setup.seatNames[i])
                    else -> LobbySeat(open = true)
                }
            },
            colours = setup.roomColours(0L),
        )

        /** Stub data until #7, matching the Lobby A design: three people, one not ready, one open seat. */
        fun sample() = host(
            GameSetup(name = "Mauro").withSeat(1, SeatKind.FRIEND).withSeat(2, SeatKind.FRIEND).withSeat(3, SeatKind.FRIEND).withDecks(2),
            code = "ROVER-42",
        ).let { it.join("Erin")!!.join("Tiberiu")!! }
            .let { l -> l.copy(seats = l.seats.mapIndexed { i, s -> if (i <= 1) s.copy(ready = true) else s }, recent = listOf("Tiberiu", "Erin", "Sam")) }
    }
}

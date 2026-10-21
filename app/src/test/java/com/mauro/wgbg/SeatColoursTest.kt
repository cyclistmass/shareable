package com.mauro.wgbg

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.net.Friends
import wgbg.net.InMemoryTransport
import wgbg.net.MemoryStore
import wgbg.net.Room
import wgbg.net.Seat
import wgbg.net.Status
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Seat colours picked on New game, or drawn at random (0.3.4, Mauro: "random or I can pick the color when I make the
 * game"). Two colours "clash" when they are under 30 CIELAB ΔE apart with normal sight or as seen with deuteranopia,
 * protanopia or tritanopia (Machado 2009, full severity; 36's check from the colour options). The default is Red, Blue,
 * Purple, White; random only draws sets with no clash; a pick that clashes is allowed, with a warning.
 */
class SeatColoursTest {
    // ------------------------------------------------------------ the palette

    /** 36's ΔE, worked from Compose's own colours: a second hand on [SeatColour.apart]. */
    private val sights = listOf(
        null,
        listOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
        listOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
        listOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900),
    )
    private fun lin(c: Float) = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lab(c: Color, m: List<Double>?): DoubleArray {
        val l = doubleArrayOf(lin(c.red), lin(c.green), lin(c.blue))
        val v = if (m == null) l else DoubleArray(3) { i -> (m[3 * i] * l[0] + m[3 * i + 1] * l[1] + m[3 * i + 2] * l[2]).coerceIn(0.0, 1.0) }
        val x = (0.4124 * v[0] + 0.3576 * v[1] + 0.1805 * v[2]) / 0.95047
        val y = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
        val z = (0.0193 * v[0] + 0.1192 * v[1] + 0.9505 * v[2]) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
    private fun apart(a: SeatColour, b: SeatColour) = sights.minOf { m ->
        val p = lab(Color(a.argb), m); val q = lab(Color(b.argb), m); sqrt((0..2).sumOf { (p[it] - q[it]).pow(2) })
    }

    private val all = SeatColour.entries

    @Test
    fun `the ΔE matches 36's check, and only Red-Orange, Orange-Yellow and Purple-Black clash`() {
        val clashing = mutableSetOf<Set<SeatColour>>()
        for (a in all) for (b in all) if (a != b) {
            assertEquals(apart(a, b), SeatColour.apart(a, b), 0.5, "$a vs $b")
            assertEquals(SeatColour.apart(a, b), SeatColour.apart(b, a), 1e-9)
            if (a.clashes(b)) clashing += setOf(a, b)
        }
        assertEquals(setOf(setOf(SeatColour.RED, SeatColour.ORANGE), setOf(SeatColour.ORANGE, SeatColour.YELLOW),
            setOf(SeatColour.PURPLE, SeatColour.BLACK)), clashing)
        for (c in all) assertFalse(c.clashes(c), "a colour never clashes with itself")
    }

    @Test
    fun `the default is Red, Blue, Purple, White, which nobody mixes up, and each colour has its own name`() {
        assertEquals(listOf("Red", "Blue", "Purple", "White"), SeatColour.DEFAULT.take(4).map { it.label })
        assertEquals(all.toSet(), SeatColour.DEFAULT.toSet())
        assertEquals(emptyList<Pair<Int, Int>>(), SeatColour.clashes(SeatColour.DEFAULT.take(4)))
        assertEquals(all.size, all.map { it.label }.toSet().size)
        assertEquals(listOf("Red", "Blue", "Purple", "White"), PLAYER_NAMES, "outside a game the palette is the default")
        assertEquals(SeatColour.DEFAULT.take(4).map { Color(it.argb) }, PLAYER_COLORS)
    }

    @Test
    fun `the draw gives n different colours, never two that clash while such a set exists`() {
        for (n in 0..all.size) repeat(300) { k ->
            val d = SeatColour.draw(n, Random(n * 1000L + k))
            assertEquals(n, d.size); assertEquals(n, d.toSet().size, "$d")
            if (n <= 5) assertEquals(emptyList<Pair<Int, Int>>(), SeatColour.clashes(d), "$d")
        }
        // Every clean 4-set turns up: random really is random over them.
        val seen = (0 until 3000).map { SeatColour.draw(4, Random(it.toLong())).toSet() }.toSet()
        assertEquals(11, seen.size)
    }

    @Test
    fun `resolve - picks win, an earlier seat keeps a shared pick, the rest take default colours, empty seats none`() {
        val r = SeatColour.resolve(listOf(null, null, null, null), listOf(true, true, true, true))
        assertEquals(SeatColour.DEFAULT.take(4), r)
        val p = SeatColour.resolve(listOf(SeatColour.BLUE, SeatColour.BLUE, null, SeatColour.RED), listOf(true, true, true, true))
        assertEquals(listOf(SeatColour.BLUE, SeatColour.PURPLE, SeatColour.WHITE, SeatColour.RED), p)
        val e = SeatColour.resolve(listOf(SeatColour.YELLOW, null, SeatColour.BLACK, null), listOf(true, true, false, true))
        assertEquals(listOf(SeatColour.YELLOW, SeatColour.RED, null, SeatColour.BLUE), e)
        // Six seats: works on any count, no repeats.
        val six = SeatColour.resolve(List(6) { null }, List(6) { true })
        assertEquals(6, six.filterNotNull().toSet().size)
    }

    // ------------------------------------------------------------ New game

    @Test
    fun `with nobody picking, the game plays the default colours and names, as the board draws them`() {
        val s = GameSetup(name = "Mauro")
        assertEquals(SeatColour.DEFAULT.take(4), s.playerColours(99))
        assertEquals(listOf("Mauro", "Blue", "Purple", "White"), s.playerNames("Mauro"))
        assertEquals(emptyList<Pair<Int, Int>>(), s.colourClashes)
        // An empty seat drops out; the players after it take the next default colour.
        assertEquals(listOf(SeatColour.RED, SeatColour.BLUE, SeatColour.PURPLE), s.withSeat(2, SeatKind.EMPTY).playerColours(1))
    }

    @Test
    fun `picking a colour another seat has swaps the two, and the other seats keep theirs`() {
        val s = GameSetup().withSeatColour(2, SeatColour.BLUE)
        assertEquals(listOf(SeatColour.RED, SeatColour.PURPLE, SeatColour.BLUE, SeatColour.WHITE), s.seatColours)
        val you = s.withSeatColour(0, SeatColour.YELLOW)
        assertEquals(listOf(SeatColour.YELLOW, SeatColour.PURPLE, SeatColour.BLUE, SeatColour.WHITE), you.seatColours)
        // A seat that empties and comes back keeps its pick; the others don't move meanwhile.
        val back = you.withSeat(1, SeatKind.EMPTY).withSeat(1, SeatKind.BOT)
        assertEquals(you.seatColours, back.seatColours)
        assertEquals(listOf("You", "Purple", "Blue", "White"), back.playerNames("You"), "Colours names each bot by its colour")
    }

    @Test
    fun `a clashing pick is allowed but reported, and the clash names both seats`() {
        val s = GameSetup().withSeatColour(3, SeatColour.ORANGE)
        assertEquals(SeatColour.ORANGE, s.seatColours[3])
        assertEquals(listOf(0 to 3), s.colourClashes)
        assertTrue(s.canStart, "a warning, not a block")
    }

    @Test
    fun `random colours - the same game always gets the same clean set, each new game a fresh one, and a pick turns it off`() {
        val s = GameSetup().withSeatColour(1, SeatColour.ORANGE).withRandomColours(true)
        assertEquals(emptyList<Pair<Int, Int>>(), s.colourClashes)
        val sets = (0L until 200L).map { seed ->
            val c = s.playerColours(seed)
            assertEquals(c, s.playerColours(seed), "a resumed game draws the same")
            assertEquals(emptyList<Pair<Int, Int>>(), SeatColour.clashes(c), "$c")
            assertEquals(c.map { it.label }.drop(1), s.playerNames("You", seed).drop(1), "Colours names the bots by the drawn colours")
            c
        }
        assertTrue(sets.toSet().size > 20, "fresh at each Play")
        assertFalse(s.withSeatColour(2, SeatColour.RED).randomColours)
        assertEquals(s.seats, s.withRandomColours(false).seats, "Pick again brings back the picks")
    }

    @Test
    fun `colours and random survive close and reopen (v3), and setups saved before colours still read`() {
        val s = GameSetup(name = "Mauro").withSeatColour(0, SeatColour.BLACK).withSeatColour(2, SeatColour.YELLOW).withSeat(3, SeatKind.EMPTY)
        val back = GameSetup.decode(s.encode())!!
        assertEquals(s, back); assertEquals(s.seatColours, back.seatColours)
        assertEquals(s.withRandomColours(true), GameSetup.decode(s.withRandomColours(true).encode()))
        val v2 = "v2|Mauro||BASE|10|0|FRIEND:GREEDY:,BOT:EASY:,BOT:GREEDY:,BOT:GREEDY:|COLOURS"
        val old = GameSetup.decode(v2)!!
        assertEquals(SeatColour.DEFAULT.take(4), old.playerColours(5)); assertFalse(old.randomColours)
        assertTrue(old.encode().startsWith("v3|"))
        assertEquals(SeatColour.DEFAULT.take(3), GameSetup.decode("v1|Mauro||BASE|10|0|FRIEND:GREEDY,BOT:EASY,EMPTY:GREEDY,BOT:GREEDY")!!.playerColours(1))
    }

    // ------------------------------------------------------------ online

    @Test
    fun `the host's colours go to every phone in the room, open seats included, and over the wire`() {
        val store = MemoryStore()
        val setup = GameSetup(name = "Mauro").withSeat(1, SeatKind.FRIEND).withSeatColour(1, SeatColour.YELLOW).withSeatColour(0, SeatColour.BLACK)
        val h = LobbySession.host(InMemoryTransport("h", store), setup, 5, code = "ROVER-42").join()
        val room = h.client.view().room!!
        assertEquals(listOf("BLACK", "YELLOW", "PURPLE", "WHITE"), room.colours)
        assertEquals(room, com.mauro.wgbg.room(room.id, room.wire()))
        assertEquals(listOf(SeatColour.BLACK, SeatColour.YELLOW, SeatColour.PURPLE, SeatColour.WHITE), h.model(room).colours)
        val g = OnlineSeats(room.copy(seats = room.seats.mapIndexed { i, st -> if (i == 1) Seat("g", "Erin", ready = true) else st }), "g")
        assertEquals(listOf(SeatColour.BLACK, SeatColour.YELLOW, SeatColour.PURPLE, SeatColour.WHITE), g.colours)
        assertEquals("Purple", g.names[2])
        // A friend's seat holds a waiting bot; with nobody (no bot either) it drops out, and the players after it keep
        // their seat's colour.
        assertEquals(4, OnlineSeats(room, "h").colours.size)
        val alone = OnlineSeats(room.copy(seats = room.seats.mapIndexed { i, st -> if (i == 1) Seat(open = true) else st }), "h")
        assertEquals(listOf(SeatColour.BLACK, SeatColour.PURPLE, SeatColour.WHITE), alone.colours)
        h.close()
    }

    @Test
    fun `random colours online - the room carries one clean draw, and a rematch draws again`() {
        val store = MemoryStore()
        val setup = GameSetup(name = "Mauro").withSeat(1, SeatKind.FRIEND).withRandomColours(true)
        val h = LobbySession.host(InMemoryTransport("h", store), setup, 5, code = "ROVER-42").join()
        val room = h.client.view().room!!
        val c = room.colours.map { SeatColour.valueOf(it) }
        assertEquals(4, c.toSet().size); assertEquals(emptyList<Pair<Int, Int>>(), SeatColour.clashes(c))
        assertEquals(setup.roomColours(5), c)
        assertEquals(room.colours, Friends.rematch(room.copy(status = Status.done), "ELM-2", 6).colours, "net keeps them")
        h.close()
    }

    @Test
    fun `a room without colours plays the default ones by player, as every game before`() {
        val room = Room("ROVER-42", "h", wgbg.engine.GameConfig(numPlayers = 3), 7, listOf(
            Seat("h", "Anne", ready = true), Seat(null, "", null, open = true), Seat(null, "", "EASY", open = false), Seat(null, "", "GREEDY", open = false)))
        assertEquals(SeatColour.DEFAULT.take(3), OnlineSeats(room, "h").colours)
        assertEquals(emptyList<String>(), com.mauro.wgbg.room(room.id, room.wire()).colours)
        assertFalse("colours" in (room.wire()["options"] as Map<*, *>), "nothing written when there are none")
    }

    @Test
    fun `the server's rules allow exactly these colours (a new colour needs the rules deployed first)`() {
        val rules = java.io.File("../firestore.rules").readText()
        val listed = Regex("""o\.get\('colours', \[\]\)\.hasOnly\(\[([^]]*)]""").find(rules)!!.groupValues[1]
            .split(",").map { it.trim().trim('\'') }
        assertEquals(all.map { it.name }.toSet(), listed.toSet())
    }

    // ------------------------------------------------------------ fuzz

    @Test
    fun `fuzz - any run of picks, seats and random keeps every player's colour different, and saves exactly`() {
        val r = Random(3404)
        repeat(4000) { game ->
            var s = GameSetup()
            repeat(r.nextInt(1, 30)) {
                s = when (r.nextInt(6)) {
                    0, 1 -> s.withSeatColour(r.nextInt(-1, GameSetup.MAX_SEATS + 1), all.random(r))
                    2 -> s.withSeat(1 + r.nextInt(GameSetup.MAX_SEATS - 1), SeatKind.entries.random(r))
                    3 -> s.withRandomColours(r.nextBoolean())
                    4 -> s.withTheme(NameTheme.entries.random(r))
                    else -> s
                }
                val seed = r.nextLong()
                val c = s.playerColours(seed)
                assertEquals(s.players.size, c.toSet().size, "game $game: $c")
                if (s.randomColours) assertEquals(emptyList<Pair<Int, Int>>(), SeatColour.clashes(c), "game $game")
                val names = s.playerNames("You", seed)
                assertEquals(names.size, names.toSet().size, "game $game: $names")
                assertEquals(s, GameSetup.decode(s.encode()), "game $game")
                val room = s.roomColours(seed)
                assertEquals(GameSetup.MAX_SEATS, room.toSet().size, "game $game: $room")
                assertEquals(s.seatColoursFor(seed).filterNotNull(), s.players.map { room[it] }, "game $game")
            }
        }
    }

    // ------------------------------------------------------------ break it

    @Test
    fun `garbage in a saved setup reads as none (the defaults), never a crash or a half setup`() {
        val good = GameSetup().withSeatColour(1, SeatColour.BLACK).encode()
        val f = good.split("|")
        val bad = listOf(
            good.replace("BLACK", "PINK"), good.replace("BLACK", "black"), good.replace(":BLACK", ":BLACK:extra"),
            f.dropLast(1).joinToString("|") + "|X", f.joinToString("|") + "|R", good.replace("v3|", "v4|"),
            f.take(8).joinToString("|"), good.replace(":BLACK", ""),
        )
        for (b in bad) assertNull(GameSetup.decode(b), b)
    }

    @Test
    fun `a pick for an empty seat, a seat that doesn't exist or the colour it has changes nothing`() {
        val s = GameSetup().withSeat(2, SeatKind.EMPTY).withSeatColour(1, SeatColour.YELLOW)
        assertSame(s, s.withSeatColour(2, SeatColour.BLACK))
        assertSame(s, s.withSeatColour(-1, SeatColour.BLACK)); assertSame(s, s.withSeatColour(GameSetup.MAX_SEATS, SeatColour.BLACK))
        assertEquals(s.seatColours, s.withSeatColour(1, SeatColour.YELLOW).seatColours)
    }

    @Test
    fun `garbage colours in a room (unknown names, blanks, repeats, too few) still give every seat its own colour`() {
        for (colours in listOf(listOf("PINK", "", "RED", "RED"), listOf("BLUE"), listOf("WHITE", "WHITE", "WHITE", "WHITE", "BLACK"), listOf("", "", "", ""))) {
            val room = Room("ROVER-42", "h", wgbg.engine.GameConfig(numPlayers = 4), 7, listOf(
                Seat("h", "Anne", ready = true), Seat(null, "", "EASY", open = false), Seat(null, "", "EASY", open = false), Seat(null, "", "EASY", open = false)),
                colours = colours)
            val c = OnlineSeats(room, "h").colours
            assertEquals(4, c.toSet().size, "$colours -> $c")
            assertEquals(4, roomSeatColours(room).toSet().size)
            @Suppress("UNCHECKED_CAST")
            val wire = room.wire().toMutableMap().also { w -> w["options"] = (w["options"] as Map<String, Any?>) + ("colours" to listOf(1, null, "RED")) }
            assertEquals(listOf("", "", "RED"), com.mauro.wgbg.room(room.id, wire).colours, "non-text entries read as blank, not a crash")
        }
    }
}

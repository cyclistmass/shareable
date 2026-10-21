package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.net.InMemoryTransport
import wgbg.net.MemoryStore
import wgbg.net.Room
import wgbg.net.Seat
import kotlin.random.Random

/**
 * Bot names (Mauro, 0.3.4: "I would also like to name my AI", picked from a theme's list, no typing). The name comes apart
 * from the colour: the colour stays the seat's, every text uses the name.
 */
class SeatNamesTest {
    private val themes = NameTheme.entries.filter { it != NameTheme.COLOURS }
    private fun bots() = GameSetup()   // you and three bots

    // ------------------------------------------------------------ rules of the names

    @Test
    fun `Colours is the default and names everyone by colour, as before`() {
        assertEquals(NameTheme.COLOURS, GameSetup().theme)
        assertEquals(listOf("Mauro", "Blue", "Purple", "White"), GameSetup(name = "Mauro").playerNames("Mauro"))
        assertEquals(listOf("Red", "Blue", "Purple", "White"), GameSetup().playerNames(null), "Watch mode: bots in every seat")
        assertEquals(listOf(null, null, null, null), GameSetup().seatNames)
    }

    @Test
    fun `a theme gives each other seat its own name, in the theme's order, and the colour stays the player's`() {
        for (t in themes) {
            val s = bots().withTheme(t)
            assertEquals(listOf(null) + t.names.take(3).map { it.name }, s.seatNames, t.label)
            assertEquals(listOf("You") + t.names.take(3).map { it.name }, s.playerNames("You"), t.label)
            // Watch mode: the bot in seat 0 takes a theme name too, and nobody shares one.
            assertEquals(4, s.playerNames(null).toSet().size, t.label)
        }
    }

    @Test
    fun `an empty seat drops out, and the players after it keep their own names`() {
        val s = bots().withTheme(NameTheme.FRENCH_QUEENS).withSeatName(3, "Marie").withSeat(2, SeatKind.EMPTY)
        assertEquals(listOf(0, 1, 3), s.players)
        assertEquals(listOf("You", "Aliénor", "Marie"), s.playerNames("You"))
        // Under Colours the names follow the players' colours (player 2 is Purple), as the board draws them.
        assertEquals(listOf("You", "Blue", "Purple"), s.withTheme(NameTheme.COLOURS).playerNames("You"))
    }

    @Test
    fun `picking a name another seat has swaps the two, and the other seats keep theirs`() {
        val s = bots().withTheme(NameTheme.ENGLISH_QUEENS)
        assertEquals(listOf(null, "Matilda", "Eleanor", "Isabella"), s.seatNames)
        val swapped = s.withSeatName(3, "Matilda")
        assertEquals(listOf(null, "Isabella", "Eleanor", "Matilda"), swapped.seatNames)
        val fresh = swapped.withSeatName(2, "Victoria")
        assertEquals(listOf(null, "Isabella", "Victoria", "Matilda"), fresh.seatNames)
    }

    @Test
    fun `the longest names (Elizabeth I, Marguerite, Go-Daigo) can all be picked, one per seat`() {
        val e = bots().withTheme(NameTheme.ENGLISH_QUEENS).withSeatName(1, "Elizabeth I").withSeatName(2, "Charlotte").withSeatName(3, "Catherine")
        assertEquals(listOf("You", "Elizabeth I", "Charlotte", "Catherine"), e.playerNames("You"))
        assertEquals("Marguerite", bots().withTheme(NameTheme.FRENCH_QUEENS).withSeatName(1, "Marguerite").seatNames[1])
        assertEquals("Go-Daigo", bots().withTheme(NameTheme.JAPANESE_EMPERORS).withSeatName(1, "Go-Daigo").seatNames[1])
    }

    @Test
    fun `a name picked for every seat comes back after the app is closed and opened (saved setup and saved game)`() {
        for (t in themes) {
            var s = bots().withTheme(t).withRuleset(Ruleset.CHIVALRY)
            val picks = t.names.map { it.name }.reversed().take(3)
            picks.forEachIndexed { k, n -> s = s.withSeatName(k + 1, n) }
            assertEquals(listOf(null) + picks, s.seatNames)
            val back = GameSetup.decode(s.encode())!!
            assertEquals(s, back, t.label)
            assertEquals(s.playerNames("You"), back.playerNames("You"))
            val game = LocalSave.decode(LocalSave(s, 42L, emptyList()).encode())!!
            assertEquals(s.playerNames("You"), game.setup.playerNames("You"), "a saved game resumes with its names")
        }
    }

    @Test
    fun `a setup saved before themes (v1) still reads, as Colours`() {
        val v1 = "v1|Mauro||CHIVALRY|13|2|FRIEND:GREEDY,BOT:EASY,EMPTY:GREEDY,BOT:GREEDY"
        val s = GameSetup.decode(v1)!!
        assertEquals(NameTheme.COLOURS, s.theme)
        assertEquals(listOf("Mauro", "Blue", "Purple"), s.playerNames("Mauro"))
        assertTrue(s.encode().startsWith("v3|"))
    }

    @Test
    fun `names are resolved for any number of seats (six-player games), never assuming four`() {
        val t = NameTheme.JAPANESE_EMPERORS
        val six = t.resolve(listOf(null, "Meiji", null, "Meiji", "Saga", null), listOf(false, true, true, true, true, true))
        assertNull(six[0])
        assertEquals("Meiji", six[1]); assertEquals("Saga", six[4])
        assertEquals(5, six.filterNotNull().toSet().size, "six seats, five bots, five different names: $six")
        // More seats than names: the extra seats fall back to their colour (null here), never a repeat.
        val many = NameTheme.OLD_TRADES.resolve(List(14) { null }, List(14) { true })
        assertEquals(10, many.filterNotNull().toSet().size); assertEquals(4, many.count { it == null })
        assertEquals("Player 5", colourName(4)); assertEquals("Player 6", colourName(5))
    }

    @Test
    fun `every theme has 10 to 12 different names, each with a line on who she or he was, short enough for the strip`() {
        for (t in themes) {
            assertTrue(t.names.size in 10..12, t.label)
            assertEquals(t.names.size, t.names.map { it.name }.toSet().size, "${t.label}: a name twice")
            for (n in t.names) {
                assertTrue(n.name.length <= 11, "${n.name} is longer than Elizabeth I")
                assertTrue(n.who.isNotBlank() && n.who.length <= 80, n.name)
                assertFalse(SeatColour.entries.any { it.label == n.name }, "${n.name} would read as a colour")
            }
        }
    }

    @Test
    fun `no theme name is one of the original game's names (OurNamesTest's list) or one of our pieces and cards`() {
        // The same pattern as OurNamesTest (0.3.5), kept here until that test covers NameTheme.
        val original = Regex(listOf("catan(?!atron)", "settlers", "siedler", "seafarers", "kosmos", "teuber", "brick", "lumber",
            "wool", "sheep", "\\bore\\b", "wheat", "settlement", "\\bcity\\b", "robber", "barbarian", "metropol", "monopol",
            "alchemist", "\\bcrane", "engineer", "\\binventor\\b", "medicine", "\\bmining", "\\bprinter", "\\bsmith\\b", "merchant",
            "bishop", "constitution", "deserter", "diplomat", "intrigue", "saboteur", "\\bspy\\b", "warlord", "wedding").joinToString("|"),
            RegexOption.IGNORE_CASE)
        val ours = setOf("Timber", "Clay", "Fleece", "Grain", "Stone", "Bandit", "Trader", "Raiders", "Capital", "Sentinel")
        for (t in themes) for (n in t.names) {
            assertFalse(original.containsMatchIn(n.name) || original.containsMatchIn(n.who), "${t.label}: ${n.name}")
            assertFalse(n.name in ours, n.name)
        }
    }

    @Test
    fun `the card tester's game names the bots by colour, the names its scenario texts use`() {
        for (sc in CardTester.scenarios) {
            val p = sc.build()
            val setup = CardTester.setupFor(p.state, "you")
            assertEquals(NameTheme.COLOURS, setup.theme)
            assertEquals(TESTER_NAMES.take(setup.players.size), setup.playerNames("you"), sc.id)
            val said = Regex(SeatColour.entries.joinToString("|", """\b(""", """)\b""") { it.label }).findAll(p.howTo + " " + p.expect).map { it.value }.toSet()
            assertTrue(TESTER_NAMES.containsAll(said), "${sc.id} names $said")
        }
    }

    // ------------------------------------------------------------ break it

    @Test
    fun `a name the theme doesn't have, your own seat, an empty seat or another theme's name changes nothing`() {
        val s = bots().withTheme(NameTheme.ENGLISH_QUEENS).withSeat(2, SeatKind.EMPTY)
        for ((i, n) in listOf(1 to "Blanche", 1 to "", 1 to "matilda", 1 to "Matilda ", 0 to "Anne", 2 to "Anne", 1 to "Red"))
            assertEquals(s, s.withSeatName(i, n), "seat $i, '$n'")
        assertEquals(bots(), bots().withSeatName(1, "Matilda"), "nothing to pick from under Colours")
    }

    @Test
    fun `a new theme starts the seats on that theme, so no name carries over from the old one`() {
        val s = bots().withTheme(NameTheme.ENGLISH_QUEENS).withSeatName(1, "Anne").withTheme(NameTheme.FRENCH_QUEENS)
        assertEquals(listOf(null, "Aliénor", "Blanche", "Jeanne"), s.seatNames)
        assertTrue(s.seats.all { it.name == null })
    }

    @Test
    fun `damaged or tampered saved names are dropped or refused, never shown`() {
        val ok = "v2|You||BASE|10|0|FRIEND:GREEDY:,BOT:GREEDY:Anne,BOT:GREEDY:Anne,BOT:GREEDY:Zorro|FRENCH_QUEENS"
        val s = GameSetup.decode(ok)!!
        assertEquals(listOf(null, "Anne", "Aliénor", "Blanche"), s.seatNames, "a repeat and a stranger take the next free names")
        for (bad in listOf(
            "v2|You||BASE|10|0|FRIEND:GREEDY:,BOT:GREEDY:,BOT:GREEDY:,BOT:GREEDY:|NOPE",
            "v2|You||BASE|10|0|FRIEND:GREEDY,BOT:GREEDY,BOT:GREEDY,BOT:GREEDY|COLOURS",   // v2 needs a name slot per seat
            "v2|You||BASE|10|0|FRIEND:GREEDY:,BOT:GREEDY:,BOT:GREEDY:,BOT:GREEDY:",       // no theme
            "v1|You||BASE|10|0|FRIEND:GREEDY:,BOT:GREEDY:,BOT:GREEDY:,BOT:GREEDY:"))     // v1 had no names
            assertNull(GameSetup.decode(bad), bad)
        // A name with the line's separators in it is encoded, not split.
        val odd = GameSetup(name = "a|b:c,d").withTheme(NameTheme.JAPANESE_EMPERORS).withSeatName(1, "Go-Daigo")
        assertEquals(odd, GameSetup.decode(odd.encode()))
    }

    // ------------------------------------------------------------ fuzz

    @Test
    fun `5000 random edits of the setup keep every seat's name its own, and every save reads back the same`() {
        val r = Random(72)
        repeat(5000) { game ->
            var s = GameSetup()
            repeat(r.nextInt(1, 25)) {
                val i = r.nextInt(GameSetup.MAX_SEATS)
                s = when (r.nextInt(5)) {
                    0 -> s.withTheme(NameTheme.entries.random(r))
                    1 -> s.withSeat(i, SeatKind.entries.random(r))
                    2 -> s.withBot(i, BotLevel.entries.random(r))
                    else -> s.withSeatName(i, (NameTheme.entries.flatMap { t -> t.names.map { it.name } } + listOf("", "x", "Red")).random(r))
                }
            }
            val names = s.seatNames
            val sitting = s.seats.indices.filter { it != 0 && s.seats[it].kind != SeatKind.EMPTY }
            if (s.theme == NameTheme.COLOURS) assertTrue(names.all { it == null }, "game $game")
            else {
                assertTrue(sitting.all { names[it] != null && s.theme.who(names[it]) != null }, "game $game: $names")
                assertEquals(sitting.size, sitting.map { names[it] }.toSet().size, "game $game: a name twice $names")
            }
            val shown = s.playerNames("You")
            assertEquals(s.players.size, shown.size)
            assertEquals(shown.size, shown.toSet().size, "game $game: two players called the same: $shown")
            val back = GameSetup.decode(s.encode())!!
            assertEquals(names, back.seatNames, "game $game"); assertEquals(shown, back.playerNames("You"))
        }
    }

    // ------------------------------------------------------------ online

    @Test
    fun `online, the host's bots keep their theme names on every phone, and a person who sits down replaces it`() {
        val store = MemoryStore()
        val setup = GameSetup(name = "Mauro").withTheme(NameTheme.OLD_TRADES).withSeat(1, SeatKind.FRIEND).withSeatName(3, "Potter")
        val lobby = Lobby.host(setup, "ROVER-42")
        assertEquals(listOf(null, "Cooper", "Chandler", "Potter"), lobby.seats.map { it.botName })
        assertEquals("Potter, Greedy bot", lobby.seats[3].label)
        val h = LobbySession.host(InMemoryTransport("h", store), setup, 5, code = "ROVER-42").join()
        val erin = joinFirstOpenSeat(InMemoryTransport("e", store), GameSetup(name = "Erin"), "ROVER-42").join()
        val room = erin.client.view().room!!
        assertEquals(listOf("Mauro", "Erin", "Chandler", "Potter"), room.seats.map { it.name })
        assertEquals(listOf("Mauro", "Erin", "Chandler", "Potter"), OnlineSeats(room, "e").names.let { listOf(it[0], it[1], it[2], it[3]) })
        assertEquals("Chandler", h.model(room).seats[2].botName); assertFalse(h.model(room).seats[2].isPerson)
        assertEquals(null, h.model(room).seats[1].botName, "Erin sits there now")
    }

    @Test
    fun `online, a bot never shows a person's name, a blank bot name shows its colour, and old rooms read as before`() {
        val room = Room("ROVER-42", "h", wgbg.engine.GameConfig(numPlayers = 4), 7, listOf(
            Seat("h", "Anne", ready = true), Seat(null, "anne", "GREEDY", open = false),
            Seat(null, "", "EASY", open = false), Seat(null, "Potter", "GREEDY", open = false)))
        assertEquals(listOf("Anne", "Blue", "Purple", "Potter"), OnlineSeats(room, "h").names)
    }

    @Test
    fun `online rematch (#72) keeps the bots' theme names and opens the people's seats again`() {
        val store = MemoryStore()
        val setup = GameSetup(name = "Mauro").withTheme(NameTheme.FRENCH_QUEENS).withSeat(1, SeatKind.FRIEND).withSeatName(2, "Marguerite")
        val ht = InMemoryTransport("h", store)
        LobbySession.host(ht, setup, 5, code = "ROVER-42").join()
        val erin = joinFirstOpenSeat(InMemoryTransport("e", store), GameSetup(name = "Erin"), "ROVER-42").join()
        val old = erin.client.view().room!!
        assertEquals(listOf("Mauro", "Erin", "Marguerite", "Jeanne"), old.seats.map { it.name })
        val again = LobbySession.rematch(ht, setup, old, 6, code = "OTTER-7").join()
        val room = joinFirstOpenSeat(InMemoryTransport("e", store), GameSetup(name = "Erin"), "OTTER-7").join().client.view().room!!
        assertEquals("ROVER-42", room.rematchOf)
        assertEquals(listOf("Mauro", "Erin", "Marguerite", "Jeanne"), room.seats.map { it.name }, "Erin back in her seat, the bots as they were")
        assertEquals(listOf(null, null, "Marguerite", "Jeanne"), again.model(room).seats.map { it.botName })
    }
}

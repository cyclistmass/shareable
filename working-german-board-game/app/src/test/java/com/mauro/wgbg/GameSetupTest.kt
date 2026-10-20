package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wgbg.engine.DiceDeck
import wgbg.engine.Expansion
import wgbg.engine.Rules
import wgbg.engine.TrueRandomDice
import kotlin.random.Random

/** The game-start settings (#3) and the lobby (#19): mapping to the engine, validation, and seat rules. */
class GameSetupTest {
    private val base = GameSetup(name = "Mauro")

    // ---------------------------------------------------------------- game setup

    @Test
    fun `defaults are four players, random dice, base game to 10`() {
        assertEquals(listOf(0, 1, 2, 3), base.players)
        assertTrue(base.canStart)
        val c = base.config()
        assertEquals(4, c.numPlayers)
        assertEquals(Expansion.BASE, c.expansion)
        assertEquals(10, c.vpToWin)
        assertTrue(base.dice() is TrueRandomDice)
    }

    @Test
    fun `empty seats drop out and the config counts only players`() {
        val s = base.withSeat(2, SeatKind.EMPTY)
        assertEquals(listOf(0, 1, 3), s.players)
        assertEquals(3, s.config().numPlayers)
    }

    @Test
    fun `seat 0 is always you`() {
        assertSame(base, base.withSeat(0, SeatKind.EMPTY))
        assertSame(base, base.withBot(0, BotLevel.EASY))
    }

    @Test
    fun `player counts outside the ruleset block start`() {
        val solo = base.withSeat(1, SeatKind.EMPTY).withSeat(2, SeatKind.EMPTY).withSeat(3, SeatKind.EMPTY)
        assertFalse(solo.canStart)
        assertTrue(solo.problems.single().contains("2-4"))
        val two = solo.withSeat(1, SeatKind.BOT)
        assertTrue(two.canStart)
        // Age of Chivalry needs at least 3.
        val chivalryTwo = two.withRuleset(Ruleset.CHIVALRY)
        assertFalse(chivalryTwo.canStart)
        assertTrue(chivalryTwo.problems.single().contains("3-4"))
    }

    @Test
    fun `a blank name blocks start`() {
        assertEquals(listOf("Enter your name"), base.copy(name = "  ").problems)
    }

    @Test
    fun `switching ruleset moves untouched points to its default but keeps custom points`() {
        assertEquals(13, base.withRuleset(Ruleset.CHIVALRY).points)
        assertEquals(10, base.withRuleset(Ruleset.CHIVALRY).withRuleset(Ruleset.BASE).points)
        assertEquals(12, base.withPoints(12).withRuleset(Ruleset.CHIVALRY).points)
        assertEquals(Expansion.CITIES_AND_KNIGHTS, base.withRuleset(Ruleset.CHIVALRY).config().expansion)
    }

    @Test
    fun `points and decks are clamped, and bad values constructed directly are reported`() {
        assertEquals(GameSetup.POINTS_RANGE.last, base.withPoints(99).points)
        assertEquals(GameSetup.POINTS_RANGE.first, base.withPoints(-3).points)
        assertEquals(GameSetup.MAX_DECKS, base.withDecks(9).decks)
        assertEquals(0, base.withDecks(-1).decks)
        assertFalse(base.copy(points = 50).canStart)
        assertFalse(base.copy(decks = 7).canStart)
    }

    @Test
    fun `deck dice hold 36 cards per deck`() {
        for (d in 1..GameSetup.MAX_DECKS) {
            val dice = base.withDecks(d).dice()
            assertTrue(dice is DiceDeck)
            assertEquals("${36 * d}-card deck", GameSetup.diceLabel(d))
        }
        assertEquals("Random", GameSetup.diceLabel(0))
    }

    @Test
    fun `every startable setup builds a playable engine game`() {
        // Every seat combination x ruleset x points: canStart must agree with the engine accepting it.
        val kinds = SeatKind.entries
        for (a in kinds) for (b in kinds) for (c in kinds) for (r in Ruleset.entries) {
            val s = base.withSeat(1, a).withSeat(2, b).withSeat(3, c).withRuleset(r)
            val engineOk = runCatching { Rules.newGame(s.config(), 1L) }.isSuccess
            assertEquals(engineOk, s.canStart, "seats $a $b $c, ${r.label}")
        }
    }

    @Test
    fun `the setup needs exactly four seats`() {
        assertThrows<IllegalArgumentException> { GameSetup(seats = listOf(SeatSetup(SeatKind.FRIEND))) }
    }

    // ---------------------------------------------------------------- lobby

    private val lobby = Lobby.host(base.withSeat(1, SeatKind.FRIEND).withSeat(2, SeatKind.FRIEND).withBot(3, BotLevel.EASY), code = "ROVER-42")

    @Test
    fun `hosting seats you, reserves the chosen bots and opens friend seats with a Greedy fallback`() {
        assertEquals("Mauro", lobby.seats[0].name)
        assertTrue(lobby.seats[1].open && lobby.seats[2].open)
        assertEquals(BotLevel.GREEDY, lobby.seats[1].bot)
        assertFalse(lobby.seats[3].open)
        assertEquals(BotLevel.EASY, lobby.seats[3].bot)
        assertEquals("Easy bot", lobby.seats[3].label)
        assertEquals("https://cyclistmass.github.io/shareable/join/#ROVER-42", lobby.link)
        assertEquals(1, lobby.taken)
    }

    @Test
    fun `an empty seat in the setup stays open with no bot`() {
        val l = Lobby.host(base.withSeat(2, SeatKind.EMPTY))
        assertTrue(l.seats[2].open)
        assertNull(l.seats[2].bot)
        assertFalse(l.seats[2].plays)
    }

    @Test
    fun `room codes read aloud cleanly and typed codes are forgiven`() {
        val r = Random(7)
        repeat(500) {
            val code = Lobby.newCode(r)
            assertTrue(Lobby.isValidCode(code), code)
        }
        assertEquals("ROVER-42", Lobby.normalizeCode(" rover 42 "))
        assertEquals("ROVER-42", Lobby.normalizeCode("rover42"))
        assertEquals("ROVER-42", Lobby.normalizeCode("Rover-42"))
        assertNull(Lobby.normalizeCode("ROVER-4"))
        assertNull(Lobby.normalizeCode("ROVER-04"))
        assertNull(Lobby.normalizeCode("BANANA-42")) // not one of our words
        assertNull(Lobby.normalizeCode(""))
        assertFalse(Lobby.isValidCode("rover-42"))
        // New rooms get three digits (#80); two-digit codes of older rooms still join.
        assertEquals("ROVER-427", Lobby.normalizeCode("rover 427"))
        assertNull(Lobby.normalizeCode("ROVER-4270"))
        assertNull(Lobby.normalizeCode("ROVER-042"))
        val r3 = kotlin.random.Random(5)
        assertTrue((1..500).all { Lobby.newCode(r3).substringAfter('-').length == 3 })
    }

    @Test
    fun `friends join open seats in order, never the reserved bot's, until the room is full`() {
        val one = lobby.join("Justin")!!
        assertEquals("Justin", one.seats[1].name)
        val two = one.join("Erin")!!
        assertEquals("Erin", two.seats[2].name)
        assertNull(two.join("Late")) // seat 3 is a reserved bot
        assertEquals(BotLevel.EASY, two.seats[3].bot)
        assertEquals(3, two.taken)
    }

    @Test
    fun `start waits until every person is ready`() {
        val full = lobby.join("Justin")!!
        assertFalse(full.canStart)
        // The host starts ready (they press Start); only the friend who joined is waited on.
        assertTrue(full.seats[0].ready)
        assertEquals(listOf("Justin isn't ready"), full.waitingOn)
        val ready = full.copy(seats = full.seats.map { if (it.isPerson) it.copy(ready = true) else it })
        assertTrue(ready.canStart)
        assertTrue(ready.waitingOn.isEmpty())
        assertFalse(ready.copy(me = 1).canStart) // a guest can't start
    }

    @Test
    fun `open seats with a bot play, empty open seats drop out, and the count must fit the ruleset`() {
        val host = lobby // the host is ready from the start
        assertTrue(host.canStart)
        assertEquals(listOf(0, 1, 2, 3), host.gameSetup().players) // seats 1-2 fall to Greedy bots
        val sparse = host.setBot(1, null).setBot(2, null)
        assertEquals(listOf(0, 3), sparse.gameSetup().players)
        assertTrue(sparse.canStart)
        val chivalry = sparse.copy(setup = sparse.setup.withRuleset(Ruleset.CHIVALRY))
        assertFalse(chivalry.canStart)
        assertTrue(chivalry.waitingOn.single().contains("3-4"))
    }

    @Test
    fun `only the host sets bots, never on a person, seat 0, or to nothing on a reserved seat`() {
        val withJustin = lobby.join("Justin")!!
        assertSame(withJustin, withJustin.setBot(1, BotLevel.STRONG)) // Justin's seat
        assertSame(withJustin, withJustin.setBot(0, BotLevel.STRONG))
        assertSame(withJustin, withJustin.setBot(3, null)) // reserved bot can change level, not vanish
        assertEquals(BotLevel.STRONG, withJustin.setBot(3, BotLevel.STRONG).seats[3].bot)
        assertEquals(BotLevel.EASY, withJustin.setBot(2, BotLevel.EASY).seats[2].bot)
        assertTrue(withJustin.setBot(2, BotLevel.EASY).seats[2].open) // still joinable
        val guest = withJustin.copy(me = 1)
        assertSame(guest, guest.setBot(2, BotLevel.EASY))
    }

    @Test
    fun `a person leaving reopens the seat`() {
        val l = lobby.join("Justin")!!.leave(1)
        assertTrue(l.seats[1].open)
        assertFalse(l.seats[1].isPerson)
        assertSame(l, l.leave(0)) // the host can't leave their own room this way
        assertSame(l, l.leave(3)) // a bot isn't a person
    }

    @Test
    fun `lobby seats become the game's friend, bot and empty seats`() {
        val g = lobby.join("Justin")!!.setBot(2, null).gameSetup()
        assertEquals(listOf(SeatKind.FRIEND, SeatKind.FRIEND, SeatKind.EMPTY, SeatKind.BOT), g.seats.map { it.kind })
        assertEquals(BotLevel.EASY, g.seats[3].bot)
    }

    @Test
    fun `ready toggles only for a seated person`() {
        assertTrue(lobby.seats[0].ready, "the host starts ready")
        assertFalse(lobby.toggleReady().seats[0].ready)
        assertTrue(lobby.toggleReady().toggleReady().seats[0].ready)
        val ghost = lobby.copy(me = 1) // seat 1 is open
        assertSame(ghost, ghost.toggleReady())
    }

    @Test
    fun `the stub lobby matches the Lobby A design`() {
        val s = Lobby.sample()
        assertEquals("ROVER-42", s.code)
        assertEquals(listOf("Mauro", "Erin", "Tiberiu"), s.seats.take(3).map { it.name })
        assertEquals(listOf(true, true, false), s.seats.take(3).map { it.ready })
        assertTrue(s.seats[3].open)
        assertEquals(BotLevel.GREEDY, s.seats[3].bot)
        assertEquals(3, s.taken)
        assertEquals("72-card deck", GameSetup.diceLabel(s.setup.decks))
        assertEquals(listOf("Tiberiu isn't ready"), s.waitingOn)
    }

    // ---------------------------------------------------------------- invite and QR

    @Test
    fun `the invite names the room, ruleset and link, and greets a recent player by name`() {
        val l = Lobby.sample()
        assertEquals("Join my game: room ROVER-42, Base game. https://cyclistmass.github.io/shareable/join/#ROVER-42", inviteText(l))
        assertEquals("Sam, join my game: room ROVER-42, Base game. https://cyclistmass.github.io/shareable/join/#ROVER-42", inviteText(l, "Sam"))
    }

    @Test
    fun `the QR code is square, has finder patterns, and differs per room`() {
        val a = qrMatrix(Lobby.sample().link)
        assertEquals(a.width, a.height)
        assertTrue(a.width in 21 + 2..41 + 2, "a short link fits a small QR version (got ${a.width})")
        // Top-left finder pattern: a dark 7x7 ring inside the 1-module margin.
        for (k in 0 until 7) {
            assertTrue(a[1 + k, 1] && a[1, 1 + k] && a[1 + k, 7] && a[7, 1 + k])
        }
        assertFalse(a[0, 0]) // quiet margin
        val b = qrMatrix(Lobby.sample().copy(code = "TIDE-17").link)
        assertFalse(a == b)
    }

    @Test
    fun `pickers offer no Strong until it plays differently, and a saved Strong shows as Greedy`() {
        assertEquals(listOf(BotLevel.EASY, BotLevel.GREEDY), BotLevel.offered)
        assertEquals(BotLevel.GREEDY, BotLevel.STRONG.plays)
        BotLevel.offered.forEach { assertSame(it, it.plays) }
        // Defaults: you, then three bots (a Friend seat is only ever chosen, never the default).
        assertTrue(base.seats.drop(1).all { it.kind == SeatKind.BOT })
    }
}

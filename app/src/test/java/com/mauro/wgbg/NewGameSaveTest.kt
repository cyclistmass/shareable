package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * The game-start screen saves every change as it's made (0.3.4, Mauro via Bosun: "New game saves on every change"), so
 * closing the app or going Back before Play keeps every choice. Before, only Play, Host, Join and Look & art saved.
 */
class NewGameSaveTest {
    /** The phone's preferences: one string that outlives the app. */
    private class Disk { var last: String? = null; var writes = 0 }
    private fun store(d: Disk) = SetupStore({ d.last }, { d.last = it; d.writes++ })

    /** Every change the game-start screen can make, as its buttons make it. */
    private fun change(s: GameSetup, r: Random): GameSetup = when (r.nextInt(10)) {
        0 -> s.copy(name = randomName(r).take(16))
        1 -> s.copy(photo = if (r.nextBoolean()) null else "content://media/external/images/${r.nextInt(1000)}?a=b|c")
        2 -> s.withSeat(1 + r.nextInt(GameSetup.MAX_SEATS - 1), SeatKind.entries.random(r))
        3 -> s.withBot(1 + r.nextInt(GameSetup.MAX_SEATS - 1), BotLevel.offered.random(r))
        4 -> s.withTheme(NameTheme.entries.random(r))
        5 -> if (s.theme.names.isEmpty()) s else s.withSeatName(1 + r.nextInt(GameSetup.MAX_SEATS - 1), s.theme.names.random(r).name)
        6 -> s.withRuleset(Ruleset.entries.random(r))
        7 -> s.withPoints(s.points + if (r.nextBoolean()) 1 else -1)
        8 -> s.withDecks(GameSetup.DICE_CHOICES.random(r))
        else -> s
    }
    private fun randomName(r: Random) = (0 until r.nextInt(0, 20)).map { "aZ é|:,%+\"ō你 ".random(r) }.joinToString("")

    /** What comes back of [s]: all of it, except that a blank name reads as "You" (as it always has). */
    private fun reopened(s: GameSetup) = s.copy(name = s.name.ifBlank { "You" })

    @Test
    fun `closing before Play brings back every choice`() {
        val d = Disk()
        val s = GameSetup(name = "Mauro", photo = "content://media/1").withRuleset(Ruleset.CHIVALRY).withPoints(11)
            .withDecks(3).withBot(1, BotLevel.EASY).withSeat(2, SeatKind.FRIEND).withSeat(3, SeatKind.EMPTY)
            .withTheme(NameTheme.FRENCH_QUEENS).withSeatName(1, "Marguerite")
        store(d).save(s)                       // each change, as the screen reports it
        assertEquals(s, store(d).load())       // the app closed and opened again: a fresh store over the same disk
        assertEquals("Marguerite", store(d).load()!!.seatNames[1])
    }

    @Test
    fun `nothing saved yet reads as no setup (the defaults)`() {
        assertNull(store(Disk()).load())
    }

    @Test
    fun `fuzz - random changes, closed at a random moment, every choice comes back`() {
        val r = Random(304)
        repeat(3000) { game ->
            val d = Disk()
            var s = GameSetup()
            repeat(r.nextInt(1, 40)) {
                s = change(s, r)
                store(d).save(s)
                // Closed now (before Play, or Back to the start screen): the next New game is exactly this.
                if (r.nextInt(5) == 0) assertEquals(reopened(s), store(d).load(), "game $game")
            }
            val back = store(d).load()!!
            assertEquals(reopened(s), back, "game $game")
            assertEquals(reopened(s).seatNames, back.seatNames, "game $game")
            assertEquals(back, GameSetup.decode(back.encode()), "game $game: stable once reopened")
        }
    }

    @Test
    fun `the screen reports every change and the app saves each one`() {
        val src = File("src/main/java/com/mauro/wgbg")
        fun text(name: String) = File(src, name).readText().replace("\r\n", "\n")
        val screen = text("NewGameScreen.kt")
        assertTrue("snapshotFlow { setup }.drop(1).collect { onChange(it) }" in screen, "NewGameScreen reports changes")
        val main = text("MainActivity.kt")
        assertTrue("onChange = { s -> setup = s; setupStore.save(s) }" in main, "MainActivity saves each change")
        assertTrue("setupStore.load()" in main, "MainActivity starts from the saved setup")
        // One place to save: no write to the preferences around the store.
        assertEquals(false, "putString(\"last\"" in main, "MainActivity writes the setup only through SetupStore")
    }
}

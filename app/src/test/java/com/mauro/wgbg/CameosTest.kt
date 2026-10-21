package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Player cameos (0.3.5): which seat shows which portrait, picks and swaps, saving, and the art behind them. */
class CameosTest {
    private val fq = GameSetup(name = "Mauro").withTheme(NameTheme.FRENCH_QUEENS)

    // ---------------------------------------------------------------- the art

    @Test
    fun `every painted cameo has its drawable and every cameo drawable is listed, and they are credited`() {
        val files = File("src/main/res/drawable-nodpi").list()!!.filter { it.startsWith("cameo_") }
        assertEquals(Cameos.PAINTED, files.map { it.removePrefix("cameo_").removeSuffix(".webp") }.toSet())
        assertTrue(files.all { it.endsWith(".webp") }, "$files")
        assertTrue("cameo_*.webp" in File("../CREDITS.md").readText())
    }

    @Test
    fun `every theme name has its own drawable-safe id, accents and spaces folded`() {
        val ids = NameTheme.entries.flatMap { t -> t.names.map { Cameos.forName(t, it.name)!! } }
        assertEquals(ids.size, ids.toSet().size, "French and English Anne and Catherine stay apart")
        assertTrue(ids.all { it.matches(Regex("[a-z0-9_]+")) }, "$ids")
        assertEquals("alienor", Cameos.slug("Aliénor")); assertEquals("jito", Cameos.slug("Jitō"))
        assertEquals("elizabeth_i", Cameos.slug("Elizabeth I")); assertEquals("go_daigo", Cameos.slug("Go-Daigo"))
        assertNull(Cameos.forName(NameTheme.COLOURS, "Red")); assertNull(Cameos.forName(NameTheme.FRENCH_QUEENS, "Victoria"))
        assertEquals(Cameos.PAINTED, Cameos.all.map { it.id }.toSet(), "the picker shows exactly the painted ones")
    }

    // ---------------------------------------------------------------- who shows what

    @Test
    fun `bots show their name's cameo, you your photo or initial, and names without art their initial`() {
        assertEquals(listOf(null, "fq_alienor", "fq_blanche", "fq_jeanne"), fq.seatCameos)
        assertEquals(listOf(null, "fq_alienor", "fq_blanche", "fq_jeanne"), fq.playerCameos("Mauro"))
        assertEquals(List(4) { null }, GameSetup().seatCameos, "Colours: initials, as before")
        assertEquals(List(4) { null }, GameSetup().withTheme(NameTheme.ENGLISH_QUEENS).seatCameos, "not painted yet")
    }

    @Test
    fun `in Watch mode the bot in your seat shows its name's cameo too`() {
        assertEquals(listOf("fq_alienor", "fq_blanche", "fq_jeanne", "fq_clemence"), fq.playerCameos(null))
    }

    @Test
    fun `empty seats show nothing and drop out of the game's list`() {
        val s = fq.withSeat(2, SeatKind.EMPTY)
        assertEquals(listOf(null, "fq_alienor", null, "fq_blanche"), s.seatCameos)
        assertEquals(listOf(null, "fq_alienor", "fq_blanche"), s.playerCameos("Mauro"))
    }

    // ---------------------------------------------------------------- picking

    @Test
    fun `picking another seat's cameo swaps the two and leaves the rest alone`() {
        val s = fq.withSeatCameo(1, "fq_jeanne")
        assertEquals(listOf(null, "fq_jeanne", "fq_blanche", "fq_alienor"), s.seatCameos)
        val you = fq.withSeatCameo(0, "fox").withSeatCameo(2, "fox")
        assertEquals(listOf("fq_blanche", "fq_alienor", "fox", "fq_jeanne"), you.seatCameos, "you swap too")
    }

    @Test
    fun `match the name follows a later name pick, a picked cameo stays put`() {
        assertEquals("fq_marie", fq.withSeatName(1, "Marie").seatCameos[1])
        val owl = fq.withSeatCameo(1, "owl")
        assertEquals("owl", owl.withSeatName(1, "Louise").seatCameos[1])
        assertEquals("fq_louise", owl.withSeatName(1, "Louise").withSeatCameo(1, null).seatCameos[1], "back to the name")
        val fox = fq.withSeatCameo(0, "fox")
        assertEquals("fox", fox.seatCameos[0])
        assertNull(fox.withSeatCameo(0, null).seatCameos[0], "you go back to your photo or initial")
    }

    @Test
    fun `a new theme drops the old theme's cameos but keeps yours and the others`() {
        val s = fq.withSeatCameo(0, "fq_marie").withSeatCameo(1, "owl").withSeatCameo(2, "fq_eugenie")
        val t = s.withTheme(NameTheme.OLD_TRADES)
        assertEquals(listOf("fq_marie", "owl", null, null), t.seatCameos)
        assertEquals(listOf("fq_marie", "owl", null, null), t.seats.map { it.cameo })
    }

    @Test
    fun `cameos resolve for any number of seats (six-player games), a lost pick falling back to the name`() {
        val six = Cameos.resolve(listOf(null, "fq_marie", null, "fq_marie", "fox", null),
            listOf(null, "Aliénor", "Blanche", "Marie", "Jeanne", null), NameTheme.FRENCH_QUEENS, listOf(false) + List(5) { true })
        assertEquals(listOf(null, "fq_marie", "fq_blanche", null, "fox", null), six)
    }

    // ---------------------------------------------------------------- saving

    @Test
    fun `cameos survive close and reopen (v4), and older saves read with no picks`() {
        val s = fq.withSeatCameo(0, "fox").withSeatCameo(1, "fq_marie").withSeat(3, SeatKind.EMPTY)
        assertTrue(s.encode().startsWith("v4|"))
        val back = GameSetup.decode(s.encode())!!
        assertEquals(s, back); assertEquals(s.seatCameos, back.seatCameos)
        val v3 = GameSetup.decode("v3|Mauro||BASE|10|0|FRIEND:GREEDY::,BOT:GREEDY::,BOT:GREEDY::,BOT:GREEDY::|FRENCH_QUEENS|")!!
        assertEquals(listOf(null, "fq_alienor", "fq_blanche", "fq_jeanne"), v3.seatCameos)
        val game = LocalSave.decode(LocalSave(s, 42L, emptyList()).encode())!!
        assertEquals(s.playerCameos("Mauro"), game.setup.playerCameos("Mauro"), "a saved game resumes with its cameos")
    }

    // ---------------------------------------------------------------- break it

    @Test
    fun `an unknown cameo, an empty seat or a seat that doesn't exist changes nothing`() {
        val s = fq.withSeat(2, SeatKind.EMPTY)
        for (bad in listOf(s.withSeatCameo(1, "nope"), s.withSeatCameo(1, "eq_victoria"), s.withSeatCameo(2, "fox"),
            s.withSeatCameo(-1, "fox"), s.withSeatCameo(GameSetup.MAX_SEATS, "fox"))) assertSame(s, bad)
    }

    @Test
    fun `a saved cameo with no art (removed, or garbage) reads as none, and a damaged seat field as no save`() {
        val good = fq.withSeatCameo(1, "owl").encode()
        assertEquals("fq_alienor", GameSetup.decode(good.replace(":owl", ":zzz"))!!.seatCameos[1])
        assertNull(GameSetup.decode(good.replace(":owl", ":owl:extra")))
        assertNull(GameSetup.decode(good.replace("v4|", "v3|")), "a v3 line has four parts per seat")
    }
}

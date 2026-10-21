package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ChivalryActionType
import wgbg.engine.Expansion
import wgbg.engine.Phase
import wgbg.net.MoveCodec
import wgbg.net.Room
import wgbg.net.Seat
import wgbg.net.Status

/**
 * #84 renamed the Cities and Knights identifiers to Chivalry. What 0.3.3 wrote must still read, and what this version
 * writes must read the same in 0.3.3: the move "1:CK_DONE:n" and the room option "CITIES_AND_KNIGHTS" keep their names.
 */
class ChivalryRenameTest {
    @Test
    fun `the done move keeps its old name on the wire, and both names read`() {
        val done = Action.of(ChivalryActionType.CHIVALRY_DONE, 0)
        assertEquals("1:CK_DONE:0", MoveCodec.encode(done))
        assertEquals(done, MoveCodec.decode("1:CK_DONE:0"))
        assertEquals(done, MoveCodec.decode("1:CHIVALRY_DONE:0"))
        // Every kind, with any payload, comes back as itself.
        for (k in wgbg.engine.ActionType.entries + ChivalryActionType.entries) {
            val a = Action.of(k, 7)
            assertEquals(a, MoveCodec.decode(MoveCodec.encode(a)), "$k")
        }
        for (bad in listOf("1:CK_DONE", "1:CK_DONE:-1", "2:CK_DONE:0", "1:ck_done:0", "1:CITIES_AND_KNIGHTS:0", "1::0"))
            assertNull(MoveCodec.decode(bad), bad)
    }

    @Test
    fun `a game saved by 0_3_3 (a player's report, with CK_DONE) loads, replays and saves its moves the same`() {
        for (name in listOf("hoist", "grandfactor")) {
            val text = javaClass.getResource("/reports/$name.save")!!.readText()
            assertTrue("CK_DONE" in text, name)
            val save = LocalSave.decode(text)
            assertNotNull(save, name)
            assertEquals(Ruleset.CHIVALRY, save!!.setup.ruleset)
            assertTrue(save.moves.any { it.type == ChivalryActionType.CHIVALRY_DONE }, name)
            assertNotNull(save.replay()?.chivalry, "$name replays to the end, an Age of Chivalry game")
            val moves = text.trimEnd().split("\n").map { it.trimEnd('\r') }.drop(3).filter { it.isNotEmpty() }
            assertEquals(moves, save.encode().split("\n").drop(3), "$name: every move written as 0.3.3 wrote it")
        }
    }

    @Test
    fun `a room's options as 0_3_3 wrote them read as Age of Chivalry, and this version writes the same name`() {
        val r = Room("ROVER-42", "h", GameSetup().withRuleset(Ruleset.CHIVALRY).config(), 9,
            listOf(Seat("h", "Anne"), Seat(), Seat(null, "", "EASY", open = false), Seat(null, "", "EASY", open = false)), Status.playing)
        val wire = r.wire()
        assertEquals("CITIES_AND_KNIGHTS", (wire["options"] as Map<*, *>)["expansion"])
        assertEquals(Expansion.CHIVALRY, room(r.id, wire).config.expansion)
        @Suppress("UNCHECKED_CAST")
        val renamed = wire.toMutableMap().also { w -> w["options"] = (w["options"] as Map<String, Any?>) + ("expansion" to "CHIVALRY") }
        assertEquals(Expansion.CHIVALRY, room(r.id, renamed).config.expansion, "the new name reads too")
        assertEquals(Expansion.BASE, room(r.id, r.copy(config = GameSetup().config()).wire()).config.expansion)
        assertEquals("BASE", Expansion.BASE.wire)
    }

    @Test
    fun `a New game setup saved by 0_3_3 (v2, Age of Chivalry) still reads`() {
        val s = GameSetup.decode("v2|Mauro||CHIVALRY|13|2|FRIEND:GREEDY:,BOT:EASY:,BOT:GREEDY:,BOT:GREEDY:|COLOURS")!!
        assertEquals(Ruleset.CHIVALRY, s.ruleset); assertEquals(Expansion.CHIVALRY, s.config().expansion)
        assertEquals(Phase.BUILD_INITIAL_SETTLEMENT, wgbg.engine.Game(s.config(), 1L).state.phase)
        assertNotNull(wgbg.engine.Game(s.config(), 1L).state.chivalry)
    }
}

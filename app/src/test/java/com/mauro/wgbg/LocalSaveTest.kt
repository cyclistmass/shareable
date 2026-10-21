package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wgbg.ai.GreedyBot
import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.GameState
import wgbg.engine.Rules
import java.io.File

/** Local games survive closing the app or an update (#82): setup + seed + moves replay to the same game. */
class LocalSaveTest {
    private val chivalry = GameSetup().withRuleset(Ruleset.CHIVALRY)

    /** Plays [moves] bot moves (or to the end), returning the game and every move made. */
    private fun play(setup: GameSetup, seed: Long, moves: Int): Pair<GameState, List<Action>> {
        val s = Rules.newGame(setup.config(), seed)
        val bots = List(setup.players.size) { GreedyBot(seed * 31 + it) }
        val buf = ActionBuffer(); val made = ArrayList<Action>()
        while (!s.isOver && made.size < moves) {
            Rules.legalActions(s, buf)
            val a = bots[s.actingPlayer].choose(s, buf)
            Rules.apply(s, a); made += a
        }
        return s to made
    }

    @Test
    fun `a saved game replays to exactly the same game, mid-way, in both rulesets`() {
        for ((setup, seed) in listOf(GameSetup() to 7L, chivalry to 11L, chivalry.copy(name = "Mauro | 100%") to 3L)) {
            val (live, moves) = play(setup, seed, 300)
            val back = LocalSave.decode(LocalSave(setup, seed, moves).encode())
            assertNotNull(back)
            assertEquals(setup, back!!.setup)
            assertEquals(moves, back.moves)
            assertEquals(live.stateHash(), back.replay()!!.stateHash())
            assertTrue(back.resumable)
        }
    }

    @Test
    fun `a resumed game's tracker shows the same last roll and hits as the live one (cold reopen)`() {
        for ((setup, seed) in listOf(GameSetup() to 7L, chivalry to 11L, chivalry to 4L)) {
            val names = List(setup.players.size) { "P$it" }
            val s = Rules.newGame(setup.config(), seed)
            val live = TurnTracker(names)
            val bots = List(setup.players.size) { GreedyBot(seed * 31 + it) }
            val dice = wgbg.engine.TrueRandomDice()
            val buf = ActionBuffer(); val made = ArrayList<Action>()
            while (!s.isOver && made.size < 400) {
                Rules.legalActions(s, buf)
                var a = bots[s.actingPlayer].choose(s, buf)
                if (a.type == ActionType.ROLL) a = dice.next(withEvent = s.chivalry != null).toAction()
                val who = Rules.mover(s, a) // as the game loop does
                live.apply(s, who, a) { Rules.apply(s, a) }; made += a
            }
            val back = TurnTracker(names)
            val state = Rules.newGame(setup.config(), seed)
            LocalSave.decode(LocalSave(setup, seed, made).encode())!!.replay(state) { who, a, applyIt -> back.apply(state, who, a, applyIt) }
            assertEquals(s.stateHash(), state.stateHash())
            assertEquals(live.current?.let { listOf(it.player, it.turn, it.die1, it.die2, it.event) }, back.current?.let { listOf(it.player, it.turn, it.die1, it.die2, it.event) }, "seed $seed")
            assertEquals(live.last?.let { listOf(it.player, it.turn, it.die1, it.die2, it.event) }, back.last?.let { listOf(it.player, it.turn, it.die1, it.die2, it.event) }, "seed $seed")
            assertEquals(live.hits, back.hits, "seed $seed")
        }
    }

    @Test
    fun `replaying hands each move to the caller, who applies it (the history is rebuilt this way)`() {
        val (_, moves) = play(chivalry, 5L, 120)
        val seen = ArrayList<Action>()
        LocalSave(chivalry, 5L, moves).replay { _, a, applyIt -> seen += a; applyIt() }
        assertEquals(moves, seen)
    }

    @Test
    fun `a finished game is not offered for resuming`() {
        val (end, moves) = play(GameSetup(), 9L, 100_000)
        assertTrue(end.isOver)
        assertFalse(LocalSave(GameSetup(), 9L, moves).resumable)
    }

    @Test
    fun `damaged, foreign or out-of-date saves are refused, never half-loaded`() {
        val (_, moves) = play(GameSetup(), 4L, 60)
        val good = LocalSave(GameSetup(), 4L, moves).encode()
        assertNull(LocalSave.decode(null))
        assertNull(LocalSave.decode(""))
        assertNull(LocalSave.decode(good.replaceFirst(LocalSave.HEADER, "wgbg-local v9")))
        assertNull(LocalSave.decode(good.replace("\n4\n", "\nfour\n")))
        assertNull(LocalSave.decode(good + "\nnot a move"))
        // A move that isn't legal where it stands (the save and the rules disagree): no game, not a wrong one.
        val bad = LocalSave(GameSetup(), 4L, moves + Action.of(ActionType.BUILD_CITY, 999L))
        assertNull(bad.replay())
        assertFalse(bad.resumable)
        // Windows line endings (a file copied around) still read.
        assertNotNull(LocalSave.decode(good.replace("\n", "\r\n")))
    }

    @Test
    fun `the store saves move by move, loads the game back, and forgets it when cleared`(@TempDir dir: File) {
        val store = LocalSaveStore(File(dir, "local-game.txt"))
        assertNull(store.load())
        val (live, moves) = play(chivalry, 21L, 200)
        store.start(chivalry, 21L)
        for (a in moves) store.add(a)
        val back = store.load()
        assertNotNull(back)
        assertEquals(live.stateHash(), back!!.replay()!!.stateHash())
        // A new game replaces the old one.
        store.start(GameSetup(), 22L)
        assertEquals(22L, store.load()?.seed)
        store.clear()
        assertNull(store.load())
        // Moves without a started game are ignored, not saved as a broken file.
        store.add(moves[0])
        assertNull(store.load())
    }

    @Test
    fun `#126 new games start from the seed's seat, and a save from before #126 still replays with seat 0 first`() {
        val seed = (1L..100L).first { Rules.firstPlayer(it, 4) != 0 }
        val (live, moves) = play(GameSetup(), seed, 40)
        assertEquals(Rules.firstPlayer(seed, 4), live.firstPlayer)
        val text = LocalSave(GameSetup(), seed, moves).encode()
        assertTrue(text.startsWith(LocalSave.HEADER + "\n"))
        assertEquals(live.stateHash(), LocalSave.decode(text)!!.replay()!!.stateHash())
        // A 0.2.9 save ("v1", seat 0 started) comes back as that game, and saves again as v1.
        val old = GameSetup(randomFirst = false)
        val (oldLive, oldMoves) = play(old, seed, 40)
        assertEquals(0, oldLive.firstPlayer)
        val oldText = LocalSave(old, seed, oldMoves).encode()
        assertTrue(oldText.startsWith(LocalSave.HEADER_V1 + "\n"))
        val back = LocalSave.decode(oldText)!!
        assertFalse(back.setup.randomFirst)
        assertEquals(oldLive.stateHash(), back.replay()!!.stateHash())
        assertEquals(oldText, back.encode())
    }
}

package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #55, the regression dice stack: six whole games (base with 2, 3 and 4 players and a short one, Age of Chivalry with 3 and 4) on
 * fixed seeds, the moves picked by a seeded bot and the dice from a scripted stack that drives them into the rare
 * corners random dice seldom reach: three 7s before anyone holds a card, a run of one number until the bank runs out
 * of what it pays, then a 7 with hands to halve; the Raiders' ship on the first seven event rolls (they land before any
 * city), then gate after gate. After that the dice come from a fixed sequence, so every game is the same every run.
 *
 * Each game's last position is pinned (state hash, turn, winner) in regression-stack.tsv: any change to the rules,
 * the board, the bots' legal moves or the dice handling shows here as a changed game. A deliberate change: run with
 * UPDATE_FIXTURES=1 and say in the commit why the games changed. The stack must also reach every rare state below.
 */
class RegressionStackTest {
    private companion object { const val FILL = 40 }

    private data class Stack(val name: String, val config: GameConfig, val seed: Long)

    private val stacks = listOf(
        Stack("base-2", GameConfig(numPlayers = 2, maxTurns = 400), 5501),
        Stack("base-3", GameConfig(numPlayers = 3, maxTurns = 400, randomDiscard = true), 5502),
        Stack("base-4", GameConfig(numPlayers = 4, maxTurns = 400, generateTradeOffers = true), 5503),
        // A short game, so one game of the stack ends at the turn limit with no winner.
        Stack("base-4-limit", GameConfig(numPlayers = 4, maxTurns = 60, layout = BoardLayout.BEGINNER), 5504),
        Stack("chivalry-3", GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3, maxTurns = 400), 5505),
        Stack("chivalry-4", GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 4, maxTurns = 400), 5506),
    )

    /**
     * The scripted opening: three 7s while hands are still small; then [FILL] rolls of the number that pays the most
     * of one resource on this board (read when the 7s are done, so wherever the bots built and the Bandit stands), to
     * run the bank out of it; then a 7 with full hands to halve. In Age of Chivalry the event die shows the ship on the
     * first seven rolls (the Raiders land before anyone has a city), then each gate in turn. Then [then].
     */
    private class StackDice(private val s: GameState, private val then: DiceSource) : DiceSource {
        private val sevens = ScriptedDice.parse("3,4 4,3 2,5")
        private val halve = ScriptedDice.parse("1,6")
        private val scripted = sevens.size + FILL + halve.size
        private var stack: ScriptedDice? = null
        private var thrown = 0

        /** Scripted rolls still to come. */
        val left get() = scripted - thrown.coerceAtMost(scripted)

        override fun next(withEvent: Boolean): DiceRoll {
            val i = thrown++
            if (i >= scripted) return then.next(withEvent)
            val r = if (i < sevens.size) sevens[i]
                else (stack ?: ScriptedDice(List(FILL) { ScriptedDice.sum(drainNumber()) } + halve).also { stack = it }).next()
            return when {
                !withEvent -> r
                i < 7 -> r.copy(event = i % EventDie.SHIP_FACES)   // faces 0..2 are the ship
                else -> r.copy(event = EventDie.SHIP_FACES + (i - 7) % Track.COUNT)
            }
        }

        /** The number whose hexes pay the most cards of a single resource right now (the lowest on a tie). */
        private fun drainNumber(): Int {
            val b = s.board
            var best = 6
            var most = -1
            for (n in 2..12) {
                if (n == 7) continue
                val pays = IntArray(Resource.COUNT)
                for (h in b.hexesByNumber[n]) {
                    val r = b.hexResource[h]
                    if (h == s.robberHex || r !in 0 until Resource.COUNT) continue
                    for (i in 0 until 6) {
                        val v = b.hexVerts[h * 6 + i]
                        if (s.vertexOwner[v] >= 0) pays[r] += if (s.vertexBuilding[v] == Piece.CITY) 2 else 1
                    }
                }
                if (pays.max() > most) { most = pays.max(); best = n }
            }
            return best
        }
    }

    /** After the opening: a fixed sequence, the engine's own generator on a fixed seed (never the system's). */
    private fun rest(seed: Long) = object : DiceSource {
        private val r = Rng(seed)
        override fun next(withEvent: Boolean) = DiceRoll(r.nextInt(6) + 1, r.nextInt(6) + 1, if (withEvent) r.nextInt(6) else -1)
    }

    private fun play(st: Stack, coverage: Coverage, game: Int): GameState {
        val chivalry = st.config.expansion == Expansion.CHIVALRY
        val s = Rules.newGame(st.config, st.seed)
        val dice = StackDice(s, rest(st.seed * 31))
        val bot = Rng(st.seed + 1)
        val chooser = ChivalryFuzzTest()
        val buf = ActionBuffer()
        var steps = 0
        while (!s.isOver) {
            Rules.legalActions(s, buf)
            assertTrue(buf.size > 0, "${st.name}: no legal action in ${s.phase}")
            var a = if (chivalry) chooser.pick(s, buf, bot) else buf[bot.nextInt(buf.size)]
            if (a == Action.ROLL) a = dice.next(chivalry).toAction()
            val attacks0 = s.chivalry?.barbarianAttacks ?: 0
            val defenders0 = s.chivalry?.defenderVp?.sum() ?: 0
            Rules.applyChecked(s, a)
            if (chivalry) CK.checkInvariants(s) else T.checkInvariants(s)
            Coverage.base(coverage, game, s, a)
            if (!chivalry) coverage.note("a 2-player game won", game, s.isOver && s.winner >= 0 && s.numPlayers == 2)
            if (chivalry) chooser.raiders(coverage, game, s, s.chivalry!!.barbarianAttacks > attacks0, s.chivalry!!.defenderVp.sum() > defenders0)
            steps++
            assertTrue(steps < 300_000, "${st.name} does not terminate")
        }
        assertEquals(0, dice.left, "${st.name}: the game ended before the whole scripted opening was rolled")
        return s
    }

    @Test
    fun `the regression stack plays the same six games, through every rare state`() {
        val coverage = Coverage("regression stack")
        val got = stacks.mapIndexed { i, st ->
            val s = play(st, coverage, i)
            "${st.name}\t${s.stateHash()}\t${s.turn}\t${s.winner}"
        }
        coverage.print()
        // Dice alone can't build Capitals or commodity cities: random play reaches these (ChivalryFuzzTest).
        coverage.assertAll(elsewhere = mapOf(
            "the Raiders win, every city a Capital (none to take)" to "ChivalryFuzzTest",
            "a commodity ran out in the bank" to "ChivalryFuzzTest",
        ))
        val file = File("src/test/resources/regression-stack.tsv")
        val text = "# game\tstate hash\tturn\twinner (-1: turn limit). Regenerate with UPDATE_FIXTURES=1 (RegressionStackTest).\n" +
            got.joinToString("\n") + "\n"
        if (System.getenv("UPDATE_FIXTURES") != null) file.writeText(text)
        assertEquals(file.readText().replace("\r", ""), text,
            "A game of the regression stack changed. If the rules, board or dice handling changed on purpose, run with " +
                "UPDATE_FIXTURES=1 and say why in the commit.")
    }

    @Test
    fun `scripted dice play in order, then fall back, and never go random on their own`() {
        val d = ScriptedDice(ScriptedDice.parse("6,6 1,2,4 3,4"))
        assertEquals(DiceRoll(6, 6), d.next())
        assertEquals(DiceRoll(1, 2, -1), d.next(), "no event die asked for: the face is dropped")
        assertEquals(DiceRoll(3, 4), d.next(withEvent = true), "no face scripted: the engine rolls the event die")
        assertEquals(0, d.left)
        assertTrue(runCatching { d.next() }.isFailure, "ran out with nothing after it")
        val then = ScriptedDice(ScriptedDice.parse("1,6,2"), ScriptedDice(listOf(DiceRoll(2, 2))))
        assertEquals(DiceRoll(1, 6, 2), then.next(withEvent = true)); assertEquals(DiceRoll(2, 2), then.next())
        for (sum in 2..12) assertEquals(sum, ScriptedDice.sum(sum).sum)
        for (bad in listOf("7,1", "1", "1,2,6", "a,b", "1,2,3,4")) assertTrue(runCatching { ScriptedDice.parse(bad) }.isFailure, bad)
        // The dice ride in the move: the engine plays exactly them and leaves its generator alone.
        val s = Rules.newGame(GameConfig(), 1)
        T.randomSetup(s, Rng(2))
        val seed = s.rng.seed
        Rules.applyChecked(s, DiceRoll(5, 6).toAction())
        assertEquals(11, s.lastRoll); assertEquals(seed, s.rng.seed)
    }
}

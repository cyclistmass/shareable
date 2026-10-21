package wgbg.engine

import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Which rare game states a run of random games reached (Fable's study, Phase A): the fuzzers check every invariant
 * after every move, but only that each action kind was played, not that the games ever got into the corners where
 * rules bugs hide. Each state is counted, with the first game that reached it, so a missing one fails by name and a
 * found one can be replayed from its seed.
 */
class Coverage(private val run: String) {
    /** Games that reached each state (a game counts once however long it stays there). */
    private val counts = LinkedHashMap<String, Int>()
    private val first = HashMap<String, Int>()
    private val last = HashMap<String, Int>()

    fun note(state: String, game: Int, reached: Boolean) {
        counts.putIfAbsent(state, 0)
        if (!reached || last[state] == game) return
        last[state] = game
        counts.merge(state, 1, Int::plus)
        first.putIfAbsent(state, game)
    }

    fun print() = println("$run coverage, games reaching each state: " +
        counts.entries.joinToString { (k, v) -> "$k=$v" + (first[k]?.let { " (first: game $it)" } ?: "") })

    /** Every state noted was reached at least once, but those in [elsewhere] (state to where it is covered instead). */
    fun assertAll(elsewhere: Map<String, String> = emptyMap()) {
        for ((k, v) in counts) assertTrue(v > 0 || k in elsewhere, "$run never reached: $k")
        for (k in elsewhere.keys) assertTrue(k in counts, "$run: no state called '$k'")
    }

    companion object {
        /** States of the base rules, after move [a] in [s] (both rulesets share them). */
        fun base(c: Coverage, game: Int, s: GameState, a: Action) {
            val seven = a.type == ActionType.ROLL && s.lastRoll == 7
            val owing = (0 until s.numPlayers).filter { s.discardOwed[it] > 0 }
            c.note("a resource ran out in the bank", game, (0 until Resource.COUNT).any { s.bank[it] == 0 })
            c.note("a 7 with nobody over the limit", game, seven && owing.isEmpty())
            c.note("a 7 with an odd hand to halve", game, seven && owing.any { s.handSize(it) % 2 == 1 })
            c.note("a 7 with two players discarding", game, seven && owing.size >= 2)
            c.note("a player out of roads", game, (0 until s.numPlayers).any { s.piecesLeft[it * Piece.COUNT + Piece.ROAD] == 0 })
            c.note("a player out of homesteads", game, (0 until s.numPlayers).any { s.piecesLeft[it * Piece.COUNT + Piece.SETTLEMENT] == 0 })
            c.note("a game over at the turn limit, no winner", game, s.isOver && s.winner < 0)
        }
    }
}

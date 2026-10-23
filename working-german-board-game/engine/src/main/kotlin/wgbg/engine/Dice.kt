package wgbg.engine

import java.security.SecureRandom

/** One throw: two production dice, plus the Age of Chivalry event die face (0..5) when it's used. */
data class DiceRoll(val d1: Int, val d2: Int, val event: Int = -1) {
    val sum get() = d1 + d2

    /** The ROLL action that records exactly these dice in the game log. */
    fun toAction() = Action.roll(d1, d2, event)
}

/** Where real games get their dice. AI lookahead keeps using the engine's fast seeded RNG instead. */
interface DiceSource {
    fun next(withEvent: Boolean = false): DiceRoll
}

/** RANDOM: true-random dice. DECK: pre-rolled stack of 1-4 decks of 36 (see [DiceDeck]). */
enum class DiceMode { RANDOM, DECK }

/**
 * Truly random dice from the operating system's cryptographic generator: on Android and Linux
 * the kernel CSPRNG, continuously reseeded from hardware noise (interrupt timing, CPU jitter,
 * and hardware RNGs where present). Nothing about past rolls predicts the next one, and
 * [SecureRandom.nextInt] uses rejection sampling, so each face has exactly 1/6 odds.
 */
class TrueRandomDice(private val rng: SecureRandom = SecureRandom()) : DiceSource {
    override fun next(withEvent: Boolean) =
        DiceRoll(rng.nextInt(6) + 1, rng.nextInt(6) + 1, if (withEvent) rng.nextInt(6) else -1)
}

/**
 * Pre-rolled dice deck: [decks] copies of the 36 cards (one per combination of the two dice),
 * shuffled together with the same secure generator. Over a full stack every sum comes up
 * exactly as often as the odds say (per deck: one 2, two 3s, ... six 7s ... one 12), so no long
 * droughts. A deeper stack (72, 108, 144 cards) still evens out but allows streaks within it.
 * The stack is reshuffled when only [reshuffleAt] cards remain, so the last few cards can't be
 * counted. The event die (Age of Chivalry) is not part of the deck; it stays truly random.
 */
class DiceDeck(
    val decks: Int = 1,
    private val reshuffleAt: Int = 5,
    private val rng: SecureRandom = SecureRandom(),
) : DiceSource {
    private val size = 36 * decks
    private val cards = IntArray(size) { it % 36 } // card c = dice (c / 6 + 1, c % 6 + 1)
    private var next = size

    /** Cards left before the next reshuffle (for the UI). */
    val remaining get() = (size - next - reshuffleAt).coerceAtLeast(0)

    init { require(decks in 1..8 && reshuffleAt in 0 until size) }

    override fun next(withEvent: Boolean): DiceRoll {
        if (size - next <= reshuffleAt) shuffle()
        val c = cards[next++]
        return DiceRoll(c / 6 + 1, c % 6 + 1, if (withEvent) rng.nextInt(6) else -1)
    }

    private fun shuffle() {
        for (i in size - 1 downTo 1) { // Fisher-Yates
            val j = rng.nextInt(i + 1)
            val t = cards[i]; cards[i] = cards[j]; cards[j] = t
        }
        next = 0
    }

    companion object {
        /**
         * The next card of a deck known only by its draws so far ([drawn], cards 0..35 as
         * `(d1 - 1) * 6 + (d2 - 1)`, oldest first), for play-by-file games that keep no hidden state.
         * Picking uniformly among the cards still in the stack deals exactly like a shuffled deck,
         * and nothing in the log predicts the next card. Reshuffles on the same schedule as [next].
         */
        fun nextFromLog(drawn: List<Int>, decks: Int, reshuffleAt: Int = 5, rng: SecureRandom = SecureRandom()): DiceRoll {
            val left = leftInStack(drawn, decks, reshuffleAt)
            var k = rng.nextInt(left.sum())
            var c = 0
            while (k >= left[c]) { k -= left[c]; c++ }
            return DiceRoll(c / 6 + 1, c % 6 + 1)
        }

        /** How many of each card (0..35) remain in the stack after [drawn]; a full stack after a reshuffle. */
        fun leftInStack(drawn: List<Int>, decks: Int, reshuffleAt: Int = 5): IntArray {
            require(decks in 1..8 && reshuffleAt in 0 until 36 * decks)
            val perStack = 36 * decks - reshuffleAt
            val left = IntArray(36) { decks }
            for (c in drawn.takeLast(drawn.size % perStack)) left[c]--
            require(left.all { it >= 0 }) { "the log draws a combination more often than the deck holds it" }
            return left
        }

        fun cardOf(d1: Int, d2: Int) = (d1 - 1) * 6 + (d2 - 1)

        fun of(mode: DiceMode, decks: Int = 1): DiceSource = when (mode) {
            DiceMode.RANDOM -> TrueRandomDice()
            DiceMode.DECK -> DiceDeck(decks)
        }
    }
}

/**
 * Dice thrown in a fixed order, for tests and debug runs only (#55): [rolls] in turn, then [then]; running out with
 * nothing after it is an error, so a scripted game never silently goes random. A roll scripted without an event face
 * (event -1) leaves the event die to the engine's seeded RNG when played through [DiceRoll.toAction].
 * Never offered to players: their dice are [TrueRandomDice] or a [DiceDeck], whose fairness is tested.
 */
class ScriptedDice(rolls: List<DiceRoll>, private val then: DiceSource? = null) : DiceSource {
    private val queue = ArrayDeque(rolls)

    /** Scripted rolls not thrown yet. */
    val left get() = queue.size

    override fun next(withEvent: Boolean): DiceRoll {
        val r = queue.removeFirstOrNull() ?: return then?.next(withEvent) ?: error("the scripted dice ran out")
        return if (withEvent || r.event < 0) r else r.copy(event = -1)
    }

    companion object {
        /**
         * Rolls written "d1,d2" or "d1,d2,event" (event face 0..5), separated by spaces: "6,6 3,4 1,2,5". The form
         * a debug launch extra or a scenario file would carry.
         */
        fun parse(text: String): List<DiceRoll> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { t ->
            val n = t.split(",").map { it.trim().toIntOrNull() ?: throw IllegalArgumentException("bad roll '$t'") }
            require(n.size in 2..3 && n[0] in 1..6 && n[1] in 1..6 && (n.size == 2 || n[2] in 0..5)) { "bad roll '$t'" }
            DiceRoll(n[0], n[1], n.getOrElse(2) { -1 })
        }

        /** A roll of [sum] (2..12), split as evenly as the faces allow. */
        fun sum(sum: Int, event: Int = -1): DiceRoll {
            require(sum in 2..12) { "no roll of $sum" }
            val d1 = (sum + 1) / 2
            return DiceRoll(minOf(d1, 6), sum - minOf(d1, 6), event)
        }
    }
}

package com.mauro.wgbg

import wgbg.engine.Action
import wgbg.engine.GameState
import wgbg.engine.Rules
import wgbg.net.MoveCodec
import java.io.File

/**
 * A local game in progress, kept so closing the app, Android stopping it in the background, or an update never loses
 * it (#82). The game is its setup, its seed and every move so far (the dice travel in the moves), so replaying the
 * moves rebuilds it exactly, the way online rooms come back.
 */
data class LocalSave(val setup: GameSetup, val seed: Long, val moves: List<Action>) {
    /**
     * "wgbg-local v2" (v1: a game from before #126, where seat 0 always started), the setup line, the seed, then one
     * move per line.
     */
    fun encode(): String = (listOf(if (setup.randomFirst) HEADER else HEADER_V1, setup.encode(), seed.toString()) +
        moves.map { MoveCodec.encode(it) }).joinToString("\n")

    /**
     * The game rebuilt by replaying every move, each passed to [apply] (which must apply it, so the caller can record
     * history on the way); null if any move isn't legal where it stands (a damaged or out-of-date save).
     */
    fun replay(state: GameState = Rules.newGame(setup.config(), seed), apply: (who: Int, a: Action, applyIt: () -> Unit) -> Unit =
        { _, _, applyIt -> applyIt() }): GameState? {
        for (a in moves) {
            if (state.isOver || !Rules.isLegal(state, a)) return null
            apply(Rules.mover(state, a), a) { Rules.apply(state, a) } // #114: a withdraw is the offerer's
        }
        return state
    }

    /**
     * The game can be picked up: it replays cleanly and isn't finished. A setup the rules refuse (a player count its
     * ruleset doesn't allow) is not resumable either, rather than a crash every time the app opens.
     */
    val resumable: Boolean get() = runCatching { replay() }.getOrNull()?.isOver == false

    companion object {
        const val HEADER = "wgbg-local v2"
        /** A save from before #126: seat 0 started, so it replays that way. */
        const val HEADER_V1 = "wgbg-local v1"
        /**
         * The colours every game had before 0.3.4, by player. A game saved then (its setup line before v3) carries no
         * colours, and the new defaults would turn its Orange player Purple mid-game after the update; it keeps these.
         */
        val BEFORE_034 = listOf(SeatColour.RED, SeatColour.BLUE, SeatColour.ORANGE, SeatColour.WHITE)

        /** The save [encode] wrote, or null if [text] is missing, from another version or damaged. */
        fun decode(text: String?): LocalSave? = runCatching {
            val lines = text!!.trimEnd().split("\n").map { it.trimEnd('\r') }
            require(lines.size >= 3 && (lines[0] == HEADER || lines[0] == HEADER_V1))
            val setup = GameSetup.decode(lines[1])!!.copy(randomFirst = lines[0] == HEADER)
                .let { if (lines[1].substringBefore("|") in listOf("v1", "v2")) it.withColours(BEFORE_034) else it }   // v3 on stores colours
            LocalSave(setup, lines[2].toLong(), lines.drop(3).filter { it.isNotEmpty() }.map { MoveCodec.decode(it)!! })
        }.getOrNull()
    }
}

/**
 * Where the local game is kept: one small file, started with the setup and seed, then a line appended per move, so
 * saving after every move costs a single short write.
 */
class LocalSaveStore(private val file: File) {
    fun start(setup: GameSetup, seed: Long) = runCatching { file.writeText(LocalSave(setup, seed, emptyList()).encode() + "\n") }

    fun add(a: Action) = runCatching { if (file.exists()) file.appendText(MoveCodec.encode(a) + "\n") }

    /** The saved game if there is one that can still be played. */
    fun load(): LocalSave? = runCatching { LocalSave.decode(file.readText()) }.getOrNull()?.takeIf { it.resumable }

    fun clear() { runCatching { file.delete() } }
}

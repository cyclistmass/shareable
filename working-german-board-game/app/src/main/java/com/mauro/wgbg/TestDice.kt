package com.mauro.wgbg

import wgbg.engine.DiceRoll
import wgbg.engine.DiceSource
import wgbg.engine.ScriptedDice

/**
 * Debug builds only: `--es dice "3,3 4,4 2,3"` makes local games throw these dice, in order and over again, so an
 * automated test reaches the turn it needs every run (ci_trade's two lots to pay: no 7, so no hand is ever halved).
 * Never offered to players, and never online (a room's dice are thrown by its host's real dice). A face for the event
 * die is kept only where a roll gives one; otherwise the engine throws the event die as usual.
 */
internal object TestDice {
    /** The rolls from the launch extra, or null for real dice. */
    var rolls: List<DiceRoll>? = null
        private set

    /** Reads the extra's text; a typo means real dice and a log line, never a crash on launch. */
    fun use(text: String?) {
        rolls = text?.let { t ->
            runCatching { ScriptedDice.parse(t) }.onFailure { android.util.Log.w("WGBG", "--es dice ignored: ${it.message}") }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    /** The dice local games throw while [rolls] is set; null for real dice. */
    fun source(): DiceSource? = rolls?.let { cycle(it) }

    internal fun cycle(rolls: List<DiceRoll>): DiceSource = object : DiceSource {
        private var i = 0
        override fun next(withEvent: Boolean): DiceRoll {
            val r = rolls[i++ % rolls.size]
            return if (withEvent || r.event < 0) r else r.copy(event = -1)
        }
    }
}

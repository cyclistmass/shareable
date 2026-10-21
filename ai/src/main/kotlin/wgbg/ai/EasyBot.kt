package wgbg.ai

import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.CARD_TYPES
import wgbg.engine.ChivalryActionType
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Rng

/**
 * The "Easy" bot for learners: random legal moves with a little sense. It always builds when it
 * can (town over homestead over road), never makes or counters offers, and accepts a trade only
 * when it is clearly good: more cards in than out, or a card it has none of for one it has 3+ of,
 * and never from someone within 2 points of winning. Everything else is a coin flip.
 */
class EasyBot(seed: Long = 0, override val name: String = "Easy") : Bot {
    private val rng = Rng(seed)

    override fun choose(state: GameState, legal: ActionBuffer): Action {
        if (state.chivalry == null) when (state.phase) {
            Phase.DECIDE_TRADE -> return if (Action.ACCEPT_TRADE in legal && clearlyGood(state, offerIn(state), offerOut(state), state.currentPlayer))
                Action.ACCEPT_TRADE else Action.REJECT_TRADE
            else -> {}
        }
        for (kind in BUILD_ORDER) {
            val builds = (0 until legal.size).filter { legal[it].type == kind }
            if (builds.isNotEmpty()) return legal[builds[rng.nextInt(builds.size)]]
        }
        // Random among the rest, but never start or counter a trade (Easy waits to be asked).
        val rest = (0 until legal.size).filter { legal[it].type !in TRADE_STARTS }
        return if (rest.isEmpty()) legal[rng.nextInt(legal.size)] else legal[rest[rng.nextInt(rest.size)]]
    }

    /** What the open offer gives us (the offerer's give side) and takes from us. */
    private fun offerIn(s: GameState) = IntArray(CARD_TYPES) { s.tradeOffer[it] }
    private fun offerOut(s: GameState) = IntArray(CARD_TYPES) { s.tradeOffer[CARD_TYPES + it] }

    private fun clearlyGood(s: GameState, cardsIn: IntArray, cardsOut: IntArray, partner: Int): Boolean {
        if (partner >= 0 && s.publicVictoryPoints(partner) >= s.config.vpToWin - 2) return false
        val me = s.actingPlayer
        if ((0 until CARD_TYPES).any { cardsOut[it] > s.hand(me, it) }) return false
        val nIn = cardsIn.sum()
        val nOut = cardsOut.sum()
        if (nIn > nOut) return true
        if (nIn != 1 || nOut != 1) return false
        val get = cardsIn.indexOfFirst { it > 0 }
        val give = cardsOut.indexOfFirst { it > 0 }
        return s.hand(me, get) == 0 && s.hand(me, give) >= 3
    }

    companion object {
        private val BUILD_ORDER = listOf(ActionType.BUILD_CITY, ActionType.BUILD_SETTLEMENT, ActionType.BUILD_ROAD)
        private val TRADE_STARTS = setOf(ActionType.OFFER_TRADE, ActionType.COUNTER_TRADE, ChivalryActionType.OFFER_TRADE_CK, ChivalryActionType.COUNTER_TRADE_CK)
    }
}

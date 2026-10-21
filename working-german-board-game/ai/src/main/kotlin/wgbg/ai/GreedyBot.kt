package wgbg.ai

import wgbg.engine.Action
import wgbg.engine.ActionBuffer
import wgbg.engine.ActionType
import wgbg.engine.CARD_TYPES
import wgbg.engine.CHIVALRY_CARD_TYPES
import wgbg.engine.Chivalry
import wgbg.engine.ChivalryActionType
import wgbg.engine.ChivalryPiece
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Piece
import wgbg.engine.Rules
import wgbg.engine.Resource
import wgbg.engine.Rng

/**
 * Simple rule-of-thumb bot so demo games look sensible: builds the best-yield
 * spot it can afford, trades with players and the bank for what it is missing,
 * accepts trades that help it (not from a player close to winning), robs the leader.
 * A stand-in until the Catanatron-style AlphaBeta bot lands in Phase 3.
 */
class GreedyBot(seed: Long = 0, override val name: String = "Greedy") : Bot {
    private val rng = Rng(seed)
    private var offerTurn = -1
    private var offersThisTurn = 0
    /** Age of Chivalry: what the acting player is saving for and how the Raiders stand, set per choose(). */
    private var chivalryPlan: ChivalryPlan? = null

    override fun choose(state: GameState, legal: ActionBuffer): Action {
        tradeOffer(state, legal)?.let { return it }
        tradeResponse(state, legal)?.let { return it }
        chivalryPlan = if (state.chivalry != null) ChivalryPlan(state, state.actingPlayer) else null
        var best = legal[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until legal.size) {
            val a = legal[i]
            val score = score(state, a) + rng.nextInt(1000) / 1e5 // tie-break
            if (score > bestScore) {
                bestScore = score
                best = a
            }
        }
        return best
    }

    private fun score(s: GameState, a: Action): Double {
        val me = s.actingPlayer
        chivalryPlan?.let { plan -> chivalryScore(s, me, a, plan)?.let { return it } }
        return when (a.type) {
            ActionType.BUILD_CITY -> 1000.0 + vertexValue(s, a.a.toInt())
            ActionType.BUILD_SETTLEMENT -> 900.0 + vertexValue(s, a.a.toInt())
            ActionType.BUY_DEVELOPMENT_CARD -> 300.0
            ActionType.PLAY_KNIGHT_CARD -> if (robberHurtsMe(s, me)) 950.0 else 100.0
            ActionType.PLAY_ROAD_BUILDING, ActionType.PLAY_YEAR_OF_PLENTY -> 800.0
            ActionType.PLAY_MONOPOLY -> 700.0 + s.handSizeOthers(me, a.payload.toInt())
            ActionType.BUILD_ROAD -> roadScore(s, me, a.payload.toInt())
            ActionType.MARITIME_TRADE ->
                // Card indices here may be commodities (Age of Chivalry), so read them with card(), not hand().
                if (s.card(me, a.maritimeGive) >= 4 && s.card(me, a.maritimeGet) == 0) 200.0 else -1.0
            ActionType.MOVE_ROBBER -> robberScore(s, me, a.robberHex, a.robberVictim)
            ActionType.DISCARD -> if (a.discardResource < 0) 0.0 else s.card(me, a.discardResource).toDouble()
            ActionType.ROLL -> 50.0
            ActionType.ACCEPT_TRADE -> if (offerHelpsMe(s, me)) 5.0 else -1.0
            ActionType.CONFIRM_TRADE -> {
                val q = a.payload.toInt()
                // Weakest partner first; a counter only when its terms suit us (else withdraw beats it).
                val gain = s.counterOf(q)?.let { counterGain(s, me, it) } ?: 0.0
                if (s.counterOf(q) != null && (gain <= -0.05 || s.publicVictoryPoints(q) >= s.config.vpToWin - 2)) -1.0 else 10.0 + gain - s.publicVictoryPoints(q)
            }
            ActionType.OFFER_TRADE, ActionType.COUNTER_TRADE, ChivalryActionType.OFFER_TRADE_CK, ChivalryActionType.COUNTER_TRADE_CK -> -10.0
            ActionType.END_TURN, ActionType.REJECT_TRADE, ActionType.CANCEL_TRADE -> 0.0
            else -> 1.0
        }
    }

    // ------------------------------------------------------------------ Age of Chivalry

    /**
     * One snapshot per decision: the build the bot is saving for (deterministic, unlike [target], so the
     * tie-break stream is untouched) and whether the Raiders threaten us.
     */
    private class ChivalryPlan(s: GameState, me: Int) {
        val chivalry = s.chivalry!!
        val savingCity = s.piecesOnBoard(me, Piece.SETTLEMENT) > 0 && s.piecesLeft[me * Piece.COUNT + Piece.CITY] > 0
        val goal: IntArray = when {
            savingCity -> COST_CITY
            (0 until s.board.topology.vertexCount).any { v ->
                s.vertexOwner[v] < 0 && s.board.topology.vertexEdges[v].any { s.edgeOwner[it] == me } &&
                    s.board.topology.vertexNeighbors[v].all { s.vertexOwner[it] < 0 }
            } -> COST_SETTLEMENT
            s.piecesLeft[me * Piece.COUNT + Piece.ROAD] > 0 -> COST_ROAD
            else -> IntArray(CARD_TYPES)
        }
        /** The Raiders land within two rolls. */
        val raidersNear = chivalry.barbarianPos >= ChivalryPiece.BARBARIAN_TRACK - 2
        /** A city (not a Capital) the Raiders could take from us. */
        val cityAtRisk = (0 until s.board.vertexCount).any {
            s.vertexOwner[it] == me && s.vertexBuilding[it] == Piece.CITY && !chivalry.isMetropolis(it)
        }
        /** All active knights together are weaker than the Raiders and we stand to lose a city. */
        val defenceShort = cityAtRisk &&
            (0 until s.numPlayers).sumOf { Chivalry.activeKnightStrength(s, it) } < Chivalry.barbarianStrength(s)
        /** Spending on knights is worth it now: the Raiders are close, or the defence is short. */
        val defend = raidersNear || defenceShort

        /** Cards of [c] we can let go of without falling short of the goal (commodities: keep one each). */
        fun spare(s: GameState, me: Int, c: Int) =
            s.card(me, c) - if (c < CARD_TYPES) goal[c] else 1

        /** Giving away [c] hurts the goal: grain and ore are never given while saving for a city. */
        fun protects(c: Int) = c < CARD_TYPES && (goal[c] > 0 || (savingCity && (c == Resource.WHEAT || c == Resource.ORE)))
    }

    /**
     * Chivalry scores, or null to fall back to the shared table. Every knight action, trade or discard that
     * spends a city card is scored below END_TURN unless the Raiders make it worth it, so the bots save
     * for towns instead of burning grain on activations and knight shuffles.
     */
    private fun chivalryScore(s: GameState, me: Int, a: Action, plan: ChivalryPlan): Double? {
        val chivalry = plan.chivalry
        return when (a.type) {
            ChivalryActionType.IMPROVE_CITY -> 990.0 // just under a town
            ChivalryActionType.ACTIVATE_KNIGHT -> if (plan.defend) 600.0 + chivalry.knightLevel[a.a] else -1.0
            ChivalryActionType.BUILD_KNIGHT -> when {
                plan.defenceShort -> 550.0
                !plan.savingCity && plan.spare(s, me, Resource.SHEEP) >= 1 && plan.spare(s, me, Resource.ORE) >= 1 -> 250.0
                else -> -1.0
            }
            ChivalryActionType.PROMOTE_KNIGHT -> when {
                chivalry.freePromotions > 0 -> 500.0
                plan.defenceShort && chivalry.isActive(a.a) -> 540.0
                !plan.savingCity && plan.spare(s, me, Resource.SHEEP) >= 1 && plan.spare(s, me, Resource.ORE) >= 1 -> 240.0
                else -> -1.0
            }
            // Moving deactivates the knight, wasting the grain that woke it.
            ChivalryActionType.MOVE_KNIGHT, ChivalryActionType.DISPLACE_KNIGHT -> -1.0
            ChivalryActionType.CHASE_ROBBER -> if (robberHurtsMe(s, me)) 400.0 else -1.0
            ChivalryActionType.BUILD_CITY_WALL -> if (plan.goal[Resource.BRICK] == 0 && plan.cityAtRisk) 5.0 else -0.5
            ChivalryActionType.PLAY_PROGRESS -> 1.0
            ChivalryActionType.COMMERCIAL_HARBOR_OFFER -> if (plan.protects(a.a) || plan.spare(s, me, a.a) < 1) -1.0 else 0.5
            ChivalryActionType.CHIVALRY_DONE -> 0.0
            ActionType.MARITIME_TRADE -> {
                val give = a.maritimeGive
                val get = a.maritimeGet
                val rate = Chivalry.tradeRate(s, me, give)
                val keepsGoal = plan.spare(s, me, give) >= rate && !(plan.savingCity && (give == Resource.WHEAT || give == Resource.ORE))
                when {
                    !keepsGoal -> -1.0
                    get < CARD_TYPES && s.card(me, get) < plan.goal[get] -> 200.0 + plan.goal[get] - s.card(me, get)
                    s.card(me, give) >= 4 && s.card(me, get) == 0 && !plan.protects(give) -> 50.0
                    else -> -1.0
                }
            }
            ActionType.DISCARD -> {
                val c = a.discardResource
                when {
                    c < 0 -> -20.0 // at random could drop a city card
                    plan.protects(c) && plan.spare(s, me, c) <= 0 -> -10.0 + s.card(me, c)
                    plan.savingCity && (c == Resource.WHEAT || c == Resource.ORE) -> -5.0 + plan.spare(s, me, c)
                    c >= CARD_TYPES -> plan.spare(s, me, c) - 0.5
                    else -> plan.spare(s, me, c).toDouble()
                }
            }
            ChivalryActionType.OFFER_TRADE_CK, ChivalryActionType.COUNTER_TRADE_CK -> null
            is ChivalryActionType -> -0.5 // forced prompts still pick their best (only) options; nothing optional beats END_TURN
            else -> null
        }
    }

    // ------------------------------------------------------------------ trading

    /** What the bot is saving for right now: town, homestead, Fortune card, or a road. */
    private fun target(s: GameState, me: Int): IntArray {
        val hasTowns = s.piecesOnBoard(me, Piece.SETTLEMENT) > 0 && s.piecesLeft[me * Piece.COUNT + Piece.CITY] > 0
        val canSettle = (0 until s.board.topology.vertexCount).any { v ->
            s.vertexOwner[v] < 0 && s.board.topology.vertexEdges[v].any { s.edgeOwner[it] == me } &&
                s.board.topology.vertexNeighbors[v].all { s.vertexOwner[it] < 0 }
        }
        return when {
            hasTowns -> COST_CITY
            canSettle -> COST_SETTLEMENT
            else -> COST_ROAD.takeIf { s.piecesLeft[me * Piece.COUNT + Piece.ROAD] > 0 && rng.nextInt(2) == 0 } ?: COST_DEV
        }
    }

    /** How much one more card of [r] is worth to [me]: high if missing for the target, low if hoarded. */
    private fun worth(s: GameState, me: Int, goal: IntArray, r: Int, extra: Int = 0): Double {
        val have = s.hand(me, r) + extra
        return when {
            have < goal[r] -> 3.0
            have >= goal[r] + 2 -> 0.4
            else -> 1.0
        }
    }

    /**
     * After rolling, if one card short of the target and holding a spare, offer 1 spare for it
     * (at most twice a turn, so a refused offer can't loop).
     */
    private fun tradeOffer(s: GameState, legal: ActionBuffer): Action? {
        if (s.phase != Phase.PLAY_TURN || !s.hasRolled) return null
        if (offerTurn != s.turn) { offerTurn = s.turn; offersThisTurn = 0 }
        if (offersThisTurn >= 2) return null
        // Only bother when nothing can be built right now.
        for (i in 0 until legal.size) {
            val t = legal[i].type
            if (t == ActionType.BUILD_CITY || t == ActionType.BUILD_SETTLEMENT || t == ActionType.BUY_DEVELOPMENT_CARD) return null
        }
        val me = s.actingPlayer
        val goal = target(s, me)
        val missing = (0 until CARD_TYPES).filter { s.hand(me, it) < goal[it] }
        if (missing.size != 1 || goal[missing[0]] - s.hand(me, missing[0]) != 1) return null
        val spare = (0 until CARD_TYPES).filter { it != missing[0] && s.hand(me, it) > goal[it] }
            .maxByOrNull { s.hand(me, it) - goal[it] } ?: return null
        // First offer is 1-for-1; if nobody bites, sweeten it to 2-for-1 when we can spare it.
        val sweeten = offersThisTurn == 1 && s.hand(me, spare) >= goal[spare] + 2
        val give = IntArray(CARD_TYPES).also { it[spare] = if (sweeten) 2 else 1 }
        val want = IntArray(CARD_TYPES).also { it[missing[0]] = 1 }
        val offer = Action.offerTrade(give, want)
        if (!Rules.isLegal(s, offer)) return null
        offersThisTurn++
        return offer
    }

    /**
     * Answering someone's offer (both rulesets): accept it if it helps; otherwise counter. A one-sided
     * offer ("who has grain?" / "anyone want wool?") always gets a counter when one suits us; a full
     * offer now and then. Asks: we name a card we're missing for what they want. Gives: we take what
     * they offer for our most spare card. Null outside DECIDE_TRADE.
     */
    private fun tradeResponse(s: GameState, legal: ActionBuffer): Action? {
        if (s.phase != Phase.DECIDE_TRADE) return null
        val me = s.actingPlayer
        if (Action.ACCEPT_TRADE in legal && offerHelpsMe(s, me)) return Action.ACCEPT_TRADE
        val o = s.currentPlayer
        val n = cardTypes(s)
        val openGive = IntArray(n) { openTerm(s, it, false) }
        val openWant = IntArray(n) { openTerm(s, it, true) }
        val oneSided = openGive.sum() == 0 || openWant.sum() == 0
        if (s.publicVictoryPoints(o) < s.config.vpToWin - 2 && (oneSided || rng.nextInt(100) < 50)) {
            val goal = target(s, me)
            if (openWant.sum() > 0) {
                val missing = (0 until CARD_TYPES).filter { s.hand(me, it) < goal[it] && openWant[it] == 0 && s.hand(o, it) > 0 }
                for (m in missing.shuffled(java.util.Random(rng.nextInt(1 shl 30).toLong()))) {
                    val counter = counterAction(s, IntArray(n).also { it[m] = 1 }, openWant)
                    if (Rules.isLegal(s, counter)) return counter
                }
            } else {
                // They only give: ask for what they offer and pay with our most spare card, if it's worth it.
                var gain = 0.0
                for (c in 0 until n) repeat(openGive[c]) { gain += cardWorth(s, me, goal, c, it) }
                val spare = (0 until n).filter { openGive[it] == 0 && s.card(me, it) > (if (it < CARD_TYPES) goal[it] else 0) }
                    .minByOrNull { cardWorth(s, me, goal, it, -1) }
                if (spare != null && gain > cardWorth(s, me, goal, spare, -1)) {
                    val counter = counterAction(s, openGive, IntArray(n).also { it[spare] = 1 })
                    if (Rules.isLegal(s, counter)) return counter
                }
            }
        }
        return Action.REJECT_TRADE
    }

    private fun cardTypes(s: GameState) = if (s.chivalry != null) CHIVALRY_CARD_TYPES else CARD_TYPES

    /** The open offer's count of card [c] (Age of Chivalry card space in Chivalry), give side or [want] side. */
    private fun openTerm(s: GameState, c: Int, want: Boolean): Int {
        val k = if (want) 1 else 0
        return if (c < CARD_TYPES) s.tradeOffer[k * CARD_TYPES + c]
        else s.chivalry!!.offer[k * (CHIVALRY_CARD_TYPES - CARD_TYPES) + c - CARD_TYPES]
    }

    /** Counter terms from the offerer's side: they give [give], get [want]. */
    private fun counterAction(s: GameState, give: IntArray, want: IntArray) =
        if (s.chivalry != null) Action.counterTradeCk(give, want) else Action.counterTrade(give, want)

    /** Resources by the build target; commodities a steady middling value. */
    private fun cardWorth(s: GameState, me: Int, goal: IntArray, c: Int, extra: Int) =
        if (c < CARD_TYPES) worth(s, me, goal, c, extra) else 0.8

    /** As the offerer: what a counter's terms are worth to us (negative = it costs more than it brings). */
    private fun counterGain(s: GameState, me: Int, c: Action): Double {
        val chivalry = s.chivalry != null
        val goal = target(s, me)
        var gain = 0.0
        for (r in 0 until cardTypes(s)) {
            val gets = if (chivalry) c.chivalryOfferWant(r) else c.offerWant(r)
            val gives = if (chivalry) c.chivalryOfferGive(r) else c.offerGive(r)
            repeat(gets) { gain += cardWorth(s, me, goal, r, it) }
            repeat(gives) { gain -= cardWorth(s, me, goal, r, -it - 1) }
        }
        return gain
    }

    /** Accept if what we get is worth more to us than what we give, and the offerer isn't about to win. */
    private fun offerHelpsMe(s: GameState, me: Int): Boolean {
        if (s.publicVictoryPoints(s.currentPlayer) >= s.config.vpToWin - 2) return false
        val goal = target(s, me)
        var gain = 0.0
        for (r in 0 until CARD_TYPES) {
            val get = s.tradeOffer[r]              // the offerer gives this to us
            val give = s.tradeOffer[CARD_TYPES + r] // we give this
            repeat(get) { gain += worth(s, me, goal, r, it) }
            repeat(give) { gain -= worth(s, me, goal, r, -it - 1) }
        }
        // Clearly good: accept. About even: accept sometimes, like a friendly player would.
        return gain > 0.5 || (gain >= -0.05 && rng.nextInt(100) < 40)
    }

    /** Expected yield of a spot: dice combinations of touching hexes, plus a bonus for variety. */
    private fun vertexValue(s: GameState, v: Int): Double {
        val b = s.board
        var pips = 0
        val kinds = HashSet<Int>()
        for (h in b.topology.vertexHexes[v]) {
            if (b.hexResource[h] == Resource.NONE || h == s.robberHex) continue
            pips += pips(s.numberAt(h))
            kinds += b.hexResource[h]
        }
        return pips + 1.5 * kinds.size
    }

    private fun roadScore(s: GameState, me: Int, edge: Int): Double {
        // Roads are only worth it while they lead somewhere we could settle.
        val roads = s.piecesOnBoard(me, Piece.ROAD)
        val towns = s.piecesOnBoard(me, Piece.SETTLEMENT) + s.piecesOnBoard(me, Piece.CITY)
        if (s.freeRoads == 0 && s.hasRolled && roads >= 2 * towns + 1) return -1.0
        val ends = s.board.topology.edgeVertices[edge]
        val reach = ends.maxOf { v -> if (canSettle(s, v)) vertexValue(s, v) else 0.0 }
        return 400.0 + reach
    }

    private fun canSettle(s: GameState, v: Int): Boolean =
        s.vertexOwner[v] < 0 && s.board.topology.vertexNeighbors[v].all { s.vertexOwner[it] < 0 }

    private fun robberHurtsMe(s: GameState, me: Int) =
        s.robberHex >= 0 && s.board.topology.hexVertices[s.robberHex].any { s.vertexOwner[it] == me }

    private fun robberScore(s: GameState, me: Int, hex: Int, victim: Int): Double {
        var score = 0.0
        val number = pips(s.numberAt(hex)).toDouble()
        for (v in s.board.topology.hexVertices[hex]) {
            val owner = s.vertexOwner[v]
            if (owner < 0) continue
            val weight = if (s.vertexBuilding[v] == Piece.CITY) 2 else 1
            score += if (owner == me) -3 * number * weight else number * weight * (1 + s.publicVictoryPoints(owner) / 4.0)
        }
        if (victim >= 0) score += s.cardCount(victim) / 2.0   // the bandit can take a commodity too
        return score
    }

    private fun GameState.handSizeOthers(me: Int, r: Int) =
        (0 until numPlayers).filter { it != me }.sumOf { hand(it, r) }.toDouble()

    companion object {
        // Costs in engine resource order: wood, brick, sheep, wheat, ore.
        private val COST_ROAD = intArrayOf(1, 1, 0, 0, 0)
        private val COST_SETTLEMENT = intArrayOf(1, 1, 1, 1, 0)
        private val COST_CITY = intArrayOf(0, 0, 0, 2, 3)
        private val COST_DEV = intArrayOf(0, 0, 1, 1, 1)

        fun pips(n: Int) = if (n == 0) 0 else 6 - kotlin.math.abs(7 - n)
    }
}

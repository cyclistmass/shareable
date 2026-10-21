package wgbg.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Age of Chivalry rules (5th-edition rulebook), used by [Rules] whenever
 * [GameState.chivalry] is non-null. Like [Rules] it is stateless: every random event
 * draws from [GameState.rng] and every player choice is an action.
 *
 * A dice roll is resolved in stages ([ChivalryState.rollStage]) because it can stop
 * for player decisions (lost cities, defender draws, progress-card discards,
 * Aqueduct picks, 7-discards, robber). [resume] carries on from wherever the
 * last decision left off and finally hands the turn back to PLAY_TURN.
 */
object Chivalry {
    /** Roll stages (see [resume]). */
    private const val STAGE_NONE = 0
    private const val STAGE_PRODUCE = 1
    private const val STAGE_AQUEDUCT = 2
    private const val STAGE_SEVEN = 3

    private const val ACTIVE = ChivalryState.ACTIVE
    private const val FRESH = ChivalryState.FRESH
    private const val PROMOTED = ChivalryState.PROMOTED

    // ================================================================ card helpers

    fun bankOf(s: GameState, c: Int): Int =
        if (c < Resource.COUNT) s.bank[c] else s.chivalry!!.commodityBank[c - Resource.COUNT]

    /** Moves [n] cards of type [c] from the bank to [p] (negative [n]: back to the bank). */
    internal fun fromBank(s: GameState, p: Int, c: Int, n: Int) {
        if (c < Resource.COUNT) {
            s.bank[c] -= n
            s.hands[p * CARD_TYPES + c] += n
        } else {
            val chivalry = s.chivalry!!
            val l = c - Resource.COUNT
            chivalry.commodityBank[l] -= n
            chivalry.commodities[p * Commodity.COUNT + l] += n
        }
    }

    /** Moves [n] cards of type [c] from player [from] to player [to]. */
    internal fun transfer(s: GameState, from: Int, to: Int, c: Int, n: Int) {
        if (c < Resource.COUNT) {
            s.hands[from * CARD_TYPES + c] -= n
            s.hands[to * CARD_TYPES + c] += n
        } else {
            val chivalry = s.chivalry!!
            val l = c - Resource.COUNT
            chivalry.commodities[from * Commodity.COUNT + l] -= n
            chivalry.commodities[to * Commodity.COUNT + l] += n
        }
    }

    /** A uniformly random card (resource or commodity) from [p]'s non-empty hand. */
    private fun randomCard(s: GameState, p: Int): Int {
        var k = s.rng.nextInt(s.cardCount(p))
        var c = 0
        while (k >= s.card(p, c)) k -= s.card(p, c++)
        return c
    }

    /** 7-discard threshold: the configured limit plus 2 per city wall. */
    fun discardLimit(s: GameState, p: Int): Int {
        val walls = ChivalryPiece.WALLS - s.chivalry!!.wallsLeft[p]
        return s.config.discardLimit + ChivalryPiece.WALL_HAND_BONUS * walls
    }

    /** Sum of the strengths of [p]'s active knights. */
    fun activeKnightStrength(s: GameState, p: Int): Int {
        val chivalry = s.chivalry!!
        var n = 0
        for (v in 0 until s.board.vertexCount) if (chivalry.knightOwner[v] == p && chivalry.isActive(v)) n += chivalry.knightLevel[v]
        return n
    }

    fun knightsOnBoard(s: GameState, p: Int): Int {
        val chivalry = s.chivalry!!
        var n = 0
        for (v in 0 until s.board.vertexCount) if (chivalry.knightOwner[v] == p) n++
        return n
    }

    /** Barbarian strength: every city on the board (metropolises included). */
    fun barbarianStrength(s: GameState): Int {
        var n = 0
        for (q in 0 until s.numPlayers) n += s.piecesOnBoard(q, Piece.CITY)
        return n
    }

    /** Best maritime rate for giving away card [c] (Age of Chivalry card index). */
    fun tradeRate(s: GameState, p: Int, c: Int): Int {
        val chivalry = s.chivalry!!
        val mask = s.portMask[p]
        var rate = if (mask and Port.GENERIC_BIT != 0) 3 else 4
        if (c < Resource.COUNT) {
            if (mask and (1 shl c) != 0) rate = 2
            if (chivalry.merchantOwner == p && s.board.hexResource[chivalry.merchantHex] == c) rate = 2
        } else if (chivalry.level(p, Track.TRADE) >= Track.ABILITY_LEVEL) {
            rate = 2 // Trading House
        }
        if (p == s.currentPlayer && chivalry.fleetMask and (1 shl c) != 0) rate = 2
        return rate
    }

    // ================================================================ knights

    private class Scratch {
        var queue = IntArray(0)
        var seen = IntArray(0)
        var stamp = 0
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    /**
     * Breadth-first walk from [from] along [p]'s roads. Calls [visit] with (vertex, false) for
     * every reachable empty intersection and (vertex, true) for every opponent knight that ends
     * a path. Own buildings and knights can be passed; opponents' pieces cannot.
     */
    private inline fun reach(s: GameState, p: Int, from: Int, visit: (Int, Boolean) -> Unit) {
        val b = s.board
        val chivalry = s.chivalry!!
        val sc = scratch.get()
        if (sc.queue.size < b.vertexCount) {
            sc.queue = IntArray(b.vertexCount)
            sc.seen = IntArray(b.vertexCount)
        }
        val stamp = ++sc.stamp
        val queue = sc.queue
        val seen = sc.seen
        var head = 0
        var tail = 0
        queue[tail++] = from
        seen[from] = stamp
        while (head < tail) {
            val x = queue[head++]
            for (i in 0 until 3) {
                val e = b.vertexEdge[x * 3 + i]
                if (e < 0 || s.edgeOwner[e] != p) continue
                val y = b.vertexNbr[x * 3 + i]
                if (seen[y] == stamp) continue
                seen[y] = stamp
                val bo = s.vertexOwner[y]
                val ko = chivalry.knightOwner[y]
                if (bo >= 0 && bo != p) continue
                if (ko >= 0 && ko != p) {
                    visit(y, true)
                    continue
                }
                if (bo < 0 && ko < 0) visit(y, false)
                queue[tail++] = y
            }
        }
    }

    /** True if [q]'s knight displaced from [from] has somewhere to go. */
    private fun canRelocate(s: GameState, q: Int, from: Int): Boolean {
        var found = false
        reach(s, q, from) { _, knight -> if (!knight) found = true }
        return found
    }

    /** Empty intersection touching one of [p]'s roads: where new knights are placed. */
    fun canPlaceKnightAt(s: GameState, p: Int, v: Int): Boolean =
        s.vertexOwner[v] < 0 && s.chivalry!!.knightOwner[v] < 0 && Rules.hasRoadAt(s, p, v)

    /** May [p] promote the knight at [v] (ignoring cost)? */
    fun canPromote(s: GameState, p: Int, v: Int): Boolean {
        val chivalry = s.chivalry!!
        if (chivalry.knightOwner[v] != p || chivalry.knightFlags[v] and PROMOTED != 0) return false
        val lvl = chivalry.knightLevel[v]
        if (lvl >= ChivalryPiece.MAX_KNIGHT_LEVEL) return false
        if (lvl == 2 && chivalry.level(p, Track.POLITICS) < Track.ABILITY_LEVEL) return false // mighty needs the Fortress
        return chivalry.knightsLeft[p * ChivalryPiece.MAX_KNIGHT_LEVEL + lvl] > 0
    }

    private fun anyPromotable(s: GameState, p: Int): Boolean {
        for (v in 0 until s.board.vertexCount) if (canPromote(s, p, v)) return true
        return false
    }

    /** Knight that may perform an action this turn. */
    private fun canAct(chivalry: ChivalryState, p: Int, v: Int) =
        chivalry.knightOwner[v] == p && chivalry.knightFlags[v] and (ACTIVE or FRESH) == ACTIVE

    internal fun recomputeRoads(s: GameState) {
        for (q in 0 until s.numPlayers) s.roadLength[q] = Rules.longestRoad(s, q)
        Rules.updateLongestRoad(s)
    }

    private fun placeKnight(s: GameState, p: Int, v: Int, level: Int, flags: Int) {
        val chivalry = s.chivalry!!
        chivalry.knightOwner[v] = p
        chivalry.knightLevel[v] = level
        chivalry.knightFlags[v] = flags
    }

    private fun clearKnight(chivalry: ChivalryState, v: Int) {
        chivalry.knightOwner[v] = -1
        chivalry.knightLevel[v] = 0
        chivalry.knightFlags[v] = 0
    }

    // ================================================================ improvements

    /**
     * Does reaching [level] in [track] give [p] the metropolis? First to level 4 claims it;
     * a level-5 player takes it from a holder still at level 4.
     */
    fun gainsMetropolis(s: GameState, p: Int, track: Int, level: Int): Boolean {
        val chivalry = s.chivalry!!
        if (level < Track.METROPOLIS_LEVEL) return false
        val owner = chivalry.metroOwner[track]
        if (owner < 0) return true
        return owner != p && level == Track.MAX_LEVEL && chivalry.level(owner, track) < Track.MAX_LEVEL
    }

    private fun isPlainCity(s: GameState, p: Int, v: Int) =
        s.vertexOwner[v] == p && s.vertexBuilding[v] == Piece.CITY && !s.chivalry!!.isMetropolis(v)

    /** Adds every legal improvement of [track] (with [discount] commodities off) via [emit](track, metroVertex). */
    private inline fun improveOptions(s: GameState, p: Int, track: Int, discount: Int, emit: (Int) -> Unit) {
        val chivalry = s.chivalry!!
        val level = chivalry.level(p, track) + 1
        if (level > Track.MAX_LEVEL) return
        if (s.piecesOnBoard(p, Piece.CITY) == 0) return
        val cost = max(0, level - discount)
        if (s.card(p, Track.commodity(track)) < cost) return
        if (gainsMetropolis(s, p, track, level)) {
            for (v in 0 until s.board.vertexCount) if (isPlainCity(s, p, v)) emit(v)
        } else {
            emit(-1)
        }
    }

    private fun improve(s: GameState, p: Int, track: Int, metroVertex: Int, discount: Int) {
        val chivalry = s.chivalry!!
        val level = chivalry.level(p, track) + 1
        fromBank(s, p, Track.commodity(track), -max(0, level - discount))
        chivalry.trackLevel[p * Track.COUNT + track] = level
        if (metroVertex >= 0) {
            chivalry.metroOwner[track] = p
            chivalry.metroVertex[track] = metroVertex
        }
    }

    // ================================================================ progress deck

    private fun draw(s: GameState, p: Int, track: Int) {
        val chivalry = s.chivalry!!
        if (chivalry.deckSize[track] == 0) return
        val card = chivalry.deck[track * Progress.PER_DECK + chivalry.deckHead[track]]
        chivalry.deckHead[track] = (chivalry.deckHead[track] + 1) % Progress.PER_DECK
        chivalry.deckSize[track]--
        // Printer and Constitution are revealed at once and stay in front of the player.
        if (Progress.isVictoryPoint(card)) chivalry.progressVp[p]++ else chivalry.progressHand[p * Progress.COUNT + card]++
    }

    /** Played or discarded cards go face down under their deck. */
    private fun toDeckBottom(chivalry: ChivalryState, card: Int) {
        val t = Progress.track(card)
        val i = (chivalry.deckHead[t] + chivalry.deckSize[t]) % Progress.PER_DECK
        chivalry.deck[t * Progress.PER_DECK + i] = card
        chivalry.deckSize[t]++
    }

    // ================================================================ move generation

    fun legalActions(s: GameState, out: ActionBuffer): ActionBuffer {
        val chivalry = s.chivalry!!
        val p = s.actingPlayer
        val b = s.board
        val n = s.numPlayers
        when (s.phase) {
            Phase.BUILD_INITIAL_SETTLEMENT -> {
                val city = s.setupStep >= n // Age of Chivalry: the second placement is a city
                for (v in 0 until b.vertexCount) if (Rules.isVertexFree(s, v)) {
                    out.add(if (city) Action.buildCity(v) else Action.buildSettlement(v))
                }
            }

            Phase.BUILD_INITIAL_ROAD -> {
                val v = s.lastSettlement
                for (i in 0 until 3) {
                    val e = b.vertexEdge[v * 3 + i]
                    if (e >= 0 && s.edgeOwner[e] < 0) out.add(Action.buildRoad(e))
                }
            }

            Phase.PLAY_TURN -> playTurnActions(s, p, out)
            Phase.DISCARD -> {
                if (s.config.randomDiscard) out.add(Action.discard(-1))
                else for (c in 0 until CHIVALRY_CARD_TYPES) if (s.card(p, c) > 0) out.add(Action.discard(c))
            }

            Phase.MOVE_ROBBER -> for (h in 0 until b.hexCount) {
                if (h == s.robberHex) continue
                val victims = robberVictims(s, p, h)
                if (victims == 0) out.add(Action.moveRobber(h, -1))
                else for (q in 0 until n) if (victims and (1 shl q) != 0) out.add(Action.moveRobber(h, q))
            }

            Phase.DECIDE_TRADE -> {
                out.add(Action.REJECT_TRADE)
                var ok = Rules.isTwoSided(s) // one-sided offers are answered with counters
                for (r in 0 until CARD_TYPES) if (s.hand(p, r) < s.tradeOffer[CARD_TYPES + r]) ok = false
                for (l in 0 until Commodity.COUNT) if (chivalry.commodity(p, l) < chivalry.offer[Commodity.COUNT + l]) ok = false
                if (ok) out.add(Action.ACCEPT_TRADE)
                if (s.config.generateTradeOffers) counterActions(s, p, out)
            }

            Phase.DECIDE_ACCEPTEES -> Rules.acceptees(s, out)

            Phase.GAME_OVER, Phase.DECIDE_COUNTER -> {} // DECIDE_COUNTER is no longer entered
            Phase.PROGRESS_DISCARD ->
                for (c in 0 until Progress.COUNT) if (chivalry.progressHand[p * Progress.COUNT + c] > 0) out.add(Action.discardProgress(c))

            Phase.BARBARIAN_LOSS -> for (v in 0 until b.vertexCount) if (isPlainCity(s, p, v)) out.add(Action.chooseVertex(v))
            Phase.DEFENDER_DRAW -> for (t in 0 until Track.COUNT) if (chivalry.deckSize[t] > 0) out.add(Action.chooseProgressDeck(t))
            Phase.AQUEDUCT -> for (r in 0 until Resource.COUNT) if (s.bank[r] > 0) out.add(Action.chooseCard(r))
            Phase.RELOCATE_KNIGHT -> reach(s, p, chivalry.heldFrom) { v, knight -> if (!knight) out.add(Action.relocateKnight(v)) }
            Phase.COMMERCIAL_HARBOR -> {
                out.add(Action.CHIVALRY_DONE)
                for (q in 0 until n) {
                    if (q == p || chivalry.chDoneMask and (1 shl q) != 0 || chivalry.commodityCount(q) == 0) continue
                    for (r in 0 until Resource.COUNT) if (s.hand(p, r) > 0) out.add(Action.commercialHarborOffer(r, q))
                }
            }

            Phase.COMMERCIAL_HARBOR_RESPOND ->
                for (l in 0 until Commodity.COUNT) if (chivalry.commodity(p, l) > 0) out.add(Action.chooseCard(Resource.COUNT + l))

            Phase.MASTER_MERCHANT -> {
                val q = chivalry.pendingTarget
                for (c in 0 until CHIVALRY_CARD_TYPES) if (s.card(q, c) > 0) out.add(Action.chooseCard(c))
            }

            Phase.WEDDING -> for (c in 0 until CHIVALRY_CARD_TYPES) if (s.card(p, c) > 0) out.add(Action.chooseCard(c))
            Phase.DESERTER_REMOVE -> for (v in 0 until b.vertexCount) if (chivalry.knightOwner[v] == p) out.add(Action.chooseVertex(v))
            Phase.DESERTER_PLACE -> {
                out.add(Action.CHIVALRY_DONE)
                for (v in 0 until b.vertexCount) if (canPlaceKnightAt(s, p, v)) out.add(Action.chooseVertex(v))
            }

            Phase.SPY -> {
                val q = chivalry.pendingTarget
                for (c in 0 until Progress.COUNT) if (chivalry.progressHand[q * Progress.COUNT + c] > 0) out.add(Action.takeProgress(c))
            }
        }
        return out
    }

    /** Bitmask of opponents with a building on [h] and at least one card (resource or commodity). */
    fun robberVictims(s: GameState, p: Int, h: Int): Int {
        var mask = 0
        for (i in 0 until 6) {
            val o = s.vertexOwner[s.board.hexVerts[h * 6 + i]]
            if (o >= 0 && o != p && s.cardCount(o) > 0) mask = mask or (1 shl o)
        }
        return mask
    }

    private fun playTurnActions(s: GameState, p: Int, out: ActionBuffer) {
        val chivalry = s.chivalry!!
        val b = s.board
        if (s.freeRoads > 0) {
            for (e in 0 until b.edgeCount) if (Rules.canBuildRoadAt(s, p, e)) out.add(Action.buildRoad(e))
            out.add(Action.CHIVALRY_DONE) // free roads may be declined
            return
        }
        if (chivalry.freePromotions > 0) {
            for (v in 0 until b.vertexCount) if (canPromote(s, p, v)) out.add(Action.promoteKnight(v))
            out.add(Action.CHIVALRY_DONE)
            return
        }
        if (!s.hasRolled) {
            out.add(Action.ROLL)
            // The Alchemist is the only card played before rolling: choose both production dice.
            if (chivalry.progressHand[p * Progress.COUNT + Progress.ALCHEMIST] > 0) {
                for (red in 1..6) for (yellow in 1..6) out.add(Action.playProgress(Progress.ALCHEMIST, red, yellow))
            }
            return
        }
        out.add(Action.END_TURN)
        val hand = p * CARD_TYPES
        val wood = s.hands[hand + Resource.WOOD]
        val brick = s.hands[hand + Resource.BRICK]
        val sheep = s.hands[hand + Resource.SHEEP]
        val wheat = s.hands[hand + Resource.WHEAT]
        val ore = s.hands[hand + Resource.ORE]
        if (Rules.piecesLeft(s, p, Piece.ROAD) > 0 && wood >= 1 && brick >= 1) {
            for (e in 0 until b.edgeCount) if (Rules.canBuildRoadAt(s, p, e)) out.add(Action.buildRoad(e))
        }
        if (Rules.piecesLeft(s, p, Piece.SETTLEMENT) > 0 && Rules.canAfford(s, p, Cost.SETTLEMENT)) {
            for (v in 0 until b.vertexCount) if (Rules.canBuildSettlementAt(s, p, v)) out.add(Action.buildSettlement(v))
        }
        val cityOk = Rules.piecesLeft(s, p, Piece.CITY) > 0 && Rules.canAfford(s, p, Cost.CITY)
        val wallOk = chivalry.wallsLeft[p] > 0 && brick >= 2
        val knightOk = sheep >= 1 && ore >= 1
        for (v in 0 until b.vertexCount) {
            if (s.vertexOwner[v] == p) {
                if (cityOk && s.vertexBuilding[v] == Piece.SETTLEMENT) out.add(Action.buildCity(v))
                if (wallOk && s.vertexBuilding[v] == Piece.CITY && chivalry.wall[v] == 0) out.add(Action.buildCityWall(v))
            }
        }
        // Knights.
        if (knightOk && chivalry.knightsLeft[p * ChivalryPiece.MAX_KNIGHT_LEVEL] > 0) {
            for (v in 0 until b.vertexCount) if (canPlaceKnightAt(s, p, v)) out.add(Action.buildKnight(v))
        }
        for (v in 0 until b.vertexCount) {
            if (chivalry.knightOwner[v] != p) continue
            if (wheat >= 1 && !chivalry.isActive(v)) out.add(Action.activateKnight(v))
            if (knightOk && canPromote(s, p, v)) out.add(Action.promoteKnight(v))
            if (!canAct(chivalry, p, v)) continue
            val lvl = chivalry.knightLevel[v]
            reach(s, p, v) { w, knight ->
                if (!knight) out.add(Action.moveKnight(v, w))
                else if (chivalry.knightLevel[w] < lvl) out.add(Action.displaceKnight(v, w))
            }
            if (chivalry.robberActive && touchesHex(b, v, s.robberHex)) out.add(Action.chaseRobber(v))
        }
        // City improvements.
        for (t in 0 until Track.COUNT) improveOptions(s, p, t, 0) { mv -> out.add(Action.improveCity(t, mv)) }
        // Maritime trade (resources and commodities).
        for (give in 0 until CHIVALRY_CARD_TYPES) {
            if (s.card(p, give) < tradeRate(s, p, give)) continue
            for (get in 0 until CHIVALRY_CARD_TYPES) if (get != give && bankOf(s, get) > 0) out.add(Action.maritimeTrade(give, get))
        }
        progressActions(s, p, out)
        if (s.config.generateTradeOffers) {
            val give = IntArray(CHIVALRY_CARD_TYPES)
            val want = IntArray(CHIVALRY_CARD_TYPES)
            for (g in 0 until CHIVALRY_CARD_TYPES) {
                if (s.card(p, g) == 0) continue
                for (w in 0 until CHIVALRY_CARD_TYPES) {
                    if (w == g) continue
                    give[g] = 1; want[w] = 1
                    out.add(Action.offerTradeCk(give, want))
                    give[g] = 0; want[w] = 0
                }
            }
        }
    }

    private fun touchesHex(b: Board, v: Int, h: Int): Boolean {
        for (i in 0 until 3) if (b.vertexHex[v * 3 + i] == h) return true
        return false
    }

    private fun touchesOwnBuilding(s: GameState, p: Int, h: Int): Boolean {
        for (i in 0 until 6) if (s.vertexOwner[s.board.hexVerts[h * 6 + i]] == p) return true
        return false
    }

    /** A road with nothing of its owner (road or building) attached at one of its ends. */
    fun isOpenRoad(s: GameState, e: Int): Boolean {
        val q = s.edgeOwner[e]
        if (q < 0) return false
        val b = s.board
        fun openEnd(v: Int): Boolean {
            if (s.vertexOwner[v] == q) return false
            for (i in 0 until 3) {
                val f = b.vertexEdge[v * 3 + i]
                if (f >= 0 && f != e && s.edgeOwner[f] == q) return false
            }
            return true
        }
        return openEnd(b.edgeA[e]) || openEnd(b.edgeB[e])
    }

    /**
     * Whether a no-target card would change anything for [p] now (all public facts: the board, the bank, points and
     * card counts), so the app only offers plays that do something (Mauro, 2026-10-13: "I shouldn't be able to play it
     * unless I can actually use it"; his own Saboteur once hit nobody). Irrigation / Mining need a field / mountain by your buildings and some left in the bank; the
     * Saboteur someone at your points or more with two cards or more; the Wedding someone ahead of you holding a card;
     * the Warlord a knight of yours that isn't already raised.
     */
    fun progressHasEffect(s: GameState, p: Int, card: Int): Boolean {
        val chivalry = s.chivalry!!
        val b = s.board
        val mine = s.victoryPoints(p)
        return when (card) {
            Progress.IRRIGATION, Progress.MINING -> {
                val r = if (card == Progress.IRRIGATION) Resource.WHEAT else Resource.ORE
                s.bank[r] > 0 && (0 until b.hexCount).any { b.hexResource[it] == r && touchesOwnBuilding(s, p, it) }
            }
            Progress.SABOTEUR -> (0 until s.numPlayers).any { it != p && s.victoryPoints(it) >= mine && s.cardCount(it) >= 2 }
            Progress.WEDDING -> (0 until s.numPlayers).any { it != p && s.victoryPoints(it) > mine && s.cardCount(it) > 0 }
            Progress.WARLORD -> (0 until b.vertexCount).any { chivalry.knightOwner[it] == p && !chivalry.isActive(it) }
            else -> true
        }
    }

    private fun progressActions(s: GameState, p: Int, out: ActionBuffer) {
        val chivalry = s.chivalry!!
        val b = s.board
        val n = s.numPlayers
        val base = p * Progress.COUNT
        val myVp = s.victoryPoints(p)
        for (card in 0 until Progress.COUNT) {
            if (chivalry.progressHand[base + card] == 0) continue
            when (card) {
                Progress.ALCHEMIST -> {} // only before rolling
                Progress.CRANE -> for (t in 0 until Track.COUNT) {
                    improveOptions(s, p, t, 1) { mv -> out.add(Action.playProgress(card, t, mv + 1)) }
                }

                Progress.ENGINEER -> if (chivalry.wallsLeft[p] > 0) {
                    for (v in 0 until b.vertexCount) {
                        if (s.vertexOwner[v] == p && s.vertexBuilding[v] == Piece.CITY && chivalry.wall[v] == 0) {
                            out.add(Action.playProgress(card, v))
                        }
                    }
                }

                Progress.INVENTOR -> for (h1 in 0 until b.hexCount) {
                    val n1 = chivalry.hexNumber[h1]
                    if (!Progress.inventorMovable(n1)) continue
                    for (h2 in h1 + 1 until b.hexCount) {
                        val n2 = chivalry.hexNumber[h2]
                        if (n2 != n1 && Progress.inventorMovable(n2)) out.add(Action.playProgress(card, h1, h2))
                    }
                }

                // Legal even when they'd do nothing (saved games and online rooms replay such plays); players are only
                // offered them when progressHasEffect says they'd do something.
                Progress.IRRIGATION, Progress.MINING, Progress.SABOTEUR, Progress.WEDDING ->
                    out.add(Action.playProgress(card))

                Progress.MEDICINE -> if (Rules.piecesLeft(s, p, Piece.CITY) > 0 && Rules.canAfford(s, p, ChivalryCost.MEDICINE)) {
                    for (v in 0 until b.vertexCount) {
                        if (s.vertexOwner[v] == p && s.vertexBuilding[v] == Piece.SETTLEMENT) out.add(Action.playProgress(card, v))
                    }
                }

                Progress.ROAD_BUILDING ->
                    if (Rules.piecesLeft(s, p, Piece.ROAD) > 0 && Rules.anyRoadSpot(s, p)) out.add(Action.playProgress(card))

                Progress.SMITH -> if (anyPromotable(s, p)) out.add(Action.playProgress(card))
                Progress.COMMERCIAL_HARBOR -> if (s.handSize(p) > 0) {
                    var any = false
                    for (q in 0 until n) if (q != p && chivalry.commodityCount(q) > 0) any = true
                    if (any) out.add(Action.playProgress(card))
                }

                Progress.MASTER_MERCHANT -> for (q in 0 until n) {
                    if (q != p && s.victoryPoints(q) > myVp && s.cardCount(q) > 0) out.add(Action.playProgress(card, q))
                }

                Progress.MERCHANT -> for (h in 0 until b.hexCount) {
                    if (b.hexResource[h] >= 0 && touchesOwnBuilding(s, p, h)) out.add(Action.playProgress(card, h))
                }

                Progress.MERCHANT_FLEET -> for (c in 0 until CHIVALRY_CARD_TYPES) out.add(Action.playProgress(card, c))
                Progress.RESOURCE_MONOPOLY -> for (r in 0 until Resource.COUNT) out.add(Action.playProgress(card, r))
                Progress.TRADE_MONOPOLY -> for (c in Commodity.PAPER..Commodity.COIN) out.add(Action.playProgress(card, c))
                Progress.BISHOP -> if (chivalry.robberActive) {
                    for (h in 0 until b.hexCount) if (h != s.robberHex) out.add(Action.playProgress(card, h))
                }

                Progress.DESERTER -> for (q in 0 until n) {
                    if (q != p && knightsOnBoard(s, q) > 0) out.add(Action.playProgress(card, q))
                }

                Progress.DIPLOMAT -> for (e in 0 until b.edgeCount) if (isOpenRoad(s, e)) out.add(Action.playProgress(card, e))
                Progress.INTRIGUE -> for (v in 0 until b.vertexCount) {
                    val o = chivalry.knightOwner[v]
                    if (o >= 0 && o != p && Rules.hasRoadAt(s, p, v)) out.add(Action.playProgress(card, v))
                }

                Progress.SPY -> for (q in 0 until n) {
                    if (q != p && chivalry.progressCount(q) > 0) out.add(Action.playProgress(card, q))
                }

                Progress.WARLORD -> if (knightsOnBoard(s, p) > 0) out.add(Action.playProgress(card))
                else -> {} // Printer / Constitution never stay in hand
            }
        }
    }

    /** 1-for-1 counters over resources and commodities, enumerated only for bots and fuzzing. */
    private fun counterActions(s: GameState, p: Int, out: ActionBuffer) {
        val o = s.currentPlayer
        val give = IntArray(CHIVALRY_CARD_TYPES)
        val want = IntArray(CHIVALRY_CARD_TYPES)
        for (g in 0 until CHIVALRY_CARD_TYPES) {
            if (s.card(o, g) == 0) continue
            for (w in 0 until CHIVALRY_CARD_TYPES) {
                if (w == g || s.card(p, w) == 0) continue
                give[g] = 1; want[w] = 1
                val a = Action.counterTradeCk(give, want)
                if (isValidCounter(s, a)) out.add(a)
                give[g] = 0; want[w] = 0
            }
        }
    }

    /** The open offer's count of Age of Chivalry card [card], on the give side or the [want] side. */
    private fun openTerms(s: GameState, card: Int, want: Boolean): Int {
        val k = if (want) 1 else 0
        return if (card < Resource.COUNT) s.tradeOffer[k * CARD_TYPES + card]
        else s.chivalry!!.offer[k * Commodity.COUNT + card - Resource.COUNT]
    }

    /**
     * A Age of Chivalry counter: changes the open offer, gives and gets something, never swaps a card for itself,
     * and both sides hold what they would hand over.
     */
    fun isValidCounter(s: GameState, a: Action): Boolean {
        if (s.chivalry == null || s.phase != Phase.DECIDE_TRADE) return false
        val p = s.actingPlayer
        val o = s.currentPlayer
        var gives = 0
        var gets = 0
        var same = true
        for (c in 0 until CHIVALRY_CARD_TYPES) {
            val g = a.chivalryOfferGive(c)
            val w = a.chivalryOfferWant(c)
            if (g > 0 && w > 0) return false
            if (s.card(o, c) < g || s.card(p, c) < w) return false
            if (g != openTerms(s, c, false) || w != openTerms(s, c, true)) same = false
            gives += g
            gets += w
        }
        return gives > 0 && gets > 0 && !same
    }

    /** OFFER_TRADE_CK is not enumerated by default, so it is validated here; it may be one-sided (see [Rules.isValidOffer]). */
    fun isValidOffer(s: GameState, a: Action): Boolean {
        val chivalry = s.chivalry ?: return false
        if (s.phase != Phase.PLAY_TURN || !s.hasRolled || s.freeRoads > 0 || chivalry.freePromotions > 0) return false
        val p = s.actingPlayer
        var gives = 0
        var wants = 0
        for (c in 0 until CHIVALRY_CARD_TYPES) {
            val g = a.chivalryOfferGive(c)
            val w = a.chivalryOfferWant(c)
            if (g > 0 && w > 0) return false
            if (s.card(p, c) < g) return false
            gives += g
            wants += w
        }
        return gives > 0 || wants > 0
    }

    // ================================================================ transitions

    fun apply(s: GameState, a: Action) {
        val chivalry = s.chivalry!!
        val p = s.actingPlayer
        when (a.type) {
            ActionType.BUILD_SETTLEMENT -> Rules.buildSettlement(s, p, a.payload.toInt())
            ActionType.BUILD_CITY -> {
                val v = a.payload.toInt()
                if (s.phase == Phase.BUILD_INITIAL_SETTLEMENT) setupCity(s, p, v) else {
                    Rules.pay(s, p, Cost.CITY)
                    upgradeToCity(s, p, v)
                }
            }

            ActionType.BUILD_ROAD -> Rules.buildRoad(s, p, a.payload.toInt())
            ActionType.ROLL -> roll(s, p, a.a, a.b, ((a.payload ushr 16) and 0xff).toInt() - 1)
            ActionType.DISCARD -> {
                val c = a.discardResource
                val count = if (c < 0) s.discardOwed[p] else 1
                repeat(count) { fromBank(s, p, if (c < 0) randomCard(s, p) else c, -1) }
                s.discardOwed[p] -= count
                resume(s)
            }

            ActionType.MOVE_ROBBER -> {
                s.robberHex = a.robberHex
                val victim = a.robberVictim
                if (victim >= 0) {
                    transfer(s, victim, p, randomCard(s, victim), 1)
                    s.unseenSteals[p * s.numPlayers + victim]++
                }
                resume(s)
            }

            ActionType.MARITIME_TRADE -> {
                val give = a.maritimeGive
                fromBank(s, p, give, -tradeRate(s, p, give))
                fromBank(s, p, a.maritimeGet, 1)
            }

            ChivalryActionType.OFFER_TRADE_CK -> {
                for (r in 0 until Resource.COUNT) {
                    s.tradeOffer[r] = a.chivalryOfferGive(r)
                    s.tradeOffer[CARD_TYPES + r] = a.chivalryOfferWant(r)
                }
                for (l in 0 until Commodity.COUNT) {
                    chivalry.offer[l] = a.chivalryOfferGive(Resource.COUNT + l)
                    chivalry.offer[Commodity.COUNT + l] = a.chivalryOfferWant(Resource.COUNT + l)
                }
                s.tradeAccepted = 0
                s.phase = Phase.DECIDE_TRADE
                s.actingPlayer = (p + 1) % s.numPlayers
            }

            ActionType.ACCEPT_TRADE, ActionType.REJECT_TRADE, ChivalryActionType.COUNTER_TRADE_CK -> {
                if (a.type == ActionType.ACCEPT_TRADE) s.tradeAccepted = s.tradeAccepted or (1 shl p)
                if (a.type == ChivalryActionType.COUNTER_TRADE_CK) s.counters[p] = a.payload // collected; chosen at the end
                val next = (p + 1) % s.numPlayers
                when {
                    next != s.currentPlayer -> s.actingPlayer = next
                    s.tradeAccepted == 0 && !s.anyCounter -> endTrade(s)
                    else -> {
                        s.phase = Phase.DECIDE_ACCEPTEES
                        s.actingPlayer = s.currentPlayer
                    }
                }
            }

            ActionType.CONFIRM_TRADE -> {
                val q = a.payload.toInt()
                s.counterOf(q)?.let { c ->
                    for (r in 0 until Resource.COUNT) {
                        s.tradeOffer[r] = c.chivalryOfferGive(r)
                        s.tradeOffer[CARD_TYPES + r] = c.chivalryOfferWant(r)
                    }
                    for (l in 0 until Commodity.COUNT) {
                        chivalry.offer[l] = c.chivalryOfferGive(Resource.COUNT + l)
                        chivalry.offer[Commodity.COUNT + l] = c.chivalryOfferWant(Resource.COUNT + l)
                    }
                }
                for (r in 0 until Resource.COUNT) {
                    transfer(s, p, q, r, s.tradeOffer[r])
                    transfer(s, q, p, r, s.tradeOffer[CARD_TYPES + r])
                }
                for (l in 0 until Commodity.COUNT) {
                    transfer(s, p, q, Resource.COUNT + l, chivalry.offer[l])
                    transfer(s, q, p, Resource.COUNT + l, chivalry.offer[Commodity.COUNT + l])
                }
                endTrade(s)
            }

            ActionType.CANCEL_TRADE -> endTrade(s)
            ActionType.END_TURN -> endTurn(s, p)

            ChivalryActionType.BUILD_KNIGHT -> {
                Rules.pay(s, p, ChivalryCost.KNIGHT)
                chivalry.knightsLeft[p * ChivalryPiece.MAX_KNIGHT_LEVEL]--
                placeKnight(s, p, a.payload.toInt(), 1, 0)
                recomputeRoads(s)
            }

            ChivalryActionType.ACTIVATE_KNIGHT -> {
                Rules.pay(s, p, ChivalryCost.ACTIVATE)
                val v = a.payload.toInt()
                chivalry.knightFlags[v] = chivalry.knightFlags[v] or ACTIVE or FRESH
            }

            ChivalryActionType.PROMOTE_KNIGHT -> {
                val v = a.payload.toInt()
                if (chivalry.freePromotions > 0) chivalry.freePromotions-- else Rules.pay(s, p, ChivalryCost.PROMOTE)
                val lvl = chivalry.knightLevel[v]
                chivalry.knightsLeft[p * ChivalryPiece.MAX_KNIGHT_LEVEL + lvl - 1]++
                chivalry.knightsLeft[p * ChivalryPiece.MAX_KNIGHT_LEVEL + lvl]--
                chivalry.knightLevel[v] = lvl + 1
                chivalry.knightFlags[v] = chivalry.knightFlags[v] or PROMOTED
                if (chivalry.freePromotions > 0 && !anyPromotable(s, p)) chivalry.freePromotions = 0
            }

            ChivalryActionType.MOVE_KNIGHT -> {
                val from = a.a
                placeKnight(s, p, a.b, chivalry.knightLevel[from], chivalry.knightFlags[from] and (ACTIVE or FRESH).inv())
                clearKnight(chivalry, from)
                recomputeRoads(s)
            }

            ChivalryActionType.DISPLACE_KNIGHT -> {
                val from = a.a
                val to = a.b
                val q = chivalry.knightOwner[to]
                chivalry.heldKnight = chivalry.knightLevel[to] or ((chivalry.knightFlags[to] and ACTIVE) shl 2)
                chivalry.heldOwner = q
                chivalry.heldFrom = to
                placeKnight(s, p, to, chivalry.knightLevel[from], chivalry.knightFlags[from] and (ACTIVE or FRESH).inv())
                clearKnight(chivalry, from)
                displaced(s)
            }

            ChivalryActionType.CHASE_ROBBER -> {
                val v = a.payload.toInt()
                chivalry.knightFlags[v] = chivalry.knightFlags[v] and ACTIVE.inv()
                s.phase = Phase.MOVE_ROBBER
            }

            ChivalryActionType.RELOCATE_KNIGHT -> {
                val held = chivalry.heldKnight
                placeKnight(s, p, a.payload.toInt(), held and 3, (held shr 2) and ACTIVE)
                chivalry.heldKnight = 0
                chivalry.heldOwner = -1
                chivalry.heldFrom = -1
                recomputeRoads(s)
                resume(s)
            }

            ChivalryActionType.BUILD_CITY_WALL -> {
                Rules.pay(s, p, ChivalryCost.WALL)
                chivalry.wall[a.payload.toInt()] = 1
                chivalry.wallsLeft[p]--
            }

            ChivalryActionType.IMPROVE_CITY -> improve(s, p, a.a, a.b - 1, 0)
            ChivalryActionType.PLAY_PROGRESS -> playProgress(s, p, a.a, a.b, a.c)
            ChivalryActionType.DISCARD_PROGRESS -> {
                chivalry.progressHand[p * Progress.COUNT + a.a]--
                toDeckBottom(chivalry, a.a)
                resume(s)
            }

            ChivalryActionType.CHOOSE_CARD -> chooseCard(s, p, a.a)
            ChivalryActionType.CHOOSE_VERTEX -> chooseVertex(s, p, a.payload.toInt())
            ChivalryActionType.CHOOSE_PROGRESS_DECK -> {
                draw(s, p, a.a)
                chivalry.defenderDrawMask = chivalry.defenderDrawMask and (1 shl p).inv()
                resume(s)
            }

            ChivalryActionType.TAKE_PROGRESS -> {
                val q = chivalry.pendingTarget
                chivalry.progressHand[q * Progress.COUNT + a.a]--
                chivalry.progressHand[p * Progress.COUNT + a.a]++
                chivalry.pendingTarget = -1
                resume(s)
            }

            ChivalryActionType.COMMERCIAL_HARBOR_OFFER -> {
                val q = a.b
                transfer(s, p, q, a.a, 1)
                chivalry.chDoneMask = chivalry.chDoneMask or (1 shl q)
                chivalry.pendingTarget = q
                s.phase = Phase.COMMERCIAL_HARBOR_RESPOND
                s.actingPlayer = q
            }

            ChivalryActionType.CHIVALRY_DONE -> when (s.phase) {
                Phase.PLAY_TURN -> {
                    s.freeRoads = 0
                    chivalry.freePromotions = 0
                }

                Phase.DESERTER_PLACE -> {
                    chivalry.heldKnight = 0
                    chivalry.heldOwner = -1
                    resume(s)
                }

                else -> { // COMMERCIAL_HARBOR
                    chivalry.chDoneMask = 0
                    resume(s)
                }
            }

            else -> error("${a.type} is not an Age of Chivalry action")
        }
    }

    private fun upgradeToCity(s: GameState, p: Int, v: Int) {
        s.vertexBuilding[v] = Piece.CITY
        s.piecesLeft[p * Piece.COUNT + Piece.CITY]--
        s.piecesLeft[p * Piece.COUNT + Piece.SETTLEMENT]++
    }

    /** Second setup placement: a city, yielding one resource per adjacent producing hex. */
    private fun setupCity(s: GameState, p: Int, v: Int) {
        val b = s.board
        s.vertexOwner[v] = p
        s.vertexBuilding[v] = Piece.CITY
        s.piecesLeft[p * Piece.COUNT + Piece.CITY]--
        s.portMask[p] = s.portMask[p] or b.vertexPortBit[v]
        for (i in 0 until 3) {
            val h = b.vertexHex[v * 3 + i]
            if (h >= 0 && b.hexResource[h] >= 0 && s.bank[b.hexResource[h]] > 0) Rules.take(s, p, b.hexResource[h], 1)
        }
        s.lastSettlement = v
        s.phase = Phase.BUILD_INITIAL_ROAD
        recomputeRoads(s)
    }

    private fun endTrade(s: GameState) {
        s.chivalry!!.offer.fill(0)
        Rules.endTrade(s)
    }

    private fun endTurn(s: GameState, p: Int) {
        val chivalry = s.chivalry!!
        for (v in 0 until s.board.vertexCount) chivalry.knightFlags[v] = chivalry.knightFlags[v] and (FRESH or PROMOTED).inv()
        chivalry.fleetMask = 0
        chivalry.freePromotions = 0
        s.hasRolled = false
        s.freeRoads = 0
        s.turn++
        s.currentPlayer = (p + 1) % s.numPlayers
        s.actingPlayer = s.currentPlayer
        if (s.turn >= s.config.maxTurns) {
            s.phase = Phase.GAME_OVER
            s.winner = -1
        }
    }

    /** After a displacement or Intrigue: the held knight's owner relocates it, or it returns to supply. */
    private fun displaced(s: GameState) {
        val chivalry = s.chivalry!!
        recomputeRoads(s)
        val q = chivalry.heldOwner
        if (canRelocate(s, q, chivalry.heldFrom)) {
            s.phase = Phase.RELOCATE_KNIGHT
            s.actingPlayer = q
        } else {
            chivalry.knightsLeft[q * ChivalryPiece.MAX_KNIGHT_LEVEL + (chivalry.heldKnight and 3) - 1]++
            chivalry.heldKnight = 0
            chivalry.heldOwner = -1
            chivalry.heldFrom = -1
            resume(s)
        }
    }

    // ---------------------------------------------------------------- dice

    /** Rolls the production and event dice; [red]/[yellow] > 0 are fixed by an Alchemist. */
    private fun roll(s: GameState, p: Int, red: Int, yellow: Int, eventFace: Int = -1) {
        val chivalry = s.chivalry!!
        val r = if (red > 0) red else s.rng.nextInt(6) + 1
        val y = if (yellow > 0) yellow else s.rng.nextInt(6) + 1
        val event = if (eventFace >= 0) eventFace else s.rng.nextInt(6)
        chivalry.lastRed = r
        chivalry.lastEvent = event
        s.lastRoll = r + y
        s.hasRolled = true
        // The event die is resolved before production.
        val track = EventDie.gateTrack(event)
        if (track < 0) {
            chivalry.barbarianPos++
            if (chivalry.barbarianPos >= ChivalryPiece.BARBARIAN_TRACK) {
                barbarianAttack(s)
                chivalry.barbarianPos = 0
            }
        } else {
            // Players whose improvement in that colour shows the red die's number draw a card,
            // starting with the active player: level L shows 1..L+1.
            for (i in 0 until s.numPlayers) {
                val q = (p + i) % s.numPlayers
                val lvl = chivalry.level(q, track)
                if (lvl >= 1 && r <= lvl + 1) draw(s, q, track)
            }
        }
        chivalry.rollStage = STAGE_PRODUCE
        resume(s)
    }

    private fun barbarianAttack(s: GameState) {
        val chivalry = s.chivalry!!
        val n = s.numPlayers
        chivalry.barbarianAttacks++
        val strength = barbarianStrength(s)
        val defence = IntArray(n) { activeKnightStrength(s, it) }
        if (defence.sum() >= strength) {
            val best = defence.max()
            if (best > 0) {
                var mask = 0
                for (q in 0 until n) if (defence[q] == best) mask = mask or (1 shl q)
                if (Integer.bitCount(mask) == 1 && chivalry.defenderLeft > 0) {
                    val q = Integer.numberOfTrailingZeros(mask)
                    chivalry.defenderVp[q]++
                    chivalry.defenderLeft--
                } else {
                    // Tied defenders (or no Defender cards left) each draw a progress card of their choice.
                    chivalry.defenderDrawMask = mask
                }
            }
        } else {
            // Weakest defenders among players with a city that is not a metropolis each lose a city.
            var weakest = Int.MAX_VALUE
            for (q in 0 until n) if (hasPlainCity(s, q)) weakest = min(weakest, defence[q])
            for (q in 0 until n) if (hasPlainCity(s, q) && defence[q] == weakest) chivalry.barbLossMask = chivalry.barbLossMask or (1 shl q)
        }
        for (v in 0 until s.board.vertexCount) chivalry.knightFlags[v] = chivalry.knightFlags[v] and (ACTIVE or FRESH).inv()
    }

    private fun hasPlainCity(s: GameState, p: Int): Boolean {
        for (v in 0 until s.board.vertexCount) if (isPlainCity(s, p, v)) return true
        return false
    }

    /** City at [v] pillaged by the barbarians: back to a settlement, losing its wall. */
    private fun pillage(s: GameState, p: Int, v: Int) {
        val chivalry = s.chivalry!!
        if (chivalry.wall[v] != 0) {
            chivalry.wall[v] = 0
            chivalry.wallsLeft[p]++
        }
        s.piecesLeft[p * Piece.COUNT + Piece.CITY]++
        if (s.piecesLeft[p * Piece.COUNT + Piece.SETTLEMENT] > 0) {
            s.vertexBuilding[v] = Piece.SETTLEMENT
            s.piecesLeft[p * Piece.COUNT + Piece.SETTLEMENT]--
        } else {
            // No settlement left in supply: the city is removed outright.
            s.vertexOwner[v] = -1
            s.vertexBuilding[v] = 0
            var mask = 0
            for (w in 0 until s.board.vertexCount) if (s.vertexOwner[w] == p) mask = mask or s.board.vertexPortBit[w]
            s.portMask[p] = mask
            recomputeRoads(s)
        }
    }

    /** Pays out a non-7 roll: cities on forest/pasture/mountains give 1 resource + 1 commodity. */
    private fun produce(s: GameState, roll: Int) {
        val chivalry = s.chivalry!!
        val b = s.board
        val n = s.numPlayers
        val owed = IntArray(n * CHIVALRY_CARD_TYPES)
        for (h in 0 until b.hexCount) {
            if (chivalry.hexNumber[h] != roll || h == s.robberHex) continue
            val r = b.hexResource[h]
            if (r < 0) continue
            val com = Commodity.fromTerrain(r)
            for (i in 0 until 6) {
                val v = b.hexVerts[h * 6 + i]
                val o = s.vertexOwner[v]
                if (o < 0) continue
                if (s.vertexBuilding[v] != Piece.CITY) owed[o * CHIVALRY_CARD_TYPES + r]++
                else if (com >= 0) {
                    owed[o * CHIVALRY_CARD_TYPES + r]++
                    owed[o * CHIVALRY_CARD_TYPES + com]++
                } else {
                    owed[o * CHIVALRY_CARD_TYPES + r] += 2
                }
            }
        }
        var received = 0
        for (c in 0 until CHIVALRY_CARD_TYPES) {
            var total = 0
            var recipients = 0
            var last = -1
            for (q in 0 until n) {
                val x = owed[q * CHIVALRY_CARD_TYPES + c]
                if (x > 0) {
                    total += x
                    recipients++
                    last = q
                }
            }
            if (total == 0) continue
            val bank = bankOf(s, c)
            if (total <= bank) {
                for (q in 0 until n) {
                    val x = owed[q * CHIVALRY_CARD_TYPES + c]
                    if (x > 0) {
                        fromBank(s, q, c, x)
                        received = received or (1 shl q)
                    }
                }
            } else if (recipients == 1 && bank > 0) {
                fromBank(s, last, c, bank)
                received = received or (1 shl last)
            }
        }
        var aqueduct = 0
        for (q in 0 until n) {
            if (received and (1 shl q) == 0 && chivalry.level(q, Track.SCIENCE) >= Track.ABILITY_LEVEL) aqueduct = aqueduct or (1 shl q)
        }
        chivalry.aqueductMask = aqueduct
    }

    /** First player in [mask] in seat order from the current player. */
    private fun firstIn(s: GameState, mask: Int): Int {
        for (i in 0 until s.numPlayers) {
            val q = (s.currentPlayer + i) % s.numPlayers
            if (mask and (1 shl q) != 0) return q
        }
        return -1
    }

    /**
     * Continues after any decision: progress-hand overflow, lost cities, defender draws,
     * then the pending stages of the dice roll, then pending discards, then back to the turn.
     */
    internal fun resume(s: GameState) {
        val chivalry = s.chivalry!!
        val n = s.numPlayers
        while (true) {
            for (i in 0 until n) {
                val q = (s.currentPlayer + i) % n
                if (chivalry.progressCount(q) > Progress.HAND_LIMIT) {
                    s.phase = Phase.PROGRESS_DISCARD
                    s.actingPlayer = q
                    return
                }
            }
            if (chivalry.barbLossMask != 0) {
                s.phase = Phase.BARBARIAN_LOSS
                s.actingPlayer = firstIn(s, chivalry.barbLossMask)
                return
            }
            if (chivalry.defenderDrawMask != 0) {
                if (chivalry.deckSize.sum() == 0) {
                    chivalry.defenderDrawMask = 0
                } else {
                    s.phase = Phase.DEFENDER_DRAW
                    s.actingPlayer = firstIn(s, chivalry.defenderDrawMask)
                    return
                }
            }
            when (chivalry.rollStage) {
                STAGE_PRODUCE -> {
                    if (s.lastRoll == 7) {
                        for (q in 0 until n) {
                            val c = s.cardCount(q)
                            s.discardOwed[q] = if (c > discardLimit(s, q)) c / 2 else 0
                        }
                        chivalry.rollStage = STAGE_SEVEN
                    } else {
                        produce(s, s.lastRoll)
                        chivalry.rollStage = STAGE_AQUEDUCT
                    }
                    continue
                }

                STAGE_AQUEDUCT -> {
                    if (chivalry.aqueductMask != 0 && s.bank.sum() > 0) {
                        s.phase = Phase.AQUEDUCT
                        s.actingPlayer = firstIn(s, chivalry.aqueductMask)
                        return
                    }
                    chivalry.aqueductMask = 0
                    chivalry.rollStage = STAGE_NONE
                    continue
                }

                STAGE_SEVEN -> {
                    val debtor = Rules.nextDebtor(s, s.currentPlayer)
                    if (debtor >= 0) {
                        s.phase = Phase.DISCARD
                        s.actingPlayer = debtor
                        return
                    }
                    chivalry.rollStage = STAGE_NONE
                    // The robber stays in the desert until the barbarians first attack.
                    if (chivalry.robberActive) {
                        s.phase = Phase.MOVE_ROBBER
                        s.actingPlayer = s.currentPlayer
                        return
                    }
                    continue
                }
            }
            // Saboteur discards.
            val debtor = Rules.nextDebtor(s, s.currentPlayer)
            if (debtor >= 0) {
                s.phase = Phase.DISCARD
                s.actingPlayer = debtor
                return
            }
            s.phase = Phase.PLAY_TURN
            s.actingPlayer = s.currentPlayer
            return
        }
    }

    // ---------------------------------------------------------------- decisions

    private fun chooseCard(s: GameState, p: Int, c: Int) {
        val chivalry = s.chivalry!!
        val cur = s.currentPlayer
        when (s.phase) {
            Phase.AQUEDUCT -> {
                fromBank(s, p, c, 1)
                chivalry.aqueductMask = chivalry.aqueductMask and (1 shl p).inv()
                resume(s)
            }

            Phase.COMMERCIAL_HARBOR_RESPOND -> {
                transfer(s, p, cur, c, 1)
                chivalry.pendingTarget = -1
                s.phase = Phase.COMMERCIAL_HARBOR
                s.actingPlayer = cur
            }

            Phase.MASTER_MERCHANT -> {
                val q = chivalry.pendingTarget
                transfer(s, q, p, c, 1)
                chivalry.pendingCount--
                if (chivalry.pendingCount == 0 || s.cardCount(q) == 0) {
                    chivalry.pendingCount = 0
                    chivalry.pendingTarget = -1
                    resume(s)
                }
            }

            else -> { // WEDDING
                transfer(s, p, cur, c, 1)
                chivalry.giveOwed[p]--
                val next = nextGiver(s)
                if (next >= 0) s.actingPlayer = next else resume(s)
            }
        }
    }

    private fun nextGiver(s: GameState): Int {
        val chivalry = s.chivalry!!
        for (i in 0 until s.numPlayers) {
            val q = (s.currentPlayer + i) % s.numPlayers
            if (chivalry.giveOwed[q] > 0) return q
        }
        return -1
    }

    private fun chooseVertex(s: GameState, p: Int, v: Int) {
        val chivalry = s.chivalry!!
        when (s.phase) {
            Phase.BARBARIAN_LOSS -> {
                pillage(s, p, v)
                chivalry.barbLossMask = chivalry.barbLossMask and (1 shl p).inv()
                resume(s)
            }

            Phase.DESERTER_REMOVE -> {
                val lvl = chivalry.knightLevel[v]
                val active = chivalry.knightFlags[v] and ACTIVE
                clearKnight(chivalry, v)
                chivalry.knightsLeft[p * ChivalryPiece.MAX_KNIGHT_LEVEL + lvl - 1]++
                recomputeRoads(s)
                val cur = s.currentPlayer
                chivalry.pendingTarget = -1
                var spot = false
                for (w in 0 until s.board.vertexCount) if (canPlaceKnightAt(s, cur, w)) spot = true
                if (spot && chivalry.knightsLeft[cur * ChivalryPiece.MAX_KNIGHT_LEVEL + lvl - 1] > 0) {
                    // The deserter's strength and state carry over; the knight itself is not a held piece.
                    chivalry.heldKnight = lvl or (active shl 2)
                    chivalry.heldOwner = cur
                    s.phase = Phase.DESERTER_PLACE
                    s.actingPlayer = cur
                } else {
                    resume(s)
                }
            }

            else -> { // DESERTER_PLACE
                val held = chivalry.heldKnight
                val lvl = held and 3
                chivalry.knightsLeft[p * ChivalryPiece.MAX_KNIGHT_LEVEL + lvl - 1]--
                placeKnight(s, p, v, lvl, (held shr 2) and ACTIVE)
                chivalry.heldKnight = 0
                chivalry.heldOwner = -1
                recomputeRoads(s)
                resume(s)
            }
        }
    }

    // ---------------------------------------------------------------- progress cards

    private fun playProgress(s: GameState, p: Int, card: Int, a1: Int, a2: Int) {
        val chivalry = s.chivalry!!
        val b = s.board
        val n = s.numPlayers
        chivalry.progressHand[p * Progress.COUNT + card]--
        toDeckBottom(chivalry, card)
        when (card) {
            Progress.ALCHEMIST -> roll(s, p, a1, a2)
            Progress.CRANE -> improve(s, p, a1, a2 - 1, 1)
            Progress.ENGINEER -> {
                chivalry.wall[a1] = 1
                chivalry.wallsLeft[p]--
            }

            Progress.INVENTOR -> {
                val t = chivalry.hexNumber[a1]
                chivalry.hexNumber[a1] = chivalry.hexNumber[a2]
                chivalry.hexNumber[a2] = t
            }

            Progress.IRRIGATION, Progress.MINING -> {
                val r = if (card == Progress.IRRIGATION) Resource.WHEAT else Resource.ORE
                var hexes = 0
                for (h in 0 until b.hexCount) if (b.hexResource[h] == r && touchesOwnBuilding(s, p, h)) hexes++
                fromBank(s, p, r, min(2 * hexes, s.bank[r]))
            }

            Progress.MEDICINE -> {
                Rules.pay(s, p, ChivalryCost.MEDICINE)
                upgradeToCity(s, p, a1)
            }

            Progress.ROAD_BUILDING -> s.freeRoads = min(2, Rules.piecesLeft(s, p, Piece.ROAD))
            Progress.SMITH -> chivalry.freePromotions = 2
            Progress.COMMERCIAL_HARBOR -> {
                chivalry.chDoneMask = 0
                s.phase = Phase.COMMERCIAL_HARBOR
                return
            }

            Progress.MASTER_MERCHANT -> {
                chivalry.pendingTarget = a1
                chivalry.pendingCount = min(2, s.cardCount(a1))
                s.phase = Phase.MASTER_MERCHANT
                return
            }

            Progress.MERCHANT -> {
                chivalry.merchantHex = a1
                chivalry.merchantOwner = p
            }

            Progress.MERCHANT_FLEET -> chivalry.fleetMask = chivalry.fleetMask or (1 shl a1)
            Progress.RESOURCE_MONOPOLY, Progress.TRADE_MONOPOLY -> {
                val most = if (card == Progress.RESOURCE_MONOPOLY) 2 else 1
                for (q in 0 until n) if (q != p) transfer(s, q, p, a1, min(most, s.card(q, a1)))
            }

            Progress.BISHOP -> {
                s.robberHex = a1
                val victims = robberVictims(s, p, a1)
                for (q in 0 until n) if (victims and (1 shl q) != 0) transfer(s, q, p, randomCard(s, q), 1)
            }

            Progress.DESERTER -> {
                chivalry.pendingTarget = a1
                s.phase = Phase.DESERTER_REMOVE
                s.actingPlayer = a1
                return
            }

            Progress.DIPLOMAT -> {
                val q = s.edgeOwner[a1]
                s.edgeOwner[a1] = -1
                s.piecesLeft[q * Piece.COUNT + Piece.ROAD]++
                recomputeRoads(s)
                // Removing your own road lets you place it again for free (optional).
                if (q == p && Rules.anyRoadSpot(s, p)) s.freeRoads = 1
            }

            Progress.INTRIGUE -> {
                val q = chivalry.knightOwner[a1]
                chivalry.heldKnight = chivalry.knightLevel[a1] or ((chivalry.knightFlags[a1] and ACTIVE) shl 2)
                chivalry.heldOwner = q
                chivalry.heldFrom = a1
                clearKnight(chivalry, a1)
                displaced(s)
                return
            }

            Progress.SABOTEUR -> {
                val mine = s.victoryPoints(p)
                for (q in 0 until n) if (q != p && s.victoryPoints(q) >= mine) s.discardOwed[q] = s.cardCount(q) / 2
            }

            Progress.SPY -> {
                chivalry.pendingTarget = a1
                s.phase = Phase.SPY
                return
            }

            Progress.WARLORD -> for (v in 0 until b.vertexCount) {
                if (chivalry.knightOwner[v] == p && !chivalry.isActive(v)) chivalry.knightFlags[v] = chivalry.knightFlags[v] or ACTIVE or FRESH
            }

            Progress.WEDDING -> {
                val mine = s.victoryPoints(p)
                for (q in 0 until n) if (q != p && s.victoryPoints(q) > mine) chivalry.giveOwed[q] = min(2, s.cardCount(q))
                val next = nextGiver(s)
                if (next >= 0) {
                    s.phase = Phase.WEDDING
                    s.actingPlayer = next
                    return
                }
            }
        }
        if (card != Progress.ALCHEMIST) resume(s)
    }
}

package wgbg.engine

/**
 * Age of Chivalry part of a [GameState] (null in base games). Flat arrays like the
 * rest of the state so [copy] stays cheap. Rules live in [Chivalry].
 */
class ChivalryState internal constructor(
    /** Commodity cards, `player * Commodity.COUNT + local commodity`. */
    val commodities: IntArray,
    val commodityBank: IntArray,
    /** Number token per hex; mutable in Age of Chivalry because of the Inventor. */
    val hexNumber: IntArray,
    /** City-improvement level per track, `player * Track.COUNT + track`. */
    val trackLevel: IntArray,
    /** Metropolis holder per track, -1 if unclaimed. */
    val metroOwner: IntArray,
    /** Vertex of the city carrying each track's metropolis, -1 if unclaimed. */
    val metroVertex: IntArray,
    /** 1 if the city at the vertex has a city wall. */
    val wall: IntArray,
    val wallsLeft: IntArray,
    /** Knight owner per vertex, -1 if none. */
    val knightOwner: IntArray,
    /** Knight strength 1..3 per vertex (0 if none). */
    val knightLevel: IntArray,
    /** [ACTIVE] / [FRESH] / [PROMOTED] bits per vertex. */
    val knightFlags: IntArray,
    /** Knights in supply, `player * 3 + (level - 1)`. */
    val knightsLeft: IntArray,
    /** Progress cards in hand, `player * Progress.COUNT + card`. */
    val progressHand: IntArray,
    /** Printer / Constitution cards revealed. */
    val progressVp: IntArray,
    /** Defender of the Realm cards held. */
    val defenderVp: IntArray,
    /** Three circular decks of [Progress.PER_DECK] slots each, top at [deckHead]. */
    val deck: IntArray,
    val deckHead: IntArray,
    val deckSize: IntArray,
    /** Cards each player still has to hand over (Wedding). */
    val giveOwed: IntArray,
    /** Commodity part of a domestic offer: given (3) then requested (3). */
    val offer: IntArray,
) {
    /** Ship position on the barbarian track (0..6). */
    var barbarianPos = 0
    /** Attacks so far; the robber can move only after the first one. */
    var barbarianAttacks = 0
    var lastRed = 0
    /** Last event die face 0..5, -1 before the first roll. */
    var lastEvent = -1
    /** Where an interrupted dice roll resumes (see [Chivalry.resume]). */
    var rollStage = 0
    /** Players that must still pick a city to lose to the barbarians. */
    var barbLossMask = 0
    /** Players tied as best defenders who still pick a progress deck to draw from. */
    var defenderDrawMask = 0
    /** Players entitled to an Aqueduct resource for this roll. */
    var aqueductMask = 0
    var merchantHex = -1
    var merchantOwner = -1
    /** Card types the current player trades 2:1 this turn (Merchant Fleet). */
    var fleetMask = 0
    /** Remaining free promotions from a Smith. */
    var freePromotions = 0
    /** Opponent targeted by the card being resolved. */
    var pendingTarget = -1
    /** Cards still to take (Master Merchant). */
    var pendingCount = 0
    /** Knight taken off the board, waiting to be relocated or replaced: level | active << 2; 0 = none. */
    var heldKnight = 0
    var heldOwner = -1
    var heldFrom = -1
    /** Opponents already offered a Commercial Harbor trade this card. */
    var chDoneMask = 0
    var defenderLeft = ChivalryPiece.DEFENDER_CARDS

    val robberActive get() = barbarianAttacks > 0

    fun commodity(p: Int, local: Int) = commodities[p * Commodity.COUNT + local]

    fun commodityCount(p: Int): Int {
        val b = p * Commodity.COUNT
        return commodities[b] + commodities[b + 1] + commodities[b + 2]
    }

    fun level(p: Int, track: Int) = trackLevel[p * Track.COUNT + track]

    fun progressCount(p: Int): Int {
        var n = 0
        val b = p * Progress.COUNT
        for (c in 0 until Progress.COUNT) n += progressHand[b + c]
        return n
    }

    fun metropolises(p: Int): Int {
        var n = 0
        for (t in 0 until Track.COUNT) if (metroOwner[t] == p) n++
        return n
    }

    fun isMetropolis(v: Int): Boolean = metroVertex[0] == v || metroVertex[1] == v || metroVertex[2] == v

    fun extraVictoryPoints(p: Int): Int =
        ChivalryPiece.METROPOLIS_VP * metropolises(p) + defenderVp[p] + progressVp[p] + (if (merchantOwner == p) 1 else 0)

    fun isActive(v: Int) = knightFlags[v] and ACTIVE != 0

    fun copy(): ChivalryState {
        val c = ChivalryState(
            commodities.copyOf(), commodityBank.copyOf(), hexNumber.copyOf(), trackLevel.copyOf(),
            metroOwner.copyOf(), metroVertex.copyOf(), wall.copyOf(), wallsLeft.copyOf(),
            knightOwner.copyOf(), knightLevel.copyOf(), knightFlags.copyOf(), knightsLeft.copyOf(),
            progressHand.copyOf(), progressVp.copyOf(), defenderVp.copyOf(), deck.copyOf(),
            deckHead.copyOf(), deckSize.copyOf(), giveOwed.copyOf(), offer.copyOf(),
        )
        c.barbarianPos = barbarianPos
        c.barbarianAttacks = barbarianAttacks
        c.lastRed = lastRed
        c.lastEvent = lastEvent
        c.rollStage = rollStage
        c.barbLossMask = barbLossMask
        c.defenderDrawMask = defenderDrawMask
        c.aqueductMask = aqueductMask
        c.merchantHex = merchantHex
        c.merchantOwner = merchantOwner
        c.fleetMask = fleetMask
        c.freePromotions = freePromotions
        c.pendingTarget = pendingTarget
        c.pendingCount = pendingCount
        c.heldKnight = heldKnight
        c.heldOwner = heldOwner
        c.heldFrom = heldFrom
        c.chDoneMask = chDoneMask
        c.defenderLeft = defenderLeft
        return c
    }

    internal fun hashInto(mix: (Int) -> Unit) {
        fun arr(a: IntArray) {
            mix(a.size)
            for (x in a) mix(x)
        }
        arr(commodities); arr(commodityBank); arr(hexNumber); arr(trackLevel); arr(metroOwner); arr(metroVertex)
        arr(wall); arr(wallsLeft); arr(knightOwner); arr(knightLevel); arr(knightFlags); arr(knightsLeft)
        arr(progressHand); arr(progressVp); arr(defenderVp); arr(deck); arr(deckHead); arr(deckSize)
        arr(giveOwed); arr(offer)
        for (x in intArrayOf(
            barbarianPos, barbarianAttacks, lastRed, lastEvent, rollStage, barbLossMask, defenderDrawMask,
            aqueductMask, merchantHex, merchantOwner, fleetMask, freePromotions, pendingTarget, pendingCount,
            heldKnight, heldOwner, heldFrom, chDoneMask, defenderLeft,
        )) mix(x)
    }

    companion object {
        /** Knight is active. */
        const val ACTIVE = 1
        /** Knight was activated this turn and may not act until next turn. */
        const val FRESH = 2
        /** Knight was promoted this turn (one promotion per knight per turn). */
        const val PROMOTED = 4

        internal fun initial(n: Int, board: Board, rng: Rng): ChivalryState {
            val deck = IntArray(Track.COUNT * Progress.PER_DECK)
            val fill = IntArray(Track.COUNT)
            for (c in 0 until Progress.COUNT) {
                val t = Progress.track(c)
                repeat(Progress.DECK[c]) { deck[t * Progress.PER_DECK + fill[t]++] = c }
            }
            // Shuffle each colour deck (drawn from the shared RNG after board generation).
            val tmp = IntArray(Progress.PER_DECK)
            for (t in 0 until Track.COUNT) {
                deck.copyInto(tmp, 0, t * Progress.PER_DECK, (t + 1) * Progress.PER_DECK)
                rng.shuffle(tmp)
                tmp.copyInto(deck, t * Progress.PER_DECK)
            }
            return ChivalryState(
                commodities = IntArray(n * Commodity.COUNT),
                commodityBank = IntArray(Commodity.COUNT) { Commodity.BANK },
                hexNumber = board.hexNumber.copyOf(),
                trackLevel = IntArray(n * Track.COUNT),
                metroOwner = IntArray(Track.COUNT) { -1 },
                metroVertex = IntArray(Track.COUNT) { -1 },
                wall = IntArray(board.vertexCount),
                wallsLeft = IntArray(n) { ChivalryPiece.WALLS },
                knightOwner = IntArray(board.vertexCount) { -1 },
                knightLevel = IntArray(board.vertexCount),
                knightFlags = IntArray(board.vertexCount),
                knightsLeft = IntArray(n * ChivalryPiece.MAX_KNIGHT_LEVEL) { ChivalryPiece.KNIGHTS_PER_LEVEL },
                progressHand = IntArray(n * Progress.COUNT),
                progressVp = IntArray(n),
                defenderVp = IntArray(n),
                deck = deck,
                deckHead = IntArray(Track.COUNT),
                deckSize = IntArray(Track.COUNT) { Progress.PER_DECK },
                giveOwed = IntArray(n),
                offer = IntArray(2 * Commodity.COUNT),
            )
        }
    }
}

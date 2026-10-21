package wgbg.engine

/**
 * Turn phases, modelled on Catanatron's ActionPrompt. Expansions add entries
 * (e.g. Age of Chivalry's progress-card or barbarian prompts).
 */
enum class Phase {
    BUILD_INITIAL_SETTLEMENT,
    BUILD_INITIAL_ROAD,
    PLAY_TURN,
    DISCARD,
    MOVE_ROBBER,
    DECIDE_TRADE,
    DECIDE_ACCEPTEES,
    GAME_OVER,

    // Age of Chivalry prompts (appended so base ordinals stay stable).
    /** Acting player holds more than 4 progress cards and discards one (DISCARD_PROGRESS). */
    PROGRESS_DISCARD,
    /** Barbarians won: acting player picks a city to lose (CHOOSE_VERTEX). */
    BARBARIAN_LOSS,
    /** Barbarians beaten with tied best defenders: acting player picks a deck (CHOOSE_PROGRESS_DECK). */
    DEFENDER_DRAW,
    /** Acting player got nothing from production and takes a resource via Aqueduct (CHOOSE_CARD). */
    AQUEDUCT,
    /** Acting player moves their displaced knight (RELOCATE_KNIGHT). */
    RELOCATE_KNIGHT,
    /** Commercial Harbor: offer a resource to an opponent (COMMERCIAL_HARBOR_OFFER) or stop (CHIVALRY_DONE). */
    COMMERCIAL_HARBOR,
    /** Commercial Harbor: the acting opponent returns a commodity of their choice (CHOOSE_CARD). */
    COMMERCIAL_HARBOR_RESPOND,
    /** Master Merchant: take a card from the target's hand (CHOOSE_CARD). */
    MASTER_MERCHANT,
    /** Wedding: acting opponent hands over a card of their choice (CHOOSE_CARD). */
    WEDDING,
    /** Deserter: acting opponent removes one of their knights (CHOOSE_VERTEX). */
    DESERTER_REMOVE,
    /** Deserter: place a knight of equal strength (CHOOSE_VERTEX) or decline (CHIVALRY_DONE). */
    DESERTER_PLACE,
    /** Spy: take a progress card from the target's hand (TAKE_PROGRESS). */
    SPY,

    // Appended after the Age of Chivalry prompts so every earlier ordinal stays stable.
    /** No longer entered (0.2.10: counters are collected with the round, see [GameState.counters]); kept for ordinals. */
    DECIDE_COUNTER,
}

/**
 * Complete mutable game state in flat primitive arrays. [config] and [board]
 * are immutable and shared; [copy] deep-clones everything else.
 *
 * Per-player arrays are indexed `player * stride + kind`.
 */
class GameState internal constructor(
    val config: GameConfig,
    val board: Board,
    val rng: Rng,
    /** Owner of the building at each vertex, -1 if empty. */
    val vertexOwner: IntArray,
    /** 0 = empty, [Piece.SETTLEMENT] or [Piece.CITY]. */
    val vertexBuilding: IntArray,
    val edgeOwner: IntArray,
    val bank: IntArray,
    /** Resource cards, `player * CARD_TYPES + resource`. */
    val hands: IntArray,
    /** Undrawn development cards per type. */
    val devDeck: IntArray,
    /** Unplayed dev cards (including VP cards), `player * DevCard.COUNT + card`. */
    val devHand: IntArray,
    /** Dev cards bought this turn (not yet playable), same layout as [devHand]. */
    val devNew: IntArray,
    val devPlayed: IntArray,
    /** Pieces still in supply, `player * Piece.COUNT + piece`. */
    val piecesLeft: IntArray,
    /** OR of [Port.bit] for every port a player has a building on. */
    val portMask: IntArray,
    /** Current longest-road length per player. */
    val roadLength: IntArray,
    /** Cards each player still has to discard after a 7. */
    val discardOwed: IntArray,
    /** Current domestic trade: offered cards then requested cards (2 * CARD_TYPES). */
    val tradeOffer: IntArray,
    /** Age of Chivalry state, null in base games. */
    val chivalry: ChivalryState? = null,
) {
    val numPlayers get() = config.numPlayers

    var phase = Phase.BUILD_INITIAL_SETTLEMENT
    /** Player whose turn it is. */
    var currentPlayer = 0
    /** Player who must choose the next action (differs during discards and trade responses). */
    var actingPlayer = 0
    /** Completed regular turns (setup not counted). */
    var turn = 0
    /** Index into the setup snake order (0 until 2 * numPlayers). */
    var setupStep = 0
    /** Seat that places first in setup and takes the first regular turn (#126). */
    var firstPlayer = 0
    var lastSettlement = -1
    var robberHex = -1
    var hasRolled = false
    var devPlayedThisTurn = false
    /** Remaining free roads from a Road Building card. */
    var freeRoads = 0
    /** Sum of the last dice roll (0 before the first roll). */
    var lastRoll = 0
    var longestRoadOwner = -1
    var largestArmyOwner = -1
    /** Bitmask of players that accepted the current trade offer. */
    var tradeAccepted = 0
    /**
     * Counter-offers made in the open trade round, per player: the COUNTER_TRADE (base) or COUNTER_TRADE_CK
     * payload, from the offerer's side; 0 = none. The offerer picks one (CONFIRM_TRADE) when the round ends.
     */
    val counters = LongArray(config.numPlayers)
    /** Winning player, or -1 (also when the game ended by the turn limit). */
    var winner = -1

    val isOver get() = phase == Phase.GAME_OVER

    /** [q]'s counter-offer in the open round as a COUNTER_TRADE / COUNTER_TRADE_CK action, or null. */
    fun counterOf(q: Int): Action? = if (counters[q] == 0L) null
        else Action.of(if (chivalry != null) ChivalryActionType.COUNTER_TRADE_CK else ActionType.COUNTER_TRADE, counters[q])

    val anyCounter: Boolean get() = counters.any { it != 0L }

    fun hand(p: Int, r: Int) = hands[p * CARD_TYPES + r]

    fun handSize(p: Int): Int {
        var n = 0
        val base = p * CARD_TYPES
        for (r in 0 until CARD_TYPES) n += hands[base + r]
        return n
    }

    fun devCount(p: Int): Int {
        var n = 0
        val base = p * DevCard.COUNT
        for (d in 0 until DevCard.COUNT) n += devHand[base + d]
        return n
    }

    fun piecesOnBoard(p: Int, piece: Int) = Piece.LIMITS[piece] - piecesLeft[p * Piece.COUNT + piece]

    fun knightsPlayed(p: Int) = devPlayed[p * DevCard.COUNT + DevCard.KNIGHT]

    /** Points everyone can see (no hidden VP cards). */
    fun publicVictoryPoints(p: Int): Int =
        piecesOnBoard(p, Piece.SETTLEMENT) + 2 * piecesOnBoard(p, Piece.CITY) +
            (if (longestRoadOwner == p) 2 else 0) + (if (largestArmyOwner == p) 2 else 0) +
            (chivalry?.extraVictoryPoints(p) ?: 0)

    /** Resource cards plus (Age of Chivalry) commodity cards. */
    fun cardCount(p: Int): Int = handSize(p) + (chivalry?.commodityCount(p) ?: 0)

    /** Cards of type [c] in [p]'s hand; Age of Chivalry card index space (commodities 5..7). */
    fun card(p: Int, c: Int): Int =
        if (c < Resource.COUNT) hands[p * CARD_TYPES + c] else chivalry!!.commodities[p * Commodity.COUNT + c - Resource.COUNT]

    /** Actual points, including hidden VP cards. */
    /** The number on hex [h] now: an Inventor can swap them in Age of Chivalry, so draw and score from this, not [Board.hexNumber]. */
    fun numberAt(h: Int): Int = chivalry?.hexNumber?.get(h) ?: board.hexNumber[h]

    fun victoryPoints(p: Int) = publicVictoryPoints(p) + devHand[p * DevCard.COUNT + DevCard.VICTORY_POINT]

    /**
     * Steals that only the thief and the victim saw: `thief * numPlayers + victim` -> count. Search AIs
     * resample those cards for everyone else ([HiddenInfo.determinize]). Bookkeeping only: it doesn't change
     * the game, so it isn't part of [stateHash]; replaying the moves rebuilds it.
     */
    val unseenSteals = IntArray(config.numPlayers * config.numPlayers)

    fun copy(): GameState {
        val s = GameState(
            config, board, rng.copy(),
            vertexOwner.copyOf(), vertexBuilding.copyOf(), edgeOwner.copyOf(), bank.copyOf(),
            hands.copyOf(), devDeck.copyOf(), devHand.copyOf(), devNew.copyOf(), devPlayed.copyOf(),
            piecesLeft.copyOf(), portMask.copyOf(), roadLength.copyOf(), discardOwed.copyOf(),
            tradeOffer.copyOf(), chivalry?.copy(),
        )
        s.phase = phase
        s.currentPlayer = currentPlayer
        s.actingPlayer = actingPlayer
        s.turn = turn
        s.setupStep = setupStep
        s.firstPlayer = firstPlayer
        s.lastSettlement = lastSettlement
        s.robberHex = robberHex
        s.hasRolled = hasRolled
        s.devPlayedThisTurn = devPlayedThisTurn
        s.freeRoads = freeRoads
        s.lastRoll = lastRoll
        s.longestRoadOwner = longestRoadOwner
        s.largestArmyOwner = largestArmyOwner
        s.tradeAccepted = tradeAccepted
        counters.copyInto(s.counters)
        s.winner = winner
        unseenSteals.copyInto(s.unseenSteals)
        return s
    }

    /** 64-bit FNV-1a hash of the whole state (including board and RNG), for determinism checks. */
    fun stateHash(): Long {
        var h = -0x340d631b7bdddcdbL
        fun mix(x: Int) {
            h = (h xor (x.toLong() and 0xffffffffL)) * 0x100000001b3L
        }
        fun mix(a: IntArray) {
            mix(a.size)
            for (x in a) mix(x)
        }
        mix(vertexOwner); mix(vertexBuilding); mix(edgeOwner); mix(bank); mix(hands)
        mix(devDeck); mix(devHand); mix(devNew); mix(devPlayed); mix(piecesLeft)
        mix(portMask); mix(roadLength); mix(discardOwed); mix(tradeOffer)
        mix(board.hexResource); mix(board.hexNumber)
        for (p in board.ports) mix(p.type)
        mix(phase.ordinal); mix(currentPlayer); mix(actingPlayer); mix(turn); mix(setupStep)
        if (firstPlayer != 0) mix(firstPlayer)   // only when set, so seat-0 games hash as before
        mix(lastSettlement); mix(robberHex); mix(if (hasRolled) 1 else 0)
        mix(if (devPlayedThisTurn) 1 else 0); mix(freeRoads); mix(lastRoll)
        mix(longestRoadOwner); mix(largestArmyOwner); mix(tradeAccepted); mix(winner)
        mix((rng.seed ushr 32).toInt()); mix(rng.seed.toInt())
        // Counters are mixed in only while one is open, so older hashes are unchanged.
        for (q in counters.indices) if (counters[q] != 0L) { mix(q); mix((counters[q] ushr 32).toInt()); mix(counters[q].toInt()) }
        // Base games hash exactly as before; Age of Chivalry state is appended only when present.
        chivalry?.hashInto(::mix)
        return h
    }

    companion object {
        /** Fresh state at the start of setup. Prefer [Rules.newGame]. */
        fun initial(config: GameConfig, board: Board, rng: Rng): GameState {
            val n = config.numPlayers
            val s = GameState(
                config, board, rng,
                vertexOwner = IntArray(board.vertexCount) { -1 },
                vertexBuilding = IntArray(board.vertexCount),
                edgeOwner = IntArray(board.edgeCount) { -1 },
                bank = IntArray(CARD_TYPES) { BANK_PER_RESOURCE },
                hands = IntArray(n * CARD_TYPES),
                devDeck = DevCard.DECK.copyOf(),
                devHand = IntArray(n * DevCard.COUNT),
                devNew = IntArray(n * DevCard.COUNT),
                devPlayed = IntArray(n * DevCard.COUNT),
                piecesLeft = IntArray(n * Piece.COUNT) { Piece.LIMITS[it % Piece.COUNT] },
                portMask = IntArray(n),
                roadLength = IntArray(n),
                discardOwed = IntArray(n),
                tradeOffer = IntArray(2 * CARD_TYPES),
                chivalry = if (config.chivalry) ChivalryState.initial(n, board, rng) else null,
            )
            s.robberHex = board.desertHex
            return s
        }
    }
}

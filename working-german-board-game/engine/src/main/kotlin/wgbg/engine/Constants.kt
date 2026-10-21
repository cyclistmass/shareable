package wgbg.engine

/**
 * Resource card indices, in Catanatron's order. Age of Chivalry commodities
 * (paper, cloth, coin) will be appended after ORE by growing [CARD_TYPES].
 */
object Resource {
    const val WOOD = 0
    const val BRICK = 1
    const val SHEEP = 2
    const val WHEAT = 3
    const val ORE = 4
    const val COUNT = 5
    val NAMES = listOf("WOOD", "BRICK", "SHEEP", "WHEAT", "ORE")

    /** Terrain value for hexes that produce nothing. */
    const val NONE = -1
}

/** Number of card slots in hands and bank (resources only in the base game). */
const val CARD_TYPES = Resource.COUNT

/** Each resource has this many cards in the base game. */
const val BANK_PER_RESOURCE = 19

/** Development card indices, in Catanatron's order. */
object DevCard {
    const val KNIGHT = 0
    const val YEAR_OF_PLENTY = 1
    const val MONOPOLY = 2
    const val ROAD_BUILDING = 3
    const val VICTORY_POINT = 4
    const val COUNT = 5
    val NAMES = listOf("KNIGHT", "YEAR_OF_PLENTY", "MONOPOLY", "ROAD_BUILDING", "VICTORY_POINT")

    /** Base-game deck composition (25 cards). */
    val DECK = intArrayOf(14, 2, 2, 2, 5)
}

/** Piece kinds a player owns a limited supply of. */
object Piece {
    const val ROAD = 0
    const val SETTLEMENT = 1
    const val CITY = 2
    const val COUNT = 3
    val LIMITS = intArrayOf(15, 5, 4)
}

/** Building costs as per-card-type counts. */
object Cost {
    val ROAD = intArrayOf(1, 1, 0, 0, 0)
    val SETTLEMENT = intArrayOf(1, 1, 1, 1, 0)
    val CITY = intArrayOf(0, 0, 0, 2, 3)
    val DEV_CARD = intArrayOf(0, 0, 1, 1, 1)
}

// ------------------------------------------------------------------ Age of Chivalry

/**
 * Age of Chivalry commodities. In actions that name a card (DISCARD, MARITIME_TRADE, CHOOSE_CARD, ...)
 * Age of Chivalry uses one card index space: resources 0..4 then commodities [PAPER]..[COIN] = 5..7.
 * Commodity hands live in [ChivalryState.commodities], indexed by [local] (0..2).
 */
object Commodity {
    const val PAPER = 5
    const val CLOTH = 6
    const val COIN = 7
    const val COUNT = 3
    /** Commodity cards per type in the bank. */
    const val BANK = 12
    val NAMES = listOf("PAPER", "CLOTH", "COIN")

    /** Card index -> local commodity index (0..2). */
    fun local(card: Int) = card - Resource.COUNT

    /** Commodity card produced (with one resource) by a city on terrain [resource], or -1 (hills, fields). */
    fun fromTerrain(resource: Int) = when (resource) {
        Resource.WOOD -> PAPER
        Resource.SHEEP -> CLOTH
        Resource.ORE -> COIN
        else -> -1
    }
}

/** Number of card types (resources + commodities) in Age of Chivalry's card index space. */
const val CHIVALRY_CARD_TYPES = Resource.COUNT + Commodity.COUNT

/** Card names for Age of Chivalry's card index space (resources then commodities). */
val CARD_NAMES = Resource.NAMES + Commodity.NAMES

/** City-improvement tracks, which are also the progress-card colours and event-die gates. */
object Track {
    /** Yellow: cloth, Trading House at level 3, trade progress cards. */
    const val TRADE = 0
    /** Blue: coin, Fortress at level 3, politics progress cards. */
    const val POLITICS = 1
    /** Green: paper, Aqueduct at level 3, science progress cards. */
    const val SCIENCE = 2
    const val COUNT = 3
    const val MAX_LEVEL = 5
    /** Level that unlocks the track's ability. */
    const val ABILITY_LEVEL = 3
    /** Level at which the metropolis can first be claimed. */
    const val METROPOLIS_LEVEL = 4
    val NAMES = listOf("TRADE", "POLITICS", "SCIENCE")

    /** Commodity (card index) used to build improvements in [track]. */
    fun commodity(track: Int) = when (track) {
        TRADE -> Commodity.CLOTH
        POLITICS -> Commodity.COIN
        else -> Commodity.PAPER
    }
}

/** Event die faces, rolled as 0..5: three barbarian-ship faces, then the trade, politics and science gates. */
object EventDie {
    const val SHIP_FACES = 3

    /** Track of a gate face, or -1 for the ship. */
    fun gateTrack(face: Int) = if (face < SHIP_FACES) -1 else face - SHIP_FACES
}

/** Age of Chivalry progress cards. Ids are grouped by colour: science 0..9, trade 10..15, politics 16..24. */
object Progress {
    // Science (green)
    const val ALCHEMIST = 0
    const val CRANE = 1
    const val ENGINEER = 2
    const val INVENTOR = 3
    const val IRRIGATION = 4
    const val MEDICINE = 5
    const val MINING = 6
    const val PRINTER = 7
    const val ROAD_BUILDING = 8
    const val SMITH = 9
    // Trade (yellow)
    const val COMMERCIAL_HARBOR = 10
    const val MASTER_MERCHANT = 11
    const val MERCHANT = 12
    const val MERCHANT_FLEET = 13
    const val RESOURCE_MONOPOLY = 14
    const val TRADE_MONOPOLY = 15
    // Politics (blue)
    const val BISHOP = 16
    const val CONSTITUTION = 17
    const val DESERTER = 18
    const val DIPLOMAT = 19
    const val INTRIGUE = 20
    const val SABOTEUR = 21
    const val SPY = 22
    const val WARLORD = 23
    const val WEDDING = 24
    const val COUNT = 25

    val NAMES = listOf(
        "ALCHEMIST", "CRANE", "ENGINEER", "INVENTOR", "IRRIGATION", "MEDICINE", "MINING", "PRINTER",
        "ROAD_BUILDING", "SMITH", "COMMERCIAL_HARBOR", "MASTER_MERCHANT", "MERCHANT", "MERCHANT_FLEET",
        "RESOURCE_MONOPOLY", "TRADE_MONOPOLY", "BISHOP", "CONSTITUTION", "DESERTER", "DIPLOMAT", "INTRIGUE",
        "SABOTEUR", "SPY", "WARLORD", "WEDDING",
    )

    /** Copies of each card (5th edition: 18 per colour, 54 total). */
    val DECK = intArrayOf(
        2, 2, 1, 2, 2, 2, 2, 1, 2, 2, // science
        2, 2, 6, 2, 4, 2, // trade
        2, 1, 2, 2, 2, 2, 3, 2, 2, // politics
    )

    /** Cards per colour deck. */
    const val PER_DECK = 18

    /** Progress cards a player may hold (VP cards are revealed at once and do not count). */
    const val HAND_LIMIT = 4

    /** Colour ([Track]) of each card. */
    fun track(card: Int) = when {
        card < COMMERCIAL_HARBOR -> Track.SCIENCE
        card < BISHOP -> Track.TRADE
        else -> Track.POLITICS
    }

    fun isVictoryPoint(card: Int) = card == PRINTER || card == CONSTITUTION

    /** Number tokens the Inventor may swap (not 2, 12, 6 or 8). */
    fun inventorMovable(number: Int) = number != 0 && number != 2 && number != 12 && number != 6 && number != 8
}

/** Age of Chivalry piece supplies and constants. */
object ChivalryPiece {
    /** Knights per strength level (basic, strong, mighty) per player. */
    const val KNIGHTS_PER_LEVEL = 2
    const val MAX_KNIGHT_LEVEL = 3
    const val WALLS = 3
    const val METROPOLIS_VP = 2
    const val DEFENDER_CARDS = 6
    /** The ship attacks when it has moved this many spaces. */
    const val BARBARIAN_TRACK = 7
    /** Each city wall raises the 7-discard limit by this much. */
    const val WALL_HAND_BONUS = 2
}

object ChivalryCost {
    val WALL = intArrayOf(0, 2, 0, 0, 0)
    val KNIGHT = intArrayOf(0, 0, 1, 0, 1)
    val PROMOTE = intArrayOf(0, 0, 1, 0, 1)
    val ACTIVATE = intArrayOf(0, 0, 0, 1, 0)
    val MEDICINE = intArrayOf(0, 0, 0, 1, 2)
}

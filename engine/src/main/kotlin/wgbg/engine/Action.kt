package wgbg.engine

/**
 * Any action kind: a base-game [ActionType] or a Age of Chivalry [ChivalryActionType].
 * [id] is the value stored in bits 48..55 of an [Action] code.
 */
sealed interface ActionKind {
    val id: Int
}

/** Base-game action kinds, named after Catanatron's ActionType. Ids are the ordinals. */
enum class ActionType : ActionKind {
    ROLL,
    MOVE_ROBBER,
    DISCARD,
    BUILD_ROAD,
    BUILD_SETTLEMENT,
    BUILD_CITY,
    BUY_DEVELOPMENT_CARD,
    PLAY_KNIGHT_CARD,
    PLAY_YEAR_OF_PLENTY,
    PLAY_MONOPOLY,
    PLAY_ROAD_BUILDING,
    MARITIME_TRADE,
    OFFER_TRADE,
    ACCEPT_TRADE,
    REJECT_TRADE,
    CONFIRM_TRADE,
    CANCEL_TRADE,
    END_TURN,
    /**
     * A responder's counter to the open domestic offer. Same encoding as OFFER_TRADE and, like it,
     * written from the offerer's side: what the offerer would give and get.
     */
    COUNTER_TRADE,
    ;

    override val id get() = ordinal
}

/**
 * Age of Chivalry action kinds. They live in their own enum (ids from [ID_BASE]) so the
 * base [ActionType] set, and code that enumerates it, is unchanged; new kinds are appended.
 * Age of Chivalry games also use the base kinds (ROLL, BUILD_*, MOVE_ROBBER, DISCARD, MARITIME_TRADE,
 * trade responses, END_TURN), but not dev cards or the 4-bit OFFER_TRADE.
 */
enum class ChivalryActionType : ActionKind {
    BUILD_KNIGHT,
    ACTIVATE_KNIGHT,
    PROMOTE_KNIGHT,
    MOVE_KNIGHT,
    DISPLACE_KNIGHT,
    CHASE_ROBBER,
    RELOCATE_KNIGHT,
    BUILD_CITY_WALL,
    IMPROVE_CITY,
    PLAY_PROGRESS,
    DISCARD_PROGRESS,
    CHOOSE_CARD,
    CHOOSE_VERTEX,
    CHOOSE_PROGRESS_DECK,
    TAKE_PROGRESS,
    COMMERCIAL_HARBOR_OFFER,
    CHIVALRY_DONE,
    OFFER_TRADE_CK,
    /** A Age of Chivalry counter-offer: OFFER_TRADE_CK terms from the offerer's side, collected until the round ends. */
    COUNTER_TRADE_CK,
    ;

    override val id get() = ID_BASE + ordinal

    companion object {
        /** First Age of Chivalry kind id; base kinds stay below it. */
        const val ID_BASE = 64
    }
}

/**
 * An action packed into a Long: kind in bits 48..55, payload in bits 0..47.
 * The acting player is implicit ([GameState.actingPlayer]), so a game is just
 * config + seed + a list of these codes.
 *
 * Payloads:
 * - BUILD_ROAD: edge id; BUILD_SETTLEMENT / BUILD_CITY: vertex id
 * - MOVE_ROBBER: hex | (victim + 1) << 8   (victim -1 = steal from nobody)
 * - DISCARD: resource + 1   (0 = discard half the hand at random, Catanatron style)
 * - PLAY_YEAR_OF_PLENTY: r1 | (r2 + 1) << 8   (r2 -1 = only one card)
 * - PLAY_MONOPOLY: resource
 * - MARITIME_TRADE: give | get << 8   (rate is the best one the player has)
 * - OFFER_TRADE: 4 bits per count, offered resources in bits 0..19, asked in 20..39
 * - CONFIRM_TRADE: the accepting player
 *
 * Age of Chivalry (card indices use the Age of Chivalry space: resources 0..4, commodities 5..7):
 * - DISCARD / MARITIME_TRADE / CHOOSE_CARD: card indices may be commodities
 * - BUILD_KNIGHT, ACTIVATE_KNIGHT, PROMOTE_KNIGHT, CHASE_ROBBER, RELOCATE_KNIGHT,
 *   BUILD_CITY_WALL, CHOOSE_VERTEX: vertex id
 * - MOVE_KNIGHT / DISPLACE_KNIGHT: from | to << 8
 * - IMPROVE_CITY: track | (metropolis vertex + 1) << 8   (vertex only when the metropolis is gained)
 * - PLAY_PROGRESS: card | arg1 << 8 | arg2 << 16   (arguments per card, see Chivalry.kt)
 * - DISCARD_PROGRESS / TAKE_PROGRESS: progress card id; CHOOSE_PROGRESS_DECK: track
 * - COMMERCIAL_HARBOR_OFFER: resource | opponent << 8
 * - OFFER_TRADE_CK: 3 bits per count, offered cards 0..7 in bits 0..23, asked in 24..47
 * - COUNTER_TRADE / COUNTER_TRADE_CK: as OFFER_TRADE / OFFER_TRADE_CK, from the offerer's side
 */
@JvmInline
value class Action(val code: Long) {
    val typeId: Int get() = (code ushr TYPE_SHIFT).toInt()
    val type: ActionKind
        get() = if (typeId < ChivalryActionType.ID_BASE) ActionType.entries[typeId] else ChivalryActionType.entries[typeId - ChivalryActionType.ID_BASE]
    val payload: Long get() = code and PAYLOAD_MASK
    /** Low 8 bits of the payload. */
    val a: Int get() = (code and 0xff).toInt()
    /** Bits 8..15 of the payload. */
    val b: Int get() = ((code ushr 8) and 0xff).toInt()

    val robberHex get() = a
    val robberVictim get() = b - 1
    val discardResource get() = a - 1
    val maritimeGive get() = a
    val maritimeGet get() = b
    val yopFirst get() = a
    val yopSecond get() = b - 1

    fun offerGive(r: Int) = ((code ushr (4 * r)) and 0xf).toInt()
    fun offerWant(r: Int) = ((code ushr (20 + 4 * r)) and 0xf).toInt()

    /** Bits 16..23 of the payload. */
    val c: Int get() = ((code ushr 16) and 0xff).toInt()

    /** OFFER_TRADE_CK counts per Age of Chivalry card index. */
    fun chivalryOfferGive(card: Int) = ((code ushr (3 * card)) and 0x7).toInt()
    fun chivalryOfferWant(card: Int) = ((code ushr (24 + 3 * card)) and 0x7).toInt()

    /**
     * Safe for any 64-bit code, including garbage from a broken or hostile client: unknown types
     * and out-of-range payloads describe themselves instead of throwing (error messages build on this).
     */
    override fun toString(): String =
        if (!isKnownType(typeId)) "Action(unknown type $typeId, payload 0x${payload.toString(16)})"
        else runCatching { describe() }.getOrElse { "$type(raw 0x${payload.toString(16)})" }

    private fun describe(): String {
        val r = Resource.NAMES
        val cn = CARD_NAMES
        return when (type) {
            ChivalryActionType.BUILD_KNIGHT, ChivalryActionType.ACTIVATE_KNIGHT, ChivalryActionType.PROMOTE_KNIGHT, ChivalryActionType.CHASE_ROBBER,
            ChivalryActionType.RELOCATE_KNIGHT, ChivalryActionType.BUILD_CITY_WALL, ChivalryActionType.CHOOSE_VERTEX -> "$type(v$payload)"
            ChivalryActionType.MOVE_KNIGHT, ChivalryActionType.DISPLACE_KNIGHT -> "$type(v$a -> v$b)"
            ChivalryActionType.IMPROVE_CITY -> "IMPROVE_CITY(${Track.NAMES[a]}${if (b > 0) ", metropolis=v${b - 1}" else ""})"
            ChivalryActionType.PLAY_PROGRESS -> "PLAY_PROGRESS(${Progress.NAMES[a]}, $b, $c)"
            ChivalryActionType.DISCARD_PROGRESS, ChivalryActionType.TAKE_PROGRESS -> "$type(${Progress.NAMES[a]})"
            ChivalryActionType.CHOOSE_CARD -> "CHOOSE_CARD(${cn[a]})"
            ChivalryActionType.CHOOSE_PROGRESS_DECK -> "CHOOSE_PROGRESS_DECK(${Track.NAMES[a]})"
            ChivalryActionType.COMMERCIAL_HARBOR_OFFER -> "COMMERCIAL_HARBOR_OFFER(${r[a]} -> p$b)"
            ChivalryActionType.OFFER_TRADE_CK -> "OFFER_TRADE_CK(give=${(0 until CHIVALRY_CARD_TYPES).map(::chivalryOfferGive)}, " +
                "want=${(0 until CHIVALRY_CARD_TYPES).map(::chivalryOfferWant)})"
            ChivalryActionType.COUNTER_TRADE_CK -> "COUNTER_TRADE_CK(offerer gives=${(0 until CHIVALRY_CARD_TYPES).map(::chivalryOfferGive)}, " +
                "gets=${(0 until CHIVALRY_CARD_TYPES).map(::chivalryOfferWant)})"
            ActionType.DISCARD -> "DISCARD(${if (a == 0) "random" else cn[discardResource]})"
            ActionType.MARITIME_TRADE -> "MARITIME_TRADE(${cn[maritimeGive]} -> ${cn[maritimeGet]})"
            ActionType.MOVE_ROBBER -> "MOVE_ROBBER(hex=$robberHex, victim=$robberVictim)"
            ActionType.BUILD_ROAD -> "BUILD_ROAD(e$payload)"
            ActionType.BUILD_SETTLEMENT, ActionType.BUILD_CITY -> "$type(v$payload)"
            ActionType.PLAY_YEAR_OF_PLENTY ->
                "PLAY_YEAR_OF_PLENTY(${r[yopFirst]}${if (yopSecond >= 0) ", " + r[yopSecond] else ""})"
            ActionType.PLAY_MONOPOLY -> "PLAY_MONOPOLY(${r[a]})"
            ActionType.OFFER_TRADE -> "OFFER_TRADE(give=${(0 until CARD_TYPES).map(::offerGive)}, " +
                "want=${(0 until CARD_TYPES).map(::offerWant)})"
            ActionType.COUNTER_TRADE -> "COUNTER_TRADE(offerer gives=${(0 until CARD_TYPES).map(::offerGive)}, " +
                "gets=${(0 until CARD_TYPES).map(::offerWant)})"
            ActionType.CONFIRM_TRADE -> "CONFIRM_TRADE(with=$payload)"
            else -> type.toString()
        }
    }

    companion object {
        const val TYPE_SHIFT = 48
        const val PAYLOAD_MASK = (1L shl TYPE_SHIFT) - 1

        fun of(type: ActionKind, payload: Long = 0) = Action((type.id.toLong() shl TYPE_SHIFT) or payload)

        /** True if [typeId] names a base or Age of Chivalry action kind. */
        fun isKnownType(typeId: Int) = typeId in ActionType.entries.indices ||
            typeId - ChivalryActionType.ID_BASE in ChivalryActionType.entries.indices

        val ROLL = of(ActionType.ROLL)

        /**
         * A roll whose dice were thrown outside the engine (true-random or deck dice), so the
         * result is recorded in the action log. [event] is the Age of Chivalry event die face 0..5,
         * or -1 to let the engine roll it. Plain [ROLL] lets the engine's seeded RNG roll.
         */
        fun roll(d1: Int, d2: Int, event: Int = -1): Action {
            require(d1 in 1..6 && d2 in 1..6 && event in -1..5) { "bad dice $d1 $d2 $event" }
            return of(ActionType.ROLL, (d1 or (d2 shl 8) or ((event + 1) shl 16)).toLong())
        }
        val BUY_DEVELOPMENT_CARD = of(ActionType.BUY_DEVELOPMENT_CARD)
        val PLAY_KNIGHT_CARD = of(ActionType.PLAY_KNIGHT_CARD)
        val PLAY_ROAD_BUILDING = of(ActionType.PLAY_ROAD_BUILDING)
        val ACCEPT_TRADE = of(ActionType.ACCEPT_TRADE)
        val REJECT_TRADE = of(ActionType.REJECT_TRADE)
        val CANCEL_TRADE = of(ActionType.CANCEL_TRADE)
        val END_TURN = of(ActionType.END_TURN)

        fun buildRoad(edge: Int) = of(ActionType.BUILD_ROAD, edge.toLong())
        fun buildSettlement(vertex: Int) = of(ActionType.BUILD_SETTLEMENT, vertex.toLong())
        fun buildCity(vertex: Int) = of(ActionType.BUILD_CITY, vertex.toLong())
        fun moveRobber(hex: Int, victim: Int) = of(ActionType.MOVE_ROBBER, (hex or ((victim + 1) shl 8)).toLong())
        /** [resource] -1 = Catanatron-style random discard of half the hand. */
        fun discard(resource: Int) = of(ActionType.DISCARD, (resource + 1).toLong())
        fun yearOfPlenty(r1: Int, r2: Int = -1) = of(ActionType.PLAY_YEAR_OF_PLENTY, (r1 or ((r2 + 1) shl 8)).toLong())
        fun monopoly(resource: Int) = of(ActionType.PLAY_MONOPOLY, resource.toLong())
        fun maritimeTrade(give: Int, get: Int) = of(ActionType.MARITIME_TRADE, (give or (get shl 8)).toLong())
        fun confirmTrade(acceptor: Int) = of(ActionType.CONFIRM_TRADE, acceptor.toLong())

        /** Domestic offer: [give] and [want] are per-resource counts (each 0..15). */
        fun offerTrade(give: IntArray, want: IntArray) = of(ActionType.OFFER_TRADE, tradeTerms(give, want))

        /** Counter-offer: [give] and [want] are what the OFFERER would give and get (each 0..15). */
        fun counterTrade(give: IntArray, want: IntArray) = of(ActionType.COUNTER_TRADE, tradeTerms(give, want))

        private fun tradeTerms(give: IntArray, want: IntArray): Long {
            var p = 0L
            for (r in 0 until CARD_TYPES) {
                require(give[r] in 0..15 && want[r] in 0..15) { "trade counts must be 0..15" }
                p = p or (give[r].toLong() shl (4 * r)) or (want[r].toLong() shl (20 + 4 * r))
            }
            return p
        }

        // ---------------------------------------------------- Age of Chivalry

        val CHIVALRY_DONE = of(ChivalryActionType.CHIVALRY_DONE)

        private fun v(type: ChivalryActionType, vertex: Int) = of(type, vertex.toLong())
        fun buildKnight(vertex: Int) = v(ChivalryActionType.BUILD_KNIGHT, vertex)
        fun activateKnight(vertex: Int) = v(ChivalryActionType.ACTIVATE_KNIGHT, vertex)
        fun promoteKnight(vertex: Int) = v(ChivalryActionType.PROMOTE_KNIGHT, vertex)
        fun chaseRobber(knightVertex: Int) = v(ChivalryActionType.CHASE_ROBBER, knightVertex)
        fun relocateKnight(vertex: Int) = v(ChivalryActionType.RELOCATE_KNIGHT, vertex)
        fun buildCityWall(vertex: Int) = v(ChivalryActionType.BUILD_CITY_WALL, vertex)
        fun chooseVertex(vertex: Int) = v(ChivalryActionType.CHOOSE_VERTEX, vertex)
        fun moveKnight(from: Int, to: Int) = of(ChivalryActionType.MOVE_KNIGHT, (from or (to shl 8)).toLong())
        fun displaceKnight(from: Int, to: Int) = of(ChivalryActionType.DISPLACE_KNIGHT, (from or (to shl 8)).toLong())

        /** [metropolisVertex] names the city that receives the metropolis when this level claims it. */
        fun improveCity(track: Int, metropolisVertex: Int = -1) =
            of(ChivalryActionType.IMPROVE_CITY, (track or ((metropolisVertex + 1) shl 8)).toLong())

        fun playProgress(card: Int, arg1: Int = 0, arg2: Int = 0) =
            of(ChivalryActionType.PLAY_PROGRESS, (card or (arg1 shl 8) or (arg2 shl 16)).toLong())

        fun discardProgress(card: Int) = of(ChivalryActionType.DISCARD_PROGRESS, card.toLong())
        fun takeProgress(card: Int) = of(ChivalryActionType.TAKE_PROGRESS, card.toLong())
        fun chooseCard(card: Int) = of(ChivalryActionType.CHOOSE_CARD, card.toLong())
        fun chooseProgressDeck(track: Int) = of(ChivalryActionType.CHOOSE_PROGRESS_DECK, track.toLong())
        fun commercialHarborOffer(resource: Int, opponent: Int) =
            of(ChivalryActionType.COMMERCIAL_HARBOR_OFFER, (resource or (opponent shl 8)).toLong())

        /** Age of Chivalry domestic offer over resources and commodities: [give]/[want] per Age of Chivalry card index (each 0..7). */
        fun offerTradeCk(give: IntArray, want: IntArray) = of(ChivalryActionType.OFFER_TRADE_CK, chivalryTradeTerms(give, want))

        /** Age of Chivalry counter-offer: [give] and [want] are what the OFFERER would give and get (each 0..7). */
        fun counterTradeCk(give: IntArray, want: IntArray) = of(ChivalryActionType.COUNTER_TRADE_CK, chivalryTradeTerms(give, want))

        private fun chivalryTradeTerms(give: IntArray, want: IntArray): Long {
            var p = 0L
            for (c in 0 until CHIVALRY_CARD_TYPES) {
                require(give[c] in 0..7 && want[c] in 0..7) { "trade counts must be 0..7" }
                p = p or (give[c].toLong() shl (3 * c)) or (want[c].toLong() shl (24 + 3 * c))
            }
            return p
        }
    }
}

/** Reusable, growable list of action codes, so move generation allocates nothing in steady state. */
class ActionBuffer(capacity: Int = 128) {
    var codes = LongArray(capacity)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(a: Action) {
        if (size == codes.size) codes = codes.copyOf(size * 2)
        codes[size++] = a.code
    }

    operator fun get(i: Int) = Action(codes[i])

    operator fun contains(a: Action): Boolean {
        for (i in 0 until size) if (codes[i] == a.code) return true
        return false
    }

    fun toList(): List<Action> = List(size) { Action(codes[it]) }
}

package wgbg.engine

/**
 * What one player is allowed to know: everything on the table, card counts of
 * opponents, and their own hand and development cards. Suitable for sending
 * over the network or rendering a player's screen.
 */
class PlayerView(val viewer: Int, s: GameState) {
    val config = s.config
    val board = s.board
    val phase = s.phase
    val currentPlayer = s.currentPlayer
    val actingPlayer = s.actingPlayer
    val turn = s.turn
    val lastRoll = s.lastRoll
    val robberHex = s.robberHex
    val hasRolled = s.hasRolled
    val freeRoads = s.freeRoads
    val winner = s.winner
    val longestRoadOwner = s.longestRoadOwner
    val largestArmyOwner = s.largestArmyOwner
    val vertexOwner = s.vertexOwner.copyOf()
    val vertexBuilding = s.vertexBuilding.copyOf()
    val edgeOwner = s.edgeOwner.copyOf()
    val bank = s.bank.copyOf()
    val devDeckSize = s.devDeck.sum()
    val devPlayed = s.devPlayed.copyOf()
    val roadLength = s.roadLength.copyOf()
    val discardOwed = s.discardOwed.copyOf()
    val tradeOffer = s.tradeOffer.copyOf()
    val tradeAccepted = s.tradeAccepted
    /** Counter-offers in the open round (public: everyone sees them on the table). */
    val counters = s.counters.copyOf()
    val handSize = IntArray(s.numPlayers) { s.handSize(it) }
    val devCount = IntArray(s.numPlayers) { s.devCount(it) }
    val publicVictoryPoints = IntArray(s.numPlayers) { s.publicVictoryPoints(it) }

    /** The viewer's own resource cards. */
    val hand = IntArray(CARD_TYPES) { s.hand(viewer, it) }
    /** The viewer's own unplayed dev cards and which of them were bought this turn. */
    val devHand = IntArray(DevCard.COUNT) { s.devHand[viewer * DevCard.COUNT + it] }
    val devNew = IntArray(DevCard.COUNT) { s.devNew[viewer * DevCard.COUNT + it] }
    val victoryPoints = s.victoryPoints(viewer)

    /** Age of Chivalry information visible to the viewer, null in base games. */
    val chivalry: ChivalryView? = s.chivalry?.let { ChivalryView(viewer, s, it) }
}

/** What a player may see of the Age of Chivalry state: all board pieces and counts, but only their own cards. */
class ChivalryView(viewer: Int, s: GameState, chivalry: ChivalryState) {
    private val n = s.numPlayers
    val hexNumber = chivalry.hexNumber.copyOf()
    val commodityBank = chivalry.commodityBank.copyOf()
    val trackLevel = chivalry.trackLevel.copyOf()
    val metroOwner = chivalry.metroOwner.copyOf()
    val metroVertex = chivalry.metroVertex.copyOf()
    val wall = chivalry.wall.copyOf()
    val knightOwner = chivalry.knightOwner.copyOf()
    val knightLevel = chivalry.knightLevel.copyOf()
    val knightFlags = chivalry.knightFlags.copyOf()
    val progressVp = chivalry.progressVp.copyOf()
    val defenderVp = chivalry.defenderVp.copyOf()
    val deckSize = chivalry.deckSize.copyOf()
    val barbarianPos = chivalry.barbarianPos
    val barbarianAttacks = chivalry.barbarianAttacks
    val lastRed = chivalry.lastRed
    val lastEvent = chivalry.lastEvent
    val merchantHex = chivalry.merchantHex
    val merchantOwner = chivalry.merchantOwner
    val commodityCount = IntArray(n) { chivalry.commodityCount(it) }
    val progressCount = IntArray(n) { chivalry.progressCount(it) }

    /** The viewer's own commodity cards (paper, cloth, coin). */
    val commodities = IntArray(Commodity.COUNT) { chivalry.commodity(viewer, it) }
    /** The viewer's own progress cards, per [Progress] id. */
    val progressHand = IntArray(Progress.COUNT) { chivalry.progressHand[viewer * Progress.COUNT + it] }

    /**
     * While the viewer resolves a Spy, the target's progress cards; while resolving a
     * Master Merchant, the target's resources then commodities. Null otherwise.
     */
    val peek: IntArray? = when {
        viewer != s.actingPlayer || chivalry.pendingTarget < 0 -> null
        s.phase == Phase.SPY -> IntArray(Progress.COUNT) { chivalry.progressHand[chivalry.pendingTarget * Progress.COUNT + it] }
        s.phase == Phase.MASTER_MERCHANT -> IntArray(CHIVALRY_CARD_TYPES) { s.card(chivalry.pendingTarget, it) }
        else -> null
    }
}

object HiddenInfo {
    fun view(s: GameState, viewer: Int) = PlayerView(viewer, s)

    /**
     * Determinization for search (e.g. ISMCTS): a copy of [s] in which every
     * card [viewer] cannot see is resampled consistently with public counts,
     * and the RNG (future dice, draws, steals) is reseeded with [seed].
     *
     * Opponents' unplayed dev cards and the deck order are shuffled together. Resource hands are known by
     * card counting except for steals the viewer didn't take part in ([GameState.unseenSteals]): for each, a
     * random card of the thief's is swapped with a random card of the victim's, so the stolen card's type
     * is resampled while every hand size and the total of each card type stay exact.
     */
    fun determinize(s: GameState, viewer: Int, seed: Long): GameState {
        val d = s.copy()
        d.rng.seed = seed
        val pool = d.devDeck.copyOf()
        for (q in 0 until d.numPlayers) {
            if (q == viewer) continue
            for (c in 0 until DevCard.COUNT) pool[c] += d.devHand[q * DevCard.COUNT + c]
        }
        val rng = Rng(seed xor 0x5DEECE66DL)
        for (q in 0 until d.numPlayers) {
            if (q == viewer) continue
            val base = q * DevCard.COUNT
            val total = (0 until DevCard.COUNT).sumOf { d.devHand[base + it] }
            var fresh = (0 until DevCard.COUNT).sumOf { d.devNew[base + it] }
            for (c in 0 until DevCard.COUNT) {
                d.devHand[base + c] = 0
                d.devNew[base + c] = 0
            }
            repeat(total) {
                val c = draw(pool, rng)
                d.devHand[base + c]++
                // The newest cards are a random subset of the hand; drawing them first is equivalent.
                if (fresh > 0) {
                    d.devNew[base + c]++
                    fresh--
                }
            }
        }
        pool.copyInto(d.devDeck)
        if (d.chivalry != null) shuffleProgress(d, viewer, rng)
        resampleSteals(d, viewer, rng)
        return d
    }

    /** See [determinize]: one thief-victim card swap per steal [viewer] didn't see. */
    private fun resampleSteals(d: GameState, viewer: Int, rng: Rng) {
        val n = d.numPlayers
        val types = if (d.chivalry != null) CHIVALRY_CARD_TYPES else Resource.COUNT
        for (thief in 0 until n) for (victim in 0 until n) {
            if (thief == viewer || victim == viewer || thief == victim) continue
            repeat(d.unseenSteals[thief * n + victim]) {
                val a = pick(d, thief, types, rng) ?: return@repeat
                val b = pick(d, victim, types, rng) ?: return@repeat
                if (a == b) return@repeat
                addCard(d, thief, a, -1); addCard(d, thief, b, 1)
                addCard(d, victim, b, -1); addCard(d, victim, a, 1)
            }
        }
    }

    /** A random card type from [p]'s hand, weighted by count, or null if the hand is empty. */
    private fun pick(d: GameState, p: Int, types: Int, rng: Rng): Int? {
        val total = (0 until types).sumOf { d.card(p, it) }
        if (total == 0) return null
        var k = rng.nextInt(total)
        for (c in 0 until types) { k -= d.card(p, c); if (k < 0) return c }
        return null
    }

    private fun addCard(d: GameState, p: Int, c: Int, delta: Int) {
        if (c < Resource.COUNT) d.hands[p * CARD_TYPES + c] += delta
        else d.chivalry!!.commodities[p * Commodity.COUNT + c - Resource.COUNT] += delta
    }

    /**
     * Age of Chivalry: per colour, opponents' progress cards and the undrawn deck are pooled,
     * shuffled and dealt back with the same counts (opponents keep their per-colour
     * hand sizes; the deck keeps its size, in a fresh random order).
     */
    private fun shuffleProgress(d: GameState, viewer: Int, rng: Rng) {
        val chivalry = d.chivalry!!
        val n = d.numPlayers
        val per = Progress.PER_DECK
        for (t in 0 until Track.COUNT) {
            val pool = IntArray(Progress.COUNT)
            val held = IntArray(n)
            for (i in 0 until chivalry.deckSize[t]) pool[chivalry.deck[t * per + (chivalry.deckHead[t] + i) % per]]++
            for (q in 0 until n) {
                if (q == viewer) continue
                for (c in 0 until Progress.COUNT) {
                    if (Progress.track(c) != t) continue
                    val k = chivalry.progressHand[q * Progress.COUNT + c]
                    held[q] += k
                    pool[c] += k
                    chivalry.progressHand[q * Progress.COUNT + c] = 0
                }
            }
            for (q in 0 until n) repeat(held[q]) { chivalry.progressHand[q * Progress.COUNT + draw(pool, rng)]++ }
            val size = chivalry.deckSize[t]
            chivalry.deckHead[t] = 0
            for (i in 0 until size) chivalry.deck[t * per + i] = draw(pool, rng)
        }
    }

    private fun draw(pool: IntArray, rng: Rng): Int {
        var k = rng.nextInt(pool.sum())
        var c = 0
        while (k >= pool[c]) k -= pool[c++]
        pool[c]--
        return c
    }
}

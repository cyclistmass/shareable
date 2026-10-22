package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/** How many times the per-push counts the fuzz and dice tests run: WGBG_TEST_SCALE=10 for the nightly run (build.gradle.kts). */
internal val testScale: Int = System.getProperty("wgbg.testScale")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

/** Helpers to build positions directly and to check state invariants. */
object T {
    /** A state on the beginner board, setup skipped, player 0 to move before rolling. */
    fun playState(players: Int = 4, config: GameConfig = GameConfig(numPlayers = players, layout = BoardLayout.BEGINNER)): GameState {
        val s = Rules.newGame(config, 42)
        s.phase = Phase.PLAY_TURN
        s.setupStep = 2 * s.numPlayers
        s.currentPlayer = 0
        s.actingPlayer = 0
        return s
    }

    fun corner(s: GameState, q: Int, r: Int, c: Int): Int {
        val v = s.board.cornerVertex(Hex(q, r), c)
        check(v >= 0) { "no vertex at ($q,$r) corner $c" }
        return v
    }

    /** Edge from corner c to corner c+1 of hex (q, r). */
    fun side(s: GameState, q: Int, r: Int, c: Int): Int =
        s.board.edgeBetween(corner(s, q, r, c), corner(s, q, r, (c + 1) % 6))

    fun settle(s: GameState, p: Int, v: Int, city: Boolean = false) {
        s.vertexOwner[v] = p
        s.vertexBuilding[v] = if (city) Piece.CITY else Piece.SETTLEMENT
        s.piecesLeft[p * Piece.COUNT + if (city) Piece.CITY else Piece.SETTLEMENT]--
        s.portMask[p] = s.portMask[p] or s.board.vertexPortBit[v]
    }

    fun road(s: GameState, p: Int, vararg edges: Int) {
        for (e in edges) {
            s.edgeOwner[e] = p
            s.piecesLeft[p * Piece.COUNT + Piece.ROAD]--
        }
        s.roadLength[p] = Rules.longestRoad(s, p)
        Rules.updateLongestRoad(s)
    }

    /** Road along consecutive vertices. */
    fun path(s: GameState, p: Int, vararg vs: Int) {
        for (i in 0 until vs.size - 1) {
            val e = s.board.edgeBetween(vs[i], vs[i + 1])
            check(e >= 0) { "${vs[i]} and ${vs[i + 1]} not adjacent" }
            road(s, p, e)
        }
    }

    fun give(s: GameState, p: Int, r: Int, n: Int) {
        s.bank[r] -= n
        s.hands[p * CARD_TYPES + r] += n
    }

    fun giveAll(s: GameState, p: Int, cost: IntArray, times: Int = 1) {
        for (r in 0 until CARD_TYPES) give(s, p, r, cost[r] * times)
    }

    fun giveDev(s: GameState, p: Int, card: Int, new: Boolean = false) {
        s.devDeck[card]--
        s.devHand[p * DevCard.COUNT + card]++
        if (new) s.devNew[p * DevCard.COUNT + card]++
    }

    /** Rolls [sum] with the dice in the move itself (#55), as true-random and deck dice arrive, leaving the RNG alone. */
    fun roll(s: GameState, sum: Int) {
        Rules.applyChecked(s, ScriptedDice.sum(sum).toAction())
        assertEquals(sum, s.lastRoll)
    }

    fun legal(s: GameState) = Rules.legalActions(s)

    fun hexWithNumber(s: GameState, n: Int) = (0 until s.board.hexCount).first { s.board.hexNumber[it] == n }

    /** Plays the setup phase with random legal choices. */
    fun randomSetup(s: GameState, rng: Rng) {
        val buf = ActionBuffer()
        while (s.phase == Phase.BUILD_INITIAL_SETTLEMENT || s.phase == Phase.BUILD_INITIAL_ROAD) {
            Rules.legalActions(s, buf)
            Rules.apply(s, buf[rng.nextInt(buf.size)])
        }
    }

    fun checkInvariants(s: GameState) {
        val b = s.board
        val n = s.numPlayers
        for (r in 0 until CARD_TYPES) {
            var total = s.bank[r]
            assertTrue(s.bank[r] >= 0, "bank negative")
            for (p in 0 until n) {
                assertTrue(s.hand(p, r) >= 0, "hand negative")
                total += s.hand(p, r)
            }
            assertEquals(BANK_PER_RESOURCE, total, "resource $r not conserved")
        }
        for (c in 0 until DevCard.COUNT) {
            var total = s.devDeck[c]
            for (p in 0 until n) {
                val i = p * DevCard.COUNT + c
                assertTrue(s.devNew[i] in 0..s.devHand[i])
                total += s.devHand[i] + s.devPlayed[i]
            }
            assertEquals(DevCard.DECK[c], total, "dev card $c not conserved")
        }
        for (p in 0 until n) {
            var settlements = 0
            var cities = 0
            var roads = 0
            var ports = 0
            for (v in 0 until b.vertexCount) if (s.vertexOwner[v] == p) {
                if (s.vertexBuilding[v] == Piece.CITY) cities++ else settlements++
                ports = ports or b.vertexPortBit[v]
            }
            for (e in 0 until b.edgeCount) if (s.edgeOwner[e] == p) {
                roads++
                val a = b.edgeA[e]
                val c = b.edgeB[e]
                // Roads may be cut by later opponent settlements, so only check they touch something of ours.
                val connected = s.vertexOwner[a] == p || s.vertexOwner[c] == p ||
                    countRoads(s, p, a) > 1 || countRoads(s, p, c) > 1
                assertTrue(connected, "orphan road $e")
            }
            assertEquals(settlements, s.piecesOnBoard(p, Piece.SETTLEMENT))
            assertEquals(cities, s.piecesOnBoard(p, Piece.CITY))
            assertEquals(roads, s.piecesOnBoard(p, Piece.ROAD))
            for (k in 0 until Piece.COUNT) assertTrue(s.piecesLeft[p * Piece.COUNT + k] >= 0)
            assertEquals(ports, s.portMask[p], "port mask")
            assertEquals(Rules.longestRoad(s, p), s.roadLength[p], "road length")
            assertEquals(
                settlements + 2 * cities + (if (s.longestRoadOwner == p) 2 else 0) +
                    (if (s.largestArmyOwner == p) 2 else 0) + s.devHand[p * DevCard.COUNT + DevCard.VICTORY_POINT],
                s.victoryPoints(p),
            )
        }
        for (v in 0 until b.vertexCount) {
            if (s.vertexOwner[v] < 0) continue
            for (i in 0 until 3) {
                val nb = b.vertexNbr[v * 3 + i]
                if (nb >= 0) assertTrue(s.vertexOwner[nb] < 0, "distance rule broken at $v")
            }
        }
        val maxRoad = s.roadLength.max()
        if (s.longestRoadOwner >= 0) {
            assertTrue(s.roadLength[s.longestRoadOwner] >= 5)
            assertEquals(maxRoad, s.roadLength[s.longestRoadOwner])
        } else if (maxRoad >= 5) {
            assertTrue(s.roadLength.count { it == maxRoad } > 1, "longest road unassigned")
        }
        if (s.largestArmyOwner >= 0) {
            val k = s.knightsPlayed(s.largestArmyOwner)
            assertTrue(k >= 3)
            for (p in 0 until n) assertTrue(s.knightsPlayed(p) <= k)
        } else {
            for (p in 0 until n) assertTrue(s.knightsPlayed(p) < 3)
        }
        assertTrue(s.robberHex in 0 until b.hexCount)
        when (s.phase) {
            Phase.GAME_OVER -> {
                if (s.winner >= 0) assertTrue(s.victoryPoints(s.winner) >= s.config.vpToWin)
                else assertTrue(s.turn >= s.config.maxTurns)
            }
            else -> {
                assertTrue(s.victoryPoints(s.currentPlayer) < s.config.vpToWin)
                if (Rules.legalActions(s).isEmpty()) fail<Unit>("no legal actions in ${s.phase}")
            }
        }
        if (s.phase == Phase.DISCARD) assertTrue(s.discardOwed[s.actingPlayer] > 0)
        else assertTrue(s.discardOwed.all { it == 0 })
    }

    private fun countRoads(s: GameState, p: Int, v: Int): Int {
        var c = 0
        for (i in 0 until 3) {
            val e = s.board.vertexEdge[v * 3 + i]
            if (e >= 0 && s.edgeOwner[e] == p) c++
        }
        return c
    }
}
